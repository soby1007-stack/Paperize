package com.anthonyla.paperize

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import android.util.Log
import androidx.work.Configuration
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.core.util.DataResetManager
import com.anthonyla.paperize.core.util.causeChain
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.service.screenoff.ScreenOffWatcher
import com.anthonyla.paperize.service.worker.AlbumRefreshScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Application class for Paperize
 *
 * Annotated with @HiltAndroidApp to enable dependency injection
 * Implements Configuration.Provider for WorkManager with Hilt support
 */
@HiltAndroidApp
class PaperizeApplication : Application(), Configuration.Provider, DefaultLifecycleObserver {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super<Application>.onCreate()

        // Perform one-time data reset for major version upgrades (e.g., v3 -> v4)
        // Must run before any other initialization that accesses DB/preferences
        DataResetManager.performResetIfNeeded(this)

        // Create notification channel (minSdk is 31, so always supported)
        createNotificationChannel()

        // Process lifecycle distinguishes real background/foreground transitions from activity
        // recreation, so folder-backed albums are refreshed whenever the user returns to the app.
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        // Start/stop the static-mode screen-off watcher whenever the relevant settings change.
        observeScreenOffWatcher()
    }

    private fun observeScreenOffWatcher() {
        appScope.launch {
            combine(
                settingsRepository.getScheduleSettingsFlow(),
                settingsRepository.getWallpaperModeFlow()
            ) { settings, mode -> ScreenOffWatcher.shouldRun(settings, mode) }
                .distinctUntilChanged()
                .catch { e -> Log.e(TAG, "screen-off watcher observer failed -> ${e.causeChain()}", e) }
                .collect { run -> ScreenOffWatcher.sync(this@PaperizeApplication, run, "settings") }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            Constants.NOTIFICATION_CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    override fun onStart(owner: LifecycleOwner) {
        AlbumRefreshScheduler.enqueue(this)

        // Retry starting the watcher when the user opens the app, in case the OS refused an
        // earlier start from the background.
        appScope.launch {
            try {
                ScreenOffWatcher.sync(
                    this@PaperizeApplication,
                    settingsRepository.getScheduleSettings(),
                    settingsRepository.getWallpaperMode(),
                    "app-foreground"
                )
            } catch (e: Exception) {
                Log.e(TAG, "screen-off watcher sync failed -> ${e.causeChain()}", e)
            }
        }
    }

    private companion object {
        const val TAG = "PaperizeApplication"
    }
}
