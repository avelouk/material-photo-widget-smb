package com.fibelatti.photowidget.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.AlarmManagerCompat
import androidx.core.content.getSystemService
import com.fibelatti.photowidget.configure.appWidgetId
import com.fibelatti.photowidget.di.PhotoWidgetEntryPoint
import com.fibelatti.photowidget.model.PhotoWidgetCycleMode
import com.fibelatti.photowidget.model.Time
import com.fibelatti.photowidget.platform.EntryPointBroadcastReceiver
import com.fibelatti.photowidget.platform.setIdentifierCompat
import com.fibelatti.photowidget.widget.data.PhotoWidgetStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class PhotoWidgetAlarmManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoWidgetStorage: PhotoWidgetStorage,
) {

    private val alarmManager: AlarmManager by lazy { requireNotNull(context.getSystemService()) }
    private val canScheduleExactAlarms: Boolean
        get() = AlarmManagerCompat.canScheduleExactAlarms(alarmManager)
            .also { Timber.d("Schedule exact alarms permission granted: $it") }

    fun setup(appWidgetId: Int) {
        Timber.d("Setting alarm for widget (appWidgetId=$appWidgetId)")

        if (photoWidgetStorage.getWidgetLockedInApp(appWidgetId = appWidgetId)) {
            Timber.d("Widget locked in-app. Skipping alarm setup.")
            return
        }

        cancel(appWidgetId)

        val cycleMode: PhotoWidgetCycleMode = photoWidgetStorage.getWidgetCycleMode(appWidgetId = appWidgetId)

        Timber.d("Widget alarm type: $cycleMode")

        when (cycleMode) {
            is PhotoWidgetCycleMode.Interval -> setupIntervalAlarm(cycleMode = cycleMode, appWidgetId = appWidgetId)
            is PhotoWidgetCycleMode.Schedule -> setupScheduleAlarm(cycleMode = cycleMode, appWidgetId = appWidgetId)
            is PhotoWidgetCycleMode.Disabled -> return
        }
    }

    fun cancel(appWidgetId: Int) {
        Timber.d("Cancelling existing alarms for widget (appWidgetId=$appWidgetId)")

        alarmManager.cancel(
            PhotoWidgetProvider.getChangePhotoPendingIntent(context = context, appWidgetId = appWidgetId),
        )
        alarmManager.cancel(
            ExactRepeatingAlarmReceiver.pendingIntent(context = context, appWidgetId = appWidgetId),
        )
        alarmManager.cancel(DailySmbRefreshReceiver.pendingIntent(context = context))
    }

    /**
     * Schedules a daily alarm at ~00:05 local time that fires [DailySmbRefreshReceiver], which
     * enqueues [PhotoWidgetSyncWorker] to refresh today's "on this day" photos for every SMB
     * widget. The alarm is a single app-wide schedule (not per-widget) and re-arms itself each
     * time it fires.
     */
    fun setupDailySmbRefresh() {
        Timber.d("Setting up daily SMB refresh alarm")

        val calendar = Calendar.getInstance(TimeZone.getDefault()).apply {
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 5)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val pendingIntent = DailySmbRefreshReceiver.pendingIntent(context = context)

        if (canScheduleExactAlarms) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    /* type = */ AlarmManager.RTC_WAKEUP,
                    /* triggerAtMillis = */ calendar.timeInMillis,
                    /* operation = */ pendingIntent,
                )
                return
            } catch (_: SecurityException) {
                Timber.d("SecurityException scheduling midnight alarm — falling back to inexact")
            }
        }

        alarmManager.setAndAllowWhileIdle(
            /* type = */ AlarmManager.RTC_WAKEUP,
            /* triggerAtMillis = */ calendar.timeInMillis,
            /* operation = */ pendingIntent,
        )
    }

    fun cancelDailySmbRefresh() {
        Timber.d("Cancelling daily SMB refresh alarm")
        alarmManager.cancel(DailySmbRefreshReceiver.pendingIntent(context = context))
    }

    private fun setupIntervalAlarm(cycleMode: PhotoWidgetCycleMode.Interval, appWidgetId: Int) {
        val intervalMillis = cycleMode.loopingInterval.run { timeUnit.toMillis(repeatInterval) }
        val nextCycleTime = photoWidgetStorage.getWidgetNextCycleTime(appWidgetId = appWidgetId)
        val currentTimeMillis = System.currentTimeMillis()
        val triggerAtMillis = if (nextCycleTime > currentTimeMillis) {
            nextCycleTime
        } else {
            currentTimeMillis + intervalMillis
        }

        photoWidgetStorage.saveWidgetNextCycleTime(appWidgetId = appWidgetId, nextCycleTime = triggerAtMillis)

        if (canScheduleExactAlarms) {
            try {
                alarmManager.setExact(
                    /* type = */ AlarmManager.RTC_WAKEUP,
                    /* triggerAtMillis = */ triggerAtMillis,
                    /* operation = */
                    ExactRepeatingAlarmReceiver.pendingIntent(context = context, appWidgetId = appWidgetId),
                )
            } catch (_: SecurityException) {
                Timber.d("SecurityException: fallback to inexact alarm")

                setRepeatingAlarm(
                    triggerAtMillis = triggerAtMillis,
                    intervalMillis = intervalMillis,
                    appWidgetId = appWidgetId,
                )
            }
        } else {
            setRepeatingAlarm(
                triggerAtMillis = triggerAtMillis,
                intervalMillis = intervalMillis,
                appWidgetId = appWidgetId,
            )
        }
    }

    private fun setRepeatingAlarm(triggerAtMillis: Long, intervalMillis: Long, appWidgetId: Int) {
        alarmManager.setRepeating(
            /* type = */ AlarmManager.RTC_WAKEUP,
            /* triggerAtMillis = */ triggerAtMillis,
            /* intervalMillis = */ intervalMillis,
            /* operation = */
            PhotoWidgetProvider.getChangePhotoPendingIntent(context = context, appWidgetId = appWidgetId),
        )
    }

    private fun setupScheduleAlarm(cycleMode: PhotoWidgetCycleMode.Schedule, appWidgetId: Int) {
        if (cycleMode.triggers.isEmpty()) {
            Timber.w("No triggers defined for widget (appWidgetId=$appWidgetId). Skipping schedule alarm setup.")
            return
        }

        val calendar: Calendar = Calendar.getInstance(TimeZone.getDefault())
        val currentHour: Int = calendar.get(Calendar.HOUR_OF_DAY)
        val currentMinute: Int = calendar.get(Calendar.MINUTE)

        val sortedTriggers: List<Time> = cycleMode.triggers.sortedWith(
            comparator = compareBy({ it.hour }, { it.minute }),
        )

        var nextTrigger: Time? = null
        var advanceToNextDay = false

        for (trigger in sortedTriggers) {
            if (trigger.hour > currentHour || (trigger.hour == currentHour && trigger.minute > currentMinute)) {
                nextTrigger = trigger
                break
            }
        }

        if (nextTrigger == null) {
            nextTrigger = sortedTriggers.first()
            advanceToNextDay = true
        }

        calendar.apply {
            if (advanceToNextDay) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
            set(Calendar.HOUR_OF_DAY, nextTrigger.hour)
            set(Calendar.MINUTE, nextTrigger.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (canScheduleExactAlarms) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    /* type = */ AlarmManager.RTC_WAKEUP,
                    /* triggerAtMillis = */ calendar.timeInMillis,
                    /* operation = */
                    ExactRepeatingAlarmReceiver.pendingIntent(context = context, appWidgetId = appWidgetId),
                )
            } catch (_: SecurityException) {
                Timber.d("SecurityException: fallback to inexact alarm")
                setAlarm(triggerAtMillis = calendar.timeInMillis, appWidgetId = appWidgetId)
            }
        } else {
            setAlarm(triggerAtMillis = calendar.timeInMillis, appWidgetId = appWidgetId)
        }
    }

    private fun setAlarm(triggerAtMillis: Long, appWidgetId: Int) {
        alarmManager.setAndAllowWhileIdle(
            /* type = */ AlarmManager.RTC_WAKEUP,
            /* triggerAtMillis = */ triggerAtMillis,
            /* operation = */ ExactRepeatingAlarmReceiver.pendingIntent(context = context, appWidgetId = appWidgetId),
        )
    }
}

class ExactRepeatingAlarmReceiver : EntryPointBroadcastReceiver() {

    override suspend fun doWork(context: Context, intent: Intent, entryPoint: PhotoWidgetEntryPoint) {
        Timber.d("Working... (appWidgetId=${intent.appWidgetId})")

        entryPoint.run {
            cyclePhotoUseCase().invoke(appWidgetId = intent.appWidgetId)
            photoWidgetStorage().saveWidgetNextCycleTime(appWidgetId = intent.appWidgetId, nextCycleTime = -1)
            photoWidgetAlarmManager().setup(appWidgetId = intent.appWidgetId)
        }
    }

    companion object {

        fun pendingIntent(
            context: Context,
            appWidgetId: Int,
        ): PendingIntent {
            val intent = Intent(context, ExactRepeatingAlarmReceiver::class.java).apply {
                setIdentifierCompat("$appWidgetId")
                this.appWidgetId = appWidgetId
            }
            return PendingIntent.getBroadcast(
                /* context = */ context,
                /* requestCode = */ appWidgetId,
                /* intent = */ intent,
                /* flags = */ PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}

class DailySmbRefreshReceiver : EntryPointBroadcastReceiver() {

    override suspend fun doWork(context: Context, intent: Intent, entryPoint: PhotoWidgetEntryPoint) {
        Timber.d("Daily SMB refresh alarm fired")

        PhotoWidgetSyncWorker.enqueueOneShot(context = context)
        entryPoint.photoWidgetAlarmManager().setupDailySmbRefresh()
    }

    companion object {

        private const val REQUEST_CODE = 0xDA11

        fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, DailySmbRefreshReceiver::class.java)
            return PendingIntent.getBroadcast(
                /* context = */ context,
                /* requestCode = */ REQUEST_CODE,
                /* intent = */ intent,
                /* flags = */ PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
