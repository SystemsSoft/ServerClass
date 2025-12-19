package services

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.smithy.kotlin.runtime.content.ByteStream
import java.util.Base64

class S3ApiClient {

    companion object {
        private const val REGION = "us-east-1" // Ajuste sua região
        private const val BUCKET_NAME = "repo-estrelas-leiria"
        private const val BASE_URL = "https://$BUCKET_NAME.s3.$REGION.amazonaws.com"

        private val s3Client by lazy {
            S3Client { region = REGION }
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