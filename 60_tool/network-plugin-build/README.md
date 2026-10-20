# ネットワークPluginのビルド

`12-build-network-plugins.bat`はLobby、Velocity Proxy、Geyser Extensionをビルドし、Git管理外の`network-plugin-build/output`へ3つのJARを生成します。配布は16の責務です。

```powershell
.\60_tool\12-build-network-plugins.bat
.\60_tool\12-build-network-plugins.bat -Target Proxy -SkipTests
.\60_tool\12-build-network-plugins.bat -Target Extension -OutputDirectory C:\BuildOutput
```

`-Target`は`All` / `Lobby` / `Proxy` / `Extension`です。`-SkipTests`はテストのコンパイルと実行を省略します。

| 成果物 | 配置先 |
|---|---|
| `AstralRecordLobby.jar` | Lobbyの`plugins/` |
| `AstralRecordProxy.jar` | Velocityの`plugins/` |
| `AstralRecordGeyserExtension.jar` | Velocityの`plugins/Geyser-Velocity/extensions/` |

共有パスとControl管理下の配布制限は[設定一覧](../CONFIGURATION.md)を参照してください。API接続設定と起動時登録の制約は[Extension README](../../10_plugin/AstralRecordGeyserExtension/README.md)を参照してください。
