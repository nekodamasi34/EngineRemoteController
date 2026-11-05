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
