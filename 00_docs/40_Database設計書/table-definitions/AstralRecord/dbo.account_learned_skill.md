# dbo.account_learned_skill

プレイヤーがスキルマネージャーから習得したスキル個体をアカウント単位で保持する。同じ `skill_id` の行数に上限はなく、バインドは `learned_skill_id` を参照する。

## カラム

| カラム | 型 | NULL | 既定値 | 説明 |
|:--|:--|:--|:--|:--|
| `learned_skill_id` | UNIQUEIDENTIFIER | × | - | 習得済みスキル個体 ID |
| `account_id` | UNIQUEIDENTIFIER | × | - | 所有アカウント |
| `skill_id` | NVARCHAR(128) | × | - | スキルマスタ ID |
| `level` | INT | × | 1 | 個体レベル |
| `version` | INT | × | 1 | 楽観更新・表示再読込用バージョン |
| `created_at` / `updated_at` | DATETIME2(3) | × | - | 作成・更新日時 |
| `created_by` / `updated_by` | UNIQUEIDENTIFIER | × | - | 操作者 |
| `is_deleted` | BIT | × | 0 | 論理削除 |

`skill_id` は外部キーにせず、MasterDataDB の skill マスタと API で照合する。スキル削除時は API の整合処理で対象個体とバインドを除去する。
