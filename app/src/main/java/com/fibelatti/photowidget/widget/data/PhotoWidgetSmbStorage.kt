package com.fibelatti.photowidget.widget.data

import androidx.exifinterface.media.ExifInterface
import com.fibelatti.photowidget.model.SmbConfig
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SmbConfig as SmbClientConfig
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Calendar
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

@Singleton
class PhotoWidgetSmbStorage @Inject constructor(
    private val smbPhotoIndexDao: SmbPhotoIndexDao,
    private val sharedPreferences: PhotoWidgetSharedPreferences,
) {

    fun saveSmbConfig(appWidgetId: Int, config: SmbConfig) {
        sharedPreferences.saveWidgetSmbConfig(appWidgetId, config)
    }

    fun getSmbConfig(appWidgetId: Int): SmbConfig {
        return sharedPreferences.getWidgetSmbConfig(appWidgetId)
    }

    suspend fun getIndexCount(widgetId: Int): Int = smbPhotoIndexDao.count(widgetId)

    suspend fun deleteByWidgetId(widgetId: Int) = smbPhotoIndexDao.deleteByWidgetId(widgetId)

    /**
     * Lists all shares exposed by the SMB server (excluding hidden admin shares ending with $).
     */
    suspend fun listShares(config: SmbConfig): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            withSmbSession(config) { session ->
                val transport = SMBTransportFactories.SRVSVC.getTransport(session)
                val serverService = ServerService(transport)
                serverService.getShares0()
                    .map { it.netName }
                    .filter { !it.endsWith("$") }
                    .sorted()
            }
        }.also { result ->
            if (result.isFailure) {
                Timber.e(result.exceptionOrNull(), "SMB share enumeration failed")
            }
        }
    }

    /**
     * Lists subdirectories at [path] within [share].
     * Returns a sorted list of folder names, or a failure if the connection fails.
     */
    suspend fun listFolders(
        config: SmbConfig,
        share: String,
        path: String,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            withSmbShare(config, share) { diskShare ->
                val normalizedPath = path.trimSlashes().replace('/', '\\')
                diskShare.list(normalizedPath)
                    .filter { entry ->
                        val name = entry.fileName
                        name != "." && name != ".." && !name.startsWith(".") &&
                            entry.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                    }
                    .map { it.fileName }
                    .sorted()
            }
        }
    }

    /**
     * Connects to each selected folder in [config], walks the directory trees,
     * extracts photo dates, and updates the local index.
     *
     * Safe to call repeatedly — uses upsert so existing entries are updated, and stale
     * entries (files no longer present on the share) are pruned afterwards.
     */
    suspend fun scanAndIndex(widgetId: Int, config: SmbConfig): Result<Int> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) return@withContext Result.failure(IllegalArgumentException("SMB config is incomplete"))

        runCatching {
            val scanStart = System.currentTimeMillis()
            val discovered = mutableListOf<SmbPhotoIndexDto>()

            // Group folders by share name so we open each share only once
            val foldersByShare = config.selectedFolders.groupBy { it.share }

            withSmbSession(config) { session ->
                for ((shareName, folders) in foldersByShare) {
                    val share = session.connectShare(shareName)
                    if (share !is DiskShare) {
                        share.close()
                        Timber.w("Skipping non-disk share: $shareName")
                        continue
                    }
                    share.use { diskShare ->
                        for (folder in folders) {
                            walkDirectory(
                                share = diskShare,
                                shareName = shareName,
                                host = config.host,
                                dirPath = folder.path.trimSlashes(),
                                widgetId = widgetId,
                                discovered = discovered,
                            )
                        }
                    }
                }
            }

            for (batch in discovered.chunked(UPSERT_BATCH_SIZE)) {
                smbPhotoIndexDao.upsertPhotos(batch)
            }
            smbPhotoIndexDao.deleteStaleEntries(widgetId = widgetId, threshold = scanStart)

            Timber.d("SMB scan complete: ${discovered.size} photos indexed across ${config.selectedFolders.size} folder(s)")
            discovered.size
        }
    }

    /**
     * Returns SMB paths of photos taken on today's month and day across all years.
     */
    suspend fun getPhotosForDay(widgetId: Int): List<SmbPhotoIndexDto> {
        val now = Calendar.getInstance()
        return smbPhotoIndexDao.getPhotosForDay(
            widgetId = widgetId,
            month = now.get(Calendar.MONTH) + 1,
            day = now.get(Calendar.DAY_OF_MONTH),
        )
    }

    /**
     * Downloads a single photo from the SMB share into [destination].
     * The [smbPath] format is: //host/share/path/to/file.jpg
     */
    suspend fun downloadPhoto(
        smbPath: String,
        config: SmbConfig,
        destination: File,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            // Parse share and relative path from the SMB URL
            val afterScheme = smbPath.removePrefix("//")
            val afterHost = afterScheme.removePrefix(config.host).trimStart('/')
            val shareName = afterHost.substringBefore('/')
            val relativePath = afterHost.removePrefix(shareName).trimStart('/').replace('/', '\\')

            withSmbShare(config, shareName) { share ->
                share.openFile(
                    relativePath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.noneOf(com.hierynomus.mssmb2.SMB2CreateOptions::class.java),
                ).use { file ->
                    val inputStream: InputStream = file.inputStream
                    destination.parentFile?.mkdirs()
                    inputStream.use { input ->
                        destination.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }

            destination
        }
    }

    private fun walkDirectory(
        share: DiskShare,
        shareName: String,
        host: String,
        dirPath: String,
        widgetId: Int,
        discovered: MutableList<SmbPhotoIndexDto>,
        depth: Int = 0,
    ) {
        if (depth > MAX_DIRECTORY_DEPTH) {
            Timber.w("SMB directory walk exceeded max depth ($MAX_DIRECTORY_DEPTH) at: $dirPath")
            return
        }
        val entries: List<FileIdBothDirectoryInformation> = try {
            share.list(dirPath)
        } catch (e: Exception) {
            Timber.w(e, "Failed to list SMB directory: $dirPath")
            return
        }

        for (entry in entries) {
            val name = entry.fileName
            if (name == "." || name == ".." || name.startsWith(".")) continue

            val entryPath = if (dirPath.isEmpty()) name else "$dirPath\\$name"
            val isDirectory = entry.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L

            if (isDirectory) {
                walkDirectory(
                    share = share,
                    shareName = shareName,
                    host = host,
                    dirPath = entryPath,
                    widgetId = widgetId,
                    discovered = discovered,
                    depth = depth + 1,
                )
            } else if (isImageFile(name)) {
                val smbPath = "//$host/$shareName/${entryPath.replace('\\', '/')}"
                val date = extractDate(
                    entryPath = entryPath,
                    lastModifiedMs = entry.lastWriteTime.toEpochMillis(),
                )
                discovered.add(
                    SmbPhotoIndexDto(
                        widgetId = widgetId,
                        path = smbPath,
                        month = date.month,
                        day = date.day,
                        year = date.year,
                        dateSource = date.source,
                    ),
                )
            }
        }
    }

    /**
     * Extracts month, day, year from the SMB path using folder naming conventions,
     * then falls back to the file's lastModified timestamp.
     *
     * Supported folder patterns (DD.MM or DD-MM prefix in subfolder name):
     *   2009\03.07 Lastiver  →  day=3, month=7, year=2009
     *   2012\04.08.Trchkan   →  day=4, month=8, year=2012
     *   2010\05-07 Aghveran  →  day=5, month=7, year=2010
     *
     * Also handles file naming patterns:
     *   IMG_20120307_143022.jpg  →  year=2012, month=3, day=7
     *   20190421_185837.jpg      →  year=2019, month=4, day=21
     */
    private fun extractDate(entryPath: String, lastModifiedMs: Long): PhotoDate {
        val segments = entryPath.replace('\\', '/').split('/')

        // 1. Year from the first 4-digit segment
        val year = segments.firstOrNull { it.matches(Regex("\\d{4}")) }?.toIntOrNull()

        // 2. Day/month from the subfolder name: DD.MM or DD-MM at the start
        val subfolderDate = segments.drop(1).firstOrNull()?.let { folder ->
            FOLDER_DATE_PATTERN.find(folder)?.let { match ->
                val d = match.groupValues[1].toIntOrNull() ?: return@let null
                val m = match.groupValues[2].toIntOrNull() ?: return@let null
                if (m in 1..12 && d in 1..31) Pair(d, m) else null
            }
        }

        if (year != null && subfolderDate != null) {
            return PhotoDate(month = subfolderDate.second, day = subfolderDate.first, year = year)
        }

        // 3. Filename patterns: YYYYMMDD or IMG_YYYYMMDD
        val filename = segments.last()
        FILE_DATE_PATTERN.find(filename)?.let { match ->
            val y = match.groupValues[1].toIntOrNull() ?: return@let
            val m = match.groupValues[2].toIntOrNull() ?: return@let
            val d = match.groupValues[3].toIntOrNull() ?: return@let
            if (y in 1990..2100 && m in 1..12 && d in 1..31) {
                return PhotoDate(month = m, day = d, year = y)
            }
        }

        // 4. Fall back to lastModified (mark for EXIF enrichment)
        val cal = Calendar.getInstance().also { it.timeInMillis = lastModifiedMs }
        return PhotoDate(
            month = cal.get(Calendar.MONTH) + 1,
            day = cal.get(Calendar.DAY_OF_MONTH),
            year = cal.get(Calendar.YEAR),
            source = "LAST_MODIFIED",
        )
    }

    private fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".webp") ||
            lower.endsWith(".heic") || lower.endsWith(".heif")
    }

    private fun String.trimSlashes(): String = trim('/', '\\')

    private fun <T> withSmbSession(config: SmbConfig, block: (Session) -> T): T {
        val smbClientConfig = SmbClientConfig.builder()
            .withTimeout(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .withSoTimeout(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        val client = SMBClient(smbClientConfig)
        return try {
            val host = config.host
                .removePrefix("smb://")
                .removePrefix("smb:\\\\")
                .trimStart('/', '\\')
                .trimEnd('/', '\\')
            client.connect(host, config.port).use { connection ->
                val authContext = if (config.username.isBlank()) {
                    AuthenticationContext.anonymous()
                } else {
                    AuthenticationContext(
                        config.username,
                        config.password.toCharArray(),
                        config.domain.ifBlank { null },
                    )
                }
                block(connection.authenticate(authContext))
            }
        } finally {
            client.close()
        }
    }

    private fun <T> withSmbShare(config: SmbConfig, shareName: String, block: (DiskShare) -> T): T {
        return withSmbSession(config) { session ->
            val share = session.connectShare(shareName)
            if (share !is DiskShare) {
                share.close()
                error("Share '$shareName' is not a disk share (got ${share::class.simpleName})")
            }
            share.use { block(it) }
        }
    }

    /**
     * Second pass: reads EXIF headers from photos that fell back to lastModified during the fast scan.
     * Only processes JPEG files since ExifInterface doesn't reliably handle PNG/WebP/HEIC.
     * Streams only the first ~128KB of each file to avoid excessive network I/O.
     */
    suspend fun enrichWithExif(widgetId: Int, config: SmbConfig) = withContext(Dispatchers.IO) {
        val photos = smbPhotoIndexDao.getPhotosNeedingExif(widgetId)
        if (photos.isEmpty()) return@withContext

        val jpegPhotos = photos.filter { photo ->
            val lower = photo.path.lowercase()
            lower.endsWith(".jpg") || lower.endsWith(".jpeg")
        }
        if (jpegPhotos.isEmpty()) {
            // Mark non-JPEG photos as EXIF_FAILED so we don't retry
            for (batch in photos.chunked(UPSERT_BATCH_SIZE)) {
                smbPhotoIndexDao.upsertPhotos(batch.map { it.copy(dateSource = "EXIF_FAILED") })
            }
            return@withContext
        }

        Timber.d("EXIF enrichment: ${jpegPhotos.size} JPEG photos to process")

        val exifDateFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        val updated = mutableListOf<SmbPhotoIndexDto>()

        // Group by share so we open each share only once
        val photosByShare = jpegPhotos.groupBy { photo ->
            val afterScheme = photo.path.removePrefix("//")
            val afterHost = afterScheme.removePrefix(config.host).trimStart('/')
            afterHost.substringBefore('/')
        }

        try {
            withSmbSession(config) { session ->
                for ((shareName, sharePhotos) in photosByShare) {
                    val share = session.connectShare(shareName)
                    if (share !is DiskShare) {
                        share.close()
                        Timber.w("EXIF enrichment: skipping non-disk share: $shareName")
                        // Mark these as failed
                        updated += sharePhotos.map { it.copy(dateSource = "EXIF_FAILED") }
                        continue
                    }
                    share.use { diskShare ->
                        for (photo in sharePhotos) {
                            val enriched = try {
                                val afterScheme = photo.path.removePrefix("//")
                                val afterHost = afterScheme.removePrefix(config.host).trimStart('/')
                                val relativePath = afterHost.removePrefix(shareName).trimStart('/').replace('/', '\\')

                                diskShare.openFile(
                                    relativePath,
                                    EnumSet.of(AccessMask.GENERIC_READ),
                                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                                    SMB2CreateDisposition.FILE_OPEN,
                                    EnumSet.noneOf(com.hierynomus.mssmb2.SMB2CreateOptions::class.java),
                                ).use { file ->
                                    val boundedStream = BoundedInputStream(file.inputStream, EXIF_HEADER_BYTES)
                                    val exif = ExifInterface(boundedStream)
                                    val dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                                        ?: exif.getAttribute(ExifInterface.TAG_DATETIME)

                                    if (dateStr != null) {
                                        val parsed = exifDateFormat.parse(dateStr)
                                        if (parsed != null) {
                                            val cal = Calendar.getInstance().also { it.time = parsed }
                                            photo.copy(
                                                month = cal.get(Calendar.MONTH) + 1,
                                                day = cal.get(Calendar.DAY_OF_MONTH),
                                                year = cal.get(Calendar.YEAR),
                                                dateSource = "EXIF",
                                            )
                                        } else {
                                            photo.copy(dateSource = "EXIF_FAILED")
                                        }
                                    } else {
                                        photo.copy(dateSource = "EXIF_FAILED")
                                    }
                                }
                            } catch (e: Exception) {
                                Timber.w(e, "EXIF enrichment failed for: ${photo.path}")
                                photo.copy(dateSource = "EXIF_FAILED")
                            }
                            updated.add(enriched)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "EXIF enrichment: SMB session failed")
            // Mark remaining unprocessed photos as failed
            val processedPaths = updated.map { it.path }.toSet()
            updated += jpegPhotos.filter { it.path !in processedPaths }.map { it.copy(dateSource = "EXIF_FAILED") }
        }

        for (batch in updated.chunked(UPSERT_BATCH_SIZE)) {
            smbPhotoIndexDao.upsertPhotos(batch)
        }

        // Also mark non-JPEG LAST_MODIFIED photos as failed
        val nonJpegPhotos = photos.filter { photo ->
            val lower = photo.path.lowercase()
            !(lower.endsWith(".jpg") || lower.endsWith(".jpeg"))
        }
        if (nonJpegPhotos.isNotEmpty()) {
            for (batch in nonJpegPhotos.chunked(UPSERT_BATCH_SIZE)) {
                smbPhotoIndexDao.upsertPhotos(batch.map { it.copy(dateSource = "EXIF_FAILED") })
            }
        }

        val exifCount = updated.count { it.dateSource == "EXIF" }
        Timber.d("EXIF enrichment complete: $exifCount of ${updated.size} photos enriched with EXIF dates")
    }

    /**
     * InputStream wrapper that limits reads to [limit] bytes.
     */
    private class BoundedInputStream(
        private val source: InputStream,
        private val limit: Long,
    ) : FilterInputStream(source) {
        private var bytesRead: Long = 0

        override fun read(): Int {
            if (bytesRead >= limit) return -1
            val result = super.read()
            if (result != -1) bytesRead++
            return result
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (bytesRead >= limit) return -1
            val maxLen = minOf(len.toLong(), limit - bytesRead).toInt()
            val result = super.read(b, off, maxLen)
            if (result > 0) bytesRead += result
            return result
        }

        override fun skip(n: Long): Long {
            val maxSkip = minOf(n, limit - bytesRead)
            val skipped = super.skip(maxSkip)
            bytesRead += skipped
            return skipped
        }

        override fun available(): Int {
            val remaining = (limit - bytesRead).toInt()
            return minOf(super.available(), remaining.coerceAtLeast(0))
        }
    }

    private data class PhotoDate(val month: Int, val day: Int, val year: Int, val source: String = "PATH")

    private companion object {
        const val MAX_DIRECTORY_DEPTH = 15
        const val UPSERT_BATCH_SIZE = 500
        const val CONNECTION_TIMEOUT_SECONDS = 30L
        const val EXIF_HEADER_BYTES = 128L * 1024 // 128KB — EXIF headers are in the first few KB

        // Matches DD.MM or DD-MM at the start of a folder name (European convention)
        val FOLDER_DATE_PATTERN = Regex("""^(\d{2})[.\-](\d{2})""")

        // Matches YYYYMMDD in filenames like IMG_20120307_... or 20190421_185837.jpg
        // Anchored with non-digit boundaries to avoid false matches on long digit sequences
        val FILE_DATE_PATTERN = Regex("""(?<!\d)(\d{4})(\d{2})(\d{2})(?!\d)""")
    }
}
