package com.jhonnysga.bpenvivo

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.PlayerView
import com.jhonnysga.bpenvivo.MainActivity.Companion.EXTRA_BACKEND_URL
import com.jhonnysga.bpenvivo.MainActivity.Companion.EXTRA_STREAM_ID
import com.jhonnysga.bpenvivo.MainActivity.Companion.EXTRA_STREAM_TITLE

/**
 * Reproductor a pantalla completa (teléfono y TV), optimizado para HLS en
 * vivo servido por el backend (proxy.php) a través de una conexión con
 * ancho de banda variable.
 *
 * Estrategia anti-trabones:
 *  1. Tope por defecto en 720p (2.18 Mbps): nítido y estable en el enlace
 *     actual; el usuario puede cambiar a 1080p desde el botón de calidad
 *     (la elección se guarda).
 *  2. Estimación inicial de ancho de banda realista (2 Mbps) para no
 *     arrancar probando la variante más pesada.
 *  3. Buffer dimensionado para en vivo sobre red con jitter.
 *  4. Reconexión automática con backoff exponencial ante errores: cada
 *     reintento pide una playlist maestra fresca (los tokens vencen).
 *
 * Carga `{BACKEND}/proxy.php?m3u8=<id>`: la playlist maestra HLS trae URLs
 * relativas y ExoPlayer las resuelve contra esa misma URL, así todo el
 * tráfico de video sigue pasando por el backend (el token del stream va
 * atado a la IP del servidor).
 *
 * Nota: la URL no termina en ".m3u8", por eso se declara el MIME type
 * HLS explícito en el MediaItem; sin eso ExoPlayer no detectaría el formato.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        /** Tope por defecto: 720p (2.18 Mbps), estable en el enlace actual. */
        private const val MAX_VIDEO_BITRATE = 3_000_000
        /** Estimación inicial para no arrancar en la variante más pesada. */
        private const val INITIAL_BITRATE_ESTIMATE = 2_000_000L
        private const val MAX_RETRIES = 8
        private const val RETRY_BASE_MS = 2_000L
        private const val RETRY_MAX_MS = 30_000L
        private const val PREF_MAX_BITRATE = "max_video_bitrate"
        /** Tiempo sin interacción antes de ocultar título y botón de calidad. */
        private const val OVERLAY_HIDE_MS = 5_000L
    }

    private var player: ExoPlayer? = null
    private var bandwidthMeter: DefaultBandwidthMeter? = null
    private var trackSelector: DefaultTrackSelector? = null
    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var qualityButton: Button
    private lateinit var errorView: View
    private lateinit var retryButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private var retryRunnable: Runnable? = null
    private var overlayHideRunnable: Runnable? = null

    private var streamId: String = ""
    private var streamTitle: String = ""
    private var backendUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        streamId = intent.getStringExtra(EXTRA_STREAM_ID).orEmpty()
        streamTitle = intent.getStringExtra(EXTRA_STREAM_TITLE).orEmpty()
        backendUrl = intent.getStringExtra(EXTRA_BACKEND_URL)
            .takeIf { !it.isNullOrBlank() } ?: BuildConfig.BACKEND_URL
        if (streamId.isBlank()) {
            finish()
            return
        }

        playerView = findViewById(R.id.player_view)
        titleView = findViewById(R.id.player_title)
        statusView = findViewById(R.id.player_status)
        qualityButton = findViewById(R.id.player_quality)
        errorView = findViewById(R.id.player_error)
        retryButton = findViewById(R.id.player_retry)

        titleView.text = streamTitle
        playerView.keepScreenOn = true
        playerView.requestFocus() // control remoto (Fire TV): el D-pad maneja el reproductor
        qualityButton.setOnClickListener { showQualityDialog() }
        qualityButton.isFocusable = true
        updateQualityLabel()
        retryButton.setOnClickListener {
            retryCount = 0
            startPlayback()
        }
        hideSystemBars()
        showOverlays()
    }

    /** Muestra título + botón de calidad y programa su ocultado. */
    private fun showOverlays() {
        titleView.visibility = View.VISIBLE
        qualityButton.visibility = View.VISIBLE
        scheduleOverlayHide()
    }

    private fun hideOverlays() {
        titleView.visibility = View.GONE
        // El botón de calidad se oculta solo si no tiene el foco (TV)
        if (!qualityButton.hasFocus()) {
            qualityButton.visibility = View.GONE
        } else {
            scheduleOverlayHide()
        }
    }

    private fun scheduleOverlayHide() {
        cancelOverlayHide()
        val r = Runnable { hideOverlays() }
        overlayHideRunnable = r
        handler.postDelayed(r, OVERLAY_HIDE_MS)
    }

    private fun cancelOverlayHide() {
        overlayHideRunnable?.let { handler.removeCallbacks(it) }
        overlayHideRunnable = null
    }

    // Teléfono: cualquier toque muestra los overlays de nuevo.
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_DOWN) showOverlays()
        return super.dispatchTouchEvent(ev)
    }

    // Fire TV: cualquier tecla del control muestra los overlays de nuevo.
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        showOverlays()
        return super.onKeyDown(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        retryCount = 0
        startPlayback()
    }

    override fun onStop() {
        super.onStop()
        cancelRetry()
        cancelOverlayHide()
        releasePlayer()
    }

    private fun buildPlayer(): ExoPlayer {
        bandwidthMeter = DefaultBandwidthMeter.Builder(this)
            .setInitialBitrateEstimate(INITIAL_BITRATE_ESTIMATE)
            .build()
        trackSelector = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters()
                    .setMaxVideoBitrate(currentMaxBitrate())
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .build()
            )
        }
        // Buffer para en vivo sobre red con jitter: colchón amplio sin
        // alejarse demasiado del borde del directo.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 20_000,
                /* maxBufferMs = */ 50_000,
                /* bufferForPlaybackMs = */ 2_500,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        return ExoPlayer.Builder(this)
            .setBandwidthMeter(bandwidthMeter!!)
            .setTrackSelector(trackSelector!!)
            .setLoadControl(loadControl)
            .build()
    }

    private fun startPlayback() {
        cancelRetry()
        errorView.visibility = View.GONE
        releasePlayer()
        val uri = "$backendUrl/proxy.php?m3u8=$streamId&t=${System.currentTimeMillis()}"
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
        val exo = buildPlayer().also { player = it }
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        retryCount = 0
                        statusView.visibility = View.GONE
                    }
                    Player.STATE_BUFFERING -> {
                        if (statusView.visibility != View.VISIBLE || statusView.tag != "reconnect") {
                            statusView.text = getString(R.string.status_buffering)
                            statusView.tag = "buffering"
                            statusView.visibility = View.VISIBLE
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                scheduleRetry()
            }
        })
        playerView.player = exo
        statusView.text = getString(R.string.status_connecting)
        statusView.tag = "connecting"
        statusView.visibility = View.VISIBLE
        exo.setMediaItem(mediaItem)
        exo.prepare()
        exo.play()
    }

    /** Tope de calidad elegido por el usuario (por defecto 720p). */
    private fun currentMaxBitrate(): Int {
        return getPreferences(MODE_PRIVATE).getInt(PREF_MAX_BITRATE, MAX_VIDEO_BITRATE)
    }

    private fun updateQualityLabel() {
        qualityButton.text = if (currentMaxBitrate() >= 6_000_000) "⚙ 1080p" else "⚙ 720p"
    }

    /** Diálogo para cambiar entre 720p (estable) y 1080p. */
    private fun showQualityDialog() {
        val options = arrayOf(
            getString(R.string.quality_auto_720),
            getString(R.string.quality_auto_1080)
        )
        val checked = if (currentMaxBitrate() >= 6_000_000) 1 else 0
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.quality_title)
            .setSingleChoiceItems(options, checked) { dialog, which ->
                val cap = if (which == 1) Int.MAX_VALUE else MAX_VIDEO_BITRATE
                getPreferences(MODE_PRIVATE).edit().putInt(PREF_MAX_BITRATE, cap).apply()
                trackSelector?.setParameters(
                    trackSelector!!.buildUponParameters().setMaxVideoBitrate(cap).build()
                )
                updateQualityLabel()
                dialog.dismiss()
            }
            .show()
    }

    /** Reintento automático con backoff: la señal en vivo se recupera sola. */
    private fun scheduleRetry() {        if (retryCount >= MAX_RETRIES) {
            statusView.visibility = View.GONE
            errorView.visibility = View.VISIBLE
            retryButton.requestFocus() // que el control remoto pueda pulsar Reintentar
            return
        }
        retryCount++
        val delay = (RETRY_BASE_MS * (1L shl (retryCount - 1))).coerceAtMost(RETRY_MAX_MS)
        statusView.text = getString(R.string.status_reconnecting, retryCount)
        statusView.tag = "reconnect"
        statusView.visibility = View.VISIBLE
        val r = Runnable { startPlayback() }
        retryRunnable = r
        handler.postDelayed(r, delay)
    }

    private fun cancelRetry() {
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
        trackSelector = null
        bandwidthMeter = null
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                )
        }
    }
}
