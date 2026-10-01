package com.lpsm.vod

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.lpsm.vod.data.CatalogApi

class VodApplication : Application(), Application.ActivityLifecycleCallbacks {
    var foregroundGeneration = 0
        private set
    private var startedActivities = 0
    private var wasBackgrounded = false

    override fun onCreate() {
        super.onCreate()
        CrashDiagnostics.install(this)
        CatalogApi.clearCatalogCache(this)
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        if (startedActivities == 0 && wasBackgrounded) {
            CatalogApi.clearCatalogCache(this)
            foregroundGeneration++
            wasBackgrounded = false
        }
        startedActivities++
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0 && !activity.isChangingConfigurations) {
            CatalogApi.clearCatalogCache(this)
            wasBackgrounded = true
        }
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityResumed(activity: Activity) { ScreenAdjustment.attach(activity) }
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
