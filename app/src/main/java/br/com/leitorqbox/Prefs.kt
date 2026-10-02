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

    /** IP do Q-Box usado como padrão nos eventos novos (menu > Conexão com o servidor). */
    var defaultHost by str("default_host")

    var checkOnly: Boolean
        get() = sp.getBoolean("check_only", false)
        set(value) = sp.edit().putBoolean("check_only", value).apply()

    /** Põe o volume de mídia no máximo ao abrir a tela de leitura. */
    var maxVolume: Boolean
        get() = sp.getBoolean("max_volume", true)
        set(value) = sp.edit().putBoolean("max_volume", value).apply()

    /** IP sugerido para um evento novo: o do servidor configurado ou, se vazio, o do evento ativo. */
    fun hostForNewEvent() = defaultHost.ifBlank { activeEvent?.host.orEmpty() }

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
            // Só os setores marcados; sem nenhum marcado a leitura fica bloqueada.
            sectorIds = e.selectedSectorIds,
            checkOnly = checkOnly,
        )
    }

    /**
     * Uma vez por instalação: converte a configuração única das versões antigas em evento
     * e remove os eventos de teste que versões anteriores cadastravam sozinhas.
     */
    fun seedOnce() {
        if (!sp.getBoolean(PRESET_CLEANUP_KEY, false)) {
            events = events.filterNot { it.name in OLD_PRESET_NAMES }
            if (events.none { it.id == activeEventId }) activeEventId = events.firstOrNull()?.id.orEmpty()
            sp.edit().putBoolean(PRESET_CLEANUP_KEY, true).apply()
        }
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

        // Eventos que as versões 1.0.7 a 1.0.11 cadastravam sozinhas; são removidos uma vez.
        private val OLD_PRESET_NAMES = setOf("Evento Teste TI - DAY 1", "Oktoberfest 2026 (virtual)", "Show 25860")
        private const val PRESET_CLEANUP_KEY = "presets_removed_v1"

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
