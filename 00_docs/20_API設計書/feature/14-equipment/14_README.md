# 14_README

このディレクトリは API `equipment` 機能の設計書です。  
採番・命名・参照ルールは [[README]] に従います。

`dbo.equipment_instance` を中心に、装備個体のライフサイクル（生成・強化・状態変化・エンチャント・ルーン装着）と
装備プリセット（ロードアウト）を扱う DB 書き込み系 API。

## 対象実装パス

- `20_api/AstralRecordApi/AstralRecordApi/Controllers/EquipmentController.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Controllers/EquipmentLoadoutController.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/EquipmentCreateRequest.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/EquipmentInstanceResponse.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/EquipmentOperationRequests.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/EquipmentLoadoutModels.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/IEquipmentRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/IEquipmentLoadoutRepository.cs`

## 対応プラグイン feature

- プラグイン側の `04-item` の装備個体管理・装備セット管理から呼び出される。

## ドキュメント一覧

1. [[14_0.00-概要]]
2. [[14_1.00-モデル定義]]
3. [[14_3.00-索引]]
4. [[14_3.01-インスタンス系]]
5. [[14_3.02-操作系]]
6. [[14_3.03-ロードアウト系]]
7. [[14_5.00-例外・ログ・運用]]

## 依存 feature

- `item`: マスタ定義（`itemId` ベース）の参照元
- `rune`: ルーン item マスタ（[[15_README]]）を装着スロットへ `itemId` で紐付ける
- `recipe`: 強化・状態変化の参照先（任意）

## 更新ルール

- エンドポイント追加・削除: [[14_3.00-索引]]
- DTO 変更: [[14_1.00-モデル定義]] + 該当エンドポイント
- DB スキーマ変更: `40_database/AstralRecord/dbo.equipment_instance/` 配下
