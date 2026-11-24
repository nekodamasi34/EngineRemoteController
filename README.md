# EngineRemoteController

Kotlin / Jetpack ComposeのBluetoothリレー制御アプリと、Cloudflare Workers / D1の遠隔操作画面です。

カメラ監視を追加しました。現地AndroidがLAN内のONVIFカメラから静止画を取得し、閲覧要求がある間だけ非公開R2へ最新の1枚を送信します。新しいPC、Termux、Tunnel、外部サーバーは不要です。

設定・移行・利用量試算・検証結果は [Cloudflareとカメラの設定手順](cloudflare/README.md#カメラ監視の追加設定) を参照してください。

Androidのタブは「接続」「操作」「AI監視」「設定」「ログ」の順です。「設定」タブでカメラのIPv4・ONVIFポート・認証情報を入力し、「接続テスト」で保存前の入力を確認できます。保存した設定は通常監視とAI監視で共通です。「AI監視」タブで監視を開始し、稼働状況・推論時間・最新検知を確認したり、検知条件や通知を変更したりできます。

重要な制限:

- Wansview Q5のGetSnapshotUri対応・認証・画面消灯中の動作は実機未検証です。アプリ設定の「接続テスト」でローカルJPEG取得を確認してください。
- カメラクライアントが接続できるのは、設定したプライベートIPv4のみです。ONVIFが返すURLも同じIPに限定し、URL内の認証情報とHTTPリダイレクトを拒否します。Cloudflare通信はHTTPSです。
- Androidの固定HTTP許可は引き続き `app/src/main/res/xml/network_security_config.xml` で管理されています。APKでHTTPを許可していないIPへ変更した場合、接続テストに「HTTP通信が許可されていません」と表示されます。
- RTSPフォールバックは未実装です。Media1 / GetSnapshotUri非対応時は監視失敗を表示します。
- カメラ監視は既存の遠隔待受サービスが稼働している間に利用できます。再起動後は待受を手動で開始してください。
- 映像は定期更新する静止画です。始動・停止の安全性を映像だけで判断しないでください。

ビルド・テスト:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
cd cloudflare
npm test
npx wrangler deploy --dry-run
```

本番へのデプロイ、R2作成、本番D1移行は自動実行していません。既存のD1 ID・Secret・操作許可設定を維持して設定してください。

## AI監視（MegaDetector V6）

### 1. PCでモデルを用意する

使用する重みは Microsoft AI for Good の **MDV6-yolov10-c**（[Zenodo v22 / 15398270](https://zenodo.org/records/15398270)）です。[MegaDetector Model Zoo](https://microsoft.github.io/MegaDetector/model_zoo/)の表では、このモデルは **AGPL-3.0** です。ONNX Runtime Android 1.22.0 は [MIT](https://github.com/microsoft/onnxruntime/blob/v1.22.0/LICENSE) です。重みと変換済みモデルはGitの管理対象から外しています。配布用APKにモデルを同梱する場合は、AGPLのライセンス表示・対応ソースの提供条件とAPK全体への適用範囲を確認してください。[AGPL-3.0本文](https://www.gnu.org/licenses/agpl-3.0.html)

Windows / Python 3.11 の例です。変換スクリプトが公式チェックポイントを取得し、固定のSHA-256を検証してからUltralyticsでCPU用FP32モデルを書き出します。検証画像は複数指定できます。

```powershell
python -m venv .ai-work/venv
$aiPython = '.ai-work/venv/Scripts/python.exe'
& $aiPython -m pip install torch==2.6.0 torchvision==0.21.0 --index-url https://download.pytorch.org/whl/cpu
& $aiPython -m pip install ultralytics==8.3.161 onnx==1.17.0 onnxruntime==1.22.0 numpy==1.26.4 opencv-python==4.11.0.86
$env:YOLO_CONFIG_DIR = (Resolve-Path .ai-work).Path
$env:YOLO_AUTOINSTALL = 'false'
Invoke-WebRequest 'https://raw.githubusercontent.com/microsoft/MegaDetector/fd0523ecfb2fa928483748df282d3d5eb294e63f/docs/assets/cat.png' -OutFile .ai-work/cat.png
& $aiPython scripts/export_megadetector.py .ai-work/cat.png
.\gradlew.bat testDebugUnitTest assembleDebug
```

生成先は `app/src/main/assets/MDV6-yolov10-c.onnx`（約9.3 MB）です。APKに非圧縮で同梱し、アプリ起動後にモデルをダウンロードする必要はありません。ONNX RuntimeのCPU実行スレッドは2本、Androidの最小対応はAPI 26です。通常のAPKは `arm64-v8a`、`armeabi-v7a`、`x86_64`、`x86` を含みます。対象端末のABIが決まっていれば、たとえば `.\gradlew.bat -PaiAbi=arm64-v8a assembleDebug` でそのABIだけを含められます。ABIは `adb shell getprop ro.product.cpu.abilist` で確認できます。

| テンソル | 型と固定形状 | 内容 |
| --- | --- | --- |
| `images` | FP32 `[1,3,640,640]` | RGB / NCHW / 0〜1。縦横比を保って線形リサイズ、中央に配置し余白をRGB 114で補完 |
| `output0` | FP32 `[1,300,6]` | 各行は入力画像上の `x1,y1,x2,y2,confidence,classId`。0 = animal、1 = person、2 = vehicle |

YOLOv10のエンドツーエンド出力に追加NMSは不要です。Androidは余白と拡大率を逆変換して入力元画像にクリップし、送信・表示用に左上・右下座標を0〜1に正規化します。変換スクリプトは実画像ごとにPyTorchとONNXのクラス、信頼度（絶対誤差0.002）、座標（2 px以内）を比較します。

### 2. Androidで設定する

1. 現地端末をWansview Q5と同じLANにつなぎ、「設定」タブの「カメラ監視（ONVIF）」でIP・ポート・認証情報を入力します。「接続テスト」で静止画を確認し、カメラ機能をONにして保存します。
2. Androidの「AI監視」タブでAI監視をONにします。モデル名、カメラの状態、認識間隔、推論時間、熱状態と最終検知を確認できます。通知を使う場合はAndroid 13以降で通知を許可します。
3. 初期値はanimalのみ、信頼度0.30、直近3回の有効な推論で2回以上です。対象クラスごとに30秒検出が途切れるとイベントを終了し、前回通知から60秒以内の再通知を抑制します。画像取得や推論に失敗した際は、それまでの確定前の判定材料を破棄します。設定は端末に保存します。モデルが識別する野生動物クラスは `animal` です。
4. Web画面の閲覧リースとは独立して、Androidのフォアグラウンドサービスで動作します。アプリのスイッチかサービス通知の操作でAI監視を停止できます。端末を再起動した場合はアプリを開いて監視状態を確認してください。AI監視の再開でリレー操作は発行されません。

自動モードは3秒から始まり、1、2、3、5、10秒の段階を使います。推論時間のEMA（過去0.8、新規0.2）÷認識間隔が25%を超えると1段階延長、10%未満が30回続くと1段階短縮します。変更後30秒は段階を維持します。省電力モードは最低5秒、手動は1〜10秒です。Androidの省電力モードまたは熱状態MODERATEでは全モードで最低5秒、SEVERE以上は停止して30秒後に再確認します。Android 8〜9では熱状態を「取得非対応」と表示し、推論時間と端末の省電力状態を使用します。失敗時は最大60秒まで再試行間隔を延長します。

### 3. 履歴とオフライン動作

クラウド接続先・端末ID・端末キーが保存済みの場合、確定イベントだけをHTTPSで送信します。インターネット切断中もLANカメラが利用できれば推論を継続します。保存した接続先と端末IDに対して最大40件・6 MiB・7日間の端末内再送キューを使用し、上限超過時は古いイベントから削除します。接続情報が未設定の間は最新検知を端末に記録します。検知画像保存をOFFにすると、それ以降のイベントは検知情報のみ送信します。Webの「AI検知履歴」から認証して履歴と画像を閲覧できます。サーバー設定は [Cloudflareの手順](cloudflare/README.md#ai監視イベント) を参照してください。

Webの検知画像には、保存済みの `bbox` から枠と信頼度を重ねます。animalは黄色、personは青、vehicleは緑です。画面幅に合わせて画像と枠が拡大・縮小し、画像がない場合も検知情報を表示します。

### トラブルシューティングと端末検証

- **モデルを読み込めない**場合は変換ログの形状とクラス、APK内の `assets/MDV6-yolov10-c.onnx`、端末ABIを確認します。モデルが入っていないビルドでも従来機能は利用でき、AI画面に停止理由が表示されます。
- **カメラ取得に失敗**する場合はONVIF接続テスト、LAN接続、HTTP許可IP、カメラとAndroidの時刻を確認します。**再送待ち**が続く場合は端末の時刻・端末キー、CloudflareのD1マイグレーションとR2バインディングを確認します。
- **推論が遅い・停止する**場合はAI画面で推論時間、熱状態、省電力状態を確認します。実機評価では `adb shell dumpsys meminfo dev.aw.engineremotecontroller` で監視前と監視中のPSS、`adb shell dumpsys thermalservice` で熱状態を記録し、画面消灯・復帰、カメラ切断・復旧、ネット切断・再送、Web同時閲覧時の更新とBluetooth応答を確認してください。
- 公式画像をカメラと同じJPEG形式に変換した検証画像で、Androidの推論時間とプロセスPSSを記録できます。下の `mdv6-cat.jpg` は検証用APKにだけ含まれます。

```powershell
New-Item -ItemType Directory -Force app/src/androidTest/assets | Out-Null
& $aiPython -c "import cv2; cv2.imwrite('app/src/androidTest/assets/mdv6-cat.jpg', cv2.imread('.ai-work/cat.png'), [cv2.IMWRITE_JPEG_QUALITY, 95])"
.\gradlew.bat connectedDebugAndroidTest
adb logcat -d -s MegaDetectorTest:I
```

模擬ONVIFを使ったバックグラウンド監視テストは、空のAndroidエミュレーターで実行します。デバッグビルド専用のネットワーク設定で `10.0.2.2` だけに追加のHTTP許可を設定し、PCで `python scripts/fake_onvif_camera.py app/src/androidTest/assets/mdv6-cat.jpg` を起動します。`.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.aiCameraHost=10.0.2.2 -Pandroid.testInstrumentationRunnerArguments.aiCameraPort=18765` で画面消灯中の確定検知とOFFを確認できます。
