# dbo.account_learned_skill_sigil

習得済みスキル個体へ消費装着したシジルを保持する。シジル脱着オーブによる取り外し、またはマスタ不整合時の補償処理で論理削除する。通常の脱着では同じtransactionで対象シジルをGAME/BAGへ1個返却する。

## カラム

| カラム | 型 | NULL | 既定値 | 説明 |
|:--|:--|:--|:--|:--|
| `learned_skill_sigil_id` | UNIQUEIDENTIFIER | × | - | 装着行 ID |
| `learned_skill_id` | UNIQUEIDENTIFIER | × | - | 対象スキル個体 |
| `sigil_id` | NVARCHAR(128) | × | - | シジルアイテム ID |
| `equip_group_id` | NVARCHAR(128) | × | - | 同一個体内で重複不可となる装着グループ ID |
| `slot_index` | INT | × | - | レベル別スロット内の位置（0始まり） |
| 監査列 | - | × | - | 作成・更新・論理削除情報 |

未削除行には `(learned_skill_id, equip_group_id)` と `(learned_skill_id, slot_index)` の部分一意インデックスを設定する。
