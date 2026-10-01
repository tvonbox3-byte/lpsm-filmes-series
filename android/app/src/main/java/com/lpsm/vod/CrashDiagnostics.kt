package com.lpsm.vod

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import java.io.File

object CrashDiagnostics {
    private const val FILE = "last_failure.txt"
    private var shown = false

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                // Não grava links, senhas nem conteúdo do catálogo no relatório.
                val stack = error.stackTraceToString()
                    .replace(Regex("https?://[^\\s]+"), "[link removido]")
                    .take(20000)
                File(app.filesDir, FILE).writeText(
                    "LPSM 1.8.16\nAndroid ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}\n" +
                        "Modelo: ${Build.MANUFACTURER} ${Build.MODEL}\nThread: ${thread.name}\n$stack"
                )
            } catch (_: Throwable) { }
            if (previous != null) previous.uncaughtException(thread, error)
            else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    fun showPending(activity: Activity) {
        if (shown || activity.isFinishing || activity.isDestroyed) return
        val file = File(activity.filesDir, FILE)
        if (!file.exists()) return
        try {
            val report = file.readText()
            shown = true
            AlertDialog.Builder(activity)
                .setTitle("Relatório do último fechamento")
                .setMessage("O app registrou o erro anterior. Copie o relatório e envie para corrigirmos a causa exata.")
                .setPositiveButton("COPIAR ERRO") { _, _ ->
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Erro LPSM", report))
                    Toast.makeText(activity, "Relatório copiado. Cole na conversa.", Toast.LENGTH_LONG).show()
                    file.delete()
                }
                .setNegativeButton("DEPOIS", null)
                .show()
        } catch (_: Exception) { shown = false }
    }
}
