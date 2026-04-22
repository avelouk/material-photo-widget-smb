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
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Weekly background re-index: walks every SMB widget's configured folders and refreshes the
 * photo index so new NAS uploads eventually get picked up without manually pressing Scan.
 * Runs as a foreground worker because a full SMB walk can easily exceed the 10-minute limit
 * that applies to background WorkManager jobs.
 */
@HiltWorker
class SmbFullScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val photoWidgetStorage: PhotoWidgetStorage,
) : CoroutineWorker(appContext = context, params = workerParams) {

    override suspend fun doWork(): Result {
        val ids = PhotoWidgetProvider.ids(applicationContext)
            .filter { photoWidgetStorage.getWidgetSource(appWidgetId = it) == PhotoWidgetSource.SMB }

        if (ids.isEmpty()) {
            Timber.d("SmbFullScanWorker: no SMB widgets, skipping")
            return Result.success()
        }

        setForeground(createForegroundInfo())

        var shouldRetry = false

        for (id in ids) {
            try {
                Timber.d("SmbFullScanWorker: running full sync for widget $id")
                withContext(NonCancellable) {
                    photoWidgetStorage.fullSmbSync(appWidgetId = id)
                    PhotoWidgetProvider.update(context = applicationContext, appWidgetId = id)
                }
            } catch (e: Exception) {
                Timber.e(e, "SmbFullScanWorker failed for widget $id")
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

        private const val UNIQUE_WORK_NAME = "SmbFullScanWorker"
        private const val CHANNEL_ID = "smb_sync"
        private const val NOTIFICATION_ID = 44

        fun enqueueWork(context: Context) {
            Timber.d("Enqueuing weekly SMB full-scan work")

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest: PeriodicWorkRequest = PeriodicWorkRequestBuilder<SmbFullScanWorker>(
                repeatInterval = Duration.ofDays(7),
            )
                .setConstraints(constraints)
                .setInitialDelay(Duration.ofDays(1))
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                uniqueWorkName = UNIQUE_WORK_NAME,
                existingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP,
                request = workRequest,
            )
        }
    }
}
