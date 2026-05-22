# 13_README

このディレクトリは API `inventory` 機能の設計書です。  
採番・命名・参照ルールは [[README]] に従います。

`dbo.inventory` / `dbo.inventory_entry` を扱う DB 書き込み系 API。

## 対象実装パス

- `20_api/AstralRecordApi/AstralRecordApi/Controllers/InventoryController.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/InventoryModels.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/IInventoryRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/InventoryRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Data/Entities/Inventory.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Data/Entities/InventoryEntry.cs`

## 対応プラグイン feature

- プラグイン側の `04-item` の所持品管理から呼び出される。

## ドキュメント一覧

1. [[13_0.00-概要]]
2. [[13_1.00-モデル定義]]
3. [[13_3.00-索引]]
4. [[13_5.00-例外・ログ・運用]]

## 依存 feature

- `account`: `inventory.accountId` は `dbo.account.uuid` の FK
- `item`: `inventoryEntry.itemId` は YAML 定義の itemId を指す（API 単体では存在検証しない）

## 更新ルール

- エンドポイント追加・削除: [[13_3.00-索引]]
- DTO 変更: [[13_1.00-モデル定義]] + 該当エンドポイント
- DB スキーマ変更: `40_database/AstralRecord/dbo.inventory/inventory.md` / `dbo.inventory_entry/inventory_entry.md`
