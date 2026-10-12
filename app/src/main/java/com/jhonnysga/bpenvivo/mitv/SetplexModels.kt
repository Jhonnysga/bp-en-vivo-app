package com.jhonnysga.bpenvivo.mitv

/**
 * Modelos de la API Setplex (Thundernet TV GO).
 * Todos los campos se leen de forma tolerante: la forma exacta de algunas
 * respuestas no pudo verificarse (geoblock) y se documenta en SetplexClient.
 */
data class TvChannel(
    val id: String,
    val name: String,
    val logoUrl: String?,
    val channelNumber: String?,
    val categoryId: String?,
    val categoryName: String?,
    val epgId: String?
)

data class TvCategory(val id: String, val name: String)

data class EpgProgram(val title: String, val startMs: Long, val endMs: Long)

data class VodItem(val id: String, val title: String, val posterUrl: String?)

data class SeasonItem(
    val seriesId: String,
    val id: String,
    val title: String,
    val posterUrl: String?
)

data class EpisodeItem(
    val seriesId: String,
    val seasonId: String,
    val id: String,
    val title: String,
    val posterUrl: String?
)

data class CatchupProgramme(
    val catchupId: String,
    val id: String,
    val title: String,
    val startMs: Long
)

/**
 * Resultado del endpoint `.../url`: URL del stream HLS más la info de DRM
 * cuando el contenido va cifrado (Widevine en web/Android).
 */
data class PlayableUrl(
    val streamUrl: String,
    val licenseUrl: String?,
    val licenseHeaders: Map<String, String>
)
