package com.lpsm.vod

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import androidx.media3.ui.AspectRatioFrameLayout
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lpsm.vod.data.LocalLibrary
import com.lpsm.vod.databinding.ActivityPlayerBinding
import org.json.JSONObject

class PlayerActivity : Activity() {
    private lateinit var b: ActivityPlayerBinding
    private lateinit var library: LocalLibrary
    private var player: ExoPlayer? = null

    private var urlValue = ""
    private var alternateUrl = ""
    private var triedAlternate = false
    private var contentKey = ""
    private var contentName = ""
    private var contentImage: String? = null
    private var contentModeSeries = false
    private var contentAdult = false
    private var resumePositionMs = 0L
    private var playWhenReadyValue = true
    private var headersValue: Map<String, String> = emptyMap()

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressSaver = object : Runnable {
        override fun run() {
            saveProgress()
            progressHandler.postDelayed(this, 12_000L)
        }
    }

    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        library = LocalLibrary(this)
        applyVideoFormat()
        b.screenBtn.setOnClickListener { showScreenOptions() }
        b.playerView.setControllerVisibilityListener(androidx.media3.ui.PlayerView.ControllerVisibilityListener { visibility ->
            b.screenBtn.visibility = visibility
        })

        val url = intent.getStringExtra("url")?.trim().orEmpty()
        if (url.isBlank()) {
            Toast.makeText(this, "Link do vídeo não disponível.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        urlValue = url
        alternateUrl = intent.getStringExtra("alternateUrl")?.trim().orEmpty()
        contentKey = intent.getStringExtra("contentKey").orEmpty()
        contentName = intent.getStringExtra("contentName")
            ?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra("title").orEmpty()
        contentImage = intent.getStringExtra("contentImage")
        contentModeSeries =
            intent.getBooleanExtra("contentModeSeries", false)
        contentAdult =
            intent.getBooleanExtra("contentAdult", false)

        val headers = linkedMapOf<String, String>()
        headers["User-Agent"] = "LPSM-VOD/1.8.4 (Android)"
        headers["Accept"] = "*/*"

        intent.getStringExtra("headers")?.takeIf { it.isNotBlank() }?.let { raw ->
            try {
                val obj = JSONObject(raw)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = obj.optString(key)
                    if (key.isNotBlank() && value.isNotBlank()) headers[key] = value
                }
            } catch (_: Exception) { }
        }

        headersValue = headers.toMap()

        resumePositionMs = savedInstanceState?.getLong("position")
            ?: if (contentKey.isNotBlank()) library.resumePosition(contentKey) else 0L
        playWhenReadyValue = savedInstanceState?.getBoolean("playing", true) ?: true
    }

    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    private fun initializePlayer() {
        if (player != null || urlValue.isBlank() || isFinishing || isDestroyed) return
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(30000)
            .setDefaultRequestProperties(headersValue)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpFactory)

        val loadControlBuilder = DefaultLoadControl.Builder()
        if (DeviceUi.isTouchDevice(this)) {
            loadControlBuilder.setBufferDurationsMs(10000, 30000, 1500, 3000)
                .setTargetBufferBytes(16 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
        } else {
            // Preserva o buffer que já funciona na TV Box.
            loadControlBuilder.setBufferDurationsMs(15000, 60000, 1500, 3000)
        }
        val loadControl = loadControlBuilder.build()

        player = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
            .also { exo ->
                b.playerView.player = exo
                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        if (!triedAlternate && alternateUrl.startsWith("http") && alternateUrl != urlValue) {
                            triedAlternate = true
                            urlValue = alternateUrl
                            exo.setMediaItem(mediaItem(alternateUrl))
                            exo.prepare()
                            exo.playWhenReady = true
                            return
                        }
                        saveProgress()
                        Toast.makeText(
                            this@PlayerActivity,
                            "Não foi possível reproduzir este título. ${error.errorCodeName}",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (
                            playbackState == Player.STATE_ENDED &&
                            contentKey.isNotBlank()
                        ) {
                            library.removeContinue(contentKey)
                        }
                    }
                })

                exo.setMediaItem(mediaItem(urlValue))

                if (resumePositionMs > 0L) exo.seekTo(resumePositionMs)

                exo.prepare()
                exo.playWhenReady = playWhenReadyValue
            }

        progressHandler.removeCallbacks(progressSaver)
        progressHandler.postDelayed(progressSaver, 12_000L)
    }

    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    private fun applyVideoFormat() {
        val mode = getSharedPreferences("lpsm_screen_v1", MODE_PRIVATE).getInt("video_format", 0)
        b.playerView.resizeMode = when (mode) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    private fun showScreenOptions() {
        val prefs = getSharedPreferences("lpsm_screen_v1", MODE_PRIVATE)
        val options = arrayOf("Automático / encolher: vídeo inteiro", "Expandir: preencher com recorte", "Expandir: esticar", "Ajustar bordas do aplicativo")
        AlertDialog.Builder(this)
            .setTitle("Ajustar tela")
            .setSingleChoiceItems(options, prefs.getInt("video_format", 0).coerceIn(0, 2)) { dialog, index ->
                if (index < 3) {
                    prefs.edit().putInt("video_format", index).apply()
                    applyVideoFormat()
                    dialog.dismiss()
                } else {
                    dialog.dismiss()
                    ScreenAdjustment.show(this)
                }
            }
            .setNegativeButton("VOLTAR", null)
            .show()
    }

    private fun mediaItem(url: String): MediaItem = MediaItem.Builder()
        .setUri(url)
        .apply {
            val path = url.substringBefore('?').lowercase()
            when {
                path.endsWith(".m3u8") -> setMimeType(MimeTypes.APPLICATION_M3U8)
                path.endsWith(".mpd") -> setMimeType(MimeTypes.APPLICATION_MPD)
            }
        }
        .build()

    private fun saveProgress() {
        val exo = player ?: return
        if (contentKey.isBlank() || urlValue.isBlank()) return

        val position = exo.currentPosition.coerceAtLeast(0L)
        val duration =
            exo.duration.takeIf {
                it != C.TIME_UNSET && it > 0L
            } ?: 0L

        library.saveProgress(
            contentKey = contentKey,
            name = contentName.ifBlank {
                intent.getStringExtra("title").orEmpty()
            },
            image = contentImage,
            url = urlValue,
            headers = headersValue,
            modeSeries = contentModeSeries,
            adult = contentAdult,
            positionMs = position,
            durationMs = duration
        )
    }

    private fun releasePlayer() {
        progressHandler.removeCallbacks(progressSaver)
        val exo = player ?: return
        resumePositionMs = exo.currentPosition.coerceAtLeast(0L)
        playWhenReadyValue = exo.playWhenReady
        saveProgress()
        b.playerView.player = null
        exo.release()
        player = null
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= 24) initializePlayer()
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < 24) initializePlayer()
    }

    override fun onPause() {
        saveProgress()
        if (Build.VERSION.SDK_INT < 24) releasePlayer()
        super.onPause()
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= 24) releasePlayer()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("position", player?.currentPosition ?: resumePositionMs)
        outState.putBoolean("playing", player?.playWhenReady ?: playWhenReadyValue)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }
}
