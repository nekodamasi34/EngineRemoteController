# EngineRemoteController

Kotlin / Jetpack ComposeのBluetoothリレー制御アプリと、Cloudflare Workers / D1の遠隔操作画面です。

カメラ監視を追加しました。現地AndroidがLAN内のONVIFカメラから静止画を取得し、閲覧要求がある間だけ非公開R2へ最新の1枚を送信します。新しいPC、Termux、Tunnel、外部サーバーは不要です。

設定・移行・利用量試算・検証結果は [Cloudflareとカメラの設定手順](cloudflare/README.md#カメラ監視の追加設定) を参照してください。

重要な制限:

- Wansview Q5のGetSnapshotUri対応・認証・画面消灯中の動作は実機未検証です。アプリ設定の「接続テスト」でローカルJPEG取得を確認してください。
- HTTPを許可するカメラIPは `app/src/main/res/xml/network_security_config.xml` で限定しています。実際のIPへ変更してからビルドし、アプリ設定にも同じIPを入力してください。
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
