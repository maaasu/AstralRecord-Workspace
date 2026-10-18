# AstralRecordControl

最初に [README.md](README.md) と [設計書入口](../../00_docs/30_WEB設計書/AstralRecordControl/README.md) の対象段階を読む。既存AstralRecordWeb/APIの認証・DB・設定を共有しない。

- .NET 10、専用SQLite、Razor Pages、SignalR。ContractsにWeb/Windows依存を追加しない。
- P0は台帳・owner認証・Agent接続・設定同期のみ。RunnerからMinecraftプロセスを実行しない。操作有効化は設計のP1共通gate完了後。
- 各Agent/Runnerは1PC1サービス。AgentはWebへoutbound、RunnerはACL付きlocal named pipeのみ。
- 通信入力はControlJson/InventoryValidationと各境界で検証する。認証方式を跨ぐ受入をしない。秘密の引数/URL/ログ出力を追加しない。
- 時刻はTimeProvider、保存はUTC。適用失敗でdesiredをapplied扱いにしない。
- テストは一時データルート/fixtureのみ。実サービス登録、既存Minecraftの起動停止、NAS変更は実行しない。
- 検証はこのディレクトリから `dotnet test AstralRecordControl.slnx -c Release`。配布ツールは `60_tool/control-deploy/README.md` を参照。
