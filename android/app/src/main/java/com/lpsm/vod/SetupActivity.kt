package com.lpsm.vod

import android.app.Activity
import android.os.Bundle
import com.lpsm.vod.data.SourceConfig
import com.lpsm.vod.databinding.ActivitySetupBinding
import java.util.concurrent.Executors

class SetupActivity: Activity() {
    private lateinit var b: ActivitySetupBinding
    private val pool = Executors.newSingleThreadExecutor()
    override fun onCreate(s: Bundle?) { super.onCreate(s); b = ActivitySetupBinding.inflate(layoutInflater); setContentView(b.root)
        b.save.setOnClickListener {
            val cfg = SourceConfig.fromUrl(b.url.text.toString())
            if (cfg == null) { b.msg.text = "URL inválida. Use uma URL com username e password."; return@setOnClickListener }
            b.msg.text = "Testando..."
            pool.execute {
                try {
                    val api = com.lpsm.vod.data.XtreamApi(cfg)
                    api.movieCategories()
                    SourceConfig.save(this, cfg)
                    runOnUiThread { setResult(RESULT_OK); finish() }
                } catch (e: Exception) { runOnUiThread { b.msg.text = "Não conectou: ${e.message ?: "erro"}" } }
            }
        }
    }
}
