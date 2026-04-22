@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.fibelatti.photowidget.configure

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fibelatti.photowidget.R
import com.fibelatti.photowidget.model.SmbFolder
import com.fibelatti.photowidget.ui.DefaultSheetContent
import com.fibelatti.ui.foundation.AppBottomSheet
import com.fibelatti.ui.foundation.AppSheetState
import com.fibelatti.ui.foundation.hideBottomSheet

@Composable
fun SmbFolderBrowserSheet(
    sheetState: AppSheetState,
    browseState: PhotoWidgetConfigureState.SmbBrowseState,
    serverHost: String,
    onEnterShare: (share: String) -> Unit,
    onFolderDrillDown: (share: String, path: String) -> Unit,
    onBreadcrumbClick: (share: String, path: String) -> Unit,
    onGoToRoot: () -> Unit,
    onNavigateUp: () -> Unit,
    onToggleFolder: (SmbFolder) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AppBottomSheet(sheetState = sheetState) {
        DefaultSheetContent(
            title = stringResource(R.string.photo_widget_smb_browser_title),
        ) {
            // Breadcrumb bar — always visible
            BreadcrumbBar(
                serverHost = serverHost,
                currentShare = browseState.currentShare,
                breadcrumb = browseState.breadcrumb,
                onNavigateUp = onNavigateUp,
                onGoToRoot = onGoToRoot,
                onBreadcrumbClick = { path ->
                    onBreadcrumbClick(browseState.currentShare, path)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )

            Spacer(Modifier.height(8.dp))

            when {
                browseState.isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                browseState.loadError -> {
                    Text(
                        text = stringResource(R.string.photo_widget_smb_browser_load_error),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                browseState.currentFolderContents.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.photo_widget_smb_browser_empty),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        items(browseState.currentFolderContents) { name ->
                            if (browseState.isAtRoot) {
                                // Root level: each item is a share
                                val shareFolder = SmbFolder(share = name, path = "")
                                val isChecked = shareFolder in browseState.selectedFolders
                                FolderRow(
                                    name = name,
                                    isChecked = isChecked,
                                    onCheckClick = { onToggleFolder(shareFolder) },
                                    onDrillDown = { onEnterShare(name) },
                                )
                            } else {
                                // Inside a share: each item is a subdirectory
                                val childPath = if (browseState.currentPath.isEmpty()) {
                                    name
                                } else {
                                    "${browseState.currentPath}\\$name"
                                }
                                val folder = SmbFolder(
                                    share = browseState.currentShare,
                                    path = childPath,
                                )
                                val isChecked = folder in browseState.selectedFolders
                                FolderRow(
                                    name = name,
                                    isChecked = isChecked,
                                    onCheckClick = { onToggleFolder(folder) },
                                    onDrillDown = {
                                        onFolderDrillDown(browseState.currentShare, childPath)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // Selected folders summary chip row
            if (browseState.selectedFolders.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(browseState.selectedFolders.toList()) { folder ->
                        SelectedFolderChip(
                            folder = folder,
                            onRemove = { onToggleFolder(folder) },
                        )
                    }
                }
            }

            // Confirm / Cancel buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        onCancel()
                        sheetState.hideBottomSheet()
                    },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.photo_widget_action_cancel))
                }

                Button(
                    onClick = {
                        onConfirm()
                        sheetState.hideBottomSheet()
                    },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = stringResource(
                            R.string.photo_widget_smb_browser_confirm,
                            browseState.selectedFolders.size,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun BreadcrumbBar(
    serverHost: String,
    currentShare: String,
    breadcrumb: List<String>,
    onNavigateUp: () -> Unit,
    onGoToRoot: () -> Unit,
    onBreadcrumbClick: (path: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val canGoUp = currentShare.isNotEmpty()

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (canGoUp) {
            IconButton(onClick = onNavigateUp, modifier = Modifier.size(32.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_left),
                    contentDescription = stringResource(R.string.photo_widget_smb_browser_back),
                )
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Hostname — tapping goes back to root (shares list)
            item {
                val isLast = currentShare.isEmpty()
                TextButton(
                    onClick = onGoToRoot,
                    enabled = !isLast,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = serverHost,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }

            // Share name segment
            if (currentShare.isNotEmpty()) {
                item {
                    Text(
                        text = "/",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    val isLast = breadcrumb.isEmpty()
                    TextButton(
                        onClick = { onBreadcrumbClick("") },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    ) {
                        Text(
                            text = currentShare,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Subdirectory segments
            breadcrumb.forEachIndexed { index, segment ->
                item {
                    Text(
                        text = "/",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    val isLast = index == breadcrumb.lastIndex
                    val pathUpTo = breadcrumb.take(index + 1).joinToString("\\")
                    TextButton(
                        onClick = { onBreadcrumbClick(pathUpTo) },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    ) {
                        Text(
                            text = segment,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(
    name: String,
    isChecked: Boolean,
    onCheckClick: () -> Unit,
    onDrillDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = isChecked,
            onCheckedChange = { onCheckClick() },
        )

        Icon(
            painter = painterResource(R.drawable.ic_hard_drive),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = name,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )

        IconButton(onClick = onDrillDown) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SelectedFolderChip(
    folder: SmbFolder,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clickable(onClick = onRemove)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = folder.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            painter = painterResource(R.drawable.ic_xmark),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}
