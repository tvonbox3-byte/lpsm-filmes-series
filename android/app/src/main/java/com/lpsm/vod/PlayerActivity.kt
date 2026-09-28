package com.lpsm.vod

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lpsm.vod.databinding.ActivityPlayerBinding
import org.json.JSONObject

class PlayerActivity : Activity() {
    private lateinit var b: ActivityPlayerBinding
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        val url = intent.getStringExtra("url")?.trim().orEmpty()
        if (url.isBlank()) {
            Toast.makeText(this, "Link do vídeo não disponível.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val headers = linkedMapOf<String, String>()
        headers["User-Agent"] = "LPSM-VOD/1.3.0 (Android)"
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
                        Toast.makeText(
                            this@PlayerActivity,
                            "Não foi possível reproduzir este título. ${error.errorCodeName}",
                            Toast.LENGTH_LONG
                        ).show()
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
                exo.prepare()
                exo.playWhenReady = true
            }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
