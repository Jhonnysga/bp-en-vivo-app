package com.jhonnysga.bpenvivo

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente del backend BéisbolPlay.
 *
 * Contrato: `GET {baseUrl}/index.php?api=videos` responde
 * `{"updated":<unix>,"live":[{"id","title","thumbnail"|null,"channel"|null,"logo"|null}]}`.
 * En error el backend responde HTTP 502 con `{"error":"..."}`.
 *
 * Debe llamarse fuera del hilo principal; lanza IOException si algo falla.
 */
object ApiClient {

    fun fetchLive(baseUrl: String): List<StreamItem> {
        val conn = (URL("$baseUrl/index.php?api=videos").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(body).optString("error") }
                    .getOrNull()?.takeIf { it.isNotBlank() }
                throw IOException(detail ?: "Error del servidor (HTTP $code)")
            }
            val live = JSONObject(body).optJSONArray("live") ?: return emptyList()
            val out = ArrayList<StreamItem>(live.length())
            for (i in 0 until live.length()) {
                val o = live.getJSONObject(i)
                val id = o.optString("id")
                if (id.isBlank()) continue
                out.add(
                    StreamItem(
                        id = id,
                        title = o.optString("title").ifBlank { id },
                        thumbnail = o.optNullableString("thumbnail"),
                        channel = o.optNullableString("channel"),
                        logo = o.optNullableString("logo")
                    )
                )
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    /** optString que además trata el null de JSON como nulo de Kotlin. */
    private fun JSONObject.optNullableString(name: String): String? {
        if (isNull(name)) return null
        return optString(name).takeIf { it.isNotBlank() }
    }
}
