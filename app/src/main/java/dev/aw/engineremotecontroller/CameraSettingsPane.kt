package dev.aw.engineremotecontroller

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
    var message by remember { mutableStateOf("HTTP許可IPはビルド時設定も必要です。READMEを参照してください。") }
    var testing by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var status by remember { mutableStateOf("監視OFF") }
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
                Button(onClick={runCatching { val c=config.copy(port=port.toInt());settings.save(c);config=c;message="保存しました" }.onFailure {message="設定を確認してください"}}) {Text("保存")}
                OutlinedButton(enabled=!testing,onClick={scope.launch {
                    testing=true;message="GetSnapshotUri・JPEG取得テスト中"
                    val result=withContext(Dispatchers.IO) {runCatching {val c=config.copy(port=port.toInt());c.validate();OnvifSnapshotClient(c).capture()}}
                    result.onSuccess {preview=android.graphics.BitmapFactory.decodeByteArray(it,0,it.size);message="ローカル取得成功（クラウド送信なし）"}
                        .onFailure {message="取得失敗：IP・HTTP許可設定・ポート・認証・時刻・GetSnapshotUri対応を確認"}
                    testing=false
                }}) {Text("接続テスト")}
            }
            Text(message); Text(status,style=MaterialTheme.typography.bodySmall)
            preview?.let { androidx.compose.foundation.Image(bitmap=it.asImageBitmap(),contentDescription="ローカル静止画",modifier=Modifier.fillMaxWidth()) }
            Text("監視は遠隔待受中に利用できます。映像だけでエンジン始動の安全を判断しないでください。",style=MaterialTheme.typography.bodySmall)
        }
    }
}
