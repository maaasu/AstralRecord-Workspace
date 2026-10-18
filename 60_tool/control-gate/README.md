# 既存ツールのControl gate

CLI資格情報の期限は15分です。長い保守で期限切れになった場合は、同じWindowsユーザーの別ターミナルで再度`login`し、同じrunを再開してください。期限切れだけで予約を解放したり新しいworkflowIdへ切り替えたりしません。各gate呼出は`--expected-server-url`で設定先originを検証します。

01/02/03/16/17のBATと対応するPowerShell入口は、同じ`Control.Gate.psm1`を使用します。既存設定に`controlGate`がなければ、Control管理を有効化する前のLegacy動作です。`%ProgramData%\AstralRecordControl\managed-operations.enabled`があるPCではLegacy入口を拒否します。このmarkerはP1のRunner導入時に`-EnableManagedOperations`で作成します。Controlの変更操作は全対象PCの入口・marker・Runnerを導入し、Webの有効化条件を確認してから有効にします。

Managedでは設定の`controlGate`に`mode`、Control CLIの絶対パス、固定HTTPS origin、Environment GUID、登録済みServerと管理rootの対応を指定します。例:

```json
{
  "controlGate": {
    "mode": "Managed",
    "cliPath": "C:\\Program Files\\AstralRecordControl\\Cli\\AstralRecordControl.Cli.exe",
    "serverUrl": "https://control.example.test:5443",
    "environmentId": "11111111-1111-1111-1111-111111111111",
    "bindings": {
      "servers": [
        {"runtimeServerId": "dev", "serverId": "22222222-2222-2222-2222-222222222222"}
      ],
      "paths": [
        {"path": "C:\\Minecraft\\Dev\\plugins", "accessRoot": "C:\\Minecraft", "hostId": "33333333-3333-3333-3333-333333333333", "managedRootId": "44444444-4444-4444-4444-444444444444", "relativePath": "Dev/plugins"}
      ]
    }
  }
}
```

`bindings.servers`は既存APIの`serverIds`または16の有効な`servers[].id`をControl台帳のServer GUIDへ1対1対応させます。Server資源の予約にはRunnerが対応するServer rootを自動で含めます。`bindings.paths`はそれ以外の実際の配布元・配布先と、Agent/Runnerに登録した管理root内の相対パスを1対1対応させます。`accessRoot`と`relativePath`を結合した結果が`path`と一致しなければ拒否します。01/02/03は今回有効なPlugin/API/Web/Filebaseのpath、16は任意network pluginの配布先/元を全て含めます。17はServerとEnvironmentを予約し、ファイル配布資源はありません。16のWorld/network選択が実行時に変わっても、設定済み対象を先に全て予約します。bindingが欠落・重複・不正なら予約前に停止します。管理rootとの物理対応は導入時に確認してください。

16のnetwork配布先bindingには`runtimeServerId`も指定し、そのLobby/Proxyの停止状態を確認します。network配布元はディレクトリ資源として予約します。

Control CLIは先に`login --server-url <HTTPS origin>`で対話ログインします。password/tokenをBAT引数や設定ファイルへ入れません。gateは全資源の中央予約とRunner holdを取得し、各段階で再検証します。子プロセスは同じleaseを継承します。leaseはユーザー専用の`%LOCALAPPDATA%\AstralRecordControl\GateLeases`に保存し、失敗や状態不明では解放しません。01/16は開始前にrun pathを決めて予約へ渡し、再開時は同じworkflowId/予約と既存run記録を照合します。17のPreview/Commitは同じRunDirectoryとAPI要求IDを使い、各段階で別のControl予約を取得します。

Managedの停止条件はRunnerのsnapshotで確認します。01/02/16と03停止モードはStopped、03稼働中モードと17はRunningが必要です。`-ServersStopped`等は従来の運用者宣言として残し、gateの停止証明には使用しません。RunnerがUnmanaged、Hostがオフライン、Control CLIやbindingが未設定、予約状態が不明なら配布やAPI変更を開始しません。
