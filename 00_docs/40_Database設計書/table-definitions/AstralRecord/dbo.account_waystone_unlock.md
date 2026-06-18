# dbo.account_waystone_unlock

アカウント単位のウェイストーン開放状態を保持するテーブル。
1行が「1アカウントが1ウェイストーンを開放済みである」ことを表す。

## カラム

| カラム | 型 | NULL | 既定値 | 説明 |
|:--|:--|:--|:--|:--|
| `account_waystone_unlock_id` | UNIQUEIDENTIFIER | 不可 | - | ウェイストーン開放行ID |
| `account_id` | UNIQUEIDENTIFIER | 不可 | - | `dbo.account.uuid` |
| `waystone_id` | NVARCHAR(100) | 不可 | - | plugin `waystones.yml` の内部ウェイストーンID |
| `unlocked_at` | DATETIME2(3) | 不可 | - | 初回開放日時 |
| `created_at` | DATETIME2(3) | 不可 | - | 作成日時 |
| `updated_at` | DATETIME2(3) | 不可 | - | 更新日時 |
| `created_by` | UNIQUEIDENTIFIER | 不可 | - | 作成者user ID |
| `updated_by` | UNIQUEIDENTIFIER | 不可 | - | 更新者user ID |
| `is_deleted` | BIT | 不可 | `0` | 論理削除 |

## 制約

- 主キー: `PK_account_waystone_unlock (account_waystone_unlock_id)`
- 外部キー: `FK_account_waystone_unlock_account (account_id)` -> `dbo.account(uuid)`
- 一意制約: `UX_account_waystone_unlock_account_waystone (account_id, waystone_id)` で 1 アカウントにつき同一ウェイストーンは1行
- `waystone_id` は空文字不可

## 運用メモ

- ウェイストーンの座標・表示名・常時開放フラグ・開放コストは plugin data folder の `waystones.yml` を正本とする。
- 常時開放ウェイストーンは、このテーブルに行がなくても開放済みとして扱う。
- plugin は DB へ直接接続せず、AstralRecord API `/api/account-waystone` 経由で読み書きする。
