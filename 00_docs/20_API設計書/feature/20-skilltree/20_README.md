# 20_README

このディレクトリは API `account-skilltree` 機能の設計をまとめる。
アカウント単位のスキルツリー進行状態を API / DB で永続化し、plugin はこの API を介して読み書きする。

## 対象実装パス

- `20_api/AstralRecordApi/AstralRecordApi/Controllers/AccountSkillTreeController.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/AccountSkillTreeStateModels.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/IAccountSkillTreeStateRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/AccountSkillTreeStateRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Data/Entities/AccountSkillTreeStateEntity.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Data/Entities/AccountSkillTreeUnlockedNodeEntity.cs`

## 関連 feature

- plugin 側の skilltree: `00_docs/10_プラグイン設計書/feature/13-skill/`
- account レベル進行: `00_docs/10_プラグイン設計書/feature/02-account/`
- DB テーブル: `00_docs/40_Database設計書/table-definitions/AstralRecord/`

## ドキュメント

1. [[20_3.00-索引]]
