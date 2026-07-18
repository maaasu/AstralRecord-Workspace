# 02_README

このディレクトリは API `account` 機能の設計書です。  
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `20_api/AstralRecordApi/AstralRecordApi/Controllers/AccountController.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/AccountCreateRequest.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/AccountUpdateRequest.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/AccountResponse.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/IAccountRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/AccountRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Data/Entities/Account.cs`

## 対応プラグイン feature

- プラグイン側の `account`: `00_docs/10_Plugin設計書/feature/02-account/`
  - プラグインの `AccountRepository` がここで定義されるエンドポイントを呼び出す。

## ドキュメント一覧（推奨順）

1. [[02_0.00-概要]]
2. [[02_1.00-モデル定義]]
3. [[02_3.00-索引]]
4. [[02_5.00-例外・ログ・運用]]

## 依存 feature（API 内部）

- `user`
  - `Account.userId` は `dbo.user.uuid` の FK。
  - プラグイン側で user 登録後に account 登録を行う前提。
- `40_database` の `dbo.account`

## 更新ルール（変更時に必ず更新する章）

- エンドポイント追加・削除: [[02_3.00-索引]], 該当エンドポイント仕様
- DTO 変更: [[02_1.00-モデル定義]] + 該当エンドポイント仕様
- ステータスコード変更: [[02_5.00-例外・ログ・運用]]
- DB スキーマ変更: `40_database/AstralRecord/dbo.account/account.md` + [[02_1.00-モデル定義]]
