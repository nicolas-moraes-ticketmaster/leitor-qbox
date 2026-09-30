package br.com.leitorqbox

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Configuração persistida do leitor (SharedPreferences). */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("leitor_qbox", Context.MODE_PRIVATE)

    var host by str("host")
    var showId by str("show_id")
    var token by str("token")
    var gate by str("gate", "TC22-01")
    var pin by str("pin")

    /** IDs dos setores aceitos por este leitor, ex. "10,11". Vazio = todos os setores. */
    var sectorIdsRaw by str("sector_ids")

    /** Cache da lista de setores do show (JSON), para não depender de recarregar. */
    var sectorListRaw by str("sector_list", "[]")

    var checkOnly: Boolean
        get() = sp.getBoolean("check_only", false)
        set(value) = sp.edit().putBoolean("check_only", value).apply()

    val sectorIds: List<Int>
        get() = sectorIdsRaw.split(",").mapNotNull { it.trim().toIntOrNull() }

    var sectorList: List<QboxClient.Sector>
        get() = runCatching {
            val arr = JSONArray(sectorListRaw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                QboxClient.Sector(o.getInt("id"), o.optString("name"))
            }
        }.getOrDefault(emptyList())
        set(value) {
            val arr = JSONArray()
            value.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name)) }
            sectorListRaw = arr.toString()
        }

    /** Na primeira abertura desta versão, grava o show de teste (sobrescreve o que estava salvo). */
    fun applyTestDefaultsOnce() {
        if (sp.getBoolean(TEST_DEFAULTS_KEY, false)) return
        applyTestDefaults()
        sp.edit().putBoolean(TEST_DEFAULTS_KEY, true).apply()
    }

    fun applyTestDefaults() {
        host = TEST_HOST
        showId = TEST_SHOW_ID
        token = TEST_TOKEN
        sectorList = TEST_SECTORS
        sectorIdsRaw = ""
        checkOnly = false
    }

    fun isConfigured() =
        host.isNotBlank() && showId.isNotBlank() && token.isNotBlank() && gate.isNotBlank()

    fun config() = QboxClient.Config(
        baseUrl = normalizeBaseUrl(host),
        showId = cleanShowId(showId),
        token = cleanToken(token),
        gate = gate.trim(),
        // Nenhum marcado = todos os setores conhecidos do show.
        sectorIds = sectorIds.ifEmpty { sectorList.map { it.id } },
        checkOnly = checkOnly,
    )

    private fun str(key: String, def: String = "") = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getString(key, def) ?: def
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            sp.edit().putString(key, value).apply()
        }
    }

    companion object {
        // TESTE: show "Evento Teste TI - OFICIAL - DAY 1". Remover antes de usar em evento real.
        const val TEST_HOST = "192.168.22.231"
        const val TEST_SHOW_ID = "25860"
        const val TEST_TOKEN = "W99WFZHCPSJ1FJZNMMUFC2QB9W3YN2L2NLYV"
        val TEST_SECTORS = listOf(
            QboxClient.Sector(35865665, "Facial"),
            QboxClient.Sector(204618, "VIP RFID"),
            QboxClient.Sector(198649, "Cadeira Inferior"),
            QboxClient.Sector(198646, "GA"),
            QboxClient.Sector(198645, "Pista Premium"),
        )
        private const val TEST_DEFAULTS_KEY = "test_defaults_v1"

        // Remove espaços, quebras e caracteres invisíveis que vêm junto ao colar.
        fun cleanShowId(raw: String) = raw.filter { it.isLetterOrDigit() || it == '-' }
        fun cleanToken(raw: String) = raw.filter { it.isLetterOrDigit() }

        /** "192.168.0.50" -> "http://192.168.0.50:8080" (porta padrão do Q-Box). */
        fun normalizeBaseUrl(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (s.isEmpty()) return ""
            if (!s.contains("://")) s = "http://$s"
            val uri = Uri.parse(s)
            val host = uri.host ?: return s
            val port = if (uri.port == -1) 8080 else uri.port
            return "${uri.scheme}://$host:$port"
        }
    }
}
