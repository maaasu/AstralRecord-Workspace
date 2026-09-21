# 20_README

このディレクトリは API `account-skilltree` 機能の設計をまとめる。
アカウント単位のスキルツリー進行状態を API / DB で永続化する。Web編集は状態全置換APIを呼ばず、実ロード定義のPluginが既存ゲーム内操作と同じ検証・player-state snapshot transactionで確定する。

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
- `20_api/AstralRecordApi/AstralRecordApi/Controllers/SkillTreeEditorController.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/SkillTreeOperationRepository.cs`

## 世代・Web編集の安全境界

- Pluginはロード成功後だけ、正規化済み完全snapshotのSHA-256を`definitionGenerationId`としてserver session、起動fence、互換性情報とともに登録する。reload失敗時に新世代を登録しない。
- Pluginはプレイヤー別に評価済み`tree`/`points`/`relockGoldCost` viewを登録する。Webはraw filebaseやcanonical snapshotを解釈して条件、費用、効果を再実装しない。
- Webは対象serverが実際に登録したgeneration、skilltree state version、Plugin評価fingerprintで操作を要求する。online時は同じ接続serverかつPluginが拠点・スキルツリーワールドと判定した場合だけ要求を作成する。offline時は同一target serverへ編集案を保存し、次回参加時にPluginが世代・state・fingerprint・位置を再検証する。
- 旧generationへのtarget固定案を別serverへ読み替えない。参加先が異なる場合は旧案を`RECONFIRMATION_REQUIRED`または`CANCELED`として結果照会可能にする。
- `account_skilltree_state.definition_generation_id`が`NULL`のlegacy状態は明示移行までWeb確定・自動補修対象外である。世代不一致は保留し、構造不整合だけが既存補修対象である。ノード廃止は別途明示移行で返還を検証してから適用する。
- 明示移行はruntime-keyでoffline accountにのみ実行する。operation ID、expected state version、旧node集合baseline、from/to世代、明示remove node ID、Pluginのgraph・保持node互換性証明を固定する。APIは新node付与、CP元付替え、Gold変更を行わず、除去nodeの既存spent返還はPlugin既存規則に委ねる。

## 関連 feature

- plugin 側の skilltree: `00_docs/10_Plugin設計書/feature/13-skill/`
- account レベル進行: `00_docs/10_Plugin設計書/feature/02-account/`
- DB テーブル: `00_docs/40_Database設計書/table-definitions/AstralRecord/`
- filebase JSON: `40_filebase/35.features.skilltree/docs.skilltree.JSONスキーマ定義.md`

## ドキュメント

1. [[20_3.00-索引]]
