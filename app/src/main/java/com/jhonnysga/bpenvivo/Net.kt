package com.jhonnysga.bpenvivo

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Red resiliente para bplay.
 *
 * Algunos operadores móviles fallan o bloquean el DNS para ciertos
 * dominios ("Unable to resolve host"). Este cliente intenta primero el
 * DNS normal del sistema y, si falla, resuelve vía DNS-over-HTTPS
 * (Cloudflare, con IPs fijas que no necesitan DNS) antes de rendirse.
 * Así ni la lista de canales ni la reproducción dependen del DNS del
 * operador.
 */
object Net {

    private const val TIMEOUT_S = 15L

    /** Cliente base para las consultas DoH (solo habla con IPs fijas). */
    private val bootstrapClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    private fun doh(url: String): DnsOverHttps = DnsOverHttps.Builder()
        .client(bootstrapClient)
        .url(url.toHttpUrl())
        .bootstrapDnsHosts(
            listOf(
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("1.0.0.1")
            )
        )
        .includeIPv6(false)
        .build()

    private val dohPrimary = doh("https://cloudflare-dns.com/dns-query")
    private val dohSecondary = doh("https://dns.google/dns-query")

    /** DNS: sistema primero, DoH de respaldo si el operador falla. */
    private val resilientDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            try {
                return Dns.SYSTEM.lookup(hostname)
            } catch (e: UnknownHostException) {
                try {
                    return dohPrimary.lookup(hostname)
                } catch (_: Exception) {
                    return dohSecondary.lookup(hostname)
                }
            }
        }
    }

    /** Cliente compartido: lo usan la API, el extractor y el reproductor. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .dns(resilientDns)
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    /** GET que devuelve el cuerpo como texto. Lanza IOException si falla. */
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val reqBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }
}
