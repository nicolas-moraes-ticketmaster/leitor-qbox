package br.com.leitorqbox

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Configuração persistida do leitor (SharedPreferences): dados do aparelho + eventos cadastrados. */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("leitor_qbox", Context.MODE_PRIVATE)

    var gate by str("gate", "TC22-01")
    var pin by str("pin")
    var activeEventId by str("active_event")

    var checkOnly: Boolean
        get() = sp.getBoolean("check_only", false)
        set(value) = sp.edit().putBoolean("check_only", value).apply()

    var events: List<Event>
        get() = runCatching { Event.listFromJson(sp.getString("events", "[]") ?: "[]") }.getOrDefault(emptyList())
        set(value) = sp.edit().putString("events", Event.listToJson(value)).apply()

    val activeEvent: Event?
        get() = events.let { list -> list.firstOrNull { it.id == activeEventId } ?: list.firstOrNull() }

    fun upsert(event: Event) {
        val list = events.toMutableList()
        val i = list.indexOfFirst { it.id == event.id }
        if (i >= 0) list[i] = event else list.add(event)
        events = list
    }

    fun delete(id: String) {
        events = events.filterNot { it.id == id }
        if (activeEventId == id) activeEventId = events.firstOrNull()?.id.orEmpty()
    }

    fun isConfigured() = activeEvent?.isComplete() == true && gate.isNotBlank()

    fun config(event: Event? = activeEvent): QboxClient.Config {
        val e = event ?: Event(name = "", host = "", showId = "", token = "")
        return QboxClient.Config(
            baseUrl = e.baseUrl(),
            showId = cleanShowId(e.showId),
            token = cleanToken(e.token),
            gate = gate.trim(),
            // Nenhum marcado = todos os setores conhecidos do evento.
            sectorIds = e.selectedSectorIds.ifEmpty { e.sectors.map { it.id } },
            checkOnly = checkOnly,
        )
    }

    /**
     * Uma vez por instalação: converte a configuração única das versões antigas em evento
     * e cadastra os eventos de teste.
     */
    fun seedOnce() {
        if (sp.getBoolean(SEED_KEY, false)) return
        val list = events.toMutableList()

        val oldShow = sp.getString("show_id", "").orEmpty()
        if (oldShow.isNotBlank() && list.none { it.showId == oldShow }) {
            val oldSectors = runCatching {
                val arr = JSONArray(sp.getString("sector_list", "[]"))
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    QboxClient.Sector(o.getInt("id"), o.optString("name"))
                }
            }.getOrDefault(emptyList())
            list.add(
                Event(
                    name = "Show $oldShow",
                    host = sp.getString("host", "").orEmpty(),
                    showId = oldShow,
                    token = sp.getString("token", "").orEmpty(),
                    sectors = oldSectors,
                    selectedSectorIds = sp.getString("sector_ids", "").orEmpty()
                        .split(",").mapNotNull { it.trim().toIntOrNull() },
                )
            )
        }
        TEST_EVENTS.forEach { t -> if (list.none { it.showId == t.showId }) list.add(t) }

        events = list
        if (list.none { it.id == activeEventId }) activeEventId = list.firstOrNull()?.id.orEmpty()
        sp.edit().putBoolean(SEED_KEY, true).apply()
    }

    private fun str(key: String, def: String = "") = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getString(key, def) ?: def
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            sp.edit().putString(key, value).apply()
        }
    }

    companion object {
        private const val SEED_KEY = "seed_events_v1"

        // TESTE: eventos pré-cadastrados. Remover antes de usar em evento real.
        private val TEST_EVENTS = listOf(
            Event(
                name = "Evento Teste TI - DAY 1",
                host = "192.168.22.231",
                showId = "25860",
                token = "W99WFZHCPSJ1FJZNMMUFC2QB9W3YN2L2NLYV",
                sectors = listOf(
                    QboxClient.Sector(35865665, "Facial"),
                    QboxClient.Sector(204618, "VIP RFID"),
                    QboxClient.Sector(198649, "Cadeira Inferior"),
                    QboxClient.Sector(198646, "GA"),
                    QboxClient.Sector(198645, "Pista Premium"),
                ),
            ),
            Event(
                name = "Oktoberfest 2026 (virtual)",
                host = "192.168.22.231",
                showId = "V-OKT2026",
                token = "OKT2026VIRTUALKEY9X7B2M4Q1WPRSF6ZT8L",
            ),
        )

        // Remove só espaços, quebras e caracteres invisíveis que vêm junto ao colar.
        // Não mexe no resto: shows virtuais (V-...) têm token próprio, com qualquer caractere e caixa.
        private fun stripInvisible(raw: String) =
            raw.filterNot { it.isWhitespace() || Character.getType(it) == Character.FORMAT.toInt() }

        fun cleanShowId(raw: String) = stripInvisible(raw)
        fun cleanToken(raw: String) = stripInvisible(raw)

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
