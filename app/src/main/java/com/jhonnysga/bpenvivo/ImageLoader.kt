package com.jhonnysga.bpenvivo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cargador de imágenes mínimo con caché en memoria.
 * Descarga en un hilo de fondo y publica el resultado en el hilo principal.
 * Si la URL es nula o la descarga falla, deja un placeholder.
 */
object ImageLoader {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val cache: LruCache<String, Bitmap> =
        object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }

    fun load(url: String?, into: ImageView) {
        into.tag = url
        if (url.isNullOrBlank()) {
            into.setImageResource(R.drawable.ic_placeholder)
            return
        }
        cache.get(url)?.let {
            into.setImageBitmap(it)
            return
        }
        into.setImageResource(R.drawable.ic_placeholder)
        Thread {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    instanceFollowRedirects = true
                }
                val bitmap = conn.inputStream.use { BitmapFactory.decodeStream(it) }
                conn.disconnect()
                if (bitmap != null) {
                    cache.put(url, bitmap)
                    mainHandler.post {
                        // Evita pintar en una vista reciclada que ya muestra otro item.
                        if (into.tag == url) into.setImageBitmap(bitmap)
                    }
                }
            } catch (_: Exception) {
                // Se queda el placeholder.
            }
        }.start()
    }
}
