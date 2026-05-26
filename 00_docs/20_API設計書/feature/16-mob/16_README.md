# 16_README

このディレクトリは API `mob` 機能の設計書です。  
採番・命名・参照ルールは [[README]] に従います。

YAML マスタ読み取り専用 API。

## 対象実装パス

- `20_api/AstralRecordApi/AstralRecordApi/Controllers/MobController.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/MobResponse.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/IMobRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/MobRepository.cs`

## 対応プラグイン feature

- プラグイン側の `mob`: `00_docs/10_プラグイン設計書/feature/12-mob/`

## マスタデータの所在

- `40_filebase/40.features.mob/`
  - `boss/v1.*.yml` — `master_type = mob.boss`
  - `enemy/v1.*.yml` — `master_type = mob.enemy`
  - `npc/v1.*.yml` — `master_type = mob.npc`

## ドキュメント一覧

1. [[16_0.00-概要]]
2. [[16_1.00-モデル定義]]
3. [[16_3.00-索引]]
4. [[16_5.00-例外・ログ・運用]]

## 更新ルール

- YAML スキーマ変更: [[16_1.00-モデル定義]] + `40_filebase/40.features.mob/`
- エンドポイント追加・削除: [[16_3.00-索引]]
