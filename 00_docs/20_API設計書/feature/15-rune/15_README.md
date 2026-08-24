# 15_README

このディレクトリは旧ルーン個体 API の廃止記録です。
ルーンは `category: rune` の YAML マスタで効果が固定された通常スタック item であり、個体 API は公開しません。

## 対象実装パス

- `20_api/AstralRecordApi/AstralRecordApi/Repositories/EquipmentOrbOperationRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/EquipmentOrbOperationModels.cs`

## 対応プラグイン feature

- プラグイン側の `04-item` から `/api/equipment/orb-operations` を通じて装備スロットへ itemId を装着・脱着する。

## ドキュメント一覧

1. [[15_0.00-概要]]
2. [[15_1.00-モデル定義]]
3. [[15_3.00-索引]]
4. [[15_5.00-例外・ログ・運用]]

## 依存 feature

- `item`: ルーンマスタ（`category: rune`）の参照元
- `inventory`: 所持数は通常 inventory entry の `itemId` と `quantity` で管理する
- `equipment`: 装着状態は [[14_README]] 側で扱う

## 更新ルール

- ルーン装脱着の変更: [[14_README]] と `EquipmentOrbOperationModels.cs` を同時更新する
