package com.jhonnysga.bpenvivo

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Respaldo local de IDs de canales en vivo (Dailymotion, canal bpenvivo).
 * Cada vez que se descubren canales al aire, se guardan aquí. Si el
 * descubrimiento falla (API caída, sin red), la app muestra el respaldo.
 * Crece solo: nunca se borran IDs, se actualiza el título y la fecha.
 */
object ChannelBackup {

    private const val PREFS = "channel_backup"
    private const val KEY = "channels"
    private const val MAX_KEPT = 50

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** Guarda/actualiza los canales descubiertos. */
    fun save(items: List<StreamItem>) {
        if (!::prefs.isInitialized || items.isEmpty()) return
        val existing = loadRaw().toMutableMap()
        val now = System.currentTimeMillis()
        for (item in items) {
            existing[item.id] = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("thumbnail", item.thumbnail ?: "")
                put("channel", item.channel ?: "")
                put("lastSeen", now)
            }
        }
        val arr = JSONArray()
        existing.values
            .sortedByDescending { it.optLong("lastSeen") }
            .take(MAX_KEPT)
            .forEach { arr.put(it) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    /** Canales respaldados (los más recientes primero). */
    fun load(): List<StreamItem> {
        if (!::prefs.isInitialized) return emptyList()
        return loadRaw().values
            .sortedByDescending { it.optLong("lastSeen") }
            .map { o ->
                StreamItem(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    thumbnail = o.optString("thumbnail").takeIf { it.isNotBlank() },
                    channel = o.optString("channel").takeIf { it.isNotBlank() },
                    logo = null
                )
            }
            .filter { it.id.isNotBlank() }
    }

    private fun loadRaw(): Map<String, JSONObject> {
        val out = mutableMapOf<String, JSONObject>()
        val raw = prefs.getString(KEY, null) ?: return out
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isNotBlank()) out[id] = o
        }
        return out
    }
}
