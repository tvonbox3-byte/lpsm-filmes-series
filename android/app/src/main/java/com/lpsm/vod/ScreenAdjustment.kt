package com.lpsm.vod

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Ajusta o espaço útil sem transformar coordenadas de toque ou foco do controle. */
object ScreenAdjustment {
    private data class Padding(val left: Int, val top: Int, val right: Int, val bottom: Int)
    private val originalPadding = java.util.WeakHashMap<View, Padding>()
    private fun prefs(context: Context) = context.getSharedPreferences("lpsm_screen_v1", Context.MODE_PRIVATE)
    private fun key(context: Context) = if (DeviceUi.isTouchDevice(context)) "margin_phone" else "margin_tv"
    private fun margin(context: Context) = prefs(context).getInt(key(context), 0).coerceIn(0, 10)

    fun attach(activity: Activity) {
        val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        val root = content?.getChildAt(0) ?: return
        if (!originalPadding.containsKey(root)) {
            originalPadding[root] = Padding(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
            root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
                if (r - l != or - ol || b - t != ob - ot) apply(activity, root)
            }
        }
        apply(activity, root)
    }

    private fun apply(context: Context, root: View) {
        val base = originalPadding[root] ?: return
        val percent = margin(context)
        val x = root.width * percent / 100
        val y = root.height * percent / 100
        val left = base.left + x
        val top = base.top + y
        val right = base.right + x
        val bottom = base.bottom + y
        if (root.paddingLeft != left || root.paddingTop != top || root.paddingRight != right || root.paddingBottom != bottom) {
            root.setPadding(left, top, right, bottom)
        }
    }

    fun show(activity: Activity) {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val spacing = (16 * resources.displayMetrics.density).toInt()
            setPadding(spacing, spacing, spacing, spacing)
        }
        val status = TextView(activity).apply { textSize = 18f }
        layout.addView(status)
        fun refresh() {
            status.text = if (margin(activity) == 0) "Automático: usa o espaço disponível da tela."
                else "Margem: ${margin(activity)}% em cada borda."
            attach(activity)
        }
        fun setMargin(value: Int) {
            prefs(activity).edit().putInt(key(activity), value.coerceIn(0, 10)).apply()
            refresh()
        }
        fun button(label: String, action: () -> Unit) {
            layout.addView(Button(activity).apply {
                text = label
                isFocusable = true
                setOnClickListener { action() }
            })
        }
        button("AUTOMÁTICO") { setMargin(0) }
        button("ENCOLHER TELA") { setMargin(margin(activity) + 1) }
        button("EXPANDIR TELA") { setMargin(margin(activity) - 1) }
        refresh()
        AlertDialog.Builder(activity)
            .setTitle("Ajustar tela do aplicativo")
            .setView(layout)
            .setPositiveButton("CONCLUIR", null)
            .show()
    }
}
