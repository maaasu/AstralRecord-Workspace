# 01_README

このディレクトリは API `user` 機能の設計書です。  
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `20_api/AstralRecordApi/AstralRecordApi/Controllers/UserController.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/UserCreateRequest.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/UserUpdateRequest.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/UserResponse.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/IUserRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/UserRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Data/Entities/User.cs`

## 対応プラグイン feature

- プラグイン側の `user`: [[01_README]]（`00_docs/10_プラグイン設計書/feature/01-user/`）
  - プラグインの `UserRepository` がここで定義されるエンドポイントを呼び出す。

## ドキュメント一覧（推奨順）

1. [[01_0.00-概要]]
2. [[01_1.00-モデル定義]]
3. [[01_2.00-ユースケース]]
4. [[01_3.00-索引]]
5. [[01_4.00-統合フロー]]
6. [[01_5.00-例外・ログ・運用]]
7. [[01_9.00-未決事項]]（必要時）

## 依存 feature（API 内部）

- `account`
  - 初回ユーザ登録後、プラグインが続けて `/api/account` を呼ぶ前提。
    API としては独立しているが、`accountId` 整合の責務はプラグイン側にある。
- `40_database` の `dbo.user`
  - 本機能の唯一の永続化対象テーブル。

## 更新ルール（変更時に必ず更新する章）

- エンドポイント追加・削除:
  - [[01_3.00-索引]]
  - 該当 `01_3.0x-*` ファイル
  - [[01_README]] の対象実装パス
- Request / Response DTO の項目追加・削除・型変更:
  - [[01_1.00-モデル定義]]
  - 該当エンドポイントの `リクエスト` / `レスポンス` 表
  - プラグイン側 [[01_1.00-モデル定義]].ユーザモデル
- ステータスコード / 共通エラーの変更:
  - [[01_5.00-例外・ログ・運用]]
  - 該当エンドポイントの `レスポンス` 表
- DB スキーマ変更を伴う場合:
  - `40_database/AstralRecord/dbo.user/user.md`
  - [[01_1.00-モデル定義]]
- 認証方式の変更:
  - [[README]]（共通ルール側）
  - [[01_5.00-例外・ログ・運用]]
