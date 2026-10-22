# AstralRecordControl

最初に [README.md](README.md) と [設計書入口](../../00_docs/30_WEB設計書/AstralRecordControl/README.md) の対象段階を読む。既存AstralRecordWeb/APIの認証・DB・設定を共有しない。

- .NET 10、専用SQLite、Razor Pages、SignalR。ContractsにWeb/Windows依存を追加しない。
- P1はJava/Velocityの基本運転・ログ・共通gateまで。VelocityはHostのP1.Velocity能力も必要。Control:EnableProcessControlとRunnerのEnableProcessControlは既定false。既存入口を改修し導入markerを確認するまで有効化しない。P2はAgentのファイル・停止バックアップ・journal復元を扱い、Web/Agent/RunnerのEnableFileControlは既定false。P3は同一Hostの予約実行・監視・Webhook通知を扱い、Web/AgentのEnableSchedulesは既定false。P4以降のPC横断/Environment Workflowや非対応kindは拒否する。
- 各Agent/Runnerは1PC1サービス。AgentはWebへoutbound、RunnerはACL付きlocal named pipeのみ。
- 通信入力はControlJson/InventoryValidationと各境界で検証する。認証方式を跨ぐ受入をしない。秘密の引数/URL/ログ出力を追加しない。
- 時刻はTimeProvider、保存はUTC。適用失敗でdesiredをapplied扱いにしない。
- テストは一時データルート/fixtureのみ。実サービス登録、既存Minecraftの起動停止、NAS変更は実行しない。
- 検証はこのディレクトリから `dotnet test AstralRecordControl.slnx -c Release`。配布ツールは `60_tool/control-deploy/README.md` を参照。
