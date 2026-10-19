# Control 台帳の対話登録

`Import-ControlInventory.ps1` はパスワード認証の Control Web に owner としてログインし、2 Host と 5 Server を通常の HTTP API から登録します。管理者名とパスワードだけを実行端末で入力します。先にWebをパスワード認証版へ更新してください。Web の DB や Agent の token ファイルを直接編集しません。パスワードは計画 JSON に含めません。HTTPS の証明書検証を省略しません。通信するフェーズでは、コマンドで指定する `-ExpectedPublicOrigin` と計画の `publicOrigin` を接続・秘密入力前に照合し、宛先を表示します。

計画は Git 管理外のアクセス制限された場所に保存します。`publicOrigin` は Control Web に設定済みの完全な HTTPS origin と一致させます。`managedRoots` と `executables` には各 PC の Agent と Runner に設定するものと同じ GUID、ローカル絶対パス、kind を記入します。Proxy 用には Java と同じ実行ファイルパスでも別 GUID の `Velocity` executable を登録します。次の値は構造例であり、実 Java パスと GUID を現地で確定してください。

```json
{
  "schemaVersion": 1,
  "publicOrigin": "https://control.example.test",
  "hosts": [
    {
      "hostKey": "dev-01", "displayName": "DEV-01",
      "managedRoots": [{ "id": "11111111-1111-1111-1111-111111111111", "path": "C:\\server\\AstralRecord-Dev" }],
      "executables": [{ "id": "22222222-2222-2222-2222-222222222222", "path": "C:\\ExampleJava\\bin\\java.exe", "kind": "Java" }]
    },
    {
      "hostKey": "mc-01", "displayName": "MC-01",
      "managedRoots": [{ "id": "33333333-3333-3333-3333-333333333333", "path": "E:\\mc-01" }],
      "executables": [
        { "id": "44444444-4444-4444-4444-444444444444", "path": "C:\\ExampleJava\\bin\\java.exe", "kind": "Java" },
        { "id": "55555555-5555-5555-5555-555555555555", "path": "C:\\ExampleJava\\bin\\java.exe", "kind": "Velocity" }
      ]
    }
  ],
  "servers": [
    { "serverId": "66666666-6666-6666-6666-666666666666", "hostKey": "dev-01", "serverKey": "dev", "displayName": "Dev", "kind": "Java", "managedRootId": "11111111-1111-1111-1111-111111111111", "executableId": "22222222-2222-2222-2222-222222222222", "serverDirectory": "10_Dev", "argumentList": ["-Xms4096M", "-Xmx16384M", "-jar", "purpur-1.21.11-2560.jar", "nogui"], "listenPort": 25567, "runtimeServerId": "Dev", "stopCommand": "stop", "startDelaySeconds": 10, "stopTimeoutSeconds": 60 },
    { "serverId": "77777777-7777-7777-7777-777777777777", "hostKey": "mc-01", "serverKey": "proxy", "displayName": "Proxy", "kind": "Velocity", "managedRootId": "33333333-3333-3333-3333-333333333333", "executableId": "55555555-5555-5555-5555-555555555555", "serverDirectory": "00_Proxy", "argumentList": ["-Xms1024M", "-Xmx2048M", "-jar", "velocity-4.1.0-SNAPSHOT-8.jar", "nogui"], "listenPort": 25565, "runtimeServerId": null, "stopCommand": "stop", "startDelaySeconds": 10, "stopTimeoutSeconds": 60 },
    { "serverId": "88888888-8888-8888-8888-888888888888", "hostKey": "mc-01", "serverKey": "lobby", "displayName": "Lobby", "kind": "Java", "managedRootId": "33333333-3333-3333-3333-333333333333", "executableId": "44444444-4444-4444-4444-444444444444", "serverDirectory": "01_Lobby", "argumentList": ["-Xms4096M", "-Xmx16384M", "-jar", "purpur-1.21.11-2560.jar", "nogui"], "listenPort": 25569, "runtimeServerId": "Lobby", "stopCommand": "stop", "startDelaySeconds": 10, "stopTimeoutSeconds": 60 },
    { "serverId": "99999999-9999-9999-9999-999999999999", "hostKey": "mc-01", "serverKey": "channel1", "displayName": "Channel1", "kind": "Java", "managedRootId": "33333333-3333-3333-3333-333333333333", "executableId": "44444444-4444-4444-4444-444444444444", "serverDirectory": "11_Channel1", "argumentList": ["-Xms4096M", "-Xmx16384M", "-jar", "purpur-1.21.11-2560.jar", "nogui"], "listenPort": 25570, "runtimeServerId": "Channel1", "stopCommand": "stop", "startDelaySeconds": 10, "stopTimeoutSeconds": 60 },
    { "serverId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "hostKey": "mc-01", "serverKey": "channel2", "displayName": "Channel2", "kind": "Java", "managedRootId": "33333333-3333-3333-3333-333333333333", "executableId": "44444444-4444-4444-4444-444444444444", "serverDirectory": "12_Channel2", "argumentList": ["-Xms4096M", "-Xmx16384M", "-jar", "purpur-1.21.11-2560.jar", "nogui"], "listenPort": 25571, "runtimeServerId": "Channel2", "stopCommand": "stop", "startDelaySeconds": 10, "stopTimeoutSeconds": 60 }
  ]
}
```

`environmentId` は既に Control へ登録した Environment GUID を指定する場合だけ各 Server に追加します。共有 API / master の境界に合わせて選びます。Script は Environment を自動作成しません。

```powershell
pwsh -NoProfile -File .\60_tool\control-deploy\Import-ControlInventory.ps1 -PlanPath 'C:\private\control-inventory.json' -Phase Validate
pwsh -NoProfile -File .\60_tool\control-deploy\Import-ControlInventory.ps1 -PlanPath 'C:\private\control-inventory.json' -Phase Hosts -ExpectedPublicOrigin 'https://control.example.test' -ReceiptPath 'C:\private\control-hosts.receipt.json'
```

Host phase は token・Cookie を含まない `{"schemaVersion":1,"publicOrigin":"...","hosts":[{"hostKey":"dev-01","hostId":"..."},{"hostKey":"mc-01","hostId":"..."}]}` の receipt を原子的に作成します。既存 receipt の内容が違う場合は上書きしません。

Host ID が表示されたら、通常の `/Hosts` 画面で Host ごとの Agent token を発行し、各 PC の Agent `--configure` へ対話入力します。追加の再認証は不要です。Agent と Runner の `localResources`、Host ID、Runner の Agent SID、pipe 名を揃え、両サービスの `-PreflightOnly` が成功した後、Runner と Agent を起動します。Host が Online になってから次を実行します。

```powershell
pwsh -NoProfile -File .\60_tool\control-deploy\Import-ControlInventory.ps1 -PlanPath 'C:\private\control-inventory.json' -Phase Servers -ExpectedPublicOrigin 'https://control.example.test'
```

既存の Host/Server は同じ key、ID、設定なら保持し、差異があれば停止します。Agent 報告の root / executable の ID・ローカルパス・kind が計画と違う場合も Server を登録しません。Script は Agent token を発行せず、Control の操作機能を有効化しません。P3 の Proxy は Velocity の台帳項目だけです。外部で起動済みの Crafty プロセスを Runner は採用できず、Proxy の起動停止・ログ・稼働状態は P4 の対応前に使用できません。

入力だけの fixture 検査は `pwsh -NoProfile -File .\60_tool\control-deploy\tests\import-control-inventory.tests.ps1` で行います。実 Control、Minecraft、Windows サービスには接続しません。
