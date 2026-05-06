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
         * Sanitiza o email para uso seguro como parte de uma key S3
         * (remove caracteres inválidos, converte para minúsculas).
         */
        private fun sanitizeEmail(email: String): String =
            email.lowercase().replace(Regex("[^a-z0-9@._\\-]"), "_")

        /**
         * Valida se a s3Key pertence ao userEmail informado.
         * Lança SecurityException caso não pertença.
         */
        private fun validateOwnership(s3Key: String, userEmail: String) {
            val safeEmail = sanitizeEmail(userEmail)
            val belongsToUser = s3Key.startsWith("inovacloud/photos/$safeEmail/") ||
                                s3Key.startsWith("indicados/$safeEmail/")
            if (!belongsToUser) {
                throw SecurityException("[InovaCloud S3] 🚫 Acesso negado: a imagem '$s3Key' não pertence ao usuário '$userEmail'")
            }
        }

        /**
         * Faz upload de uma foto (bytes brutos) para o S3 sob o prefixo "inovacloud/photos/{email}/".
         * Retorna a URL pública do objeto salvo.
         */
        suspend fun uploadPhotoFile(fileId: String, bytes: ByteArray, mimeType: String, originalFileName: String, userEmail: String): String {
            val extension = when {
                mimeType.contains("png")  -> "png"
                mimeType.contains("webp") -> "webp"
                mimeType.contains("gif")  -> "gif"
                else -> originalFileName.substringAfterLast('.', "jpg").lowercase().take(5).ifBlank { "jpg" }
            }
            val safeEmail = sanitizeEmail(userEmail)
            val s3Key = "inovacloud/photos/$safeEmail/$fileId.$extension"
            s3Client.putObject(PutObjectRequest {
                bucket = BUCKET_NAME
                key = s3Key
                body = ByteStream.fromBytes(bytes)
                contentType = mimeType
            })
            println("[InovaCloud S3] ✅ Foto salva: $s3Key (owner: $userEmail)")
            return "$BASE_URL/$s3Key"
        }

        /**
         * Faz upload de uma imagem em Base64 para o S3 sob o prefixo "indicados/{email}/".
         * Retorna a URL pública do objeto salvo.
         */
        suspend fun uploadImage(fileId: String, base64Raw: String, userEmail: String): String {
            try {
                var mimeType = "image/jpeg"
                var extension = "jpg"
                var base64Data = base64Raw

                if (base64Raw.contains(",")) {
                    val parts = base64Raw.split(",")
                    val header = parts[0]
                    base64Data = parts[1]

                    when {
                        header.contains("image/png")  -> { mimeType = "image/png";  extension = "png"  }
                        header.contains("image/webp") -> { mimeType = "image/webp"; extension = "webp" }
                        header.contains("image/gif")  -> { mimeType = "image/gif";  extension = "gif"  }
                    }
                }

                val imageBytes = Base64.getDecoder().decode(base64Data)
                val safeEmail = sanitizeEmail(userEmail)
                val finalKeyName = "indicados/$safeEmail/$fileId.$extension"

                s3Client.putObject(PutObjectRequest {
                    bucket = BUCKET_NAME
                    key = finalKeyName
                    body = ByteStream.fromBytes(imageBytes)
                    contentType = mimeType
                })
                println("[InovaCloud S3] ✅ Imagem salva: $finalKeyName (owner: $userEmail)")
                return "$BASE_URL/$finalKeyName"

            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                println("[InovaCloud S3] ❌ Erro no upload: ${e.message}")
                throw e
            }
        }

        /**
         * Atualiza uma foto já existente no S3, validando que a key pertence ao userEmail.
         */
        suspend fun updatePhotoFile(s3Key: String, bytes: ByteArray, mimeType: String, userEmail: String) {
            validateOwnership(s3Key, userEmail)
            s3Client.putObject(PutObjectRequest {
                bucket = BUCKET_NAME
                key = s3Key
                body = ByteStream.fromBytes(bytes)
                contentType = mimeType
            })
            println("[InovaCloud S3] 🔄 Foto atualizada: $s3Key (owner: $userEmail)")
        }

        /**
         * Atualiza uma imagem Base64 já existente no S3, validando que a key pertence ao userEmail.
         */
        suspend fun updateImage(s3Key: String, base64Raw: String, userEmail: String) {
            validateOwnership(s3Key, userEmail)

            var mimeType = "image/jpeg"
            var base64Data = base64Raw

            if (base64Raw.contains(",")) {
                val parts = base64Raw.split(",")
                val header = parts[0]
                base64Data = parts[1]
                when {
                    header.contains("image/png")  -> mimeType = "image/png"
                    header.contains("image/webp") -> mimeType = "image/webp"
                    header.contains("image/gif")  -> mimeType = "image/gif"
                }
            }

            val imageBytes = Base64.getDecoder().decode(base64Data)
            s3Client.putObject(PutObjectRequest {
                bucket = BUCKET_NAME
                key = s3Key
                body = ByteStream.fromBytes(imageBytes)
                contentType = mimeType
            })
            println("[InovaCloud S3] 🔄 Imagem atualizada: $s3Key (owner: $userEmail)")
        }

        /**
         * Remove um objeto do bucket inova-cloud, validando que a key pertence ao userEmail.
         */
        suspend fun deleteObject(s3Key: String, userEmail: String) {
            validateOwnership(s3Key, userEmail)
            s3Client.deleteObject(DeleteObjectRequest {
                bucket = BUCKET_NAME
                key = s3Key
            })
            println("[InovaCloud S3] 🗑️ Objeto removido: $s3Key (owner: $userEmail)")
        }
    }
}
