# API コード

`E:\AstralRecord-Workspace\20_api\AstralRecordApi` 配下を実装するときにこの reference を使う。

## 必読資料

1. `E:\AstralRecord-Workspace\AGENTS.md`。
2. `E:\AstralRecord-Workspace\README.md` の AstralRecord API 節。
3. `E:\AstralRecord-Workspace\00_docs\20_API設計書\feature\` 配下の関連する詳細設計書。

## 責務

1. endpoint 定義は `Controllers/` に置く。
2. request/response DTO は `Models/` に置く。
3. DB entity は `Data/Entities/` に置く。
4. persistence access は `Repositories/` に置き、`I<Feature>Repository` と `<Feature>Repository` を対にする。
5. authentication、option、shared utility は既存のディレクトリに置く。

## Runtime と設定

1. Runtime は .NET 10。
2. Framework は ASP.NET Core Web API。
3. 可変データは SQL Server から読む。
4. static data は file-system data definition から読む。
5. 設定は `AstralRecordApi/appsettings.json` と `AstralRecordApi/appsettings.Development.json` で管理する。
6. `ConnectionStrings:SqlServer` は SQL Server connection string。
7. `FileDatabase:RootPath` は static data の root path。

## API 変更ルール

API の追加、endpoint contract の変更、API 設計書の更新では次のルールを使う。

1. Controller、DTO、Repository、Entity の責務を分離する。
2. Controllers に persistence logic を置かない。
3. Entity を DTO として再利用しない。
4. API contract の変更は Plugin、Web、Database、Filebase に影響し得る変更として扱う。
5. ルート `README.md` の AstralRecord API 節は参照索引として維持する。documentation entry point が変わった場合だけ link を更新し、endpoint の詳細は対応する API 設計書に記載する。ルート README に endpoint 一覧を追加しない。
6. 変更した endpoint に対応する詳細 API 設計書が `E:\AstralRecord-Workspace\00_docs\20_API設計書\feature\` 配下にある場合は更新する。
7. endpoint を追加または変更した場合は Controller の XML doc comment（`/// <summary>`）を更新する。
8. contract が変わった場合は sample request、response example、説明文を確認する。
9. authentication には既存の `ApiKeyAuthenticationHandler` pattern に従う。

## 検証

1. 変更した Controller/Service/Repository に対する targeted test を優先する。
2. compile-time contract に影響する変更では、`E:\AstralRecord-Workspace\20_api\AstralRecordApi` から `dotnet build` を使う。
3. Database/Filebase を利用する endpoint では、`00_docs/40_Database設計書` または `40_filebase` 配下の対応する定義も確認する。
