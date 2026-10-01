package br.com.leitorqbox

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente da API de controle de acesso do Q-Box (endpoints em /api/access).
 * Todas as chamadas são bloqueantes: chamar fora da main thread.
 */
object QboxClient {

    data class Config(
        val baseUrl: String,
        val showId: String,
        val token: String,
        val gate: String,
        val sectorIds: List<Int>,
        val checkOnly: Boolean,
    )

    data class Sector(val id: Int, val name: String)

    data class TicketInfo(
        val holder: String?,
        val document: String?,
        val sector: String?,
        val ticketId: Long? = null,
    )

    sealed class ScanResult {
        /** 200: ticket válido (em /validate já foi marcado como usado). */
        data class Valid(val master: Boolean, val info: TicketInfo) : ScanResult()

        /** 401/404 com motivo (USED, VOID, INVALID_SECTOR...). */
        data class Rejected(
            val reason: String,
            val info: TicketInfo,
            val usedDate: String?,
            val usedGate: String?,
            val sameGate: Boolean,
        ) : ScanResult()

        /** 401 sem motivo: Show ID / Token não conferem. */
        object AuthError : ScanResult()

        data class ServerError(val httpCode: Int) : ScanResult()

        data class NetworkError(val detail: String) : ScanResult()
    }

    class QboxException(message: String) : Exception(message)

    private const val CONNECT_TIMEOUT_MS = 4000
    private const val READ_TIMEOUT_MS = 6000

    fun fetchSectors(cfg: Config): List<Sector> {
        val (code, body) = request(cfg, "GET", "/api/access/sectors", null)
        when {
            code == 200 -> {
                // Versões do Q-Box respondem [{...}] (PDF) ou {"sectors":[{...}]} (/public).
                val trimmed = body.trim()
                val arr = if (trimmed.startsWith("[")) JSONArray(trimmed)
                else JSONObject(trimmed).optJSONArray("sectors") ?: JSONArray()
                return (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Sector(o.getInt("id"), o.optString("name"))
                }
            }
            code == 401 -> throw QboxException("Show ID ou Token recusados (401)\n" + diagnostics(cfg, body))
            else -> throw QboxException("Q-Box respondeu HTTP $code\n" + diagnostics(cfg, body))
        }
    }

    fun scan(cfg: Config, code: String): ScanResult {
        // Só /validate e /check: as variantes *All não existem em todas as versões do Q-Box.
        // "Todos os setores" é resolvido em Prefs.config() mandando todos os IDs do show.
        val path = if (cfg.checkOnly) "/api/access/check" else "/api/access/validate"
        val body = JSONObject()
            .put("code", code)
            .put("gate", cfg.gate)
        if (cfg.sectorIds.isNotEmpty()) body.put("sectors", JSONArray(cfg.sectorIds))

        val (http, text) = try {
            request(cfg, "POST", path, body.toString())
        } catch (e: Exception) {
            return ScanResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }

        val json = runCatching { JSONObject(text) }.getOrNull()
        // 404 em HTML = rota inexistente nesta versão do Q-Box (ex. /check), não ticket.
        if (http == 404 && json == null) return ScanResult.ServerError(404)
        return when {
            http == 200 && json != null && json.optBoolean("valid", true) ->
                ScanResult.Valid(json.optBoolean("master", false), ticketInfo(json))
            http == 200 || http == 401 || http == 404 -> {
                val reason = json?.optString("message").orEmpty()
                    .ifEmpty { json?.optString("code").orEmpty() }
                if (reason.isEmpty() && http == 401) {
                    ScanResult.AuthError
                } else {
                    ScanResult.Rejected(
                        reason = reason.ifEmpty { "HTTP $http" },
                        info = json?.let { ticketInfo(it) } ?: TicketInfo(null, null, null),
                        usedDate = json?.optStringOrNull("usedDate"),
                        usedGate = json?.optStringOrNull("gate"),
                        sameGate = json?.optBoolean("sameGate", false) ?: false,
                    )
                }
            }
            else -> ScanResult.ServerError(http)
        }
    }

    /** O que foi realmente enviado, para comparar com o painel sem expor o token inteiro. */
    private fun diagnostics(cfg: Config, body: String) = buildString {
        append("URL: ${cfg.baseUrl}/api/access/sectors\n")
        append("Show ID: '${cfg.showId}' (${cfg.showId.length} caract.)\n")
        append("Token: ${cfg.token.length} caract., início ${cfg.token.take(4)}… fim …${cfg.token.takeLast(4)}\n")
        append("Resposta: ${body.trim().take(120).ifEmpty { "(vazia)" }}")
    }

    private fun ticketInfo(json: JSONObject): TicketInfo {
        val ticket = json.optJSONObject("ticket") ?: return TicketInfo(null, null, null)
        val holder = ticket.optJSONObject("holder")
        val name = holder?.let {
            "${it.optString("firstName")} ${it.optString("lastName")}".trim()
        }?.ifEmpty { null }
        val document = holder?.let {
            val type = it.optString("documentType")
            val number = it.optString("documentNumber")
            if (number.isEmpty()) null else "$type $number".trim()
        }
        val sector = ticket.optJSONObject("sector")?.optStringOrNull("name")
        val ticketId = if (json.has("ticketId") && !json.isNull("ticketId")) json.optLong("ticketId") else null
        return TicketInfo(name, document, sector, ticketId)
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).ifEmpty { null }

    private fun request(cfg: Config, method: String, path: String, body: String?): Pair<Int, String> {
        val conn = URL(cfg.baseUrl + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = method
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("X-Quentro-Show", cfg.showId)
            conn.setRequestProperty("X-Quentro-ShowKey", cfg.token)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            conn.disconnect()
        }
    }
}
