package com.fibelatti.photowidget.widget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fibelatti.photowidget.R
import com.fibelatti.photowidget.model.PhotoWidgetSource
import com.fibelatti.photowidget.widget.data.PhotoWidgetStorage
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@HiltWorker
class PhotoWidgetSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val photoWidgetStorage: PhotoWidgetStorage,
    private val coroutineScope: CoroutineScope,
) : CoroutineWorker(appContext = context, params = workerParams) {

    override suspend fun doWork(): Result {
        Timber.i("Working...")

        val ids: List<Int> = PhotoWidgetProvider.ids(applicationContext).ifEmpty {
            Timber.d("There are no widgets.")
            return Result.success()
        }

        val hasSmbWidget = ids.any { photoWidgetStorage.getWidgetSource(appWidgetId = it) == PhotoWidgetSource.SMB }
        if (hasSmbWidget) {
            setForeground(createForegroundInfo())
        }

        var shouldRetry = false

        for (id in ids) {
            try {
                Timber.d("Processing widget (id=$id)")

                val source = photoWidgetStorage.getWidgetSource(appWidgetId = id)

                when (source) {
                    PhotoWidgetSource.DIRECTORY -> {
                        coroutineScope.launch {
                            withContext(NonCancellable) {
                                photoWidgetStorage.syncWidgetPhotos(appWidgetId = id)
                            }
                        }
                    }

                    PhotoWidgetSource.SMB -> withContext(NonCancellable) {
                        photoWidgetStorage.refreshTodaysSmbPhotos(appWidgetId = id)
                        PhotoWidgetProvider.update(context = applicationContext, appWidgetId = id)
                    }

                    else -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Error processing widget (id=$id). Will retry.")
                shouldRetry = true
            }
        }

        return if (shouldRetry) Result.retry() else Result.success()
    }

    private fun createForegroundInfo(): ForegroundInfo {
        ensureNotificationChannel()

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.photo_widget_smb_sync_notification_title))
            .setContentText(applicationContext.getString(R.string.photo_widget_smb_sync_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.photo_widget_smb_sync_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {

        private const val UNIQUE_WORK_NAME = "PhotoWidgetSyncWorker"
        private const val CHANNEL_ID = "smb_sync"
        private const val NOTIFICATION_ID = 43

        fun enqueueWork(context: Context) {
            Timber.i("Enqueuing work...")

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest: PeriodicWorkRequest.Builder = PeriodicWorkRequestBuilder<PhotoWidgetSyncWorker>(
                repeatInterval = Duration.ofHours(6),
            ).setConstraints(constraints)

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                uniqueWorkName = UNIQUE_WORK_NAME,
                existingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE,
                request = workRequest.build(),
            )
        }

        /**
         * Expedited one-shot: kicks off a `PhotoWidgetSyncWorker` run immediately, outside the
         * 6-hour periodic cadence. Used by the midnight alarm and DATE_CHANGED backup trigger.
         */
        fun enqueueOneShot(context: Context) {
            Timber.d("Enqueuing one-shot sync...")

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<PhotoWidgetSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "${UNIQUE_WORK_NAME}_oneshot",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
