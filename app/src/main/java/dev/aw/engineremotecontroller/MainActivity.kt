package dev.aw.engineremotecontroller

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.concurrent.thread

data class DeviceUi(
    val name: String,
    val address: String
)

data class EngineAction(
    val title: String,
    val channel: Int,
    val defaultSeconds: String,
    val summary: String,
    val description: String,
    val dangerous: Boolean = false
)

val engineActions = listOf(
    EngineAction(
        title = "イグニッション",
        channel = 1,
        defaultSeconds = "1.0",
        summary = "CH1 / 始動系",
        description = "指定秒数だけON→OFF",
        dangerous = true
    ),
    EngineAction(
        title = "エンジン切",
        channel = 2,
        defaultSeconds = "1.0",
        summary = "CH2 / 停止系",
        description = "指定秒数だけON→OFF",
        dangerous = true
    ),
    EngineAction(
        title = "アラーム",
        channel = 3,
        defaultSeconds = "1.0",
        summary = "CH3 / DCモーター",
        description = "指定秒数だけON→OFF"
    ),
    EngineAction(
        title = "チョークA",
        channel = 5,
        defaultSeconds = "0.5",
        summary = "CH5",
        description = "少しずつ調整"
    ),
    EngineAction(
        title = "チョークB",
        channel = 6,
        defaultSeconds = "0.5",
        summary = "CH6",
        description = "少しずつ調整"
    )
)

enum class EnginePane(val title: String) {
    CONNECTION("接続"),
    OPERATION("操作"),
    SETTINGS("設定"),
    LOG("ログ")
}

class MainActivity : ComponentActivity() {
    private lateinit var relayManager: BluetoothRelayManager

    private val deviceList = mutableStateListOf<DeviceUi>()
    private val relayStates = mutableStateListOf(
        false, false, false, false,
        false, false, false, false
    )
    private val logs = mutableStateListOf<String>()

    private var selectedAddress by mutableStateOf<String?>(null)
    private var isConnected by mutableStateOf(false)
    private var statusText by mutableStateOf("未接続")
    private var permissionGranted by mutableStateOf(false)
    private var currentPane by mutableStateOf(EnginePane.OPERATION)

    private val actionSeconds = mutableStateListOf(
        "1.0",  // イグニッション
        "1.0",  // エンジン切
        "1.0",  // アラーム
        "0.5",  // チョークA
        "0.5"   // チョークB
    )

    private var engineActionRunning by mutableStateOf(false)
    private var runningActionTitle by mutableStateOf<String?>(null)
    private var runningRemainingText by mutableStateOf("-")

    @Volatile
    private var actionCancelRequested = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionGranted = hasBluetoothPermissions()
            if (permissionGranted) {
                addLog("Bluetooth権限が許可された")
                refreshBondedDevices()
            } else {
                statusText = "権限が未許可"
                addLog("Bluetooth権限が許可されなかった")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        relayManager = BluetoothRelayManager(applicationContext)
        permissionGranted = hasBluetoothPermissions()

        if (permissionGranted) {
            refreshBondedDevices()
        }

        setContent {
            MaterialTheme {
                RelayApp()
            }
        }
    }

    override fun onDestroy() {
        actionCancelRequested = true
        relayManager.disconnect()
        super.onDestroy()
    }

    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }

        val connectGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

        val scanGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED

        return connectGranted && scanGranted
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                )
            )
        } else {
            permissionGranted = true
            refreshBondedDevices()
        }
    }

    private fun addLog(message: String) {
        logs.add(message)
        while (logs.size > 30) {
            logs.removeAt(0)
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshBondedDevices() {
        if (!hasBluetoothPermissions()) {
            permissionGranted = false
            statusText = "権限が未許可"
            return
        }

        val bonded = relayManager.getBondedDevices().map {
            DeviceUi(
                name = it.name ?: "名前なし",
                address = it.address
            )
        }

        deviceList.clear()
        deviceList.addAll(bonded)

        if (selectedAddress == null || bonded.none { it.address == selectedAddress }) {
            selectedAddress = bonded.firstOrNull()?.address
        }

        statusText = if (bonded.isEmpty()) {
            "ペアリング済み機器が見つからない"
        } else {
            "機器一覧更新済み"
        }

        addLog("ペアリング済み機器: ${bonded.size}件")
    }

    private fun connectSelectedDevice() {
        val address = selectedAddress
        if (address == null) {
            statusText = "機器を選んでね"
            addLog("接続失敗: 機器未選択")
            return
        }

        thread {
            runOnUiThread {
                statusText = "接続中..."
            }

            val result = relayManager.connect(address)

            runOnUiThread {
                if (result.isSuccess) {
                    isConnected = true
                    statusText = "接続中"
                    val selectedName = deviceList.firstOrNull { it.address == address }?.name ?: address
                    addLog("接続成功: $selectedName")
                    currentPane = EnginePane.OPERATION
                } else {
                    isConnected = false
                    statusText = "接続失敗"
                    addLog("接続失敗: ${result.exceptionOrNull()?.message ?: "不明"}")
                }
            }
        }
    }

    private fun disconnectDevice() {
        actionCancelRequested = true
        relayManager.disconnect()
        isConnected = false
        engineActionRunning = false
        runningActionTitle = null
        runningRemainingText = "-"
        statusText = "切断済み"
        addLog("切断した")
    }

    private fun runTimedAction(
        action: EngineAction,
        secondsText: String
    ) {
        if (!isConnected) {
            statusText = "未接続"
            addLog("${action.title}失敗: 未接続")
            currentPane = EnginePane.CONNECTION
            return
        }

        if (engineActionRunning) {
            addLog("${action.title}失敗: 他の操作を実行中")
            return
        }

        val seconds = secondsText.toDoubleOrNull()
        if (seconds == null || seconds <= 0.0) {
            statusText = "秒数を正しく入力してね"
            addLog("${action.title}失敗: 秒数が不正")
            return
        }

        val clampedSeconds = seconds.coerceIn(0.1, 5.0)
        val totalMillis = (clampedSeconds * 1000.0).toLong()

        actionCancelRequested = false
        engineActionRunning = true
        runningActionTitle = action.title
        runningRemainingText = String.format(Locale.US, "%.1f 秒", clampedSeconds)
        statusText = "${action.title} 実行中..."
        relayStates[action.channel - 1] = true
        addLog("${action.title}: CH${action.channel} ON / ${clampedSeconds}秒")

        thread {
            val onResult = relayManager.send(RelayCommands.channelOn(action.channel))

            if (onResult.isFailure) {
                runOnUiThread {
                    engineActionRunning = false
                    runningActionTitle = null
                    runningRemainingText = "-"
                    relayStates[action.channel - 1] = false
                    statusText = "${action.title} ON失敗"
                    addLog("${action.title} ON失敗: ${onResult.exceptionOrNull()?.message ?: "不明"}")
                }
                return@thread
            }

            val intervalMs = 100L
            val startTime = System.currentTimeMillis()

            while (true) {
                if (actionCancelRequested) {
                    runOnUiThread {
                        runningRemainingText = "-"
                    }
                    return@thread
                }

                val elapsed = System.currentTimeMillis() - startTime
                val remaining = totalMillis - elapsed

                if (remaining <= 0L) {
                    break
                }

                runOnUiThread {
                    if (!actionCancelRequested) {
                        runningRemainingText = String.format(
                            Locale.US,
                            "%.1f 秒",
                            remaining / 1000.0
                        )
                    }
                }

                try {
                    Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    runOnUiThread {
                        engineActionRunning = false
                        runningActionTitle = null
                        runningRemainingText = "-"
                    }
                    return@thread
                }
            }

            if (actionCancelRequested) {
                return@thread
            }

            val offResult = relayManager.send(RelayCommands.channelOff(action.channel))

            runOnUiThread {
                engineActionRunning = false
                runningActionTitle = null
                runningRemainingText = "0.0 秒"
                relayStates[action.channel - 1] = false

                if (offResult.isSuccess) {
                    statusText = "${action.title} 完了"
                    addLog("${action.title}: CH${action.channel} OFF")
                } else {
                    statusText = "${action.title} OFF失敗"
                    addLog("${action.title} OFF失敗: ${offResult.exceptionOrNull()?.message ?: "不明"}")
                }
            }
        }
    }

    private fun emergencyAllOff() {
        if (!isConnected) {
            statusText = "未接続"
            addLog("全OFF失敗: 未接続")
            return
        }

        actionCancelRequested = true

        thread {
            val result = relayManager.send(RelayCommands.allOff)

            runOnUiThread {
                for (i in relayStates.indices) {
                    relayStates[i] = false
                }

                engineActionRunning = false
                runningActionTitle = null
                runningRemainingText = "-"

                if (result.isSuccess) {
                    statusText = "全OFF完了"
                    addLog("全OFF送信")
                } else {
                    statusText = "全OFF失敗"
                    addLog("全OFF失敗: ${result.exceptionOrNull()?.message ?: "不明"}")
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun RelayApp() {
        val selectedDevice = deviceList.firstOrNull { it.address == selectedAddress }
        val latestLogs = logs.takeLast(20).reversed()

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Engine Remote Controller") }
                )
            },
            bottomBar = {
                PaneSwitcher(
                    currentPane = currentPane,
                    onPaneSelected = { currentPane = it }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(12.dp)
            ) {
                CompactStatusHeader(
                    isConnected = isConnected,
                    permissionGranted = permissionGranted,
                    statusText = statusText,
                    selectedDeviceName = selectedDevice?.name ?: "未選択"
                )

                Spacer(modifier = Modifier.height(8.dp))

                when (currentPane) {
                    EnginePane.CONNECTION -> ConnectionPane(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )

                    EnginePane.OPERATION -> OperationPane(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )

                    EnginePane.SETTINGS -> SettingsPane(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )

                    EnginePane.LOG -> LogPane(
                        latestLogs = latestLogs,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }
            }
        }
    }

    @Composable
    private fun ConnectionPane(modifier: Modifier = Modifier) {
        LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "connection_buttons") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Bluetooth接続",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { requestBluetoothPermission() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("権限")
                            }

                            OutlinedButton(
                                onClick = { refreshBondedDevices() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("再読込")
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { connectSelectedDevice() },
                                modifier = Modifier.weight(1f),
                                enabled = permissionGranted && selectedAddress != null && !isConnected
                            ) {
                                Text("接続")
                            }

                            OutlinedButton(
                                onClick = { disconnectDevice() },
                                modifier = Modifier.weight(1f),
                                enabled = isConnected
                            ) {
                                Text("切断")
                            }
                        }
                    }
                }
            }

            item(key = "device_title") {
                SectionTitle("ペアリング済み機器")
            }

            if (deviceList.isEmpty()) {
                item(key = "device_empty") {
                    InfoCard("端末設定で先にペアリングしてから再読込してね")
                }
            } else {
                items(
                    items = deviceList,
                    key = { device -> "device-${device.address}" }
                ) { device ->
                    DeviceCard(
                        device = device,
                        selected = selectedAddress == device.address,
                        onSelect = { selectedAddress = device.address }
                    )
                }
            }
        }
    }

    @Composable
    private fun OperationPane(modifier: Modifier = Modifier) {
        val mainActions = engineActions.take(3)
        val chokeActions = engineActions.drop(3)

        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OperationInfoCard(
                    modifier = Modifier.weight(1f)
                )
                EmergencyMiniCard(
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SectionTitle("メイン操作")
                    mainActions.forEachIndexed { index, action ->
                        OperationActionCard(
                            action = action,
                            secondsText = actionSeconds[index],
                            enabled = isConnected && !engineActionRunning,
                            running = runningActionTitle == action.title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            onRun = {
                                runTimedAction(
                                    action = action,
                                    secondsText = actionSeconds[index]
                                )
                            }
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RunningMiniCard(
                        modifier = Modifier.fillMaxWidth()
                    )

                    SectionTitle("チョーク")
                    chokeActions.forEachIndexed { localIndex, action ->
                        val globalIndex = localIndex + 3
                        OperationActionCard(
                            action = action,
                            secondsText = actionSeconds[globalIndex],
                            enabled = isConnected && !engineActionRunning,
                            running = runningActionTitle == action.title,
                            compact = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            onRun = {
                                runTimedAction(
                                    action = action,
                                    secondsText = actionSeconds[globalIndex]
                                )
                            }
                        )
                    }

                    ChannelStateMiniCard(
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    @Composable
    private fun OperationInfoCard(modifier: Modifier = Modifier) {
        Card(modifier = modifier) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "操作画面",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isConnected) "各ボタンは指定秒数だけON→OFF" else "先に接続PaneでBluetooth接続してね",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    @Composable
    private fun EmergencyMiniCard(modifier: Modifier = Modifier) {
        Card(modifier = modifier) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "緊急",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Button(
                    onClick = { emergencyAllOff() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isConnected,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("全OFF")
                }
            }
        }
    }

    @Composable
    private fun RunningMiniCard(modifier: Modifier = Modifier) {
        Card(modifier = modifier) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "実行状態",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (engineActionRunning) {
                    Text(
                        text = runningActionTitle ?: "実行中",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "残り $runningRemainingText",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        text = "待機中",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "操作できます",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    @Composable
    private fun OperationActionCard(
        action: EngineAction,
        secondsText: String,
        enabled: Boolean,
        running: Boolean,
        modifier: Modifier = Modifier,
        compact: Boolean = false,
        onRun: () -> Unit
    ) {
        Card(modifier = modifier) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (compact) 10.dp else 12.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = action.title,
                        style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${action.summary} / ${secondsText.ifBlank { "?" }}秒",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (!compact) {
                        Text(
                            text = action.description,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Button(
                    onClick = onRun,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    colors = if (action.dangerous) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    }
                ) {
                    Text(if (running) "実行中" else "実行")
                }
            }
        }
    }

    @Composable
    private fun ChannelStateMiniCard(modifier: Modifier = Modifier) {
        val visibleChannels = listOf(1, 2, 3, 5, 6)

        Card(modifier = modifier) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "CH状態",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                visibleChannels.chunked(3).forEach { rowChannels ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rowChannels.forEach { ch ->
                            Box(modifier = Modifier.weight(1f)) {
                                ChannelStateChip(
                                    ch = ch,
                                    isOn = relayStates[ch - 1]
                                )
                            }
                        }
                        repeat(3 - rowChannels.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsPane(modifier: Modifier = Modifier) {
        LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                InfoCard("各操作のON時間を設定するよ。操作画面のボタンはこの秒数で動作する。入力値は0.1〜5.0秒に丸める。")
            }

            item {
                SectionTitle("動作秒数")
            }

            items(
                items = engineActions.mapIndexed { index, action -> index to action },
                key = { (_, action) -> "settings-${action.channel}" }
            ) { (index, action) ->
                SettingSecondsCard(
                    action = action,
                    secondsText = actionSeconds[index],
                    onSecondsChange = { actionSeconds[index] = it }
                )
            }
        }
    }

    @Composable
    private fun SettingSecondsCard(
        action: EngineAction,
        secondsText: String,
        onSecondsChange: (String) -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = action.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(action.summary)

                OutlinedTextField(
                    value = secondsText,
                    onValueChange = onSecondsChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("動作秒数") },
                    placeholder = { Text(action.defaultSeconds) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }
        }
    }

    @Composable
    private fun LogPane(
        latestLogs: List<String>,
        modifier: Modifier = Modifier
    ) {
        LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "log_title") {
                SectionTitle("ログ")
            }

            if (latestLogs.isEmpty()) {
                item(key = "log_empty") {
                    InfoCard("まだログなし")
                }
            } else {
                items(
                    items = latestLogs.mapIndexed { index, line -> index to line },
                    key = { (index, line) -> "log-$index-$line" }
                ) { (_, line) ->
                    LogCard(line)
                }
            }
        }
    }
}

@Composable
private fun PaneSwitcher(
    currentPane: EnginePane,
    onPaneSelected: (EnginePane) -> Unit
) {
    NavigationBar {
        EnginePane.entries.forEach { pane ->
            NavigationBarItem(
                selected = currentPane == pane,
                onClick = { onPaneSelected(pane) },
                icon = {},
                label = { Text(pane.title) }
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun InfoCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
private fun LogCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun CompactStatusHeader(
    isConnected: Boolean,
    permissionGranted: Boolean,
    statusText: String,
    selectedDeviceName: String
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                ConnectionDot(isConnected = isConnected)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = if (isConnected) "接続中" else "未接続",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = selectedDeviceName,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.End
            ) {
                StatusChip("権限: ${if (permissionGranted) "OK" else "未許可"}")
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ConnectionDot(isConnected: Boolean) {
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(
                if (isConnected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
    )
}

@Composable
private fun StatusChip(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        tonalElevation = 2.dp
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ChannelStateChip(
    ch: Int,
    isOn: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(999.dp),
        tonalElevation = if (isOn) 4.dp else 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (isOn) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        }
                    )
            )

            Spacer(modifier = Modifier.width(4.dp))

            Text(
                text = "CH$ch",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun DeviceCard(
    device: DeviceUi,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val borderModifier = if (selected) {
        Modifier.border(
            width = 2.dp,
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(12.dp)
        )
    } else {
        Modifier
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(borderModifier)
            .clickable { onSelect() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onSelect
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
