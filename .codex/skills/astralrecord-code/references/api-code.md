# API コード

`E:\AstralRecord-Workspace\20_api\AstralRecordApi` 配下を実装するときにこの参照を使う。

## 必読資料

1. `E:\AstralRecord-Workspace\AGENTS.md`。
2. `E:\AstralRecord-Workspace\README.md` の AstralRecord API 節。
3. `E:\AstralRecord-Workspace\00_docs\20_API設計書\feature\` 配下の関連する詳細設計書。

## 責務

1. エンドポイント定義は `Controllers/` に置く。
2. request/応答 DTO は `Models/` に置く。
3. DB エンティティは `Data/Entities/` に置く。
4. 永続化アクセスは `Repositories/` に置き、`I<Feature>Repository` と `<Feature>Repository` を対にする。
5. 認証、選択肢、共有共通処理は既存のディレクトリに置く。

## 実行環境と設定

1. 実行時は .NET 10。
2. Framework は ASP.NET 中核 Web API。
3. 可変データは SQL Serverから読む。
4. 静的データはファイルシステムデータ定義から読む。
5. 設定は `AstralRecordApi/appsettings.json` と `AstralRecordApi/appsettings.Development.json` で管理する。
6. `ConnectionStrings:SqlServer` は SQL Server接続文字列。
7. `FileDatabase:RootPath` は静的データのルートパス。

## API 変更ルール

API の追加、エンドポイント契約の変更、API 設計書の更新では次のルールを使う。

1. Controller、DTO、リポジトリ、エンティティの責務を分離する。
2. Controllers に永続化処理を置かない。
3. エンティティを DTO として再利用しない。
4. API 契約の変更はプラグイン、Web、データベース、ファイルベース に影響し得る変更として扱う。
5. ルート `README.md` の AstralRecord API 節は参照索引として維持する。文書入口が変わった場合だけリンクを更新し、エンドポイントの詳細は対応する API 設計書に記載する。ルート README にエンドポイント一覧を追加しない。
6. 変更したエンドポイントに対応する詳細 API 設計書が `E:\AstralRecord-Workspace\00_docs\20_API設計書\feature\` 配下にある場合は更新する。
7. エンドポイントを追加または変更した場合は Controller の XML 文書コメント（`/// <summary>`）を更新する。
8. 契約が変わった場合はサンプル リクエスト、応答例、説明文を確認する。
9. 認証には既存の `ApiKeyAuthenticationHandler` パターンに従う。

## 検証

1. 変更した Controller/サービス/リポジトリに対する対象を絞ったテストを優先する。
2. コンパイル時の契約に影響する変更では、`E:\AstralRecord-Workspace\20_api\AstralRecordApi` から `dotnet build` を使う。
3. データベース/Filebase を利用するエンドポイントでは、`00_docs/40_Database設計書` または `40_filebase` 配下の対応する定義も確認する。
