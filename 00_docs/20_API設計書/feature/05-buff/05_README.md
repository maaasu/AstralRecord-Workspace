# 05_README

このディレクトリは API `buff` 機能の設計書です。  
採番・命名・参照ルールは [[README]] に従います。

YAML マスタ読み取り専用 API。

## 対象実装パス

- `20_api/AstralRecordApi/AstralRecordApi/Controllers/BuffController.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/BuffResponse.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/IBuffRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/BuffRepository.cs`

## 対応プラグイン feature

- プラグイン側の `buff`: `00_docs/10_Plugin設計書/feature/05-buff/`

## マスタデータの所在

- `50_filebase/70.shared.buff/`

## ドキュメント一覧

1. [[05_0.00-概要]]
2. [[05_1.00-モデル定義]]
3. [[05_3.00-索引]]
4. [[05_5.00-例外・ログ・運用]]

## 更新ルール

- YAML スキーマ変更: [[05_1.00-モデル定義]] + `50_filebase/70.shared.buff/`
- エンドポイント追加・削除: [[05_3.00-索引]]
