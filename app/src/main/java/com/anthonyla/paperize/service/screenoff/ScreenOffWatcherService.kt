package com.anthonyla.paperize.service.screenoff

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.core.util.causeChain
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.presentation.MainActivity
import com.anthonyla.paperize.service.worker.WallpaperChangeWorker
import dagger.hilt.android.AndroidEntryPoint
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Static-mode helper: keeps a low-priority foreground notification so the app can receive
 * ACTION_SCREEN_OFF (only deliverable to running, dynamically registered receivers) and
 * advances the lock-screen wallpaper each time the screen turns off.
 *
 * The actual change reuses [WallpaperChangeWorker] (same code path as scheduled changes),
 * enqueued as expedited one-time work, so queue/shuffle/effects behave identically.
 *
 * Diagnostics: every stage logs under tag [TAG] (`adb logcat -s ScreenOffWatcher`), the
 * ongoing notification shows the last result + how long it took, and failures raise an
 * error notification containing the full cause chain.
 */
@AndroidEntryPoint
class ScreenOffWatcherService : Service() {

    @Inject lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var notificationManager: NotificationManager

    private var receiverRegistered = false
    private var settingsJob: Job? = null
    private var resultJob: Job? = null

    private var lastTriggerAt = 0L
    private var triggerCount = 0
    private var skippedCount = 0
    @Volatile private var statusText: String? = null

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                try {
                    onScreenOff()
                } catch (e: Exception) {
                    Log.e(TAG, "[screen-off] handler crashed -> ${e.causeChain()}", e)
                    reportFailure(e.causeChain())
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "[1/4 create] service created")
        notificationManager = getSystemService(NotificationManager::class.java)
            ?: error("NotificationManager not available")
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "[2/4 start] onStartCommand startId=$startId flags=$flags restart=${intent == null}")
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        registerReceiverIfNeeded()
        observeSettingsIfNeeded()
        return START_STICKY
    }

    private fun enterForeground(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        Log.d(TAG, "[2/4 start] now in foreground")
        true
    } catch (e: Exception) {
        // e.g. ForegroundServiceStartNotAllowedException when restarted from the background
        Log.e(TAG, "[2/4 start] startForeground refused -> ${e.causeChain()}", e)
        false
    }

    private fun registerReceiverIfNeeded() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
        Log.d(TAG, "[2/4 start] screen-off receiver registered")
    }

    /** Stops the service by itself if the settings no longer call for it (e.g. changed while backgrounded). */
    private fun observeSettingsIfNeeded() {
        if (settingsJob != null) return
        settingsJob = scope.launch {
            combine(
                settingsRepository.getScheduleSettingsFlow(),
                settingsRepository.getWallpaperModeFlow()
            ) { settings, mode -> ScreenOffWatcher.whyNotRunning(settings, mode) }
                .distinctUntilChanged()
                .catch { e -> Log.e(TAG, "settings flow failed -> ${e.causeChain()}", e) }
                .collect { reason ->
                    if (reason != null) {
                        Log.i(TAG, "settings changed, stopping: $reason")
                        stopSelf()
                    } else {
                        Log.d(TAG, "settings OK, watching for screen off")
                    }
                }
        }
    }

    private fun onScreenOff() {
        val now = SystemClock.elapsedRealtime()
        val sinceLast = now - lastTriggerAt
        if (lastTriggerAt != 0L && sinceLast < MIN_GAP_MS) {
            skippedCount++
            Log.d(TAG, "[3/4 trigger] screen off ignored: ${sinceLast}ms since last change (< ${MIN_GAP_MS}ms), skipped=$skippedCount")
            return
        }
        lastTriggerAt = now
        triggerCount++

        val request = OneTimeWorkRequestBuilder<WallpaperChangeWorker>()
            .setInputData(
                workDataOf(
                    Constants.EXTRA_SCREEN_TYPE to ScreenType.LOCK.name,
                    WallpaperChangeWorker.KEY_NO_RETRY to true
                )
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(WORK_TAG)
            .build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        Log.d(TAG, "[3/4 trigger] screen off #$triggerCount -> enqueued lock change id=${request.id}")
        watchResult(request.id, now)
    }

    private fun watchResult(id: UUID, enqueuedAt: Long) {
        resultJob?.cancel()
        resultJob = scope.launch {
            var timedOut = true
            val info: WorkInfo? = withTimeoutOrNull(RESULT_TIMEOUT_MS) {
                WorkManager.getInstance(applicationContext)
                    .getWorkInfoByIdFlow(id)
                    .first { it == null || it.state.isFinished }
                    .also { timedOut = false }
            }
            val totalMs = SystemClock.elapsedRealtime() - enqueuedAt
            when {
                timedOut ->
                    Log.w(TAG, "[4/4 result] id=$id not finished after ${totalMs}ms (deferred by OS?)")

                info == null ->
                    Log.d(TAG, "[4/4 result] id=$id dropped: previous change still running (KEEP)")

                info.state == WorkInfo.State.SUCCEEDED -> {
                    val workMs = info.outputData.getLong(WallpaperChangeWorker.KEY_DURATION_MS, -1L)
                    Log.i(TAG, "[4/4 result] success: work=${workMs}ms, total incl. scheduling=${totalMs}ms")
                    statusText = getString(
                        R.string.screen_off_status_ok,
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()),
                        workMs
                    )
                    updateNotification()
                }

                info.state == WorkInfo.State.FAILED -> {
                    val error = info.outputData.getString(WallpaperChangeWorker.KEY_ERROR)
                        ?: "unknown error"
                    Log.e(TAG, "[4/4 result] FAILED after ${totalMs}ms -> $error")
                    reportFailure(error)
                }

                else -> Log.w(TAG, "[4/4 result] id=$id ended as ${info.state}")
            }
        }
    }

    private fun reportFailure(error: String) {
        statusText = getString(R.string.screen_off_status_failed, error)
        updateNotification()
        val notification = NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.screen_off_error_title))
            .setContentText(error)
            .setStyle(NotificationCompat.BigTextStyle().bigText(error))
            .setContentIntent(mainActivityIntent())
            .setAutoCancel(true)
            .build()
        notificationManager.notify(ERROR_NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.screen_off_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.screen_off_notification_title))
            .setContentText(statusText ?: getString(R.string.screen_off_status_waiting))
            .setContentIntent(mainActivityIntent())
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    private fun updateNotification() {
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.w(TAG, "notification update failed -> ${e.causeChain()}", e)
        }
    }

    private fun mainActivityIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent().setClassName(packageName, MainActivity::class.java.name),
        PendingIntent.FLAG_IMMUTABLE
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "destroyed (changes=$triggerCount, skipped=$skippedCount)")
        if (receiverRegistered) {
            try {
                unregisterReceiver(screenOffReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "unregister failed -> ${e.causeChain()}", e)
            }
            receiverRegistered = false
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScreenOffWatcher"
        private const val CHANNEL_ID = "paperize_screen_off"
        private const val NOTIFICATION_ID = 1001
        private const val ERROR_NOTIFICATION_ID = 1002
        private const val WORK_NAME = "screen_off_lock_change"
        private const val WORK_TAG = "screen_off"

        /** Ignore screen-off events closer together than this (e.g. pocket toggles). */
        private const val MIN_GAP_MS = 15_000L

        /** How long to wait for the worker before giving up on reporting its result. */
        private const val RESULT_TIMEOUT_MS = 120_000L
    }
}
