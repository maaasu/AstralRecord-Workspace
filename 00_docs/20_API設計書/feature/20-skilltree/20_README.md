# 20_README

このディレクトリは API `account-skilltree` 機能の設計をまとめる。
アカウント単位のスキルツリー進行状態を API / DB で永続化し、plugin はこの API を介して読み書きする。

CP / PP の残高は永続化しない。API は解放ノードごとに CP の消費元クラスを保持し、Plugin がプレイヤーレベル・クラス別レベルから残高を導出する。

ノード定義と配置・接続構造は、それぞれ filebase `40_filebase/35.features.skilltree/nodes/*.json` と `structures/*.json` を正本とします。これらは Plugin が直接読み込む静的マスタであり、`account-skilltree` API の入出力や MasterDataDB へは追加しません。

skill effectはスキル個体を習得させず、現在クラス条件を満たしてノード効果が有効な間だけ使用許可を追加する。所持はスキルマネージャーが skill master の `learnRequiredItems` / `levelUpRequiredItems` に従って作成・強化する習得済み個体を正本とする。

## 対象実装パス

- `20_api/AstralRecordApi/AstralRecordApi/Controllers/AccountSkillTreeController.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/AccountSkillTreeStateModels.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/IAccountSkillTreeStateRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/AccountSkillTreeStateRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Data/Entities/AccountSkillTreeStateEntity.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Data/Entities/AccountSkillTreeUnlockedNodeEntity.cs`

## 関連 feature

- plugin 側の skilltree: `00_docs/10_Plugin設計書/feature/13-skill/`
- account レベル進行: `00_docs/10_Plugin設計書/feature/02-account/`
- DB テーブル: `00_docs/40_Database設計書/table-definitions/AstralRecord/`
- filebase JSON: `40_filebase/35.features.skilltree/docs.skilltree.JSONスキーマ定義.md`

## ドキュメント

1. [[20_3.00-索引]]
