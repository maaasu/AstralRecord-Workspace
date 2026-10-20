# ゲームホストの受動的な初期登録

`Initialize-ControlGameHost.ps1` は DEV-01 または MC-01 の**そのPC上の管理者 PowerShell 7**で実行する。Web で発行済みの非秘密 import plan と Host receipt から、自分の `COMPUTERNAME` に一致する Host ID とローカル資源だけを採用する。Minecraft の起動、既存プロセスの停止、共通 gate や管理運用 marker の作成は行わない。

## 用意するもの

- schemaVersion 1 の plan: `publicOrigin` と、`hosts[]` の `hostKey`、`managedRoots[]`（`id`, `path`）、`executables[]`（`id`, `path`, `kind: "Java"` または `"Velocity"`）。MC-01 の plan は同じ `java.exe` に Java と Velocity の別 GUID を付ける場合があり、両方を保持する。Velocity は台帳用資源として登録するだけで、この初期化は Velocity の運転操作を有効化しない。
- schemaVersion 1 の receipt: 同じ `publicOrigin` と、`hosts[]` の `hostKey`, `hostId`。両ファイルに token を書かない。
- この版のツール・CLIを含む封印済みReleaseルート。`release-manifest.json`、`Control/Agent`、`Control/Runner`、`Control/Cli`、`Tools/60_tool/control-deploy`（`Install-ControlCli.ps1`を含む）を用意する。`ExpectedManifestSha256`には準備したReleaseのmanifestハッシュを渡す。
- 指定された game root と `java.exe` がそのPCのローカルパスに実在すること。junction / symlink を含むパスは受け付けない。DEV-01 では Java を先に配置する。MC-01 の既存基盤報告では `C:\Program Files\Java\jdk-21.0.10\bin\java.exe` が候補だが、実行PCで確認する。

```powershell
# DEV-01 の例。plan / receipt はそのPC内の非秘密ファイルへ置く。
pwsh -NoProfile -File C:\AstralRecord-Workspace\60_tool\control-deploy\Initialize-ControlGameHost.ps1 `
  -PlanPath C:\ControlImport\plan.json -HostReceiptPath C:\ControlImport\host-receipt.json `
  -PackageDirectory C:\ControlPackages\Release `
  -ExpectedManifestSha256 '<準備したReleaseのmanifest SHA-256>' `
  -PreflightOnly

# 同じ引数で -PreflightOnly を外すと初期登録する。
```

MC-01でも準備した新Releaseをローカル配置し、そのフォルダを`PackageDirectory`へ指定する。旧CLIと旧導入ツールを含む2026-10-19のパッケージを新しい自動登録手順へ混在させない。

## 実行時の動作

1. plan / receipt の origin と Host ID、ローカルの root / Java、manifest と必要ファイルのハッシュを確認する。`-PreflightOnly` はここで終了し、サービスや ACL を変更しない。
2. 既存の Agent / Runner がある場合は、停止状態、専用サービスアカウント、実行ファイル、data root を検証する。未知の登録や稼働中のサービスは拒否する。元 Release の必要部分を `%ProgramData%\AstralRecordControl\Bootstrap` の管理者・SYSTEM限定領域へ検証済みコピーとして置き、そこから不足するサービスだけを登録する。サービスの起動種別は終了時にも Manual にする。
3. CLIを同じWindowsユーザーの`%LOCALAPPDATA%\AstralRecordControl\Cli\bin`へ配置する。両サービスに同じHost ID・root / Javaとパスを設定し、Agent SIDとnamed pipeを照合する。process / file / schedulesの変更機能はfalseに保ち、異なる既存bindingや資源を上書きしない。
4. 接続用資格情報が未設定の場合だけCLIの`login`を呼ぶ。初回は管理者名とパスワードを入力し、ログイン済みなら再利用する。続く`agent enroll --start-agent false`が既知のHost IDとoriginを使って資格情報を自動設定する。手動トークンの発行・コピーは不要。同じbindingの資格ファイルがある場合は再登録しない。
5. plan に列挙された game root に Agent / Runner の必要 ACL、`java.exe` を含む JDK ディレクトリに読み取り・実行 ACL を追加する。両設定の `Install-Control.ps1 -PreflightOnly` が通り、管理運用 marker が無いことを確認してから Runner、Agent の順で受動的に開始する。

起動要求の途中で失敗した場合は、要求済みの Agent / Runner を逆順に停止し、停止状態を確認する。停止確認にも失敗した場合はその状態をエラーとして報告する。サービス状態、登録先と非秘密設定を調べてから同じ plan / receipt で再実行する。既存 token、所有者、Web の設定を自動的に置換しない。登録済みでも設定が一致しない場合は停止するため、勝手に修正せず差異を確認する。

開発時の確認は `pwsh -NoProfile -File 60_tool/control-deploy/tests/gamehost-setup.fixture.ps1` を使う。一時 fixture の plan / receipt / package だけを読み、実サービス登録や実ホストへの接続を行わない。
