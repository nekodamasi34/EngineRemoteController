package dev.aw.engineremotecontroller

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun AiMonitorPane() {
    val context = LocalContext.current
    val settings = remember { AiSettings(context) }
    val status by AiMonitorService.status.collectAsState()
    var config by remember { mutableStateOf(settings.load()) }
    var savedEvent by remember { mutableStateOf<AiEvent?>(null) }
    var editing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun start() {
        runCatching { ContextCompat.startForegroundService(context, Intent(context, AiMonitorService::class.java)) }
            .onFailure { error = "AI監視サービスを開始できません" }
    }
    fun allowNotifications(config: AiConfig) {
        if (config.notify && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(status.running) {
        config = settings.load()
        savedEvent = withContext(Dispatchers.IO) { AiEventStore(context).latest() }
    }
    val event = status.latest ?: savedEvent
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI監視", style = MaterialTheme.typography.titleMedium)
            AiSwitch("AI監視を有効にする", config.enabled) { enabled ->
                config = config.copy(enabled = enabled)
                settings.save(config)
                error = ""
                if (enabled) {
                    start()
                    allowNotifications(config)
                } else context.stopService(Intent(context, AiMonitorService::class.java))
            }
            Text(MODEL_NAME)
            Text(if (config.enabled) status.message else "監視OFF")
            if (config.enabled && !status.running) TextButton(onClick = { start() }) { Text("監視を開始") }
            Text("カメラ ${if (config.enabled) status.camera else "AI取得OFF"}")
            Text("認識間隔 ${status.interval?.let { "${it / 1000}秒" } ?: "—"}")
            Text("熱状態 ${thermalName(status.thermal)}${if (status.powerSave) " / 端末の省電力ON" else ""}")
            status.timings?.let { timing ->
                Text("最新 ${timing.inferenceMs}ms / 平均 ${status.average?.toInt() ?: "—"}ms")
                val ratio = status.interval?.let { interval -> status.average?.div(interval)?.times(100) }
                Text("推論時間比率 ${ratio?.let { String.format(Locale.ROOT, "%.1f%%", it) } ?: "—"}")
                Text("画像取得 ${timing.captureMs}ms / 前処理 ${timing.preprocessMs}ms / 合計 ${timing.totalMs}ms",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (status.failures > 0) Text("連続失敗 ${status.failures}回")
            if (status.delivery.isNotEmpty()) Text(status.delivery)
            Text("最終検知 ${event?.let { DateFormat.getDateTimeInstance().format(Date(it.capturedAt)) } ?: "—"}")
            event?.let {
                Text("${it.detection.target.name.lowercase()} ${(it.detection.confidence * 100).toInt()}%")
                if (config.saveImage) EventPreview(it)
            }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { editing = !editing }) { Text(if (editing) "設定を閉じる" else "AI監視設定") }
            if (editing) AiOptions(config) { options ->
                config = options.copy(enabled = config.enabled)
                settings.save(config)
                if (config.enabled) allowNotifications(config)
                if (!config.saveImage) savedEvent = savedEvent?.copy(jpeg = null)
                editing = false
            }
        }
    }
}

@Composable
private fun AiOptions(initial: AiConfig, save: (AiConfig) -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    var seconds by remember { mutableStateOf(initial.manualSeconds.toString()) }
    var threshold by remember { mutableStateOf(initial.threshold.toString()) }
    var window by remember { mutableStateOf(initial.window.toString()) }
    var required by remember { mutableStateOf(initial.required.toString()) }
    var suppression by remember { mutableStateOf(initial.suppressionSeconds.toString()) }
    var invalid by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(AiMode.AUTO to "自動（推奨）", AiMode.ECONOMY to "省電力", AiMode.MANUAL to "手動").forEach { (mode, label) ->
            FilterChip(selected = draft.mode == mode, onClick = { draft = draft.copy(mode = mode) }, label = { Text(label) })
        }
    }
    if (draft.mode == AiMode.MANUAL) AiField("認識間隔（1〜10秒）", seconds) { seconds = it }
    DetectionClass.values().forEach { target ->
        AiSwitch(target.name.lowercase(), target in draft.targets) {
            draft = draft.copy(targets = if (it) draft.targets + target else draft.targets - target)
        }
    }
    AiField("信頼度しきい値（0.01〜1.00）", threshold) { threshold = it }
    AiField("判定対象の直近推論回数（1〜10）", window) { window = it }
    AiField("確定に必要な検出回数", required) { required = it }
    AiField("同じ対象の通知抑制時間（0〜3600秒）", suppression) { suppression = it }
    AiSwitch("検知画像を保存する", draft.saveImage) { draft = draft.copy(saveImage = it) }
    AiSwitch("Android端末に検知通知を出す", draft.notify) { draft = draft.copy(notify = it) }
    if (invalid) Text("入力範囲と検出回数を確認してください", color = MaterialTheme.colorScheme.error)
    Button(onClick = {
        val t = threshold.toFloatOrNull()
        val n = window.toIntOrNull()
        val hits = required.toIntOrNull()
        val hold = suppression.toIntOrNull()
        val manual = if (draft.mode == AiMode.MANUAL) seconds.toIntOrNull() else draft.manualSeconds
        if (t == null || t !in 0.01f..1f || n == null || n !in 1..10 || hits == null || hits !in 1..n ||
            hold == null || hold !in 0..3600 || manual == null || manual !in 1..10) invalid = true
        else save(draft.copy(manualSeconds = manual, threshold = t, window = n, required = hits, suppressionSeconds = hold))
    }) { Text("AI監視設定を保存") }
}

@Composable
private fun AiSwitch(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = value, onCheckedChange = change)
        Text(label)
    }
}

@Composable
private fun AiField(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun EventPreview(event: AiEvent) {
    val jpeg = event.jpeg ?: return
    val bitmap by produceState<android.graphics.Bitmap?>(null, jpeg) {
        value = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }
    }
    val image = bitmap ?: return
    val bounds = event.detection.box
    Box(Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height)) {
        Image(image.asImageBitmap(), "最終検知画像", Modifier.matchParentSize())
        Canvas(Modifier.matchParentSize()) {
            drawRect(Color.Yellow, Offset(bounds.left * size.width, bounds.top * size.height),
                Size((bounds.right - bounds.left) * size.width, (bounds.bottom - bounds.top) * size.height),
                style = Stroke(2.dp.toPx()))
        }
    }
}

private fun thermalName(state: Int?) = when (state) {
    null -> if (Build.VERSION.SDK_INT < 29) "取得非対応" else "—"
    0 -> "通常"
    1 -> "軽度"
    2 -> "中度"
    3 -> "高温"
    4 -> "危険"
    5 -> "緊急"
    else -> "停止レベル"
}
