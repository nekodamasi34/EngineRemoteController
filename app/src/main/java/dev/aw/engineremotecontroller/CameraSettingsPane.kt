package dev.aw.engineremotecontroller

import android.graphics.BitmapFactory
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable
fun CameraSettingsPane(manager: () -> CameraManager?) {
    val context=LocalContext.current
    val settings=remember { CameraSettings(context.applicationContext) }
    val scope=rememberCoroutineScope()
    var config by remember { mutableStateOf(settings.load()) }
    var port by remember { mutableStateOf(config.port.toString()) }
    var message by remember { mutableStateOf("接続テストは保存前の入力でも実行できます。") }
    var testing by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var status by remember { mutableStateOf("監視OFF") }
    fun inputConfig(): CameraConfig {
        val value = port.trim().toIntOrNull()
        if (value == null || value !in 1..65535) throw CameraInputException("ONVIFポートは1〜65535で入力してください")
        return config.copy(host = config.host.trim(), port = value)
    }
    LaunchedEffect(Unit) {
        while(true) {
            val m=manager()
            status=(m?.status ?: "監視OFF")+" / 最終送信: "+(m?.lastUpload?.takeIf {it>0}?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "—")
            kotlinx.coroutines.delay(1000)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("カメラ監視（ONVIF）",style=MaterialTheme.typography.titleMedium)
            Row { Switch(checked=config.enabled,onCheckedChange={config=config.copy(enabled=it)}); Text("カメラ機能を有効にする") }
            OutlinedTextField(value=config.host,onValueChange={config=config.copy(host=it)},label={Text("カメラIPv4（LAN内）")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value=port,onValueChange={port=it},label={Text("ONVIFポート")},singleLine=true)
            OutlinedTextField(value=config.username,onValueChange={config=config.copy(username=it)},label={Text("ユーザー名")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value=config.password,onValueChange={config=config.copy(password=it)},label={Text("パスワード")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    runCatching {
                        val edited = inputConfig()
                        settings.save(edited)
                        config = edited
                        message = "保存しました"
                    }.onFailure {
                        message = if (it is CameraInputException) it.message ?: "入力を確認してください"
                            else "カメラ設定を保存できませんでした"
                    }
                }) { Text("保存") }
                OutlinedButton(enabled=!testing,onClick={scope.launch {
                    testing = true
                    preview = null
                    message = "GetSnapshotUri・JPEG取得テスト中"
                    val result = runCatching {
                        val edited = inputConfig()
                        withContext(Dispatchers.IO) {
                            val jpeg = OnvifSnapshotClient(edited).capture()
                            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                                ?: throw CameraConnectionException("JPEGをデコードできません")
                        }
                    }
                    result.onSuccess {
                        preview = it
                        message = "ローカル取得成功（クラウド送信なし）"
                    }.onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        message = when (it) {
                            is CameraInputException, is CameraConnectionException -> it.message ?: "接続情報を確認してください"
                            else -> "カメラへの接続テストに失敗しました"
                        }
                    }
                    testing = false
                }}) {Text("接続テスト")}
            }
            Text(message); Text(status,style=MaterialTheme.typography.bodySmall)
            preview?.let { androidx.compose.foundation.Image(bitmap=it.asImageBitmap(),contentDescription="ローカル静止画",modifier=Modifier.fillMaxWidth()) }
            Text("Web監視は遠隔待受中に利用できます。映像だけでエンジン始動の安全を判断しないでください。",style=MaterialTheme.typography.bodySmall)
        }
    }
}
