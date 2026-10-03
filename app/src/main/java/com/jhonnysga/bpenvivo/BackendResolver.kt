package com.jhonnysga.bpenvivo

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Resuelve la mejor URL del backend.
 *
 * Estrategia: la app trae grabada la URL del Funnel (funciona desde
 * cualquier red). Al arrancar, pregunta al backend por su IP de red
 * local y la prueba con un timeout corto: si responde, se usa la vía
 * local (mucho más rápida, sin salir a internet); si no, se sigue con
 * el Funnel como hasta ahora.
 *
 * El resultado se cachea en memoria por 10 minutos para no re-probar
 * en cada pantalla.
 */
object BackendResolver {

    private const val PROBE_TIMEOUT_MS = 2_500
    private const val CACHE_MS = 10 * 60 * 1_000L

    @Volatile private var cachedUrl: String? = null
    @Volatile private var cachedAt: Long = 0L

    /** Devuelve la mejor URL base. Llamar fuera del hilo principal. */
    @Synchronized
    fun resolve(funnelUrl: String): String {
        val now = System.currentTimeMillis()
        cachedUrl?.let { if (now - cachedAt < CACHE_MS) return it }

        val best = pickBest(funnelUrl) ?: funnelUrl
        cachedUrl = best
        cachedAt = now
        return best
    }

    fun invalidate() {
        cachedUrl = null
        cachedAt = 0L
    }

    private fun pickBest(funnelUrl: String): String? {
        // 1. Pedir la IP local al backend (por el Funnel, siempre alcanzable)
        val localIp = fetchLocalIp(funnelUrl) ?: return null
        if (localIp.isBlank()) return null
        // 2. Probar la vía local con timeout corto
        val localUrl = "http://$localIp:8901"
        return if (probe(localUrl)) localUrl else null
    }

    private fun fetchLocalIp(funnelUrl: String): String? {
        return try {
            val conn = (URL("$funnelUrl/localip.php").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "GET"
            }
            try {
                if (conn.responseCode !in 200..299) return null
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                JSONObject(body).optString("ip").takeIf { it.isNotBlank() }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun probe(baseUrl: String): Boolean {
        return try {
            val conn = (URL("$baseUrl/index.php?api=videos").openConnection() as HttpURLConnection).apply {
                connectTimeout = PROBE_TIMEOUT_MS
                readTimeout = PROBE_TIMEOUT_MS
                requestMethod = "GET"
            }
            try {
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }
}
