package com.jhonnysga.bpenvivo

/**
 * Una transmisión en vivo de Dailymotion (canal BeisbolPlay).
 * El id es el ID del video en Dailymotion; la URL del stream se extrae
 * en el dispositivo al reproducir.
 */
data class StreamItem(
    val id: String,
    val title: String,
    val thumbnail: String?,
    val channel: String?,
    val logo: String?
)
