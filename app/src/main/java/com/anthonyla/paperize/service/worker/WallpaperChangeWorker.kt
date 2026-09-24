package com.anthonyla.paperize.service.worker

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.anthonyla.paperize.core.EmptyAlbumException
import com.anthonyla.paperize.core.Result as PaperizeResult
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.core.util.causeChain
import com.anthonyla.paperize.core.util.setBitmapChecked
import com.anthonyla.paperize.domain.model.PreparedWallpaper
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.domain.usecase.ChangeWallpaperUseCase
import com.anthonyla.paperize.domain.usecase.ReapplyEffectsUseCase
import com.anthonyla.paperize.service.WallpaperChangeLock
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

/**
 * WorkManager worker for scheduled wallpaper changes.
 */
@HiltWorker
class WallpaperChangeWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val changeWallpaperUseCase: ChangeWallpaperUseCase,
    private val reapplyEffectsUseCase: ReapplyEffectsUseCase,
    private val settingsRepository: SettingsRepository,
    private val wallpaperChangeLock: WallpaperChangeLock
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val startedAt = SystemClock.elapsedRealtime()
        // One-shot triggers (e.g. screen off) must not retry later with backoff:
        // a late change would be surprising, and the failure is reported instead.
        val noRetry = inputData.getBoolean(KEY_NO_RETRY, false)
        return try {
            val screenType = inputData.getString(Constants.EXTRA_SCREEN_TYPE)
                ?.let(ScreenType::fromString)
                ?: ScreenType.HOME

            Log.d(TAG, "Starting wallpaper change for $screenType (attempt=$runAttemptCount, noRetry=$noRetry)")
            wallpaperChangeLock.mutex.withLock {
                changeWallpaper(screenType)
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            Log.d(TAG, "Wallpaper change completed successfully for $screenType in ${elapsed}ms")
            Result.success(workDataOf(KEY_DURATION_MS to elapsed))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            val chain = e.causeChain()
            Log.e(TAG, "Error changing wallpaper after ${elapsed}ms -> $chain", e)
            if (!noRetry && runAttemptCount < Constants.MAX_WORK_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_ERROR to chain, KEY_DURATION_MS to elapsed))
            }
        }
    }

    private suspend fun changeWallpaper(screenType: ScreenType) {
        val settings = settingsRepository.getScheduleSettings()
        val wallpaperManager = WallpaperManager.getInstance(context)

        when (screenType) {
            ScreenType.LIVE -> {
                context.sendBroadcast(
                    Intent(Constants.ACTION_RELOAD_WALLPAPER).setPackage(context.packageName)
                )
                Log.d(TAG, "Sent reload broadcast to live wallpaper service")
            }

            ScreenType.HOME -> {
                settings.homeAlbumId?.let {
                    changeSingle(it, ScreenType.HOME, settings, wallpaperManager)
                } ?: Log.w(TAG, "No home album selected")
            }

            ScreenType.LOCK -> {
                settings.lockAlbumId?.let {
                    changeSingle(it, ScreenType.LOCK, settings, wallpaperManager)
                } ?: Log.w(TAG, "No lock album selected")
            }

            ScreenType.BOTH -> {
                val homeAlbumId = settings.homeAlbumId
                val lockAlbumId = settings.lockAlbumId
                if (
                    homeAlbumId != null &&
                    homeAlbumId == lockAlbumId &&
                    !settings.separateSchedules
                ) {
                    changeSynchronized(homeAlbumId, settings, wallpaperManager)
                } else {
                    homeAlbumId?.let {
                        changeSingle(it, ScreenType.HOME, settings, wallpaperManager)
                    } ?: Log.w(TAG, "No home album selected for BOTH mode")
                    lockAlbumId?.let {
                        changeSingle(it, ScreenType.LOCK, settings, wallpaperManager)
                    } ?: Log.w(TAG, "No lock album selected for BOTH mode")
                }
            }
        }
    }

    private suspend fun changeSynchronized(
        albumId: String,
        settings: ScheduleSettings,
        wallpaperManager: WallpaperManager
    ) {
        when (val result = changeWallpaperUseCase(albumId, ScreenType.HOME)) {
            is PaperizeResult.Success -> {
                val prepared = result.data
                val samePresentation =
                    settings.homeEffects == settings.lockEffects &&
                        settings.homeScalingType == settings.lockScalingType &&
                        !settings.homeScrollingEnabled

                if (samePresentation) {
                    applyPrepared(
                        prepared = prepared,
                        wallpaperManager = wallpaperManager,
                        which = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK,
                        completedScreens = listOf(ScreenType.HOME, ScreenType.LOCK)
                    )
                    Log.d(TAG, "Home and lock wallpaper set atomically")
                } else {
                    // Distinct effects require two platform writes. Each screen is committed only
                    // after its own write succeeds.
                    applyPrepared(
                        prepared = prepared,
                        wallpaperManager = wallpaperManager,
                        which = WallpaperManager.FLAG_SYSTEM,
                        completedScreens = listOf(ScreenType.HOME)
                    )
                    Log.d(TAG, "Home wallpaper set in BOTH mode")

                    when (
                        val lockResult = reapplyEffectsUseCase(
                            albumId,
                            ScreenType.LOCK,
                            prepared.wallpaperId
                        )
                    ) {
                        is PaperizeResult.Success -> {
                            val lockBitmap = lockResult.data
                            try {
                                wallpaperManager.setBitmapChecked(
                                    lockBitmap,
                                    WallpaperManager.FLAG_LOCK
                                )
                                changeWallpaperUseCase.complete(prepared, ScreenType.LOCK)
                                Log.d(TAG, "Lock wallpaper set separately in BOTH mode")
                            } finally {
                                lockBitmap.recycle()
                            }
                        }

                        is PaperizeResult.Error -> throw asException(lockResult.exception)
                        PaperizeResult.Loading -> error("Unexpected loading result")
                    }
                }
            }

            is PaperizeResult.Error -> {
                if (result.exception is EmptyAlbumException) {
                    Log.w(TAG, "Album is empty for BOTH screens; disabling changer")
                    settingsRepository.updateScheduleSettings(
                        settings.copy(
                            homeAlbumId = null,
                            lockAlbumId = null,
                            enableChanger = false
                        )
                    )
                } else {
                    throw asException(result.exception)
                }
            }

            PaperizeResult.Loading -> error("Unexpected loading result")
        }
    }

    private suspend fun changeSingle(
        albumId: String,
        screenType: ScreenType,
        settings: ScheduleSettings,
        wallpaperManager: WallpaperManager
    ) {
        when (val result = changeWallpaperUseCase(albumId, screenType)) {
            is PaperizeResult.Success -> {
                val flag = when (screenType) {
                    ScreenType.HOME -> WallpaperManager.FLAG_SYSTEM
                    ScreenType.LOCK -> WallpaperManager.FLAG_LOCK
                    else -> error("Unsupported static screen: $screenType")
                }
                applyPrepared(result.data, wallpaperManager, flag, listOf(screenType))
                Log.d(TAG, "$screenType wallpaper changed successfully")
            }

            is PaperizeResult.Error -> {
                if (result.exception is EmptyAlbumException) {
                    disableEmptyScreen(screenType, settings)
                } else {
                    throw asException(result.exception)
                }
            }

            PaperizeResult.Loading -> error("Unexpected loading result")
        }
    }

    private suspend fun applyPrepared(
        prepared: PreparedWallpaper,
        wallpaperManager: WallpaperManager,
        which: Int,
        completedScreens: List<ScreenType>
    ) {
        var platformAccepted = false
        try {
            wallpaperManager.setBitmapChecked(prepared.bitmap, which)
            platformAccepted = true
            completedScreens.forEach { screen ->
                changeWallpaperUseCase.complete(prepared, screen)
            }
        } catch (e: Exception) {
            if (!platformAccepted) {
                changeWallpaperUseCase.restore(prepared)
            }
            throw e
        } finally {
            prepared.bitmap.recycle()
        }
    }

    private suspend fun disableEmptyScreen(
        screenType: ScreenType,
        settings: ScheduleSettings
    ) {
        when (screenType) {
            ScreenType.HOME -> {
                val lockStillActive = settings.lockEnabled && settings.lockAlbumId != null
                settingsRepository.updateScheduleSettings(
                    settings.copy(
                        homeAlbumId = null,
                        enableChanger = lockStillActive && settings.enableChanger
                    )
                )
            }

            ScreenType.LOCK -> {
                val homeStillActive = settings.homeEnabled && settings.homeAlbumId != null
                settingsRepository.updateScheduleSettings(
                    settings.copy(
                        lockAlbumId = null,
                        enableChanger = homeStillActive && settings.enableChanger
                    )
                )
            }

            else -> Unit
        }
    }

    private fun asException(throwable: Throwable): Exception =
        throwable as? Exception ?: RuntimeException(throwable)

    companion object {
        private const val TAG = "WallpaperChangeWorker"

        /** Input: do not retry on failure (report instead). */
        const val KEY_NO_RETRY = "no_retry"

        /** Output: time spent changing the wallpaper, in milliseconds. */
        const val KEY_DURATION_MS = "duration_ms"

        /** Output (failure only): exception with its full cause chain. */
        const val KEY_ERROR = "error"
    }
}
