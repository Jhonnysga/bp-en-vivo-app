package com.jhonnysga.bpenvivo.mitv

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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.PlayerView
import com.jhonnysga.bpenvivo.R

/**
 * Reproductor de MiTV (canales en vivo, películas, episodios y catchup).
 *
 * Flujo: resuelve la URL real con `SetplexClient` (`.../url`), reescribe las
 * macros `STPLX_*` y reproduce el HLS con ExoPlayer. Si el contenido viene
 * cifrado, configura Widevine con la URL de licencia (y headers) que devuelva
 * el endpoint — ver notas en [SetplexClient].
 *
 * Reutiliza el layout del reproductor existente (`activity_player`) y su
 * estrategia anti-trabones (tope 720p por defecto, buffer para en vivo,
 * reconexión con backoff).
 */
class MiTvPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_REF1 = "ref1"
        const val EXTRA_REF2 = "ref2"

        const val KIND_TV = "tv"
        const val KIND_VOD = "vod"
        const val KIND_EPISODE = "episode"
        const val KIND_CATCHUP = "catchup"

        private const val MAX_VIDEO_BITRATE = 3_000_000
        private const val INITIAL_BITRATE_ESTIMATE = 2_000_000L
        private const val MAX_RETRIES = 8
        private const val RETRY_BASE_MS = 2_000L
        private const val RETRY_MAX_MS = 30_000L
        private const val PREF_MAX_BITRATE = "max_video_bitrate"
        private const val OVERLAY_HIDE_MS = 5_000L
    }

    private lateinit var store: SessionStore
    private var player: ExoPlayer? = null
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

    private var kind: String = KIND_TV
    private var refId: String = ""
    private var ref1: String = ""
    private var ref2: String = ""
    private var title: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        store = SessionStore(this)

        kind = intent.getStringExtra(EXTRA_KIND) ?: KIND_TV
        refId = intent.getStringExtra(EXTRA_ID).orEmpty()
        ref1 = intent.getStringExtra(EXTRA_REF1).orEmpty()
        ref2 = intent.getStringExtra(EXTRA_REF2).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (refId.isBlank() || !store.hasSession()) {
            finish()
            return
        }

        playerView = findViewById(R.id.player_view)
        titleView = findViewById(R.id.player_title)
        statusView = findViewById(R.id.player_status)
        qualityButton = findViewById(R.id.player_quality)
        errorView = findViewById(R.id.player_error)
        retryButton = findViewById(R.id.player_retry)

        titleView.text = title
        playerView.keepScreenOn = true
        playerView.requestFocus()
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

    private fun showOverlays() {
        titleView.visibility = View.VISIBLE
        qualityButton.visibility = View.VISIBLE
        scheduleOverlayHide()
    }

    private fun hideOverlays() {
        titleView.visibility = View.GONE
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

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_DOWN) showOverlays()
        return super.dispatchTouchEvent(ev)
    }

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

    /** Resuelve la URL de reproducción y arma el player (con Widevine si aplica). */
    private fun startPlayback() {
        cancelRetry()
        errorView.visibility = View.GONE
        releasePlayer()
        statusView.text = getString(R.string.status_connecting)
        statusView.tag = "connecting"
        statusView.visibility = View.VISIBLE

        Thread {
            val result = runCatching {
                val playable = when (kind) {
                    KIND_VOD -> SetplexClient.getVodUrl(store, refId)
                    KIND_EPISODE -> SetplexClient.getEpisodeUrl(store, ref1, ref2, refId)
                    KIND_CATCHUP -> SetplexClient.getCatchupUrl(store, ref1, ref2, refId)
                    else -> SetplexClient.getChannelUrl(store, refId)
                }
                playable.copy(streamUrl = SetplexClient.rewriteMacros(playable.streamUrl, store))
            }
            runOnUiThread {
                result
                    .onSuccess { playable -> attachPlayer(playable) }
                    .onFailure { e ->
                        if (e is SetplexClient.AuthException) {
                            startActivity(
                                android.content.Intent(this, MiTvLoginActivity::class.java)
                            )
                            finish()
                        } else {
                            showFatalError(e.message ?: getString(R.string.error_playback))
                        }
                    }
            }
        }.start()
    }

    /**
     * Para catchup: ref1 = channelId, ref2 = catchupId, refId = programmeId.
     * El catchupId viaja desde el listado (ver [SetplexClient.getCatchupProgrammes]).
     */
    private fun attachPlayer(playable: PlayableUrl) {
        val msf = DefaultMediaSourceFactory(this)
        if (!playable.licenseUrl.isNullOrBlank()) {
            val cb = HttpMediaDrmCallback(
                playable.licenseUrl,
                DefaultHttpDataSource.Factory()
            )
            playable.licenseHeaders.forEach { (k, v) -> cb.setKeyRequestProperty(k, v) }
            val drmManager = DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .build(cb)
            msf.setDrmSessionManagerProvider { drmManager }
        }

        val bandwidthMeter = DefaultBandwidthMeter.Builder(this)
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
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 50_000, 2_500, 5_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val itemBuilder = MediaItem.Builder()
            .setUri(playable.streamUrl)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
        if (!playable.licenseUrl.isNullOrBlank()) {
            itemBuilder.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(playable.licenseUrl)
                    .build()
            )
        }
        val exo = ExoPlayer.Builder(this, msf)
            .setBandwidthMeter(bandwidthMeter)
            .setTrackSelector(trackSelector!!)
            .setLoadControl(loadControl)
            .build()
            .also { player = it }
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        retryCount = 0
                        statusView.visibility = View.GONE
                    }
                    Player.STATE_BUFFERING -> {
                        if (statusView.tag != "reconnect") {
                            statusView.text = getString(R.string.status_buffering)
                            statusView.tag = "buffering"
                            statusView.visibility = View.VISIBLE
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (isDrmError(error)) {
                    showFatalError(getString(R.string.mitv_drm_error))
                } else {
                    scheduleRetry()
                }
            }
        })
        playerView.player = exo
        exo.setMediaItem(itemBuilder.build())
        exo.prepare()
        exo.play()
    }

    private fun isDrmError(error: PlaybackException): Boolean {
        val code = error.errorCode
        return code == PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR ||
            code == PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR ||
            code == PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED ||
            code == PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED ||
            code == PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED ||
            code == PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED
    }

    private fun showFatalError(msg: String) {
        statusView.visibility = View.GONE
        errorView.visibility = View.VISIBLE
        findViewById<TextView>(R.id.player_error_text).text = msg
        retryButton.requestFocus()
    }

    private fun currentMaxBitrate(): Int {
        return getPreferences(MODE_PRIVATE).getInt(PREF_MAX_BITRATE, MAX_VIDEO_BITRATE)
    }

    private fun updateQualityLabel() {
        qualityButton.text = if (currentMaxBitrate() >= 6_000_000) "⚙ 1080p" else "⚙ 720p"
    }

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

    private fun scheduleRetry() {
        if (retryCount >= MAX_RETRIES) {
            showFatalError(getString(R.string.error_playback))
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
