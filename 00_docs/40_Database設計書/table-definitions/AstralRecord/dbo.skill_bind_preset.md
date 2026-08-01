# dbo.skill_bind_preset

スキルバインド GUI のプリセット保存状態をアカウント単位で保持する。

## カラム

| カラム | 型 | NULL | 既定値 | 説明 |
|:--|:--|:--|:--|:--|
| `skill_bind_preset_id` | UNIQUEIDENTIFIER | × | - | プリセット行 ID |
| `account_id` | UNIQUEIDENTIFIER | × | - | `dbo.account.uuid` |
| `preset_index` | INT | × | - | 1 から 6 のプリセット番号 |
| `active_skill_slots_json` | NVARCHAR(MAX) | × | `[]` | アクションリングスロット 1〜6。JSON 配列、空きは null |
| `left_click_skill_id` | NVARCHAR(128) | ○ | `__weapon_normal_attack__` | 左クリックバインド。予約値は現在主手武器の通常攻撃を表す。既存行の `NULL` は予約値として読込み、API の未設定 (`null`) は空文字で保存する |
| `passive_skill_slots_json` | NVARCHAR(MAX) | × | `[]` | パッシブ系スキル 9 スロット。JSON 配列、空きは null |
| `is_unlocked` | BIT | × | 0 | 解放済み判定。1 から 3 は API で常時解放扱い |
| `version` | INT | × | 1 | 更新バージョン |
| `created_at` | DATETIME2(3) | × | - | 作成日時 |
| `updated_at` | DATETIME2(3) | × | - | 更新日時 |
| `created_by` | UNIQUEIDENTIFIER | × | - | 作成者 |
| `updated_by` | UNIQUEIDENTIFIER | × | - | 更新者 |
| `is_deleted` | BIT | × | 0 | 論理削除 |

## 制約

- 主キー: `PK_skill_bind_preset (skill_bind_preset_id)`
- 外部キー: `FK_skill_bind_preset_account (account_id)` -> `dbo.account(uuid)`
- 一意制約: `UX_skill_bind_preset_account_preset (account_id, preset_index)`、未削除行のみ
- `preset_index` は 1 から 6
- `active_skill_slots_json` / `passive_skill_slots_json` は JSON
- `version` は 1 以上

## 運用メモ

- バインド操作直後の自動保存で行を作成する。
- 既存DBへ導入する場合は、API 配置前に [`migrations/20260801_add_left_click_skill_id.sql`](migrations/20260801_add_left_click_skill_id.sql) を `AstralRecord` DB へ実行する。このSQLは列の追加と既存行の予約値初期化を冪等に行う。
- 旧 active 7・8 番は API 正規化時に除外する。
- 各文字列にはスキルマスタ ID ではなく `learned_skill_id` を保存する。通常攻撃予約値だけは例外とする。
- 使用許可を失ったスキル個体が保存済みスロットに残っていても自動削除せず、発動時だけ無効にする。
