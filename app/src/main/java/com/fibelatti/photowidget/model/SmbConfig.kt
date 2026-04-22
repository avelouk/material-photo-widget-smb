package com.fibelatti.photowidget.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class SmbFolder(
    val share: String,
    val path: String = "", // empty = root of the share
) : Parcelable {

    val displayName: String
        get() = if (path.isEmpty()) share else "$share/${path.replace('\\', '/')}"
}

@Parcelize
data class SmbConfig(
    val host: String = "",
    val domain: String = "",
    val username: String = "",
    val password: String = "",
    val port: Int = 445,
    val selectedFolders: List<SmbFolder> = emptyList(),
) : Parcelable {

    val isConfigured: Boolean
        get() = host.isNotBlank() && selectedFolders.isNotEmpty()
}
