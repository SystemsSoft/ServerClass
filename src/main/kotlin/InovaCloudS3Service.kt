package services

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.*
import aws.smithy.kotlin.runtime.content.ByteStream
import java.util.Base64

class InovaCloudS3Client {

    companion object {
        private const val REGION = "us-east-2"
        private const val BUCKET_NAME = "inova-cloud"
        private const val BASE_URL = "https://$BUCKET_NAME.s3.$REGION.amazonaws.com"

        private val s3Client by lazy {
            S3Client { region = REGION }
        }

        /**
         * Faz upload de uma foto (bytes brutos) para o S3 sob o prefixo "inovacloud/photos/".
         * Retorna a URL pública do objeto salvo.
         */
        suspend fun uploadPhotoFile(fileId: String, bytes: ByteArray, mimeType: String, originalFileName: String): String {
            val extension = when {
                mimeType.contains("png")  -> "png"
                mimeType.contains("webp") -> "webp"
                mimeType.contains("gif")  -> "gif"
                else -> originalFileName.substringAfterLast('.', "jpg").lowercase().take(5).ifBlank { "jpg" }
            }
            val s3Key = "inovacloud/photos/$fileId.$extension"
            s3Client.putObject(PutObjectRequest {
                bucket = BUCKET_NAME
                key = s3Key
                body = ByteStream.fromBytes(bytes)
                contentType = mimeType
            })
            println("[InovaCloud S3] ✅ Foto salva: $s3Key")
            return "$BASE_URL/$s3Key"
        }

        /**
         * Faz upload de uma imagem em Base64 para o S3 sob o prefixo "indicados/".
         * Retorna a URL pública do objeto salvo.
         */
        suspend fun uploadImage(fileId: String, base64Raw: String): String {
            try {
                var mimeType = "image/jpeg"
                var extension = "jpg"
                var base64Data = base64Raw

                if (base64Raw.contains(",")) {
                    val parts = base64Raw.split(",")
                    val header = parts[0] // ex: "data:image/png;base64"
                    base64Data = parts[1]

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
                    }
                }

                val imageBytes = Base64.getDecoder().decode(base64Data)
                val finalKeyName = "indicados/$fileId.$extension"

                val request = PutObjectRequest {
                    bucket = BUCKET_NAME
                    key = finalKeyName
                    body = ByteStream.fromBytes(imageBytes)
                    contentType = mimeType
                }

                s3Client.putObject(request)
                println("[InovaCloud S3] ✅ Imagem salva: $finalKeyName")
                return "$BASE_URL/$finalKeyName"

            } catch (e: Exception) {
                println("[InovaCloud S3] ❌ Erro no upload: ${e.message}")
                throw e
            }
        }

        /**
         * Remove um objeto do bucket inova-cloud pela sua key.
         */
        suspend fun deleteObject(s3Key: String) {
            s3Client.deleteObject(DeleteObjectRequest {
                bucket = BUCKET_NAME
                key = s3Key
            })
            println("[InovaCloud S3] 🗑️ Objeto removido: $s3Key")
        }
    }
}

