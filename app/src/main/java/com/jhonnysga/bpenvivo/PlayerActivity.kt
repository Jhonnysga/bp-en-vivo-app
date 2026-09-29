package com.jhonnysga.bpenvivo

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.jhonnysga.bpenvivo.MainActivity.Companion.EXTRA_STREAM_ID
import com.jhonnysga.bpenvivo.MainActivity.Companion.EXTRA_STREAM_TITLE

/**
 * Reproductor a pantalla completa (teléfono y TV).
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

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private lateinit var errorView: View
    private lateinit var retryButton: Button

    private var streamId: String = ""
    private var streamTitle: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        streamId = intent.getStringExtra(EXTRA_STREAM_ID).orEmpty()
        streamTitle = intent.getStringExtra(EXTRA_STREAM_TITLE).orEmpty()
        if (streamId.isBlank()) {
            Toast.makeText(this, R.string.error_no_stream, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        playerView = findViewById(R.id.player_view)
        titleView = findViewById(R.id.player_title)
        errorView = findViewById(R.id.player_error)
        retryButton = findViewById(R.id.player_retry)

        titleView.text = streamTitle
        playerView.keepScreenOn = true
        retryButton.setOnClickListener { startPlayback() }
        hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        startPlayback()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    private fun startPlayback() {
        errorView.visibility = View.GONE
        releasePlayer()
        val uri = "${BuildConfig.BACKEND_URL}/proxy.php?m3u8=$streamId"
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
        val exo = ExoPlayer.Builder(this).build().also { player = it }
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                errorView.visibility = View.VISIBLE
                Toast.makeText(
                    this@PlayerActivity,
                    R.string.error_playback,
                    Toast.LENGTH_LONG
                ).show()
            }
        })
        playerView.player = exo
        exo.setMediaItem(mediaItem)
        exo.prepare()
        exo.play()
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
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
