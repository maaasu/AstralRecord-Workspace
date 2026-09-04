# dbo.account_learned_skill_operation

スキルの習得・レベルアップ・シジル装着／脱着で発行した `operation_id` と確定結果を保持する冪等台帳。API応答が失われても、同じ operation ID の再送で mutation と素材消費を再実行せず、保存済み結果を返す。

## カラム

| カラム | 型 | NULL | 説明 |
|:--|:--|:--|:--|
| `operation_id` | UNIQUEIDENTIFIER | × | Plugin が操作ごとに発行する冪等キー（PK） |
| `account_id` | UNIQUEIDENTIFIER | × | 操作対象アカウント |
| `operation_type` | NVARCHAR(32) | × | `LEARN` / `LEVEL_UP` / `SIGIL_ATTACH` / `SIGIL_DETACH` |
| `request_hash` | CHAR(64) | × | operation ID と要求内容の対応確認用 SHA-256 |
| `result_payload_json` | NVARCHAR(MAX) | × | 成功または失敗の `AccountLearnedSkillMutationResult` |
| `created_at` / `completed_at` | DATETIME2(3) | × | 台帳作成・確定時刻 |
| `created_by` | UNIQUEIDENTIFIER | × | 操作対象アカウント |

同じ `operation_id` が同じアカウント・種別・要求hashで再送された場合は保存済み結果を返す。異なる要求で再利用した場合は `409 Conflict` とする。
