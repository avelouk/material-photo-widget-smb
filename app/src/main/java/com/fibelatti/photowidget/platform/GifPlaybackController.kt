package com.fibelatti.photowidget.platform

import android.appwidget.AppWidgetManager
import android.content.Context
import android.widget.RemoteViews
import com.fibelatti.photowidget.R
import com.fibelatti.photowidget.model.LocalPhoto
import com.fibelatti.photowidget.model.PhotoWidget
import com.fibelatti.photowidget.model.PhotoWidgetSource
import com.fibelatti.photowidget.widget.LoadPhotoWidgetUseCase
import com.fibelatti.photowidget.widget.data.PhotoWidgetStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

class GifPlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoWidgetStorage: PhotoWidgetStorage,
    private val loadPhotoWidgetUseCase: LoadPhotoWidgetUseCase,
    private val coroutineScope: CoroutineScope,
) {

    private val widgetManager: AppWidgetManager by lazy { AppWidgetManager.getInstance(context) }

    private val playbackAllowed: MutableStateFlow<Boolean> = MutableStateFlow(true)
    private val gifPlaybackJobs: ConcurrentHashMap<Int, Job> = ConcurrentHashMap()

    fun setupWidgetGif(appWidgetId: Int) {
        logger.i("Starting gif playback (appWidgetId=$appWidgetId)")

        val source: PhotoWidgetSource = photoWidgetStorage.getWidgetSource(appWidgetId = appWidgetId)
        if (source != PhotoWidgetSource.GIF) return

        coroutineScope.launch {
            val existingJob: Job? = gifPlaybackJobs.remove(appWidgetId)
            existingJob?.cancel()

            gifPlaybackJobs[appWidgetId] = getGifPlaybackJob(
                appWidgetId = appWidgetId,
                packageName = context.packageName,
            )
        }
    }

    fun tearDown() {
        logger.i("Tearing down gifs...")

        gifPlaybackJobs.values.onEach { it.cancel() }
        gifPlaybackJobs.clear()
    }

    fun setPlaybackAllowed(value: Boolean) {
        Timber.d("Playback allowed: $value")
        playbackAllowed.update { value }
    }

    private fun getGifPlaybackJob(appWidgetId: Int, packageName: String): Job {
        return coroutineScope.launch {
            val photoWidgetFlow: Flow<PhotoWidget> = loadPhotoWidgetUseCase(appWidgetId = appWidgetId)
                .filterNot { it.isLoading }
                .take(1)

            combine(flow = photoWidgetFlow, flow2 = playbackAllowed) { photoWidget: PhotoWidget, shouldPlay: Boolean ->
                photoWidget to shouldPlay
            }.collectLatest { (photoWidget: PhotoWidget, shouldPlay: Boolean) ->
                while (shouldPlay) {
                    photoWidget.photos.forEach { photo: LocalPhoto ->
                        val remoteViews = RemoteViews(packageName, R.layout.photo_widget)
                        remoteViews.setImageViewUri(R.id.iv_widget, photo.launcherUri)

                        widgetManager.partiallyUpdateAppWidget(appWidgetId, remoteViews)

                        delay(timeMillis = photoWidget.gifInterval.coerceAtLeast(33))
                    }
                }
            }
        }
    }

    private companion object {

        private val logger: Timber.Tree = Timber.tag("GifPlaybackController")
    }
}
