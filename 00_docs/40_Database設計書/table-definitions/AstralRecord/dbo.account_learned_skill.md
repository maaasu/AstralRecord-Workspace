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

スキルIDを改名するときは、ゲームサーバーを停止したメンテナンス中に、`account_learned_skill.skill_id` と旧形式プリセットの生のskill IDを新IDへ移行する。移行後、新IDのスキルマスタをMasterDataDBへseedし、APIの習得スキル整合処理を再開する前に両方を完了する。間に整合処理が走ると旧IDまたは未seedの新IDを持つ習得個体が削除される。習得個体IDは維持されるため、`learned_skill_id` を参照するバインドとシジルはそのまま保持できる。今回の弓職共用スキルID変更には `migrations/20260923_rename_archer_shared_skill_ids.sql` を使う。
