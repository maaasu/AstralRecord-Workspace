# 99_README

このディレクトリは API のシステム系（疎通確認等）の設計書です。  
採番・命名・参照ルールは [[README]] に従います。

プラグイン側に対応 feature を持たないユーティリティのみを置く。

## 対象実装パス

- `20_api/AstralRecordApi/AstralRecordApi/Controllers/HealthController.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Controllers/MasterDataController.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/HealthCheckResponse.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/MasterDataModels.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Services/IMasterDataSeeder.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Services/MasterDataSeeder.cs`

## ドキュメント一覧

1. [[99_0.00-概要]]
2. [[99_3.00-索引]]
3. [[99_3.01-取得系]]
4. [[99_3.02-登録系]]

## 更新ルール

- ヘルスチェックレスポンス形式変更: [[99_3.01-取得系]]
- MasterDataDB Seeder 契約変更: [[99_3.00-索引]] / [[99_3.01-取得系]] / [[99_3.02-登録系]]
