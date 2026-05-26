# 11_README

このディレクトリは API `skill` 機能の設計書です。  
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `20_api/AstralRecordApi/AstralRecordApi/Controllers/SkillController.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/SkillResponse.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/ISkillRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/SkillRepository.cs`

## マスタデータの所在

- `40_filebase/30.features.skill/`
- `40_filebase/30.features.skill/skill.YAMLスキーマ定義.md`

## ドキュメント一覧

1. [[11_0.00-概要]]
2. [[11_1.00-モデル定義]]
3. [[11_3.00-索引]]
4. [[11_5.00-例外・ログ・運用]]

## 更新ルール

- YAML スキーマ変更: [[11_1.00-モデル定義]] + `40_filebase/30.features.skill/`
- エンドポイント追加・削除: [[11_3.00-索引]]
