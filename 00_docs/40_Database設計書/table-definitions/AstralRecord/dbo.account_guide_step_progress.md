# dbo.account_guide_step_progress

アカウント単位のガイド手順達成状態を保持するテーブルです。1行は、1アカウントが特定ガイドの特定手順を達成した事実を表します。

## カラム

| カラム | 型 | NULL | 既定値 | 説明 |
|:--|:--|:--|:--|:--|
| `account_guide_step_progress_id` | UNIQUEIDENTIFIER | 不可 | - | ガイド手順達成行ID |
| `account_id` | UNIQUEIDENTIFIER | 不可 | - | `dbo.account.uuid` |
| `guide_id` | NVARCHAR(100) | 不可 | - | GuideマスターID |
| `step_id` | NVARCHAR(100) | 不可 | - | Guide内で一意な手順ID |
| `completed_at` | DATETIME2(3) | 不可 | - | 初回達成日時 |
| `created_at` | DATETIME2(3) | 不可 | - | 作成日時 |
| `created_by` | UNIQUEIDENTIFIER | 不可 | - | 達成したプレイヤーのuser ID |

## 制約

- 主キー: `PK_account_guide_step_progress (account_guide_step_progress_id)`
- 外部キー: `FK_account_guide_step_progress_account (account_id)` -> `dbo.account(uuid)`
- 一意制約: `UX_account_guide_step_progress_account_guide_step (account_id, guide_id, step_id)`
- `guide_id` と `step_id` は空文字不可

## 運用メモ

- ガイド本文・手順順序・達成条件は `40_filebase/09.features.guide` を正本とし、このテーブルへ複製しません。
- ガイド全体の達成状態は、現行マスターの全stepが登録済みかPlugin側で導出します。
- マスターから削除されたガイドやstepの行は履歴として残り、現行ガイド判定からは参照されません。
- PluginはDBへ直接接続せず、AstralRecord API `/api/account-guide` 経由で読み書きします。
