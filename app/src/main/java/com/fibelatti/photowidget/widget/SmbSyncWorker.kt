package com.fibelatti.photowidget.widget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fibelatti.photowidget.R
import com.fibelatti.photowidget.widget.data.PhotoWidgetStorage
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber

@HiltWorker
class SmbSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val photoWidgetStorage: PhotoWidgetStorage,
) : CoroutineWorker(appContext = context, params = workerParams) {

    override suspend fun doWork(): Result {
        val appWidgetId = inputData.getInt(KEY_WIDGET_ID, -1)
        if (appWidgetId == -1) return Result.failure()

        Timber.d("SmbSyncWorker starting for widget $appWidgetId")

        setForeground(createForegroundInfo())

        return try {
            withContext(NonCancellable) {
                photoWidgetStorage.fullSmbSync(appWidgetId = appWidgetId)
                PhotoWidgetProvider.update(context = applicationContext, appWidgetId = appWidgetId)
            }

            val count = runCatching { photoWidgetStorage.getIndexCount(appWidgetId) }.getOrDefault(0)
            Timber.d("SmbSyncWorker complete: $count photos indexed")

            Result.success(Data.Builder().putInt(KEY_PHOTO_COUNT, count).build())
        } catch (e: Exception) {
            Timber.e(e, "SmbSyncWorker failed for widget $appWidgetId")
            Result.failure()
        }
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
        private const val CHANNEL_ID = "smb_sync"
        private const val NOTIFICATION_ID = 42
        const val KEY_WIDGET_ID = "widget_id"
        const val KEY_PHOTO_COUNT = "photo_count"

        fun uniqueWorkName(appWidgetId: Int) = "SmbSyncWorker_$appWidgetId"

        fun enqueue(context: Context, appWidgetId: Int) {
            val request = OneTimeWorkRequestBuilder<SmbSyncWorker>()
                .setInputData(
                    Data.Builder().putInt(KEY_WIDGET_ID, appWidgetId).build(),
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(appWidgetId),
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
