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
import kotlin.concurrent.thread
import java.util.Locale

data class DeviceUi(
    val name: String,
    val address: String
)

data class EngineAction(
    val title: String,
    val channel: Int,
    val defaultSeconds: String,
    val description: String,
    val dangerous: Boolean = false
)

val engineActions = listOf(
    EngineAction(
        title = "イグニッション",
        channel = 1,
        defaultSeconds = "1.0",
        description = "CH1を指定秒数だけON",
        dangerous = true
    ),
    EngineAction(
        title = "エンジン切",
        channel = 2,
        defaultSeconds = "1.0",
        description = "CH2を指定秒数だけON",
        dangerous = true
    ),
    EngineAction(
        title = "アラーム",
        channel = 3,
        defaultSeconds = "1.0",
        description = "CH3のDCモーターを指定秒数だけON"
    ),
    EngineAction(
        title = "チョークA",
        channel = 5,
        defaultSeconds = "0.5",
        description = "CH5を指定秒数だけON"
    ),
    EngineAction(
        title = "チョークB",
        channel = 6,
        defaultSeconds = "0.5",
        description = "CH6を指定秒数だけON"
    )
)

enum class EnginePane(val title: String) {
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

    private var timerSelectedChannel by mutableStateOf(1)
    private var timerSecondsInput by mutableStateOf("3")
    private var timerRunning by mutableStateOf(false)
    private var timerRemainingText by mutableStateOf("-")

    private val actionSeconds = mutableStateListOf(
        "1.0",  // イグニッション
        "1.0",  // エンジン切
        "1.0",  // アラーム
        "0.5",  // チョークA
        "0.5"   // チョークB
    )

    private var engineActionRunning by mutableStateOf(false)
    private var runningActionTitle by mutableStateOf<String?>(null)

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
        relayManager.disconnect()
        isConnected = false
        timerRunning = false
        timerRemainingText = "-"
        statusText = "切断済み"
        addLog("切断した")
    }

    private fun sendCommand(
        bytes: ByteArray,
        successMessage: String,
        onSuccess: () -> Unit
    ) {
        thread {
            val result = relayManager.send(bytes)

            runOnUiThread {
                if (result.isSuccess) {
                    onSuccess()
                    addLog("送信成功: $successMessage")
                } else {
                    addLog("送信失敗: ${result.exceptionOrNull()?.message ?: "不明"}")
                }
            }
        }
    }

    private fun turnOnChannel(ch: Int) {
        sendCommand(
            bytes = RelayCommands.channelOn(ch),
            successMessage = "CH$ch ON"
        ) {
            relayStates[ch - 1] = true
        }
    }

    private fun turnOffChannel(ch: Int) {
        sendCommand(
            bytes = RelayCommands.channelOff(ch),
            successMessage = "CH$ch OFF"
        ) {
            relayStates[ch - 1] = false
        }
    }

    private fun turnAllOn() {
        sendCommand(
            bytes = RelayCommands.allOn,
            successMessage = "全ON"
        ) {
            for (i in relayStates.indices) {
                relayStates[i] = true
            }
        }
    }

    private fun turnAllOff() {
        sendCommand(
            bytes = RelayCommands.allOff,
            successMessage = "全OFF"
        ) {
            for (i in relayStates.indices) {
                relayStates[i] = false
            }
        }
    }

    private fun startTimedOpen() {
        if (!isConnected) {
            statusText = "未接続"
            addLog("タイマー開始失敗: 未接続")
            currentPane = EnginePane.OPERATION
            return
        }

        if (timerRunning) {
            addLog("タイマー実行中")
            return
        }

        val seconds = timerSecondsInput.toDoubleOrNull()
        if (seconds == null || seconds <= 0.0) {
            statusText = "秒数を正しく入力してね"
            addLog("タイマー開始失敗: 秒数が不正")
            return
        }

        val ch = timerSelectedChannel
        val totalMillis = (seconds * 1000.0).toLong()

        thread {
            runOnUiThread {
                timerRunning = true
                timerRemainingText = String.format(Locale.US, "%.2f 秒", seconds)
                relayStates[ch - 1] = true
                statusText = "CH$ch を ${timerSecondsInput}秒 ON"
                addLog("タイマー開始: CH$ch を ${timerSecondsInput}秒 ON")
            }

            val onResult = relayManager.send(RelayCommands.channelOn(ch))
            if (onResult.isFailure) {
                runOnUiThread {
                    timerRunning = false
                    timerRemainingText = "-"
                    relayStates[ch - 1] = false
                    statusText = "タイマー開始失敗"
                    addLog("タイマーON失敗: ${onResult.exceptionOrNull()?.message ?: "不明"}")
                }
                return@thread
            }

            val intervalMs = 100L
            val startTime = System.currentTimeMillis()

            while (true) {
                if (!timerRunning) {
                    runOnUiThread {
                        timerRemainingText = "-"
                    }
                    return@thread
                }

                val elapsed = System.currentTimeMillis() - startTime
                val remaining = totalMillis - elapsed

                if (remaining <= 0L) {
                    break
                }

                runOnUiThread {
                    timerRemainingText = String.format(
                        Locale.US,
                        "%.1f 秒",
                        remaining / 1000.0
                    )
                }

                try {
                    Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    runOnUiThread {
                        timerRunning = false
                        timerRemainingText = "-"
                    }
                    return@thread
                }
            }

            val offResult = relayManager.send(RelayCommands.channelOff(ch))

            runOnUiThread {
                timerRunning = false
                timerRemainingText = "0.0 秒"
                relayStates[ch - 1] = false

                if (offResult.isSuccess) {
                    statusText = "タイマー完了"
                    addLog("タイマー完了: CH$ch OFF")
                } else {
                    statusText = "タイマーOFF失敗"
                    addLog("タイマーOFF失敗: ${offResult.exceptionOrNull()?.message ?: "不明"}")
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
                    .padding(16.dp)
            ) {
                StatusCard(
                    isConnected = isConnected,
                    permissionGranted = permissionGranted,
                    statusText = statusText,
                    selectedDeviceName = selectedDevice?.name ?: "未選択"
                )

                Spacer(modifier = Modifier.height(16.dp))

                when (currentPane) {
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

    private fun runTimedAction(
        action: EngineAction,
        secondsText: String
    ) {
        if (!isConnected) {
            statusText = "未接続"
            addLog("${action.title}失敗: 未接続")
            currentPane = EnginePane.OPERATION
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

        thread {
            runOnUiThread {
                engineActionRunning = true
                runningActionTitle = action.title
                statusText = "${action.title} 実行中..."
                relayStates[action.channel - 1] = true
                addLog("${action.title}: CH${action.channel} ON / ${clampedSeconds}秒")
            }

            val onResult = relayManager.send(RelayCommands.channelOn(action.channel))

            if (onResult.isFailure) {
                runOnUiThread {
                    engineActionRunning = false
                    runningActionTitle = null
                    relayStates[action.channel - 1] = false
                    statusText = "${action.title} ON失敗"
                    addLog("${action.title} ON失敗: ${onResult.exceptionOrNull()?.message ?: "不明"}")
                }
                return@thread
            }

            try {
                Thread.sleep(totalMillis)
            } catch (_: InterruptedException) {
                // 今回は専用アプリなので特別処理なし
            }

            val offResult = relayManager.send(RelayCommands.channelOff(action.channel))

            runOnUiThread {
                engineActionRunning = false
                runningActionTitle = null
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
        thread {
            val result = relayManager.send(RelayCommands.allOff)

            runOnUiThread {
                for (i in relayStates.indices) {
                    relayStates[i] = false
                }

                engineActionRunning = false
                runningActionTitle = null

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

    @Composable
    private fun ConnectionPane(modifier: Modifier = Modifier) {
        LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "connection_buttons") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
    private fun EngineActionCard(
        action: EngineAction,
        secondsText: String,
        onSecondsChange: (String) -> Unit,
        enabled: Boolean,
        running: Boolean,
        onRun: () -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = action.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "CH${action.channel} / ${action.description}",
                    style = MaterialTheme.typography.bodyMedium
                )

                OutlinedTextField(
                    value = secondsText,
                    onValueChange = onSecondsChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("動作秒数") },
                    singleLine = true,
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )

                Button(
                    onClick = onRun,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled
                ) {
                    Text(
                        if (running) {
                            "実行中..."
                        } else {
                            "${action.title} 実行"
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun OperationButtonCard(
        action: EngineAction,
        secondsText: String,
        enabled: Boolean,
        running: Boolean,
        onRun: () -> Unit
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
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "CH${action.channel} / ${secondsText.ifBlank { "?" }} 秒",
                    style = MaterialTheme.typography.bodyMedium
                )

                Button(
                    onClick = onRun,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled
                ) {
                    Text(
                        if (running) {
                            "実行中..."
                        } else {
                            "${action.title} 実行"
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun CompactConnectionCard(
        selectedDeviceName: String
    ) {
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (isConnected) "接続中" else "未接続",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Text("機器: $selectedDeviceName")
                Text("詳細: $statusText")

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

    @Composable
    private fun OperationPane(modifier: Modifier = Modifier) {
        val selectedDevice = deviceList.firstOrNull { it.address == selectedAddress }

        LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                CompactConnectionCard(
                    selectedDeviceName = selectedDevice?.name ?: "未選択"
                )
            }

            item {
                SectionTitle("Bluetooth機器")
            }

            if (deviceList.isEmpty()) {
                item {
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

            item {
                Button(
                    onClick = { emergencyAllOff() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isConnected
                ) {
                    Text("全OFF")
                }
            }

            item {
                SectionTitle("基本操作")
            }

            items(
                items = engineActions.mapIndexed { index, action -> index to action },
                key = { (_, action) -> "operation-${action.channel}" }
            ) { (index, action) ->
                OperationButtonCard(
                    action = action,
                    secondsText = actionSeconds[index],
                    enabled = isConnected && !engineActionRunning,
                    running = runningActionTitle == action.title,
                    onRun = {
                        runTimedAction(
                            action = action,
                            secondsText = actionSeconds[index]
                        )
                    }
                )
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
                InfoCard("各操作のON時間を設定するよ。操作画面のボタンはこの秒数で動作する。")
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

                Text("CH${action.channel}")

                OutlinedTextField(
                    value = secondsText,
                    onValueChange = onSecondsChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("動作秒数") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }
        }
    }

    @Composable
    private fun EnginePaneView(modifier: Modifier = Modifier) {
        val mainActions = engineActions.take(3)

        LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                InfoCard(
                    if (isConnected) {
                        "各操作は指定秒数だけONになって自動でOFFになるよ"
                    } else {
                        "先にBluetooth接続してね"
                    }
                )
            }

            item {
                Button(
                    onClick = { emergencyAllOff() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isConnected
                ) {
                    Text("全OFF")
                }
            }

            item {
                SectionTitle("エンジン操作")
            }

            items(
                items = mainActions.mapIndexed { index, action -> index to action },
                key = { (_, action) -> "engine-action-${action.channel}" }
            ) { (index, action) ->
                EngineActionCard(
                    action = action,
                    secondsText = actionSeconds[index],
                    onSecondsChange = { actionSeconds[index] = it },
                    enabled = isConnected && !engineActionRunning,
                    running = runningActionTitle == action.title,
                    onRun = {
                        runTimedAction(
                            action = action,
                            secondsText = actionSeconds[index]
                        )
                    }
                )
            }
        }
    }

    @Composable
    private fun ChokePaneView(modifier: Modifier = Modifier) {
        val chokeActions = engineActions.drop(3)

        LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                InfoCard("チョークの向きが未確認なら、A/B表記のまま少しずつ動かして確認してね")
            }

            item {
                SectionTitle("チョーク調整")
            }

            items(
                items = chokeActions.mapIndexed { localIndex, action ->
                    val globalIndex = localIndex + 3
                    globalIndex to action
                },
                key = { (_, action) -> "choke-action-${action.channel}" }
            ) { (index, action) ->
                EngineActionCard(
                    action = action,
                    secondsText = actionSeconds[index],
                    onSecondsChange = { actionSeconds[index] = it },
                    enabled = isConnected && !engineActionRunning,
                    running = runningActionTitle == action.title,
                    onRun = {
                        runTimedAction(
                            action = action,
                            secondsText = actionSeconds[index]
                        )
                    }
                )
            }
        }
    }

    @Composable
    private fun TimerPane(modifier: Modifier = Modifier) {
        val channelRows = listOf(
            listOf(1, 2, 3, 4),
            listOf(5, 6, 7, 8)
        )

        LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "timer_info") {
                InfoCard("選んだチャンネルを指定秒数だけONにして、自動でOFFにする")
            }

            item(key = "timer_selected") {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "タイマー設定",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text("選択中: CH$timerSelectedChannel")
                        Text(
                            text = if (timerRunning) "状態: 実行中" else "状態: 待機中",
                            color = if (timerRunning) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                        Text("残り時間: $timerRemainingText")
                    }
                }
            }

            item(key = "timer_channel_title") {
                SectionTitle("チャンネル選択")
            }

            item(key = "timer_channel_grid") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    channelRows.forEach { rowChannels ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowChannels.forEach { ch ->
                                val selected = timerSelectedChannel == ch
                                if (selected) {
                                    Button(
                                        onClick = { timerSelectedChannel = ch },
                                        modifier = Modifier.weight(1f),
                                        enabled = !timerRunning
                                    ) {
                                        Text("CH$ch")
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = { timerSelectedChannel = ch },
                                        modifier = Modifier.weight(1f),
                                        enabled = !timerRunning
                                    ) {
                                        Text("CH$ch")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item(key = "timer_seconds") {
                OutlinedTextField(
                    value = timerSecondsInput,
                    onValueChange = { timerSecondsInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("秒数") },
                    placeholder = { Text("例: 3  または  1.5") },
                    singleLine = true,
                    enabled = !timerRunning,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }

            item(key = "timer_action") {
                Button(
                    onClick = { startTimedOpen() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isConnected && !timerRunning
                ) {
                    Text(
                        if (timerRunning) {
                            "実行中..."
                        } else {
                            "CH$timerSelectedChannel を ${timerSecondsInput.ifBlank { "?" }}秒 ON"
                        }
                    )
                }
            }

            item(key = "timer_note") {
                InfoCard(
                    if (isConnected) {
                        "接続中ならこのまま実行できる"
                    } else {
                        "先に接続してね"
                    }
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
private fun StatusCard(
    isConnected: Boolean,
    permissionGranted: Boolean,
    statusText: String,
    selectedDeviceName: String
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "状態",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(
                            if (isConnected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = if (isConnected) "接続中" else "未接続",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusChip("権限: ${if (permissionGranted) "OK" else "未許可"}")
                StatusChip("機器: $selectedDeviceName")
            }

            Text(
                text = "詳細: $statusText",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun StatusChip(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        tonalElevation = 2.dp
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall
        )
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
                .padding(14.dp),
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
                    style = MaterialTheme.typography.titleMedium,
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

@Composable
private fun RelayChannelCard(
    ch: Int,
    isOn: Boolean,
    enabled: Boolean,
    onOnClick: () -> Unit,
    onOffClick: () -> Unit
) {
    val borderModifier = if (isOn) {
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
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "CH$ch",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            StatusChip(
                text = if (isOn) "状態: ON" else "状態: OFF"
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOnClick,
                    modifier = Modifier.weight(1f),
                    enabled = enabled
                ) {
                    Text("ON")
                }

                OutlinedButton(
                    onClick = onOffClick,
                    modifier = Modifier.weight(1f),
                    enabled = enabled
                ) {
                    Text("OFF")
                }
            }
        }
    }
}