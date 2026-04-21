package services

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.*
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.smithy.kotlin.runtime.content.ByteStream
import java.io.InputStream
import java.util.Base64
import kotlin.time.Duration.Companion.hours

class S3ApiClient {

    companion object {
        private const val REGION = "us-east-2" // Ajuste sua região
        private const val BUCKET_NAME = "repo-english-class"
        private const val BASE_URL = "https://$BUCKET_NAME.s3.$REGION.amazonaws.com"

        private val s3Client by lazy {
            S3Client { region = REGION }
        }

        /**
         * Faz upload de um vídeo MP4 para o S3 usando Multipart Upload em chunks de 10 MB.
         * A [s3Key] define o caminho completo no bucket (ex: "lessons/ENG101-ENG102/aula1.mp4").
         */
        suspend fun uploadVideo(s3Key: String, inputStream: InputStream, contentLength: Long = -1L): String {
            val key = s3Key
            val chunkSize = 10 * 1024 * 1024
            println("[S3] Iniciando multipart upload: key=$key, tamanho=%.1f MB".format(contentLength / 1_048_576.0))

            val createResp = s3Client.createMultipartUpload(CreateMultipartUploadRequest {
                bucket = BUCKET_NAME
                this.key = key
                contentType = "video/mp4"
            })
            val uploadId = createResp.uploadId!!
            println("[S3] uploadId=$uploadId")

            val completedParts = mutableListOf<CompletedPart>()
            var partNumber = 1
            val buffer = ByteArray(chunkSize)

            try {
                while (true) {
                    var bytesRead = 0
                    // Lê exatamente chunkSize bytes (ou menos no último chunk)
                    while (bytesRead < chunkSize) {
                        val n = inputStream.read(buffer, bytesRead, chunkSize - bytesRead)
                        if (n == -1) break
                        bytesRead += n
                    }
                    if (bytesRead == 0) break

                    val chunk = buffer.copyOf(bytesRead)
                    println("[S3]   Enviando part $partNumber (${bytesRead / 1024} KB)...")
                    val uploadResp = s3Client.uploadPart(UploadPartRequest {
                        bucket = BUCKET_NAME
                        this.key = key
                        this.uploadId = uploadId
                        this.partNumber = partNumber
                        body = ByteStream.fromBytes(chunk)
                        this.contentLength = bytesRead.toLong()
                    })
                    completedParts += CompletedPart {
                        this.partNumber = partNumber
                        eTag = uploadResp.eTag
                    }
                    partNumber++
                }

                s3Client.completeMultipartUpload(CompleteMultipartUploadRequest {
                    bucket = BUCKET_NAME
                    this.key = key
                    this.uploadId = uploadId
                    multipartUpload = CompletedMultipartUpload { parts = completedParts }
                })
                println("[S3] ✅ Multipart upload concluído: $key (${partNumber - 1} parts)")
                return key
            } catch (e: Exception) {
                println("[S3] ❌ Erro no upload, abortando multipart: ${e.message}")
                runCatching {
                    s3Client.abortMultipartUpload(AbortMultipartUploadRequest {
                        bucket = BUCKET_NAME
                        this.key = key
                        this.uploadId = uploadId
                    })
                }
                throw e
            }
        }

        /**
         * Overload de compatibilidade para ByteArray.
         */
        suspend fun uploadVideo(s3Key: String, videoBytes: ByteArray): String {
            return uploadVideo(s3Key, videoBytes.inputStream(), videoBytes.size.toLong())
        }

        /**
         * Gera uma URL pré-assinada do S3 válida por 1 hora para streaming direto.
         * Suporta Range requests (seek no player de vídeo).
         */
        suspend fun generatePresignedUrl(key: String): String {
            val request = GetObjectRequest {
                bucket = BUCKET_NAME
                this.key = key
            }
            val presigned = s3Client.presignGetObject(request, 1.hours)
            return presigned.url.toString()
        }

        suspend fun uploadImage(fileId: String, base64Raw: String): String {
            try {
                // 1. Detectar o MIME Type (Content-Type) e a Extensão
                var mimeType = "image/jpeg" // Padrão
                var extension = "jpg"
                var base64Data = base64Raw

                if (base64Raw.contains(",")) {
                    val parts = base64Raw.split(",")
                    val header = parts[0] // ex: "data:image/png;base64"
                    base64Data = parts[1] // O conteúdo real

                    when {
                        header.contains("image/png") -> {
                            mimeType = "image/png"
                            extension = "png"
                        }
                        header.contains("image/webp") -> {
                            mimeType = "image/webp"
                            extension = "webp"
                        }
                        header.contains("image/gif") -> {
                            mimeType = "image/gif"
                            extension = "gif"
                        }
                        // Adicione outros se precisar
                    }
                }

                // 2. Decodificar
                val imageBytes = Base64.getDecoder().decode(base64Data)

                // 3. Montar o nome final com a extensão correta
                val finalKeyName = "indicados/$fileId.$extension"

                // 4. Preparar Requisição com o Content-Type Dinâmico
                val request = PutObjectRequest {
                    bucket = BUCKET_NAME
                    key = finalKeyName
                    body = ByteStream.fromBytes(imageBytes)
                    contentType = mimeType // <--- AQUI ESTÁ A MUDANÇA
                }

                // 5. Enviar
                s3Client.putObject(request)

                // 6. Retornar URL
                return "$BASE_URL/$finalKeyName"

            } catch (e: Exception) {
                println("Erro no upload S3: ${e.message}")
                throw e
            }
        }
    }
}