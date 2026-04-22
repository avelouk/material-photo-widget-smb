@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package com.fibelatti.photowidget.configure

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fibelatti.photowidget.R
import com.fibelatti.photowidget.model.LocalPhoto
import com.fibelatti.photowidget.model.PhotoWidget
import com.fibelatti.photowidget.model.PhotoWidgetAspectRatio
import com.fibelatti.photowidget.model.PhotoWidgetColors
import com.fibelatti.photowidget.model.PhotoWidgetSource
import com.fibelatti.photowidget.model.SmbConfig
import com.fibelatti.photowidget.model.canSort
import com.fibelatti.photowidget.ui.ShapedPhoto
import com.fibelatti.photowidget.ui.WarningSign
import com.fibelatti.ui.foundation.AppSheetState
import com.fibelatti.ui.foundation.fadingEdges
import com.fibelatti.ui.foundation.rememberAppSheetState
import com.fibelatti.ui.foundation.showBottomSheet
import com.fibelatti.ui.preview.PreviewAll
import com.fibelatti.ui.text.AutoSizeText
import com.fibelatti.ui.theme.ExtendedTheme
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

@Composable
fun PhotoWidgetConfigureContentTab(
    viewModel: PhotoWidgetConfigureViewModel,
    modifier: Modifier = Modifier,
) {
    val state: PhotoWidgetConfigureState by viewModel.state.collectAsStateWithLifecycle()

    val sourceSheetState: AppSheetState = rememberAppSheetState()
    val importFromWidgetSheetState: AppSheetState = rememberAppSheetState()
    val recentlyDeletedPhotoSheetState: AppSheetState = rememberAppSheetState()

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
        onResult = viewModel::photoPicked,
    )

    val dirPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = viewModel::dirPicked,
    )

    val smbBrowserSheetState: AppSheetState = rememberAppSheetState()

    val gifPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = viewModel::gifPicked,
    )

    var showGifReplaceDialog by rememberSaveable { mutableStateOf(false) }

    PhotoWidgetConfigureContentTab(
        photoWidget = state.photoWidget,
        onChangeSourceClick = sourceSheetState::showBottomSheet,
        isImportAvailable = state.isImportAvailable,
        onImportClick = importFromWidgetSheetState::showBottomSheet,
        onPhotoPickerClick = { photoPickerLauncher.launch(input = "image/*") },
        onDirPickerClick = { dirPickerLauncher.launch(input = null) },
        onGifPickerClick = {
            if (state.photoWidget.photos.isNotEmpty()) {
                showGifReplaceDialog = true
            } else {
                gifPickerLauncher.launch(input = "image/gif")
            }
        },
        onPhotoClick = viewModel::previewPhoto,
        onReorderFinish = viewModel::reorderPhotos,
        onRemovedPhotoClick = { photo ->
            recentlyDeletedPhotoSheetState.showBottomSheet(data = photo)
        },
        smbConfig = state.smbConfig,
        smbScanStatus = state.smbScanStatus,
        onSmbCredentialsChange = viewModel::updateSmbCredentials,
        onSmbBrowseClick = {
            viewModel.openSmbBrowser()
            smbBrowserSheetState.showBottomSheet()
        },
        onSmbScanClick = viewModel::scanSmb,
        modifier = modifier,
    )

    state.smbBrowseState?.let { browseState ->
        SmbFolderBrowserSheet(
            sheetState = smbBrowserSheetState,
            browseState = browseState,
            serverHost = state.smbConfig.host,
            onEnterShare = { share -> viewModel.enterSmbShare(share) },
            onFolderDrillDown = { share, path -> viewModel.loadSmbFolders(share = share, path = path) },
            onBreadcrumbClick = { share, path -> viewModel.loadSmbFolders(share = share, path = path) },
            onGoToRoot = viewModel::goToRootInBrowser,
            onNavigateUp = viewModel::navigateUpInBrowser,
            onToggleFolder = viewModel::toggleSmbFolderSelection,
            onConfirm = viewModel::confirmSmbFolderSelection,
            onCancel = viewModel::closeSmbBrowser,
        )
    }

    if (showGifReplaceDialog) {
        AlertDialog(
            onDismissRequest = { showGifReplaceDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        showGifReplaceDialog = false
                        gifPickerLauncher.launch(input = "image/gif")
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(text = stringResource(id = R.string.photo_widget_action_continue))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showGifReplaceDialog = false },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(text = stringResource(id = R.string.photo_widget_action_cancel))
                }
            },
            text = {
                Text(text = stringResource(id = R.string.photo_widget_configure_pick_gif_replace))
            },
        )
    }

    // region Sheets
    PhotoWidgetSourceBottomSheet(
        sheetState = sourceSheetState,
        currentSource = state.photoWidget.source,
        syncedDir = state.photoWidget.syncedDir,
        onDirRemove = viewModel::removeDir,
        onChangeSource = viewModel::changeSource,
    )

    ImportFromWidgetBottomSheet(
        sheetState = importFromWidgetSheetState,
        onWidgetSelect = viewModel::importFromWidget,
    )

    RecentlyDeletedPhotoBottomSheet(
        sheetState = recentlyDeletedPhotoSheetState,
        onRestore = viewModel::restorePhoto,
        onDelete = viewModel::deletePhotoPermanently,
    )
    // endregion Sheets
}

@Composable
fun PhotoWidgetConfigureContentTab(
    photoWidget: PhotoWidget,
    onChangeSourceClick: () -> Unit,
    isImportAvailable: Boolean,
    onImportClick: () -> Unit,
    onPhotoPickerClick: () -> Unit,
    onDirPickerClick: () -> Unit,
    onGifPickerClick: () -> Unit,
    onPhotoClick: (LocalPhoto) -> Unit,
    onReorderFinish: (List<LocalPhoto>) -> Unit,
    onRemovedPhotoClick: (LocalPhoto) -> Unit,
    smbConfig: SmbConfig = SmbConfig(),
    smbScanStatus: PhotoWidgetConfigureState.SmbScanStatus = PhotoWidgetConfigureState.SmbScanStatus.Idle,
    onSmbCredentialsChange: (SmbConfig) -> Unit = {},
    onSmbBrowseClick: () -> Unit = {},
    onSmbScanClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    PhotoPicker(
        source = photoWidget.source,
        onChangeSourceClick = onChangeSourceClick,
        isImportAvailable = isImportAvailable,
        onImportClick = onImportClick,
        photos = photoWidget.photos,
        canSort = photoWidget.canSort,
        onPhotoPickerClick = onPhotoPickerClick,
        onDirPickerClick = onDirPickerClick,
        onGifPickerClick = onGifPickerClick,
        onPhotoClick = onPhotoClick,
        onReorderFinish = onReorderFinish,
        removedPhotos = photoWidget.removedPhotos,
        onRemovedPhotoClick = onRemovedPhotoClick,
        aspectRatio = photoWidget.aspectRatio,
        shapeId = photoWidget.shapeId,
        smbConfig = smbConfig,
        smbScanStatus = smbScanStatus,
        onSmbCredentialsChange = onSmbCredentialsChange,
        onSmbBrowseClick = onSmbBrowseClick,
        onSmbScanClick = onSmbScanClick,
        modifier = modifier.fillMaxSize(),
    )
}

@Composable
private fun PhotoPicker(
    source: PhotoWidgetSource,
    onChangeSourceClick: () -> Unit,
    isImportAvailable: Boolean,
    onImportClick: () -> Unit,
    photos: List<LocalPhoto>,
    canSort: Boolean,
    onPhotoPickerClick: () -> Unit,
    onDirPickerClick: () -> Unit,
    onGifPickerClick: () -> Unit,
    onPhotoClick: (LocalPhoto) -> Unit,
    onReorderFinish: (List<LocalPhoto>) -> Unit,
    removedPhotos: List<LocalPhoto>,
    onRemovedPhotoClick: (LocalPhoto) -> Unit,
    aspectRatio: PhotoWidgetAspectRatio,
    shapeId: String,
    smbConfig: SmbConfig = SmbConfig(),
    smbScanStatus: PhotoWidgetConfigureState.SmbScanStatus = PhotoWidgetConfigureState.SmbScanStatus.Idle,
    onSmbCredentialsChange: (SmbConfig) -> Unit = {},
    onSmbBrowseClick: () -> Unit = {},
    onSmbScanClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (source == PhotoWidgetSource.SMB) {
        SmbPhotoPicker(
            onChangeSourceClick = onChangeSourceClick,
            photos = photos,
            onPhotoClick = onPhotoClick,
            aspectRatio = aspectRatio,
            shapeId = shapeId,
            smbConfig = smbConfig,
            smbScanStatus = smbScanStatus,
            onSmbCredentialsChange = onSmbCredentialsChange,
            onSmbBrowseClick = onSmbBrowseClick,
            onSmbScanClick = onSmbScanClick,
            modifier = modifier,
        )
    } else {
        DefaultPhotoPicker(
            source = source,
            onChangeSourceClick = onChangeSourceClick,
            isImportAvailable = isImportAvailable,
            onImportClick = onImportClick,
            photos = photos,
            canSort = canSort,
            onPhotoPickerClick = onPhotoPickerClick,
            onDirPickerClick = onDirPickerClick,
            onGifPickerClick = onGifPickerClick,
            onPhotoClick = onPhotoClick,
            onReorderFinish = onReorderFinish,
            removedPhotos = removedPhotos,
            onRemovedPhotoClick = onRemovedPhotoClick,
            aspectRatio = aspectRatio,
            shapeId = shapeId,
            modifier = modifier,
        )
    }
}

@Composable
private fun SmbPhotoPicker(
    onChangeSourceClick: () -> Unit,
    photos: List<LocalPhoto>,
    onPhotoClick: (LocalPhoto) -> Unit,
    aspectRatio: PhotoWidgetAspectRatio,
    shapeId: String,
    smbConfig: SmbConfig,
    smbScanStatus: PhotoWidgetConfigureState.SmbScanStatus,
    onSmbCredentialsChange: (SmbConfig) -> Unit,
    onSmbBrowseClick: () -> Unit,
    onSmbScanClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(all = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = onChangeSourceClick,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 36.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        ) {
            AutoSizeText(
                text = stringResource(R.string.photo_widget_configure_change_source),
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }

        SmbConfigSection(
            config = smbConfig,
            onCredentialsChange = onSmbCredentialsChange,
            onBrowseClick = onSmbBrowseClick,
            scanStatus = smbScanStatus,
            onScanClick = onSmbScanClick,
            modifier = Modifier.fillMaxWidth(),
        )

        if (photos.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.photo_widget_smb_todays_photos, photos.size),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )

            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(photos, key = { it.photoId }) { photo ->
                    ShapedPhoto(
                        photo = photo,
                        aspectRatio = PhotoWidgetAspectRatio.SQUARE,
                        shapeId = if (PhotoWidgetAspectRatio.SQUARE == aspectRatio) {
                            shapeId
                        } else {
                            PhotoWidget.DEFAULT_SHAPE_ID
                        },
                        cornerRadius = PhotoWidget.DEFAULT_CORNER_RADIUS,
                        modifier = Modifier
                            .aspectRatio(ratio = 1f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Image,
                                onClick = { onPhotoClick(photo) },
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun DefaultPhotoPicker(
    source: PhotoWidgetSource,
    onChangeSourceClick: () -> Unit,
    isImportAvailable: Boolean,
    onImportClick: () -> Unit,
    photos: List<LocalPhoto>,
    canSort: Boolean,
    onPhotoPickerClick: () -> Unit,
    onDirPickerClick: () -> Unit,
    onGifPickerClick: () -> Unit,
    onPhotoClick: (LocalPhoto) -> Unit,
    onReorderFinish: (List<LocalPhoto>) -> Unit,
    removedPhotos: List<LocalPhoto>,
    onRemovedPhotoClick: (LocalPhoto) -> Unit,
    aspectRatio: PhotoWidgetAspectRatio,
    shapeId: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val localHaptics = LocalHapticFeedback.current

        val currentPhotos by rememberUpdatedState(photos.toMutableStateList())
        val lazyGridState = rememberLazyGridState()
        val reorderableLazyGridState = rememberReorderableLazyGridState(lazyGridState) { from, to ->
            currentPhotos.apply {
                this[to.index] = this[from.index].also {
                    this[from.index] = this[to.index]
                }
            }
            localHaptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(count = 5),
            modifier = Modifier
                .fillMaxSize()
                .fadingEdges(scrollState = lazyGridState),
            state = lazyGridState,
            contentPadding = PaddingValues(start = 16.dp, top = 68.dp, end = 16.dp, bottom = 200.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(currentPhotos, key = { photo -> photo }) { photo ->
                ReorderableItem(reorderableLazyGridState, key = photo) {
                    ShapedPhoto(
                        photo = photo,
                        aspectRatio = PhotoWidgetAspectRatio.SQUARE,
                        shapeId = if (aspectRatio == PhotoWidgetAspectRatio.SQUARE) {
                            shapeId
                        } else {
                            PhotoWidget.DEFAULT_SHAPE_ID
                        },
                        cornerRadius = PhotoWidget.DEFAULT_CORNER_RADIUS,
                        modifier = Modifier
                            .animateItem()
                            .longPressDraggableHandle(
                                enabled = canSort,
                                onDragStarted = {
                                    localHaptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                },
                                onDragStopped = {
                                    onReorderFinish(currentPhotos)
                                    localHaptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                },
                            )
                            .aspectRatio(ratio = 1f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Image,
                                onClick = { onPhotoClick(photo) },
                            ),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to MaterialTheme.colorScheme.background,
                            0.8f to MaterialTheme.colorScheme.background.copy(alpha = 0.95f),
                            1f to Color.Transparent,
                        ),
                    ),
                )
                .padding(all = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val interactionSources: Array<MutableInteractionSource> = remember {
                    Array(size = 2) { MutableInteractionSource() }
                }

                OutlinedButton(
                    onClick = {
                        when (source) {
                            PhotoWidgetSource.PHOTOS -> onPhotoPickerClick()
                            PhotoWidgetSource.DIRECTORY -> onDirPickerClick()
                            PhotoWidgetSource.SMB -> Unit
                            PhotoWidgetSource.GIF -> onGifPickerClick()
                        }
                    },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(max = 36.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                    interactionSource = interactionSources[0],
                ) {
                    AutoSizeText(
                        text = stringResource(
                            id = when (source) {
                                PhotoWidgetSource.PHOTOS -> R.string.photo_widget_configure_pick_photo
                                PhotoWidgetSource.DIRECTORY -> R.string.photo_widget_configure_pick_folder
                                PhotoWidgetSource.SMB -> R.string.photo_widget_configure_pick_folder
                                PhotoWidgetSource.GIF -> R.string.photo_widget_configure_pick_gif
                            },
                        ),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }

                OutlinedButton(
                    onClick = onChangeSourceClick,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(max = 36.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                    interactionSource = interactionSources[1],
                ) {
                    AutoSizeText(
                        text = stringResource(R.string.photo_widget_configure_change_source),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }

            AnimatedVisibility(
                visible = isImportAvailable && photos.isEmpty() && removedPhotos.isEmpty(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(all = 32.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.medium,
                        )
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.photo_widget_configure_import_prompt),
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMediumEmphasized,
                    )

                    TextButton(
                        onClick = onImportClick,
                    ) {
                        Text(
                            text = stringResource(R.string.photo_widget_configure_import_prompt_action),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = removedPhotos.isNotEmpty() && source != PhotoWidgetSource.GIF,
            modifier = Modifier.fillMaxWidth(),
        ) {
            RemovedPhotosPicker(
                title = when (source) {
                    PhotoWidgetSource.PHOTOS -> stringResource(
                        R.string.photo_widget_configure_photos_pending_deletion,
                    )

                    PhotoWidgetSource.DIRECTORY,
                    PhotoWidgetSource.SMB,
                    -> stringResource(R.string.photo_widget_configure_photos_excluded)

                    PhotoWidgetSource.GIF -> error("GIF source does not support removing photos.")
                },
                photos = removedPhotos,
                onPhotoClick = onRemovedPhotoClick,
                aspectRatio = aspectRatio,
                shapeId = shapeId,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                0.2f to MaterialTheme.colorScheme.background.copy(alpha = 0.95f),
                                1f to MaterialTheme.colorScheme.background,
                            ),
                        ),
                    )
                    .padding(top = 32.dp),
            )
        }

        if (source == PhotoWidgetSource.GIF) {
            WarningSign(
                text = stringResource(R.string.warning_gif_widget_battery_usage),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun SmbConfigSection(
    config: SmbConfig,
    onCredentialsChange: (SmbConfig) -> Unit,
    onBrowseClick: () -> Unit,
    scanStatus: PhotoWidgetConfigureState.SmbScanStatus,
    onScanClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Credentials
        OutlinedTextField(
            value = config.host,
            onValueChange = { onCredentialsChange(config.copy(host = it)) },
            label = { Text(stringResource(R.string.photo_widget_smb_host)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = config.username,
            onValueChange = { onCredentialsChange(config.copy(username = it)) },
            label = { Text(stringResource(R.string.photo_widget_smb_username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = config.password,
            onValueChange = { onCredentialsChange(config.copy(password = it)) },
            label = { Text(stringResource(R.string.photo_widget_smb_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        // Folder selection
        OutlinedButton(
            onClick = onBrowseClick,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.photo_widget_smb_browse_folders))
        }

        Text(
            text = if (config.selectedFolders.isEmpty()) {
                stringResource(R.string.photo_widget_smb_no_folders_selected)
            } else {
                config.selectedFolders.joinToString(separator = "\n") { "• ${it.displayName}" }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        // Sync button + status
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onScanClick,
                enabled = config.isConfigured &&
                    scanStatus !is PhotoWidgetConfigureState.SmbScanStatus.Scanning,
                shapes = ButtonDefaults.shapes(),
            ) {
                if (scanStatus is PhotoWidgetConfigureState.SmbScanStatus.Scanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    text = if (scanStatus is PhotoWidgetConfigureState.SmbScanStatus.Scanning) {
                        stringResource(R.string.photo_widget_smb_scanning)
                    } else {
                        stringResource(R.string.photo_widget_smb_scan)
                    },
                )
            }

            val statusText = when (scanStatus) {
                is PhotoWidgetConfigureState.SmbScanStatus.Done ->
                    stringResource(R.string.photo_widget_smb_scan_result, scanStatus.count)
                is PhotoWidgetConfigureState.SmbScanStatus.Error ->
                    stringResource(R.string.photo_widget_smb_scan_error)
                else -> null
            }
            if (statusText != null) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (scanStatus is PhotoWidgetConfigureState.SmbScanStatus.Error) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun RemovedPhotosPicker(
    title: String,
    photos: List<LocalPhoto>,
    onPhotoClick: (LocalPhoto) -> Unit,
    aspectRatio: PhotoWidgetAspectRatio,
    shapeId: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleMedium,
        )

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(photos, key = { it.photoId }) { photo ->
                ShapedPhoto(
                    photo = photo,
                    aspectRatio = PhotoWidgetAspectRatio.SQUARE,
                    shapeId = if (aspectRatio == PhotoWidgetAspectRatio.SQUARE) {
                        shapeId
                    } else {
                        PhotoWidget.DEFAULT_SHAPE_ID
                    },
                    cornerRadius = PhotoWidget.DEFAULT_CORNER_RADIUS,
                    modifier = Modifier
                        .animateItem()
                        .fillMaxWidth()
                        .aspectRatio(ratio = 1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Image,
                            onClick = { onPhotoClick(photo) },
                        ),
                    colors = PhotoWidgetColors(saturation = 0f),
                )
            }
        }
    }
}

// region Previews
@PreviewAll
@Composable
private fun PhotoWidgetConfigureContentTabPreview() {
    ExtendedTheme {
        PhotoWidgetConfigureContentTab(
            photoWidget = PhotoWidget(
                photos = List(20) { index -> LocalPhoto(photoId = "photo-$index") },
            ),
            onChangeSourceClick = {},
            isImportAvailable = false,
            onImportClick = {},
            onPhotoPickerClick = {},
            onDirPickerClick = {},
            onGifPickerClick = {},
            onPhotoClick = {},
            onReorderFinish = {},
            onRemovedPhotoClick = {},
            modifier = Modifier.safeDrawingPadding(),
        )
    }
}

@PreviewAll
@Composable
private fun PhotoWidgetConfigureContentTabDirectoryPreview() {
    ExtendedTheme {
        PhotoWidgetConfigureContentTab(
            photoWidget = PhotoWidget(
                photos = List(20) { index -> LocalPhoto(photoId = "photo-$index") },
                source = PhotoWidgetSource.DIRECTORY,
            ),
            onChangeSourceClick = {},
            isImportAvailable = false,
            onImportClick = {},
            onPhotoPickerClick = {},
            onDirPickerClick = {},
            onGifPickerClick = {},
            onPhotoClick = {},
            onReorderFinish = {},
            onRemovedPhotoClick = {},
            modifier = Modifier.safeDrawingPadding(),
        )
    }
}
// endregion Previews
