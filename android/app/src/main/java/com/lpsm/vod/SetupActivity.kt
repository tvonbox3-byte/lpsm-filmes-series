package com.lpsm.vod

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import com.lpsm.vod.data.DeviceApi
import com.lpsm.vod.databinding.ActivitySetupBinding
import java.util.concurrent.Executors

class SetupActivity : Activity() {
    private lateinit var b: ActivitySetupBinding
    private val pool = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var checking = false

    private val poll = object : Runnable {
        override fun run() {
            checkActivation()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DeviceUi.isTouchDevice(this)) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        b = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.deviceCode.text = DeviceApi.deviceCode(this)
        b.retry.setOnClickListener { checkActivation() }
        checkActivation()
        handler.postDelayed(poll, 5000)
    }

    private fun checkActivation() {
        if (checking || isFinishing || isDestroyed || pool.isShutdown) return
        checking = true
        b.progress.visibility = View.VISIBLE
        b.msg.text = "Registrando aparelho no painel..."
        pool.execute {
            try {
                DeviceApi.heartbeat(this)
                val result = DeviceApi.fetchActivation(this)
                runOnUiThread {
                    if (isFinishing || isDestroyed || pool.isShutdown) return@runOnUiThread
                    if (result.active) {
                        b.msg.text = "Ativado. Abrindo catálogo M3U..."
                        setResult(RESULT_OK)
                        handler.postDelayed({ finish() }, 350)
                    } else {
                        b.msg.text = result.message
                    }
                    b.progress.visibility = View.VISIBLE
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed || pool.isShutdown) return@runOnUiThread
                    b.msg.text = "Servidor temporariamente indisponível. Tentaremos novamente automaticamente."
                }
            } finally {
                checking = false
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        pool.shutdownNow()
        super.onDestroy()
    }
}
