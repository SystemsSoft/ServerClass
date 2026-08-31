package services

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.*
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.sdk.kotlin.services.s3.presigners.presignPutObject
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import java.io.InputStream
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class S3ApiClient {

    companion object {
        // Busca as credenciais de variáveis de ambiente ou propriedades do sistema
        private val AWS_ACCESS_KEY_ID = System.getProperty("aws.accessKeyId")
            ?: System.getenv("AWS_ACCESS_KEY_ID")
            ?: error("AWS_ACCESS_KEY_ID não configurada (system property aws.accessKeyId ou variável de ambiente)")

        private val AWS_SECRET_ACCESS_KEY = System.getProperty("aws.secretAccessKey")
            ?: System.getenv("AWS_SECRET_ACCESS_KEY")
            ?: error("AWS_SECRET_ACCESS_KEY não configurada (system property aws.secretAccessKey ou variável de ambiente)")

        private val REGION = System.getProperty("aws.region")
            ?: System.getenv("AWS_REGION")
            ?: "us-east-2"

        private val BUCKET_NAME = System.getProperty("aws.bucketName")
            ?: System.getenv("AWS_BUCKET_NAME")
            ?: "repo-english-class"


        private val s3Client by lazy {
            S3Client {
                region = REGION
                credentialsProvider = StaticCredentialsProvider(
                    Credentials(
                        accessKeyId = AWS_ACCESS_KEY_ID,
                        secretAccessKey = AWS_SECRET_ACCESS_KEY
                    )
                )
            }
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

        /**
         * Gera uma URL pré-assinada para o cliente fazer PUT diretamente no S3.
         * Válida por [expiryMinutes] minutos (padrão: 60).
         * O cliente deve enviar o vídeo com Content-Type "video/mp4".
         */
        suspend fun generatePresignedUploadUrl(s3Key: String, expiryMinutes: Int = 60): String {
            val request = PutObjectRequest {
                bucket = BUCKET_NAME
                key = s3Key
                contentType = "video/mp4"
            }
            val presigned = s3Client.presignPutObject(request, expiryMinutes.minutes)
            println("[S3] URL pré-assinada de upload gerada: key=$s3Key, validade=${expiryMinutes}min")
            return presigned.url.toString()
        }

        /**
         * Verifica se um objeto existe no S3.
         * Retorna true se o objeto existe, false caso contrário.
         */
        suspend fun objectExists(s3Key: String): Boolean {
            return try {
                s3Client.headObject(HeadObjectRequest {
                    bucket = BUCKET_NAME
                    key = s3Key
                })
                println("[S3] ✅ Objeto existe: $s3Key")
                true
            } catch (e: Exception) {
                println("[S3] ❌ Objeto não existe: $s3Key (${e.message})")
                false
            }
        }

    }
}