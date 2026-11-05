# Cloudflareでのエンジン遠隔操作

Web画面・APIをWorkersで公開し、D1に指示と結果を保存します。現地Androidが5秒間隔で指示を取得します。クラウド側のOS管理は不要です。

初期状態で遠隔操作できるのはアラーム（CH3）と全リレーOFFです。既存の現地操作は使用できます。

## 1. PCで準備する

Node.js 22以降とCloudflareの無料アカウントを用意し、このプロジェクトの `cloudflare` フォルダでPowerShellを開きます。

```powershell
npm install
npm run setup:local
npm run db:local
npm run dev
```

表示されたローカルURL（通常 `http://localhost:8787`）をブラウザで開きます。`.dev.vars` をエディターで開き、`OPERATOR_TOKEN` の値を「操作キー」に入力してログインします。端末が接続するまではオフラインと表示されます。

`.dev.vars` はGitの対象外です。キーはチャットやスクリーンショットに貼らないでください。CloudflareアカウントのAPIトークンとは別の、このアプリ専用のキーです。

## 2. D1を作成する

```powershell
npx wrangler login
npx wrangler d1 create engine-remote
```

ログインはブラウザで行います。作成結果に出る `database_id` を `wrangler.jsonc` の同名項目に設定してください。最初の `00000000-...` は仮の値です。既存の同名DBがある場合は新規作成せず、そのIDを設定します。

```powershell
npm run db:remote
```

## 3. キーを登録して公開する

次のコマンドの入力欄に、`.dev.vars` に保存されたそれぞれの値を貼り付けます。入力した値はCloudflareのSecretとして登録されます。

```powershell
npx wrangler secret put OPERATOR_TOKEN
npx wrangler secret put DEVICE_TOKEN
npm run deploy
```

コマンドが出力した `https://engine-remote.…workers.dev` のURLを控えます。独自ドメインは不要です。Webの操作キーとAndroidの端末キーは異なる値を使います。キーを変更すると、Webは再ログイン、Androidは設定変更が必要です。

## 4. 現地Androidを設定する

1. 更新したアプリをAndroid Studioから実機にインストールします。
2. AndroidのBluetooth設定でリレー基板をペアリングします。
3. アプリの「接続」タブで対象機器を選びます。
4. 「設定」タブの「Cloudflare遠隔待受」で、公開したHTTPS URL、端末ID `engine-1`、`DEVICE_TOKEN` の値を入力します。
5. Bluetooth権限・通知権限を許可し、「保存して待受開始」を押します。
6. 「遠隔待受中 / サーバー接続OK」とBluetooth接続を確認します。

端末キーはAndroid Keystoreで暗号化して保存し、バックアップ・端末移行から除外しています。

常時電源を接続し、必要ならAndroidのアプリ設定でバッテリー使用を「制限なし」にします。通知の「待受停止・全OFF」でも終了できます。端末再起動・プロセス終了後は自動で始動操作を再開せず、アプリから手動で待受を開始します。メーカーの省電力動作により停止する可能性があるため、画面消灯中も実機で確認します。

## 5. アラームで外出先から確認する

1. 手元のスマホはWi-Fiを切り、モバイル回線で公開URLを開きます。
2. `OPERATOR_TOKEN` の値でログインします。
3. 現地端末がオンライン・Bluetooth接続中であることを確認します。
4. 動作時間を1秒にし、「アラーム」を押します。
5. 実機のON→OFFと、最近の操作の「送信完了」を確認します。
6. Webを閉じてもOFFになること、現地通信断・再接続で古い操作が再実行されないこと、画面消灯後も受信できることを確認します。

「送信完了」はBluetoothへの送信結果です。エンジンの回転・停止・リレー接点を確認した結果ではありません。全リレーOFFとエンジン停止は別です。遠隔指示には取得待ち・通信の遅延があるため、現地の緊急停止回路は別に用意します。

## 6. 始動・停止・チョークを有効にする

各チャンネルの配線と動作を現地で確認した後、`wrangler.jsonc` の設定を変更して再公開します。

```json
"ALLOWED_ACTIONS": "alarm,all_off,ignition,engine_stop,choke_a,choke_b"
```

```powershell
npm run deploy
```

始動・停止にはWeb画面で確認が表示されます。任意のチャンネルや全ONは遠隔APIから指定できません。

## 指示と失敗の扱い

- 操作時間は0.1〜5秒。ON→OFFのタイマーはAndroid側で実行し、クラウド通信とは別のスレッドで動きます。
- 指示の有効期限は15秒。20秒以上現地から応答がない場合、新しい指示を拒否します。
- 指示はD1の原子的な更新で一度だけ取得します。HTTP応答が失われた場合は実行されず「結果未確認」となり、自動再実行しません。
- 指示取得後・結果報告前にAndroidが終了した場合も、自動再実行しません。待受セッションが変わると以前の未取得指示は取り消します。
- 実行結果が30秒以内に届かない場合、「結果未確認」と表示します。接続復帰後、同じセッションの正しい結果が届けば更新します。
- 全OFFは未取得の他の操作を取り消し、優先して取得します。進行中のONへの全OFFは、Androidの次の取得後に実行されます。
- Webは8時間のHttpOnly・Secure・SameSite Cookieで認証します。操作キーをブラウザの永続ストレージに保存しません。Webの変更APIはOriginを検証し、端末APIは別のBearerキーを要求します。

## 無料枠と検証

端末1台が5秒おきに取得する場合、取得は約17,280回/日。取得時に状態報告・結果報告もまとめます。Webの状態確認は表示中5秒おき、非表示中20秒おきです。Workersには利用制限、D1には読み書き・容量の制限があります。ログを長期保存する場合は不要な履歴の整理が必要です。

無料プランの上限を超えるとAPIが使えなくなる場合があります。有料プランに変更しない構成で開始し、Cloudflare画面で利用量を確認してください。現地の通信費・電気代は別です。

- [Workers料金・制限](https://developers.cloudflare.com/workers/platform/limits/)
- [D1料金・制限](https://developers.cloudflare.com/d1/platform/pricing/)

```powershell
npm test
npx wrangler deploy --dry-run
```

Android側はプロジェクト直下で `./gradlew.bat testDebugUnitTest assembleDebug` を実行します。

## カメラ監視の追加設定

### 構成とONVIF取得

現地Android → カメラの `/onvif/device_service` にSOAP 1.2 GetCapabilities(Media) → Media1 GetProfilesの最初のプロファイル → GetSnapshotUri → HTTP(S)でJPEG取得、という順序です。成功したURLは監視中に再利用し、失敗・OFFで破棄します。XMLのDTD・外部エンティティは許可せず、応答サイズと構造を制限します。

WS-Security UsernameToken PasswordDigest（ランダムNonce・UTC Created・SHA-1）を付加し、HTTP 401時はBasicまたはDigestで1度だけ再送します。DigestはMD5 / SHA-256とsess、qop=authまたはqopなしに対応します。auth-int、Media2のみの機器、特殊な時刻補正は未対応です。カメラとAndroidの時刻を同期してください。HTTP BasicはLAN上で暗号化されないため、信頼できるWi-Fi内で運用します。証明書検証を無効化する処理はありません。

カメラの返すMedia・Snapshot URLは設定したプライベートIPv4と同一ホストに限定し、リダイレクトやURL埋込認証を拒否します。ユーザー名・パスワードはAndroid KeystoreのAES-GCMで暗号化し、バックアップと端末移行から除外します。サーバーへ送るのはJPEGと撮影時刻だけです。

取得画像は縦横比を維持して最大640×360へ縮小し、JPEG品質70→55→40→25で50KiB以下を目指します。50KiBを保証するものではなく、上限128KiBを超えた画像は送信しません。元画像の読込上限は4MiB、最大3200万画素です。接続・読込タイムアウトとストリーム読込期限を設け、失敗後は10秒間待ちます。フレームのキューは作りません。

### Androidの設定

1. カメラとAndroidを同じLANに接続し、ルーターでカメラのIPv4を予約します。外部ポートの開放は不要です。
2. カメラ側でONVIFとローカル用アカウントを有効にし、ONVIFポートを確認します。Q5の機種・ファームウェア別の対応は実機確認が必要です。
3. `app/src/main/res/xml/network_security_config.xml` の `192.168.1.100` を実際のカメラIPに置換してビルドします。HTTP例外はこの1IPだけです。任意のIPに対するHTTPをアプリ全体で許可しないため、IP変更時はこの設定の変更・再ビルドも必要です。
4. アプリの「設定」→「カメラ監視」で、有効・IP・ONVIFポート・ユーザー名・パスワードを入力し「保存」します。
5. 「接続テスト」でGetSnapshotUriとJPEG取得を実行し、端末上のプレビューを確認します。このテストはクラウドに送信しません。
6. 既存の「Cloudflare遠隔待受」を手動で開始します。カメラ状態・最終送信時刻はリレー状態とは別に表示します。待受を止めるとカメラも停止します。

カメラは独立した単一スレッドで実行し、Bluetoothや5秒ポーリングのスレッドでは画像通信しません。既存connectedDevice Foreground ServiceとWakeLockのライフサイクルに従います。外部IPカメラの取得なのでAndroid端末内蔵カメラ権限は不要です。再起動時の自動待受・自動リレー操作は追加していません。

### R2とD1の設定（既存環境への非破壊追加）

`cloudflare` フォルダで次を実行します。本番DBを削除・再作成したり、既存Secretを入れ替えたりしないでください。

```powershell
npx wrangler login
npx wrangler r2 bucket create engine-remote-camera
```

同名バケットが既にあれば作成不要です。R2はStandardを使います。CloudflareダッシュボードのR2 → 対象バケット → Settingsで、Public Development URL（r2.dev）とCustom Domainsの公開を有効にしないでください。CORSやR2用の端末アクセスキーも不要です。バケットの操作はWorker Bindingだけから行います。

`wrangler.jsonc` には次のBindingを追加済みです。バケット名を変更した場合はここも合わせます。

```json
"r2_buckets": [{ "binding": "CAMERA_BUCKET", "bucket_name": "engine-remote-camera" }]
```

既存の `d1_databases.database_id`、`DEVICE_ID`、`ALLOWED_ACTIONS` を維持します。新規の `0002_camera.sql` は閲覧リースと送信頻度用の表・索引のみ追加し、画像はD1へ保存しません。

```powershell
npm run db:local
npm test
npx wrangler deploy --dry-run
# 本番反映時のみ:
npm run db:remote
npm run deploy
```

ダッシュボードで設定する場合はWorkers & Pages → Worker → Bindings → Add binding → R2 bucketで変数名 `CAMERA_BUCKET` と対象バケットを選びます。既存の `DB` BindingとSecretは維持します。移行前に既存D1のバックアップがあることを確認してください。ローカル検証は `npm run dev` を起動して、別ターミナルで `npm run test:smoke` を実行します。本番へは接続しません。

### Web画面と監視期限

- ログイン後の「カメラ監視」で「通常（5秒）」または「詳細（最大1fps・120秒）」を選びます。初期値はOFFです。「画像更新」は監視中の取得のみ行います。
- 各ブラウザ画面は固有IDの20秒リースを8秒ごとに更新します。画面を閉じた場合の即時解除が届かなくても、最後の閲覧リースが切れると停止します。OFFは自分の閲覧を終了し、別の閲覧者のリースは消しません。
- 詳細監視の期限は開始から120秒で、ハートビートで延長しません。時間が来たら通常へ戻ります。再び詳細を使う場合は通常へ戻ってから詳細を選択してください。各閲覧者の有効な要求のうち最も高いモードを採用します。
- ブラウザ非表示時は解除・画像取得停止し、再表示時は通常監視として再要求します。詳細へは手動で戻します。
- 撮影後15秒を超えた画像、監視OFF中の画像はWorkerが配信しません。Webも表示期限後に画像を隠し「映像が更新されていません」と表示します。クリックで全画面表示できます（対応ブラウザ）。
- オンライン表示は新鮮なJPEGの存在を示し、エンジンやBluetoothの正常性を示すものではありません。

Androidは既存 `/api/device/poll` の `camera` フィールドを使用します。受信往復時間を差し引いた単調時計の期限で監視を止め、ポーリング断でも期限後のフレームを送信しません。通常5秒・詳細1秒は最短間隔で、処理や通信に時間がかかれば実効fpsは低下します。OFF時はJPEG取得・RTSP接続・画像API通信をしません。追加の端末ポーリングもありません。既に進行している通信はタイムアウトまで残り得ますが、取得後に再度期限と設定を確認して破棄します。

### APIと保存

| API | 認証 | 用途 |
| --- | --- | --- |
| POST /api/camera/watch | OPERATOR_TOKENのセッション + Origin検証 | viewerId、mode（off/normal/detail） |
| POST /api/device/camera/frame | DEVICE_TOKEN Bearer | image/jpeg、X-Captured-At（Unixミリ秒） |
| GET /api/camera/frame | 操作セッション | 最新の新鮮なJPEG |
| GET /api/camera/status | 操作セッション | 監視モード、期限、撮影時刻、online |

R2の `{DEVICE_ID}/latest.jpg` を上書きし、長期履歴や公開URLは作りません。OFFでも最後の1枚は保存されていますが配信を拒否します。撮影時刻はサーバー時計換算で送信し、Workerで過去15秒・未来5秒の範囲を検証します。アップロードは128KiBをストリーム読込中も強制し、Content-TypeとJPEGの開始・終了マーカーを検証します。D1の原子的な時刻予約で通常5000ms・詳細1000ms未満の書込みを拒否します。失敗後の同一フレーム再送はありません。配信はno-storeとnosniffを設定し、ブラウザの画像Blobも破棄します。

カメラ用DBの問い合わせが失敗しても、既存のリレー指示ポーリングはカメラOFFとして応答します。R2やカメラの失敗はリレー制御の成功条件に入りません。

### 無料枠への影響（2026-11-05公式ドキュメント確認）

[Workers料金](https://developers.cloudflare.com/workers/platform/pricing/) はFreeで100,000リクエスト/日・CPU 10ms/呼出しです。[R2料金](https://developers.cloudflare.com/r2/pricing/) はStandardで10GB-month、Class A 100万回/月、Class B 1000万回/月が無料枠で、インターネット向けegress料金は無料です。アカウント内の他用途と共有されます。端末のモバイル通信料金は別です。

端末1台・閲覧者1人・画像50KB・30日/月、毎日20分だけ監視、その他Webを閉じる想定の概算上限です。詳細は120秒ごとに手動再選択する合計20分の場合です。取得・送信時間によって実際の枚数は少なくなります。

| 項目 | 通常20分/日 | 詳細20分/日 |
| --- | ---: | ---: |
| 画像枚数・R2 PUT/日 | 240 | 1200 |
| R2 Class A/月 | 7,200 | 36,000 |
| R2画像GET/月（1人） | 約7,200 | 約36,000 |
| 画像アップロード通信/日 | 約12MB | 約60MB |
| R2保存量 | 最新1枚・最大128KiB | 同左 |
| 追加Workerリクエスト/日（送信＋取得＋リース） | 約630 | 約2,550 |

既存の端末5秒ポーリングは最大17,280回/日、Webの状態ポーリングは表示20分で約240回/日なので、詳細20分の合計は約20,070回/日＋ログイン・手動操作です。端末の処理時間を無視した上限として計算しています。複数閲覧者は画像GET・リース・Web状態取得が人数分増えます。OFFでは既存poll応答へ監視情報を同梱するだけで、画像用Workerリクエスト・R2操作は発生しません。既存Web状態ポーリングは従来どおりです。

カメラ用D1はpollで索引付きリース参照が追加され、監視中はリース更新と1フレーム1行の時刻予約が増えます。[D1料金](https://developers.cloudflare.com/d1/platform/pricing/) のFreeは読み500万行/日・書き10万行/日です。索引更新も含むため、既存リレー履歴・同時閲覧者・他アプリ分も含めてMetricsを確認してください。この20分想定は十分枠内ですが、24時間詳細を手動継続する使い方はWorker約19万回/日規模となり、R2 PUTも約259万回/月で無料枠を超えます。詳細を常時使用しないでください。CPU時間は本番Metricsでの確認が必要です。

### テストと未検証事項

- Workerユニットテスト: 既存15件＋カメラ7件、22件成功。OFF、複数閲覧者、期限切れ、詳細120秒・非延長、認証/Origin、サイズ/MIME/JPEG拒否、通常/詳細頻度、同時送信、古い画像拒否、カメラDB障害時のリレーポーリング継続。
- Androidユニットテスト: 既存19件＋カメラ5件。OFF/期限後の取得・送信抑止、取得中に期限切れした画像の破棄、取得失敗後の次回復帰、間隔/詳細期限、LAN IP検証。HTTP実通信・Keystore・UIをJVMテストで検証したものではありません。
- `testDebugUnitTest assembleDebug --offline` 成功。APK: `app/build/outputs/apk/debug/app-debug.apk`。
- `wrangler deploy --dry-run` 成功。ローカルD1移行、`npm run test:smoke` による既存リレーAPIとカメラAPI/R2連携成功。模擬JPEGでありQ5の画像ではありません。
- ブラウザ用JavaScriptは `node --check` 成功。実ブラウザでのレスポンシブ表示・全画面・可視性切替は未検証です。本番デプロイ・本番D1変更・R2作成は未実行です。
- RTSPフォールバック未実装。RTSPフレーム抽出には再生/デコードのライフサイクル管理と実機のコーデック検証が必要です。メンテナンス終了ライブラリは導入していません。GetSnapshotUri非対応時は、カメラのファームウェア/ONVIF設定を確認するか、対応機器へ変更することを推奨します。別途、保守中のAndroidメディア機能による抽出を実機検証してから追加できます。

実機では、Q5のGetSnapshotUri、HTTP/WS-Security認証、URIのホスト、ポート、カメラ時刻、画像サイズ/品質、再接続、Wi-Fi切断、Keystore再読込、通知・画面消灯中の待受、2ブラウザでの切断時停止を確認してください。カメラを故障/切断させた状態と詳細監視中に、Bluetooth操作・時間指定OFF・既存pollが遅延/切断しないことを現地で確認する必要があります。
