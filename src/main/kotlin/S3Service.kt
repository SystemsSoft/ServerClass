package services

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.smithy.kotlin.runtime.content.ByteStream
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
         * Faz upload de um vídeo MP4 para o S3 e retorna a key do objeto.
         */
        suspend fun uploadVideo(lessonId: Int, videoBytes: ByteArray): String {
            val key = "lessons/$lessonId.mp4"
            println("[S3] Iniciando upload do vídeo: key=$key, tamanho=${videoBytes.size} bytes (%.1f MB)".format(videoBytes.size / 1_048_576.0))
            try {
                s3Client.putObject(PutObjectRequest {
                    bucket = BUCKET_NAME
                    this.key = key
                    body = ByteStream.fromBytes(videoBytes)
                    contentType = "video/mp4"
                })
                println("[S3] ✅ Vídeo enviado com sucesso: $key")
                return key
            } catch (e: Exception) {
                println("[S3] ❌ Erro no upload do vídeo: ${e.message}")
                throw e
            }
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