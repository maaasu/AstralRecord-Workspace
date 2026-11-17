# API コード

`20_api/AstralRecordApi` を実装・レビューするときに使う。入口は [APIの探し方](../../../../00_docs/10_AIパーソナライズ/API/README.md)。エンドポイントの契約は `Program.cs` のルーティング、`Controllers/`、入出力モデル、既存の呼び出し元とテストで確認する。

## 責務と設定

- ASP.NET Core Web API / .NET 10 を使用する。エンドポイントは `Controllers/`、リクエスト/レスポンス DTO は `Models/`、DB エンティティは `Data/Entities/` に置く。
- 永続化処理は `Repositories/` の `I<Feature>Repository` と `<Feature>Repository` に置く。API の入出力には DTO を使う。
- 可変データは SQL Server、静的データは Filebase の定義を参照する。設定の入口は `AstralRecordApi/appsettings.json` と環境別設定。SQL Server は `ConnectionStrings:SqlServer`、静的データのルートは `FileDatabase:RootPath`。
- エンドポイントの追加・変更時は、既存の `ApiKeyAuthenticationHandler` と対象の認証・認可ポリシーを確認し、Controller の XML ドキュメントコメントを更新する。DTO と利用者向けの入力・応答例が変わる場合は、その定義が実際の挙動と一致するか確認する。
- API 契約を変更する場合は、関連する Plugin / Web の呼び出し元、DB スキーマ、Filebase 定義への影響を検証する。DB・Filebase を使う場合は `00_docs/40_Database設計書` または `40_filebase` の定義を確認する。

## 検証

- 対象の Controller・サービス・Repository のテストを優先し、コンパイル時の契約に影響する変更では `20_api/AstralRecordApi` で `dotnet build` を実行する。
- 単体・Repository テストと `MasterDataSeeder` のテストには固定 JSON/YAML または in-memory DB のデータを使う。本番 Filebase に接続する確認は明示的な統合検証で行う。
- SQL Server 統合テストは `localhost\SQLEXPRESS` の一時 DB を使う。必要な場合だけ `ASTRALRECORD_RUN_SQLSERVER_INTEGRATION=1` を設定し、`dotnet test 20_api/AstralRecordApi/AstralRecordApi.Tests/AstralRecordApi.Tests.csproj --filter Category=SqlServerIntegration` を作業ルートで実行する。
