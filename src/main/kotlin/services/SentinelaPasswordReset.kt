package services

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.apache.commons.mail.HtmlEmail
import schemas.sentinela.SentinelaUserService
import java.util.concurrent.ConcurrentHashMap

/**
 * "Esqueci minha senha" do Sentinela. O Firebase Authentication continua dono da senha: o servidor só
 * pede a ele um código de redefinição (Identity Toolkit `accounts:sendOobCode` com `returnOobLink`) e
 * envia, pelo SMTP do Sentinela, um e-mail com o link para a tela de redefinição do app web, que conclui
 * a troca com `confirmPasswordReset`.
 *
 * Configuração (propriedade do sistema ou variável de ambiente):
 * - `sentinela.smtp.host` / `SENTINELA_SMTP_HOST` (padrão smtp.gmail.com)
 * - `sentinela.smtp.port` / `SENTINELA_SMTP_PORT` (padrão 587, STARTTLS)
 * - `sentinela.smtp.user` / `SENTINELA_SMTP_USER` e `sentinela.smtp.pass` / `SENTINELA_SMTP_PASS`
 * - `sentinela.smtp.from` / `SENTINELA_SMTP_FROM` — remetente (padrão: o próprio usuário SMTP)
 * - `sentinela.appUrl` / `SENTINELA_APP_URL` — endereço do app web; o link aponta para
 *   `<appUrl>/?mode=resetPassword&oobCode=…`. Nunca vem da requisição, para ninguém conseguir mandar
 *   à vítima um link que entregue o código a outro site.
 *
 * Sem SMTP configurado, o próprio Firebase envia o e-mail padrão dele. Sem `sentinela.appUrl`, o link
 * leva à página de redefinição hospedada pelo Firebase.
 */
class SentinelaPasswordReset(private val users: SentinelaUserService) {

    private companion object {
        const val SCOPE = "https://www.googleapis.com/auth/cloud-platform"
        const val COOLDOWN_MILLIS = 60_000L

        fun config(property: String, env: String): String? =
            (System.getProperty(property) ?: System.getenv(env))?.trim()?.takeIf { it.isNotEmpty() }
    }

    private class Smtp(val host: String, val port: Int, val user: String, val pass: String, val from: String)

    private val httpClient = HttpClient(CIO)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastRequestByEmail = ConcurrentHashMap<String, Long>()

    val isConfigured: Boolean get() = GoogleServiceAccount.credentials != null

    private val smtp: Smtp?
        get() {
            val user = config("sentinela.smtp.user", "SENTINELA_SMTP_USER") ?: return null
            val pass = config("sentinela.smtp.pass", "SENTINELA_SMTP_PASS") ?: return null
            return Smtp(
                host = config("sentinela.smtp.host", "SENTINELA_SMTP_HOST") ?: "smtp.gmail.com",
                port = config("sentinela.smtp.port", "SENTINELA_SMTP_PORT")?.toIntOrNull() ?: 587,
                user = user,
                pass = pass,
                from = config("sentinela.smtp.from", "SENTINELA_SMTP_FROM") ?: user,
            )
        }

    private val appUrl: String?
        get() = config("sentinela.appUrl", "SENTINELA_APP_URL")?.trimEnd('/')

    /**
     * Agenda o envio e retorna na hora. O resultado nunca é revelado a quem pediu (nem pelo tempo de
     * resposta), para a rota não servir de consulta de quais e-mails têm conta. Pedidos repetidos para o
     * mesmo e-mail dentro de um minuto são ignorados.
     */
    fun request(email: String) {
        val normalized = email.trim().lowercase()
        val now = System.currentTimeMillis()
        val previous = lastRequestByEmail[normalized]
        if (previous != null && now - previous < COOLDOWN_MILLIS) return
        lastRequestByEmail[normalized] = now
        lastRequestByEmail.entries.removeIf { now - it.value > COOLDOWN_MILLIS }

        scope.launch {
            try {
                send(normalized)
            } catch (e: Exception) {
                println("[PasswordReset] Erro ao enviar redefinição: ${e.message}")
            }
        }
    }

    private suspend fun send(email: String) {
        val account = GoogleServiceAccount.credentials ?: return
        val smtp = smtp
        // Sem SMTP próprio, o Firebase manda o e-mail padrão dele (returnOobLink = false).
        val oobLink = requestOobLink(account, email, returnLink = smtp != null) ?: return
        if (smtp == null) {
            println("[PasswordReset] SMTP do Sentinela não configurado: e-mail enviado pelo Firebase")
            return
        }

        val link = appUrl?.let { base ->
            val code = Url(oobLink).parameters["oobCode"] ?: return@let null
            "$base/?mode=resetPassword&oobCode=${code.encodeURLParameter()}"
        } ?: oobLink

        val name = users.findByEmail(email)?.name?.substringBefore(' ')?.takeIf { it.isNotBlank() }
        withContext(Dispatchers.IO) { sendEmail(smtp, email, name, link) }
        println("[PasswordReset] Link de redefinição enviado")
    }

    /** Link de ação do Firebase com o código de redefinição, ou null se o e-mail não tem conta. */
    private suspend fun requestOobLink(
        account: GoogleServiceAccount.Credentials,
        email: String,
        returnLink: Boolean,
    ): String? {
        val payload = buildJsonObject {
            put("requestType", "PASSWORD_RESET")
            put("email", email)
            put("returnOobLink", returnLink)
            appUrl?.let { put("continueUrl", it) }
        }

        val response = httpClient.post(
            "https://identitytoolkit.googleapis.com/v1/projects/${account.projectId}/accounts:sendOobCode"
        ) {
            header(HttpHeaders.Authorization, "Bearer ${GoogleServiceAccount.accessToken(account, SCOPE)}")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }

        val body = response.bodyAsText()
        if (response.status.value !in 200..299) {
            if (!body.contains("EMAIL_NOT_FOUND")) {
                println("[PasswordReset] Firebase recusou o pedido (${response.status.value}): $body")
            }
            return null
        }
        if (!returnLink) return ""

        return Json.parseToJsonElement(body).jsonObject["oobLink"]?.jsonPrimitive?.content
            ?: error("Resposta do Firebase sem oobLink")
    }

    private fun sendEmail(smtp: Smtp, to: String, firstName: String?, link: String) {
        val email = HtmlEmail()
        email.hostName = smtp.host
        email.setSmtpPort(smtp.port)
        email.setAuthentication(smtp.user, smtp.pass)
        email.isStartTLSEnabled = true
        email.setCharset("UTF-8")
        email.setFrom(smtp.from, "Sentinela")
        email.subject = "Redefina sua senha do Sentinela"
        email.addTo(to)

        val greeting = firstName?.let { "Olá, $it!" } ?: "Olá!"
        val safeLink = escapeHtml(link)

        email.setHtmlMsg(
            """
            <!DOCTYPE html>
            <html lang="pt-BR">
            <head><meta http-equiv="Content-Type" content="text/html; charset=UTF-8" /><title>Redefinir senha</title></head>
            <body style="margin:0; padding:0; background-color:#0D1013; font-family:'Helvetica Neue', Helvetica, Arial, sans-serif;">
              <table border="0" cellpadding="0" cellspacing="0" width="100%" style="background-color:#0D1013;">
                <tr>
                  <td align="center" style="padding:32px 16px;">
                    <table border="0" cellpadding="0" cellspacing="0" width="100%" style="max-width:520px; background-color:#171B1F; border:1px solid #2A3038; border-radius:16px;">
                      <tr>
                        <td align="center" style="padding:32px 32px 8px 32px;">
                          <p style="margin:0; color:#F5F6F7; font-size:26px; font-weight:800;">Sentinela</p>
                          <p style="margin:4px 0 0 0; color:#8B939C; font-size:11px; font-weight:600; letter-spacing:2px;">MONITORAMENTO EM TEMPO REAL</p>
                        </td>
                      </tr>
                      <tr>
                        <td style="padding:24px 32px 8px 32px; color:#F5F6F7;">
                          <p style="margin:0 0 16px 0; font-size:18px; font-weight:700;">${escapeHtml(greeting)}</p>
                          <p style="margin:0 0 24px 0; font-size:15px; line-height:1.6; color:#C9CED4;">
                            Recebemos um pedido para redefinir a senha da sua conta no Sentinela.
                            Toque no botão abaixo para criar uma nova senha.
                          </p>
                        </td>
                      </tr>
                      <tr>
                        <td align="center" style="padding:0 32px 24px 32px;">
                          <a href="$safeLink" target="_blank" style="display:inline-block; background-color:#2ECC71; color:#000000; font-size:15px; font-weight:700; text-decoration:none; padding:14px 32px; border-radius:12px;">Redefinir senha</a>
                        </td>
                      </tr>
                      <tr>
                        <td style="padding:0 32px 24px 32px;">
                          <p style="margin:0 0 8px 0; font-size:13px; color:#8B939C;">Se o botão não funcionar, copie e cole este endereço no navegador:</p>
                          <p style="margin:0; font-size:12px; line-height:1.5; word-break:break-all;"><a href="$safeLink" target="_blank" style="color:#2ECC71;">$safeLink</a></p>
                        </td>
                      </tr>
                      <tr>
                        <td style="padding:16px 32px 32px 32px; border-top:1px solid #2A3038;">
                          <p style="margin:0; font-size:12px; line-height:1.6; color:#8B939C;">
                            O link vale por 1 hora e só pode ser usado uma vez. Se você não pediu a redefinição,
                            ignore este e-mail: sua senha atual continua valendo.
                          </p>
                        </td>
                      </tr>
                    </table>
                  </td>
                </tr>
              </table>
            </body>
            </html>
            """.trimIndent()
        )
        email.setTextMsg(
            "$greeting\n\nRecebemos um pedido para redefinir a senha da sua conta no Sentinela.\n" +
                "Acesse o link abaixo para criar uma nova senha:\n\n$link\n\n" +
                "O link vale por 1 hora e só pode ser usado uma vez. Se você não pediu a redefinição, ignore este e-mail."
        )
        email.send()
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
