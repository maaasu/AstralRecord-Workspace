# dbo.user_setting テーブル定義

ユーザー単位の設定値を JSON と版番号で保持します。アカウント単位ではなく `dbo.user.uuid` へ直接紐づきます。

## カラム

| カラム | 型 | NULL | 既定値 | 内容 |
|---|---|---|---|---|
| `user_setting_id` | `UNIQUEIDENTIFIER` | NO |  | 主キー |
| `user_id` | `UNIQUEIDENTIFIER` | NO |  | `dbo.user(uuid)` |
| `setting_key` | `NVARCHAR(100)` | NO |  | 設定キー（例: `ui.locale`） |
| `setting_value_json` | `NVARCHAR(MAX)` | NO |  | JSON 設定値 |
| `version` | `INT` | NO | `1` | 楽観ロック用版番号 |
| `created_at` | `DATETIME2(3)` | NO |  | 作成日時 |
| `updated_at` | `DATETIME2(3)` | NO |  | 更新日時 |
| `created_by` | `UNIQUEIDENTIFIER` | NO |  | 作成者 user UUID |
| `updated_by` | `UNIQUEIDENTIFIER` | NO |  | 更新者 user UUID |
| `is_deleted` | `BIT` | NO | `0` | 論理削除 |

## 制約

- `PK_user_setting`: `user_setting_id`
- `FK_user_setting_user`: `user_id` → `dbo.user(uuid)`
- `CK_user_setting_setting_key_not_blank`: `LEN(LTRIM(RTRIM([setting_key]))) > 0`
- `CK_user_setting_value_json`: `ISJSON([setting_value_json]) = 1`
- `CK_user_setting_version`: `[version] >= 1`

## インデックス

- `IX_user_setting_user_id`
- `UX_user_setting_user_key_active`: 未削除行のユーザー・設定キーを一意にする。
- `IX_user_setting_is_deleted`
