package com.lightningstudio.watchrss.phone.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneSyncConflictResolution
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import com.lightningstudio.watchrss.phone.viewmodel.MainBluetoothDeviceUi
import com.lightningstudio.watchrss.phone.viewmodel.MainUiState
import com.lightningstudio.watchrss.phone.viewmodel.SharedImportPromptUi

@Composable
fun MainScreen(
    uiState: MainUiState,
    onUrlChange: (String) -> Unit,
    onImportArticle: () -> Unit,
    onAddRssSource: () -> Unit,
    onImportSharedLinkAsArticle: (String) -> Unit,
    onImportSharedLinkAsRss: (String) -> Unit,
    onConfirmSharedFileImport: (SharedImportPromptUi) -> Unit,
    onDismissSharedImport: () -> Unit,
    onSyncLibrary: () -> Unit,
    onChooseBluetoothDevice: (MainBluetoothDeviceUi) -> Unit,
    onDismissBluetoothDevicePrompt: () -> Unit,
    onExportBluetoothLog: () -> Unit,
    onOpenArticle: (PhoneArticleEntity) -> Unit,
    onToggleFavorite: (PhoneArticleEntity) -> Unit,
    onToggleWatchLater: (PhoneArticleEntity) -> Unit,
    onDeleteArticle: (PhoneArticleEntity) -> Unit,
    onClearImportedContent: () -> Unit,
    onChooseConflictResolution: (PhoneSyncConflictResolution) -> Unit,
    onShowManualConflictOptions: () -> Unit,
    onDismissMessage: () -> Unit,
    onNavigateToPage: (MainTab) -> Unit,
    onNavigateToGuide: () -> Unit
) {
    val backgroundColor = MaterialTheme.colorScheme.background

    Box(modifier = Modifier.fillMaxSize()) {
        val backdrop = rememberLayerBackdrop {
            drawRect(backgroundColor)
            drawContent()
        }

        // 主内容区域 — 只显示 HOME（三卡片）
        Box(
            modifier = Modifier
                .layerBackdrop(backdrop)
                .fillMaxSize()
        ) {
            PageColumn(bottomSpacing = TAB_BAR_HEIGHT) {
                // 带动画的状态卡片：同步进度 / 消息 / 错误
                AnimatedVisibility(
                    visible = uiState.syncProgress != null || !uiState.message.isNullOrBlank() || !uiState.error.isNullOrBlank(),
                    enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
                ) {
                    StatusCard(
                        message = uiState.message,
                        error = uiState.error,
                        syncProgress = uiState.syncProgress,
                        onDismissMessage = onDismissMessage
                    )
                }

                HomePage(
                    uiState = uiState,
                    onNavigateToGuide = onNavigateToGuide,
                    onNavigateToRss = { onNavigateToPage(MainTab.RSS) },
                    onNavigateToNovel = { onNavigateToPage(MainTab.NOVEL) },
                    onDismissMessage = onDismissMessage
                )
            }
        }

        // 胶囊悬浮同步按钮
        CapsuleFloatingButton(
            backdrop = backdrop,
            onClick = onSyncLibrary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = TAB_BAR_HEIGHT + 16.dp)
        ) {
            Icon(Icons.Default.Sync, contentDescription = "同步手表")
            Text("同步")
        }

        // 底部玻璃 TabBar（3个Tab）
        GlassTabBar(
            backdrop = backdrop,
            selectedTab = MainTab.HOME,
            onTabSelected = { tab ->
                if (tab != MainTab.HOME) {
                    onNavigateToPage(tab)
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    // 全局对话框
    uiState.conflictPrompt?.let { prompt ->
        DeleteConflictDialog(
            prompt = prompt,
            onChooseResolution = onChooseConflictResolution,
            onShowManualOptions = onShowManualConflictOptions
        )
    }
    uiState.sharedImportPrompt?.let { prompt ->
        SharedImportDialog(
            prompt = prompt,
            onImportLinkAsArticle = onImportSharedLinkAsArticle,
            onImportLinkAsRss = onImportSharedLinkAsRss,
            onConfirmFileImport = onConfirmSharedFileImport,
            onDismiss = onDismissSharedImport
        )
    }
    uiState.bluetoothDevicePrompt?.let { prompt ->
        BluetoothDeviceChooserDialog(
            prompt = prompt,
            onChooseDevice = onChooseBluetoothDevice,
            onDismiss = onDismissBluetoothDevicePrompt
        )
    }
}
