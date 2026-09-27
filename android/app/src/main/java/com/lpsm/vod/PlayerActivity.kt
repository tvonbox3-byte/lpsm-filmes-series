package com.lpsm.vod

import android.app.Activity
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.lpsm.vod.databinding.ActivityPlayerBinding

class PlayerActivity: Activity() {
    private lateinit var b: ActivityPlayerBinding
    private var p: ExoPlayer? = null
    override fun onCreate(s: Bundle?) { super.onCreate(s); b = ActivityPlayerBinding.inflate(layoutInflater); setContentView(b.root)
        val url = intent.getStringExtra("url") ?: return finish()
        p = ExoPlayer.Builder(this).build().also { b.playerView.player = it; it.setMediaItem(MediaItem.fromUri(url)); it.prepare(); it.playWhenReady = true }
    }
    override fun onDestroy() { p?.release(); super.onDestroy() }
}
