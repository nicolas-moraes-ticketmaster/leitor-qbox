package br.com.leitorqbox

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Um evento cadastrado no leitor: show real ou virtual (V-...) em um Q-Box. */
data class Event(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val showId: String,
    val token: String,
    /** Setores do show como o Q-Box devolve em /sectors (no virtual, os setores virtuais). */
    val sectors: List<QboxClient.Sector> = emptyList(),
    /** Setores aceitos por este leitor. Vazio = todos os setores do evento. */
    val selectedSectorIds: List<Int> = emptyList(),
) {
    val isVirtual get() = showId.startsWith("V-", ignoreCase = true)

    fun isComplete() = host.isNotBlank() && showId.isNotBlank() && token.isNotBlank()

    fun baseUrl() = Prefs.normalizeBaseUrl(host)

    /** Atualiza a lista de setores mantendo marcados só os que ainda existem. */
    fun withSectors(list: List<QboxClient.Sector>) = copy(
        sectors = list,
        selectedSectorIds = selectedSectorIds.filter { id -> list.any { it.id == id } },
    )

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("host", host)
        .put("showId", showId)
        .put("token", token)
        .put("sectors", JSONArray().apply {
            sectors.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
        })
        .put("selected", JSONArray(selectedSectorIds))

    companion object {
        fun fromJson(o: JSONObject): Event {
            val sectors = o.optJSONArray("sectors") ?: JSONArray()
            val selected = o.optJSONArray("selected") ?: JSONArray()
            return Event(
                id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
                name = o.optString("name"),
                host = o.optString("host"),
                showId = o.optString("showId"),
                token = o.optString("token"),
                sectors = (0 until sectors.length()).map {
                    val s = sectors.getJSONObject(it)
                    QboxClient.Sector(s.getInt("id"), s.optString("name"))
                },
                selectedSectorIds = (0 until selected.length()).map { selected.getInt(it) },
            )
        }

        fun listFromJson(raw: String): List<Event> {
            val arr = JSONArray(raw)
            return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }

        fun listToJson(list: List<Event>): String =
            JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
    }
}
