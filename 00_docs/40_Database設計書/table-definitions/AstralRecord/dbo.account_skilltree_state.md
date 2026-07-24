# dbo.account_skilltree_state

アカウント単位のスキルツリー進行状態を保持するテーブル。
解放済みノード集合の親レコードを 1 アカウント 1 行で管理する。`skill_points` は旧スキルポイント互換カラムとして残す。

## カラム

| カラム | 型 | NULL | 既定値 | 説明 |
|:--|:--|:--|:--|:--|
| `account_skilltree_state_id` | UNIQUEIDENTIFIER | 不可 | - | スキルツリー進行状態 ID |
| `account_id` | UNIQUEIDENTIFIER | 不可 | - | `dbo.account.uuid` |
| `skill_points` | INT | 不可 | `0` | 旧スキルポイント互換値。現在のpluginはCP/PPをこのカラムへ保存しない |
| `version` | INT | 不可 | `1` | 更新バージョン |
| `created_at` | DATETIME2(3) | 不可 | - | 作成日時 |
| `updated_at` | DATETIME2(3) | 不可 | - | 更新日時 |
| `created_by` | UNIQUEIDENTIFIER | 不可 | - | 作成者 |
| `updated_by` | UNIQUEIDENTIFIER | 不可 | - | 更新者 |
| `is_deleted` | BIT | 不可 | `0` | 論理削除 |

## 制約

- 主キー: `PK_account_skilltree_state (account_skilltree_state_id)`
- 外部キー: `FK_account_skilltree_state_account (account_id)` -> `dbo.account(uuid)`
- 一意制約: `UX_account_skilltree_state_account (account_id)` で 1 アカウント 1 レコード
- `skill_points` は互換用途として `0` 以上。CP/PPの正本ではない
- `version` は `1` 以上

## 運用メモ

- 解放済みノード一覧は子テーブル `dbo.account_skilltree_unlocked_node` に保持する。
- filebase JSON や plugin ローカルファイルにプレイヤー進行は保持しない。正本は API / DB 側。
