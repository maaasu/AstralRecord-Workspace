# 04_README

このディレクトリは API `item` 機能の設計書です。  
採番・命名・参照ルールは [[README]] に従います。

本機能は MasterDataDB 上の item マスタを参照する読み取り専用 API。書き込みは行わない。
装備の個体管理（インスタンス）は [[14_README]] を参照する。ルーンは item マスタの通常スタック item として [[15_README]] に定義する。

## 対象実装パス

- `20_api/AstralRecordApi/AstralRecordApi/Controllers/ItemController.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Controllers/SetEffectController.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/ItemResponse.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/SetEffectResponse.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/IItemRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/ItemRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/ISetEffectRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/SetEffectRepository.cs`

## 対応プラグイン feature

- プラグイン側の `item`: `00_docs/10_Plugin設計書/feature/04-item/`

## マスタデータの所在

- API の通常参照先は MasterDataDB。
- YAML の正本は `50_filebase/10.features.item/*`。
- API 起動時または Seeder API 実行時に filebase から MasterDataDB へ投入する。

## ドキュメント一覧

1. [[04_0.00-概要]]
2. [[04_1.00-モデル定義]]
3. [[04_3.00-索引]]
4. [[04_5.00-例外・ログ・運用]]

## 更新ルール

- YAML スキーマ変更: [[04_1.00-モデル定義]] +  [[MasterDataDBテーブル一覧]]
- エンドポイント追加・削除: [[04_3.00-索引]]
