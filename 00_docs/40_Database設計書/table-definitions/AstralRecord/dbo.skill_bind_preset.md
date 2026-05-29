# dbo.skill_bind_preset

スキルバインド GUI のプリセット保存状態をアカウント単位で保持する。

## カラム

| カラム | 型 | NULL | 既定値 | 説明 |
|:--|:--|:--|:--|:--|
| `skill_bind_preset_id` | UNIQUEIDENTIFIER | × | - | プリセット行 ID |
| `account_id` | UNIQUEIDENTIFIER | × | - | `dbo.account.uuid` |
| `preset_index` | INT | × | - | 1 から 9 のプリセット番号 |
| `active_skill_slots_json` | NVARCHAR(MAX) | × | `[]` | 発動系スキル 8 スロット。JSON 配列、空きは null |
| `passive_skill_slots_json` | NVARCHAR(MAX) | × | `[]` | パッシブ系スキル 8 スロット。JSON 配列、空きは null |
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
- `preset_index` は 1 から 9
- `active_skill_slots_json` / `passive_skill_slots_json` は JSON
- `version` は 1 以上

## 運用メモ

- 保存ボタンを押したプリセットのみ行を作成する。
- 未保存プリセットは API レスポンス上で空スロット、`is_saved = false` として補完する。
- 未所持スキルが保存済みスロットに残っていても自動削除しない。発動時の所持判定で使用不可にする。
