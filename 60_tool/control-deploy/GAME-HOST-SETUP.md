# ゲームホストの受動的な初期登録

`Initialize-ControlGameHost.ps1` は DEV-01 または MC-01 の**そのPC上の管理者 PowerShell 7**で実行する。Web で発行済みの非秘密 import plan と Host receipt から、自分の `COMPUTERNAME` に一致する Host ID とローカル資源だけを採用する。Minecraft の起動、既存プロセスの停止、共通 gate や管理運用 marker の作成は行わない。

## 用意するもの

- schemaVersion 1 の plan: `publicOrigin` と、`hosts[]` の `hostKey`、`managedRoots[]`（`id`, `path`）、`executables[]`（`id`, `path`, `kind: "Java"` または `"Velocity"`）。MC-01 の plan は同じ `java.exe` に Java と Velocity の別 GUID を付ける場合があり、両方を保持する。Velocity は台帳用資源として登録するだけで、この初期化は Velocity の運転操作を有効化しない。
- schemaVersion 1 の receipt: 同じ `publicOrigin` と、`hosts[]` の `hostKey`, `hostId`。両ファイルに token を書かない。
- SHA-256 封印済みの元の Release ルート。`release-manifest.json`、`Control/Agent`、`Control/Runner`、`Tools/60_tool/control-deploy` を含める。`ExpectedManifestSha256` は別経路で確認した manifest のハッシュを渡す。
- 指定された game root と `java.exe` がそのPCのローカルパスに実在すること。junction / symlink を含むパスは受け付けない。DEV-01 では Java を先に配置する。MC-01 の既存基盤報告では `C:\Program Files\Java\jdk-21.0.10\bin\java.exe` が候補だが、実行PCで確認する。

```powershell
# DEV-01 の例。plan / receipt はそのPC内の非秘密ファイルへ置く。
pwsh -NoProfile -File C:\AstralRecord-Workspace\60_tool\control-deploy\Initialize-ControlGameHost.ps1 `
  -PlanPath C:\ControlImport\plan.json -HostReceiptPath C:\ControlImport\host-receipt.json `
  -PackageDirectory C:\ControlPackages\Release `
  -ExpectedManifestSha256 EFC6EA700852A3515C8808BA95595E11C8F968FE665CF8884B03B1CC14CCAF61 `
  -PreflightOnly

# 同じ引数で -PreflightOnly を外すと初期登録する。
```

MC-01 では `PackageDirectory` にローカルの `E:\mc-01\DeploymentPackages\20261019-47a3e3e7e` を指定する。元 Release と同じ封印値を確認してから実行する。別版のパッケージにはその版のハッシュを使用する。

## 実行時の動作

1. plan / receipt の origin と Host ID、ローカルの root / Java、manifest と必要ファイルのハッシュを確認する。`-PreflightOnly` はここで終了し、サービスや ACL を変更しない。
2. 既存の Agent / Runner がある場合は、停止状態、専用サービスアカウント、実行ファイル、data root を検証する。未知の登録や稼働中のサービスは拒否する。元 Release の必要部分を `%ProgramData%\AstralRecordControl\Bootstrap` の管理者・SYSTEM限定領域へ検証済みコピーとして置き、そこから不足するサービスだけを登録する。サービスの起動種別は終了時にも Manual にする。
3. 両サービスに同じ Host ID・root / Java ID とパスを設定し、Agent SID と named pipe を照合する。process / file / schedules の変更機能は false に保つ。既存設定の binding や資源が異なる場合は上書きしない。同じ binding の有効な token ファイルがあれば対話設定を再実行しない。
4. token が未設定のときだけ標準の `AstralRecordControl.Agent.exe --configure` を対話起動する。画面に表示した Host ID と HTTPS origin を入力し、Agent token はそのPCのターミナルにだけ入力する。入力後に Host ID と origin を再照合し、token の値は読まない・表示しない。
5. plan に列挙された game root に Agent / Runner の必要 ACL、`java.exe` を含む JDK ディレクトリに読み取り・実行 ACL を追加する。両設定の `Install-Control.ps1 -PreflightOnly` が通り、管理運用 marker が無いことを確認してから Runner、Agent の順で受動的に開始する。

起動要求の途中で失敗した場合は、要求済みの Agent / Runner を逆順に停止し、停止状態を確認する。停止確認にも失敗した場合はその状態をエラーとして報告する。サービス状態、登録先と非秘密設定を調べてから同じ plan / receipt で再実行する。既存 token、所有者、Web の設定を自動的に置換しない。登録済みでも設定が一致しない場合は停止するため、勝手に修正せず差異を確認する。

開発時の確認は `pwsh -NoProfile -File 60_tool/control-deploy/tests/gamehost-setup.fixture.ps1` を使う。一時 fixture の plan / receipt / package だけを読み、実サービス登録や実ホストへの接続を行わない。
