# Android マスターデータエディター

Android 9 以降のタブレット向けです。PCで起動中のマスターデータエディターに Tailscale 経由で接続し、
マスターの検索・編集・検証・保存を行います。

1. AndroidでTailscaleに接続して、PCと同じtailnetに参加します。
2. 署名済みAPKをAndroidで開き、インストールします。
3. PCのエディターでAndroid接続を有効にし、表示されるQRコードを読み取るか、アプリに表示された手順でHTTPSの接続先を入力します。
4. PCで発行した8桁のペアリングコードをアプリに入力します。ペアリング後は端末の鍵で再接続します。

接続設定の「再接続」は、編集中の画面を開いたままセッションを取り直します。端末を紛失したときやアクセスを止めたい
ときは、PCの端末一覧から対象を無効化します。アプリの「接続を解除」は、この端末の鍵と接続設定を削除します。
再ペアリング後に旧端末が一覧に残っていれば、PCからその登録を無効化できます。

## 署名付きAPKを作成

Windows、JDK 17以降、Android SDK Platform 36、Build Tools 36.0.0 が必要です。
[Android SDKの配布元](https://developer.android.com/studio#command-line-tools-only)から導入できます。

APKを初めて配布するときは、作業ツリー外に専用のキーストアを作成します。配布済みAPKを更新する場合は同じ鍵を
使い、`-VersionCode` を増やしてください。キーストアとパスワードは安全な場所に保管してください。

```powershell
$signingDirectory = Join-Path $env:LOCALAPPDATA 'AstralRecord/android-signing'
New-Item -ItemType Directory -Force -Path $signingDirectory | Out-Null
$keystore = Join-Path $signingDirectory 'editor.p12'
& "$env:JAVA_HOME/bin/keytool.exe" -genkeypair -storetype PKCS12 -keystore $keystore `
    -alias astralrecord-editor -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=AstralRecord Master Editor'

./Publish-Android.ps1 -KeystorePath $keystore -VersionCode 1 -VersionName '1.0.0'
```

SDKやJDKの場所は `-SdkRoot` と `-JavaHome` でも指定できます。署名パスワードは実行時に入力するか、
現在のシェルの `ASTRAL_ANDROID_KEYSTORE_PASSWORD` で渡します。鍵に別のパスワードがある場合は
`ASTRAL_ANDROID_KEY_PASSWORD` を使用します。署名・アラインメントを検証したAPKとSHA-256は
エディターの `dist/android/` に出力します。ファイル名は `AstralRecordMasterEditor.apk` です。Android SDKのPlatform Toolsがあれば、`adb install -r <apk>` でもインストールできます。

同じWindowsユーザーで、配布用のキーストアとDPAPIで暗号化した `editor-password.dpapi` がある場合は、
同じ鍵を次のように再利用できます。

```powershell
$signingDirectory = Join-Path $env:LOCALAPPDATA 'AstralRecord/android-signing'
$password = Get-Content -Raw -LiteralPath (Join-Path $signingDirectory 'editor-password.dpapi') | ConvertTo-SecureString
$env:ASTRAL_ANDROID_KEYSTORE_PASSWORD = [Net.NetworkCredential]::new('', $password).Password
try {
    ./Publish-Android.ps1 -KeystorePath (Join-Path $signingDirectory 'editor.p12') -VersionCode 2 -VersionName '1.0.1'
} finally {
    Remove-Item Env:ASTRAL_ANDROID_KEYSTORE_PASSWORD
}
```
