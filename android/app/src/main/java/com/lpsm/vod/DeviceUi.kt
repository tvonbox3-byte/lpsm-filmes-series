package com.lpsm.vod

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

object DeviceUi {
    fun isTouchDevice(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) != Configuration.UI_MODE_TYPE_TELEVISION &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
}
