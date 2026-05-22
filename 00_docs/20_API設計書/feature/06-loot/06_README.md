# 06_README

このディレクトリは API `loot` 機能の設計書です。  
採番・命名・参照ルールは [[README]] に従います。

YAML マスタ読み取り専用 API。LootPool / LootTable の 2 種類のリソースを扱う。

## 対象実装パス

- `20_api/AstralRecordApi/AstralRecordApi/Controllers/LootController.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/LootResponse.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/ILootRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/LootRepository.cs`

## 対応プラグイン feature

- プラグイン側の `loot`: `00_docs/10_プラグイン設計書/feature/06-loot/`

## マスタデータの所在

- `50_filebase/80.shared.loot/pool/`
- `50_filebase/80.shared.loot/table/`

## ドキュメント一覧

1. [[06_0.00-概要]]
2. [[06_1.00-モデル定義]]
3. [[06_3.00-索引]]
4. [[06_5.00-例外・ログ・運用]]

## 更新ルール

- YAML スキーマ変更: [[06_1.00-モデル定義]] + `50_filebase/80.shared.loot/`
- エンドポイント追加・削除: [[06_3.00-索引]]
