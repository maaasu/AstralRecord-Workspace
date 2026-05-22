# 10_README

このディレクトリは API `class` 機能の設計書です。  
採番・命名・参照ルールは [[README]] に従います。

YAML マスタ読み取り専用 API。プラグイン側に対応 feature は現状なし（API 専用）。

## 対象実装パス

- `20_api/AstralRecordApi/AstralRecordApi/Controllers/ClassController.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/ClassResponse.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/IClassRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/ClassRepository.cs`

## マスタデータの所在

- `50_filebase/20.features.class/`

## ドキュメント一覧

1. [[10_0.00-概要]]
2. [[10_1.00-モデル定義]]
3. [[10_3.00-索引]]
4. [[10_5.00-例外・ログ・運用]]

## 更新ルール

- YAML スキーマ変更: [[10_1.00-モデル定義]] + `50_filebase/20.features.class/`
- エンドポイント追加・削除: [[10_3.00-索引]]
