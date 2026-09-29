package com.jhonnysga.bpenvivo

/**
 * Una transmisión en vivo según el backend (`GET index.php?api=videos`).
 */
data class StreamItem(
    val id: String,
    val title: String,
    val thumbnail: String?,
    val channel: String?,
    val logo: String?
)
