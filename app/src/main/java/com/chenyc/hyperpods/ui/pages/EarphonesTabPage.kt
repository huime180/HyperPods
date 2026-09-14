package com.chenyc.hyperpods.ui.pages

import com.chenyc.hyperpods.ui.MoondropControls

import android.bluetooth.BluetoothDevice
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.chenyc.hyperpods.R
import com.chenyc.hyperpods.pods.NoiseControlMode
import com.chenyc.hyperpods.pods.WearStatus
import com.chenyc.hyperpods.utils.miuiStrongToast.data.BatteryParams
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
internal fun EarphonesTabPage(
    showEarphoneDetail: Boolean,
    displayTitle: String,
    displayBattery: BatteryParams,
    displayWearStatus: WearStatus,
    displayAnc: NoiseControlMode,
    onAncModeChange: (NoiseControlMode) -> Unit,
    smartAncLevel: NoiseControlMode?,
    displayTransparencyVocalEnhancement: Boolean,
    onTransparencyVocalEnhancementChange: (Boolean) -> Unit,
    displayGameMode: Boolean,
    gameModeSupported: Boolean,
    onGameModeChange: (Boolean) -> Unit,
    spatialAudioMode: Int,
    onSpatialAudioModeChange: (Int) -> Unit,
    moondrop: MoondropControls,
    equalizerVisible: Boolean,
    dualDeviceSupported: Boolean,
    onOpenEqualizer: () -> Unit,
    displayDualDeviceConnection: Boolean,
    onDualDeviceConnectionChange: (Boolean) -> Unit,
    spatialAudioSupported: Boolean,
    spatialSoundSupported: Boolean,
    adaptiveModeEnabled: Boolean,
    boxImagePath: String?,
    connectedDeviceAddress: String,
    connectingDeviceAddress: String?,
    showConnectErrorDialog: Boolean,
    contentPadding: PaddingValues,
    pageBottomContentPadding: Dp,
    nestedScrollConnection: NestedScrollConnection,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onConnectedDeviceClick: () -> Unit,
    onDeviceDisconnect: (BluetoothDevice) -> Unit,
    onDismissConnectError: () -> Unit,
) {
    AnimatedContent(
        targetState = showEarphoneDetail,
        modifier = Modifier.fillMaxSize(),
        label = "EarphonesPageAnim",
    ) { detailVisible ->
        if (detailVisible) {
            PodDetailPage(
                modifier = Modifier
                    .overScrollVertical()
                    .nestedScroll(nestedScrollConnection),
                contentPadding = contentPadding,
                bottomContentPadding = pageBottomContentPadding,
                podName = displayTitle.ifEmpty { stringResource(R.string.pod_info) },
                batteryParams = displayBattery,
                wearStatus = displayWearStatus,
                ancMode = displayAnc,
                onAncModeChange = onAncModeChange,
                smartAncLevel = smartAncLevel,
                transparencyVocalEnhancement = displayTransparencyVocalEnhancement,
                onTransparencyVocalEnhancementChange = onTransparencyVocalEnhancementChange,
                gameMode = displayGameMode,
                gameModeSupported = gameModeSupported,
                onGameModeChange = onGameModeChange,
                spatialAudioMode = spatialAudioMode,
                onSpatialAudioModeChange = onSpatialAudioModeChange,
                moondrop = moondrop,
                equalizerVisible = equalizerVisible,
                dualDeviceSupported = dualDeviceSupported,
                onOpenEqualizer = onOpenEqualizer,
                dualDeviceConnection = displayDualDeviceConnection,
                onDualDeviceConnectionChange = onDualDeviceConnectionChange,
                spatialAudioSupported = spatialAudioSupported,
                spatialSoundSupported = spatialSoundSupported,
                adaptiveModeEnabled = adaptiveModeEnabled,
                boxImagePath = boxImagePath,
            )
        } else {
            DevicePickerPage(
                connectedDeviceName = displayTitle,
                connectedDeviceAddress = connectedDeviceAddress,
                connectingDeviceAddress = connectingDeviceAddress,
                showConnectError = showConnectErrorDialog,
                contentPadding = contentPadding,
                bottomContentPadding = pageBottomContentPadding,
                onDeviceSelected = onDeviceSelected,
                onConnectedDeviceClick = onConnectedDeviceClick,
                onDeviceDisconnect = onDeviceDisconnect,
                onDismissConnectError = onDismissConnectError,
            )
        }
    }
}
