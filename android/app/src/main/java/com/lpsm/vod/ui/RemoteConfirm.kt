package com.lpsm.vod.ui

import android.view.KeyEvent
import android.view.View

/** Consome a sequência inteira de OK, sem transformar a primeira repetição em favorito. */
class RemoteConfirm(private val allowLongPress: Boolean = false) {
    private var pressed = false
    private var longHandled = false

    fun handle(view: View, keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER &&
            keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER && keyCode != KeyEvent.KEYCODE_BUTTON_A) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    pressed = true
                    longHandled = false
                }
                if (pressed && allowLongPress && !longHandled && event.eventTime - event.downTime >= 700L) {
                    longHandled = true
                    view.performLongClick()
                }
            }
            KeyEvent.ACTION_UP -> {
                if (pressed && !event.isCanceled && !longHandled) {
                    if (allowLongPress && event.eventTime - event.downTime >= 700L) view.performLongClick()
                    else view.performClick()
                }
                pressed = false
                longHandled = false
            }
        }
        return true
    }
}
