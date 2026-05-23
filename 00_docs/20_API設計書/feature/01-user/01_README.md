# 01_README

このディレクトリは API `user` 機能の設計書。

## 対象実装パス

- `20_api/AstralRecordApi/AstralRecordApi/Controllers/UserController.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/UserCreateRequest.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/UserUpdateRequest.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/UserResponse.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/UserHistoryCreateRequest.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/UserHistoryResponse.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/IUserRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/UserRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Data/Entities/UserEntity.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Data/Entities/UserHistoryEntity.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Data/AstralRecordDbContext.cs`

## 依存定義

- ユーザー本体: `00_docs/40_Database設計書/table-definitions/AstralRecord/dbo.user.md`
- ユーザー履歴: `00_docs/40_Database設計書/table-definitions/HistoryDB/dbo.user_history.md`

## ドキュメント一覧

1. [[01_0.00-概要]]
2. [[01_1.00-モデル定義]]
3. [[01_2.00-ユースケース]]
4. [[01_3.00-索引]]
5. [[01_4.00-統合フロー]]
6. [[01_5.00-例外・ログ・認可]]
7. [[01_9.00-未決事項]]（必要時）

