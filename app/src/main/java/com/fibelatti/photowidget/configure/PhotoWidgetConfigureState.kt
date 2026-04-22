package com.fibelatti.photowidget.configure

import android.net.Uri
import com.fibelatti.photowidget.model.LocalPhoto
import com.fibelatti.photowidget.model.PhotoWidget
import com.fibelatti.photowidget.model.PhotoWidgetAspectRatio
import com.fibelatti.photowidget.model.SmbConfig
import com.fibelatti.photowidget.model.SmbFolder

data class PhotoWidgetConfigureState(
    val photoWidget: PhotoWidget = PhotoWidget(),
    val selectedPhoto: LocalPhoto? = null,
    val isProcessing: Boolean = true,
    val cropQueue: List<LocalPhoto> = emptyList(),
    val messages: List<Message> = emptyList(),
    val hasEdits: Boolean = false,
    val isImportAvailable: Boolean = false,
    val smbConfig: SmbConfig = SmbConfig(),
    val smbScanStatus: SmbScanStatus = SmbScanStatus.Idle,
    val smbBrowseState: SmbBrowseState? = null,
) {

    sealed class SmbScanStatus {
        data object Idle : SmbScanStatus()
        data object Scanning : SmbScanStatus()
        data class Done(val count: Int) : SmbScanStatus()
        data object Error : SmbScanStatus()
    }

    /**
     * State for the folder browser sheet.
     *
     * When [currentShare] is empty, the browser is at the root level showing available shares.
     * When [currentShare] is set, the browser is inside that share and [breadcrumb] holds the
     * path segments within it.
     */
    data class SmbBrowseState(
        val currentShare: String = "",
        val breadcrumb: List<String> = emptyList(),
        val currentFolderContents: List<String> = emptyList(),
        val selectedFolders: Set<SmbFolder> = emptySet(),
        val isLoading: Boolean = false,
        val loadError: Boolean = false,
    ) {
        val isAtRoot: Boolean get() = currentShare.isEmpty()

        /** Full SMB path within the current share (backslash-separated). */
        val currentPath: String get() = breadcrumb.joinToString(separator = "\\")
    }

    sealed class Message {

        data object ImportFailed : Message()

        data object TooManyPhotos : Message()

        data class LaunchCrop(
            val source: Uri,
            val destination: Uri,
            val aspectRatio: PhotoWidgetAspectRatio,
        ) : Message()

        data object RequestPin : Message()

        data class AddWidget(val appWidgetId: Int) : Message()

        data object MissingPhotos : Message()

        data object MissingBackupData : Message()

        data object CancelWidget : Message()
    }
}
