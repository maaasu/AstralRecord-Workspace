# 15_README

このディレクトリは API `rune` 機能（ルーン個体管理）の設計書です。  
採番・命名・参照ルールは [[README]] に従います。

`dbo.rune_instance` を扱う DB 書き込み系 API。  
ルーン YAML マスタは [[04_README]]（`category: rune`）側を参照する。

## 対象実装パス

- `20_api/AstralRecordApi/AstralRecordApi/Controllers/RuneController.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/RuneInstanceModels.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/IRuneRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/RuneRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Data/Entities/RuneInstance.cs`

## 対応プラグイン feature

- プラグイン側の `04-item` の所持品管理から呼び出される。

## ドキュメント一覧

1. [[15_0.00-概要]]
2. [[15_1.00-モデル定義]]
3. [[15_3.00-索引]]
4. [[15_5.00-例外・ログ・運用]]

## 依存 feature

- `account`: `runeInstance.accountId` は `dbo.account.uuid` の FK
- `item`: ルーンマスタ（`category: rune`）の参照元
- `equipment`: ルーン装着は [[14_README]] 側で扱う

## 更新ルール

- エンドポイント追加・削除: [[15_3.00-索引]]
- DTO 変更: [[15_1.00-モデル定義]] + 該当エンドポイント
- DB スキーマ変更: `40_database/AstralRecord/dbo.rune_instance/` 配下
