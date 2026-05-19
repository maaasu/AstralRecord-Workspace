# AstralRecordApi Guide

対象: `20_api/AstralRecordApi/AstralRecordApi/`

## 役割

- Plugin や Web が利用する REST API を管理する。
- SQL Server とアプリケーションの間の契約を提供する。

## ディレクトリ方針

- `Controllers/`
  - エンドポイント定義のみを置く。
- `Models/`
  - Request / Response DTO のみを置く。
- `Data/Entities/`
  - DB Entity を置く。
- `Repositories/`
  - `I<Feature>Repository` と `<Feature>Repository` をセットで置く。
- `Authentication/`, `Options/`, `Utilities/`
  - 認証、設定、共通処理を扱う。

## 実装方針

- Repository パターンを守る。
- Controller から直接 DB 詳細を扱わない。
- DTO と Entity を混在させない。
- 認証は既存の `ApiKeyAuthenticationHandler` に合わせる。

## ドキュメント運用

- API を追加・変更したら `README.md` を更新する。
- 詳細資料がある場合は `docs/api/` も更新する。
- Plugin、Database、Filebase との契約変更なら関連資料も見直す。

## 補助プロンプト

- `.agents/prompts/api.md`
  - API 追加・変更時の更新手順、関連確認、ドキュメント同期ルールを扱う。
