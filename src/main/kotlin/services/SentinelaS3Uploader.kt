package services

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.AbortMultipartUploadRequest
import aws.sdk.kotlin.services.s3.model.CompleteMultipartUploadRequest
import aws.sdk.kotlin.services.s3.model.CompletedMultipartUpload
import aws.sdk.kotlin.services.s3.model.CompletedPart
import aws.sdk.kotlin.services.s3.model.CreateMultipartUploadRequest
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.model.ServerSideEncryption
import aws.sdk.kotlin.services.s3.model.UploadPartRequest
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.toByteArray
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.hours

/**
 * Upload multipart incremental para o bucket dedicado do Sentinela: recebe os
 * chunks da gravação conforme chegam pelo WebSocket e envia uma parte ao S3 a
 * cada [PART_SIZE] bytes (o S3 exige ≥5 MB por parte, exceto a última).
 * Uma instância por gravação; não é thread-safe (usada só pela sessão WS dona).
 */
class SentinelaS3Uploader(private val key: String, private val contentType: String) {

    companion object {
        private const val PART_SIZE = 5 * 1024 * 1024

        private val accessKeyId = System.getProperty("aws.accessKeyId")
            ?: System.getenv("AWS_ACCESS_KEY_ID")
        private val secretAccessKey = System.getProperty("aws.secretAccessKey")
            ?: System.getenv("AWS_SECRET_ACCESS_KEY")
        private val awsRegion = System.getProperty("aws.region")
            ?: System.getenv("AWS_REGION")
            ?: "us-east-2"
        private val bucketName = System.getProperty("aws.sentinelaBucket")
            ?: System.getenv("AWS_SENTINELA_BUCKET")

        private val s3Client by lazy {
            S3Client {
                region = awsRegion
                credentialsProvider = StaticCredentialsProvider(
                    Credentials(
                        accessKeyId = accessKeyId
                            ?: error("AWS_ACCESS_KEY_ID não configurada"),
                        secretAccessKey = secretAccessKey
                            ?: error("AWS_SECRET_ACCESS_KEY não configurada"),
                    )
                )
            }
        }

        private fun requireBucket(): String =
            bucketName ?: error("Bucket do Sentinela não configurado (aws.sentinelaBucket / AWS_SENTINELA_BUCKET)")

        /** Guarda um objeto pequeno de uma vez (ex.: o relatório em PDF), criptografado no bucket. */
        suspend fun putObject(key: String, bytes: ByteArray, contentType: String) {
            s3Client.putObject(PutObjectRequest {
                bucket = requireBucket()
                this.key = key
                body = ByteStream.fromBytes(bytes)
                this.contentType = contentType
                serverSideEncryption = ServerSideEncryption.Aes256
            })
        }

        /** Lê um objeto pequeno inteiro (ex.: o relatório em PDF). */
        suspend fun getObjectBytes(key: String): ByteArray =
            s3Client.getObject(GetObjectRequest {
                bucket = requireBucket()
                this.key = key
            }) { response -> response.body?.toByteArray() ?: ByteArray(0) }

        /** URL temporária (1 h) para o próprio dono assistir à gravação — o bucket continua privado. */
        suspend fun presignedGetUrl(key: String): String {
            val request = GetObjectRequest {
                bucket = requireBucket()
                this.key = key
            }
            return s3Client.presignGetObject(request, 1.hours).url.toString()
        }
    }

    private val buffer = ByteArrayOutputStream()
    private val completedParts = mutableListOf<CompletedPart>()
    private var uploadId: String? = null
    private var partNumber = 1

    var totalBytes: Long = 0
        private set

    suspend fun start() {
        val response = s3Client.createMultipartUpload(CreateMultipartUploadRequest {
            bucket = requireBucket()
            key = this@SentinelaS3Uploader.key
            contentType = this@SentinelaS3Uploader.contentType
            serverSideEncryption = ServerSideEncryption.Aes256
        })
        uploadId = response.uploadId ?: error("S3 não retornou uploadId")
    }

    suspend fun write(bytes: ByteArray) {
        buffer.write(bytes)
        totalBytes += bytes.size
        if (buffer.size() >= PART_SIZE) {
            flushPart()
        }
    }

    /** Envia o que restou como última parte e fecha o objeto. Retorna a key, ou null se nada foi gravado. */
    suspend fun complete(): String? {
        val currentUploadId = uploadId ?: return null
        if (totalBytes == 0L) {
            abort()
            return null
        }
        if (buffer.size() > 0) flushPart()

        s3Client.completeMultipartUpload(CompleteMultipartUploadRequest {
            bucket = requireBucket()
            key = this@SentinelaS3Uploader.key
            uploadId = currentUploadId
            multipartUpload = CompletedMultipartUpload { parts = completedParts }
        })
        uploadId = null
        return key
    }

    suspend fun abort() {
        val currentUploadId = uploadId ?: return
        runCatching {
            s3Client.abortMultipartUpload(AbortMultipartUploadRequest {
                bucket = requireBucket()
                key = this@SentinelaS3Uploader.key
                uploadId = currentUploadId
            })
        }
        uploadId = null
    }

    private suspend fun flushPart() {
        val currentUploadId = uploadId ?: error("Upload não iniciado")
        val bytes = buffer.toByteArray()
        buffer.reset()

        val response = s3Client.uploadPart(UploadPartRequest {
            bucket = requireBucket()
            key = this@SentinelaS3Uploader.key
            uploadId = currentUploadId
            partNumber = this@SentinelaS3Uploader.partNumber
            body = ByteStream.fromBytes(bytes)
            contentLength = bytes.size.toLong()
        })
        completedParts += CompletedPart {
            partNumber = this@SentinelaS3Uploader.partNumber
            eTag = response.eTag
        }
        partNumber++
    }
}
