# dbo.login_bonus_claim

ログインボーナスの受取済み日付をアカウント単位で保持するテーブル。
1行が「1アカウントが1日分のログインボーナスを受け取った」ことを表す。

## カラム

| カラム | 型 | NULL | 既定値 | 説明 |
|:--|:--|:--|:--|:--|
| `login_bonus_claim_id` | UNIQUEIDENTIFIER | 不可 | - | ログインボーナス受取ID |
| `account_id` | UNIQUEIDENTIFIER | 不可 | - | `dbo.account.uuid` |
| `claim_date` | DATE | 不可 | - | 受取対象日。Asia/Tokyo の日付で扱う |
| `claimed_at` | DATETIME2(3) | 不可 | - | 実際に受取登録した日時 |
| `created_at` | DATETIME2(3) | 不可 | - | 作成日時 |
| `updated_at` | DATETIME2(3) | 不可 | - | 更新日時 |
| `created_by` | UNIQUEIDENTIFIER | 不可 | - | 作成者 account ID |
| `updated_by` | UNIQUEIDENTIFIER | 不可 | - | 更新者 account ID |
| `is_deleted` | BIT | 不可 | `0` | 論理削除 |

## 制約

- 主キー: `PK_login_bonus_claim (login_bonus_claim_id)`
- 外部キー: `FK_login_bonus_claim_account (account_id)` -> `dbo.account(uuid)`
- 一意制約: `UX_login_bonus_claim_account_date (account_id, claim_date)` で、論理削除されていない同一日付の二重受取を防ぐ
- `claim_date` は `2000-01-01` 以降とする

## 運用メモ

- Plugin は DB へ直接接続せず、AstralRecord API `/api/login-bonus` 経由で読み書きする。
- GUI 表示時は月範囲で受取履歴を取得する。
- 受取クリック時は先にこのテーブルへ登録し、登録済みだった場合は報酬付与を行わない。
