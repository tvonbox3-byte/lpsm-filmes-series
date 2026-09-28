package com.lpsm.vod

import android.app.Activity
import android.os.Bundle
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
    private var contentKey = ""
    private var contentName = ""
    private var contentImage: String? = null
    private var contentModeSeries = false
    private var contentAdult = false
    private var headersValue: Map<String, String> = emptyMap()

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressSaver = object : Runnable {
        override fun run() {
            saveProgress()
            progressHandler.postDelayed(this, 12_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        library = LocalLibrary(this)

        val url = intent.getStringExtra("url")?.trim().orEmpty()
        if (url.isBlank()) {
            Toast.makeText(this, "Link do vídeo não disponível.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        urlValue = url
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
        headers["User-Agent"] = "LPSM-VOD/1.8.3 (Android)"
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

        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(30000)
            .setDefaultRequestProperties(headers)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpFactory)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 60000, 1500, 3000)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
            .also { exo ->
                b.playerView.player = exo
                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
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

                val media = MediaItem.Builder()
                    .setUri(url)
                    .apply {
                        val path = url.substringBefore('?').lowercase()
                        when {
                            path.endsWith(".m3u8") -> setMimeType(MimeTypes.APPLICATION_M3U8)
                            path.endsWith(".mpd") -> setMimeType(MimeTypes.APPLICATION_MPD)
                        }
                    }
                    .build()

                exo.setMediaItem(media)

                val resume =
                    if (contentKey.isNotBlank()) {
                        library.resumePosition(contentKey)
                    } else {
                        0L
                    }

                if (resume > 0L) exo.seekTo(resume)

                exo.prepare()
                exo.playWhenReady = true
            }

        progressHandler.postDelayed(progressSaver, 12_000L)
    }

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

    override fun onPause() {
        saveProgress()
        super.onPause()
    }

    override fun onDestroy() {
        progressHandler.removeCallbacksAndMessages(null)
        saveProgress()
        player?.release()
        player = null
        super.onDestroy()
    }
}
