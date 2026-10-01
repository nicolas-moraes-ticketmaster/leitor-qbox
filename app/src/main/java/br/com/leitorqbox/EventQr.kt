package br.com.leitorqbox

import android.net.Uri
import org.json.JSONObject

/**
 * Separa Show ID e Token do conteúdo de um QR de evento. Formatos aceitos:
 *  - QTR__<showId>__<token>                  (QR de pareamento da Crowder)
 *  - {"show":"V-X","token":"...","name":"...","host":"..."}   (QR gerado por este app)
 *  - qualquer URL com ?show=...&token=... (aceita também showId / key)
 *  - "<showId>;<token>", "<showId>|<token>", "<showId>,<token>" ou separados por espaço/linha
 */
object EventQr {

    data class Parsed(
        val showId: String,
        val token: String,
        val name: String? = null,
        val host: String? = null,
    )

    sealed class Result {
        data class Ok(val parsed: Parsed) : Result()
        data class Error(val message: String) : Result()
    }

    fun parse(raw: String): Result {
        val s = raw.trim()
        if (s.isEmpty()) return Result.Error("QR vazio")
        if (s.startsWith("QFIG__")) {
            return Result.Error("QR de configuração em lote (QFIG__). Cadastre pelo painel do Q-Box e use o QR do show (QTR__).")
        }
        if (s.startsWith("DQR_")) return Result.Error("Este QR é um ingresso, não o QR do evento.")

        if (s.startsWith("QTR__")) {
            val rest = s.removePrefix("QTR__")
            val i = rest.indexOf("__")
            if (i > 0 && i + 2 < rest.length) return ok(rest.substring(0, i), rest.substring(i + 2))
        }

        if (s.startsWith("{")) {
            runCatching { JSONObject(s) }.getOrNull()?.let { o ->
                val id = first(o, "show", "showId", "id", "X-Quentro-Show")
                val token = first(o, "token", "showKey", "key", "X-Quentro-ShowKey")
                if (id != null && token != null) {
                    return Result.Ok(Parsed(id, token, first(o, "name"), first(o, "host", "url", "ip")))
                }
            }
        }

        if (s.contains("?")) {
            runCatching { Uri.parse(s) }.getOrNull()?.let { u ->
                val id = u.getQueryParameter("show") ?: u.getQueryParameter("showId")
                val token = u.getQueryParameter("token") ?: u.getQueryParameter("key")
                if (!id.isNullOrBlank() && !token.isNullOrBlank()) {
                    return Result.Ok(Parsed(id.trim(), token.trim(), u.getQueryParameter("name"), u.getQueryParameter("host")))
                }
            }
        }

        val parts = s.split(Regex("[;|,\\s]+")).filter { it.isNotBlank() }
        if (parts.size == 2) return ok(parts[0], parts[1])

        return Result.Error("Não reconheci o formato do QR:\n${s.take(60)}")
    }

    /** Conteúdo do QR para cadastrar este evento em outro leitor. */
    fun encode(e: Event): String = JSONObject()
        .put("name", e.name)
        .put("host", e.host)
        .put("show", e.showId)
        .put("token", e.token)
        .toString()

    private fun ok(a: String, b: String): Result {
        // Se vier invertido (token;id), desinverte pelo formato do Show ID.
        val (id, token) = if (!looksLikeShowId(a) && looksLikeShowId(b)) b to a else a to b
        return Result.Ok(Parsed(id.trim(), token.trim()))
    }

    private fun looksLikeShowId(x: String) =
        x.all { it.isDigit() } || x.startsWith("V-", ignoreCase = true)

    private fun first(o: JSONObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> o.optString(k).trim().ifEmpty { null } }
}
