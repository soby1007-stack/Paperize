package com.anthonyla.paperize.service.screenoff

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.core.util.causeChain
import com.anthonyla.paperize.domain.model.ScheduleSettings

/**
 * Decides whether [ScreenOffWatcherService] should be running and starts/stops it.
 *
 * The service is only needed in static mode, with the changer on, the lock screen enabled,
 * a lock album selected and the "change lock screen on screen off" option switched on.
 */
object ScreenOffWatcher {

    private const val TAG = "ScreenOffWatcher"

    fun shouldRun(settings: ScheduleSettings, mode: WallpaperMode): Boolean =
        whyNotRunning(settings, mode) == null

    /** Returns null when the watcher should run, otherwise a human-readable reason (for logs). */
    fun whyNotRunning(settings: ScheduleSettings, mode: WallpaperMode): String? = when {
        mode != WallpaperMode.STATIC -> "wallpaper mode is $mode (static only)"
        !settings.lockChangeOnScreenOff -> "option switched off"
        !settings.enableChanger -> "wallpaper changer disabled"
        !settings.lockEnabled -> "lock screen not enabled"
        settings.lockAlbumId == null -> "no lock album selected"
        else -> null
    }

    /**
     * Starts or stops the watcher. Starting can be refused by the OS when the app is in the
     * background (Android 12+ foreground-service restrictions); that is logged, not thrown.
     * Apps exempt from battery optimization are allowed to start it from the background.
     */
    fun sync(context: Context, run: Boolean, source: String) {
        val intent = Intent(context, ScreenOffWatcherService::class.java)
        if (run) {
            try {
                ContextCompat.startForegroundService(context, intent)
                Log.d(TAG, "sync[$source]: start requested")
            } catch (e: Exception) {
                Log.w(TAG, "sync[$source]: start refused by OS -> ${e.causeChain()}", e)
            }
        } else {
            val wasRunning = context.stopService(intent)
            Log.d(TAG, "sync[$source]: stop requested (wasRunning=$wasRunning)")
        }
    }

    fun sync(context: Context, settings: ScheduleSettings, mode: WallpaperMode, source: String) {
        val reason = whyNotRunning(settings, mode)
        if (reason != null) Log.d(TAG, "sync[$source]: not running because $reason")
        sync(context, reason == null, source)
    }
}
