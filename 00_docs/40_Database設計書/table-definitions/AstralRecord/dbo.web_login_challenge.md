# dbo.web_login_challenge テーブル定義

Web ログインチャレンジのハッシュ、状態、有効期限を管理します。Plugin の `/web login` で発行し、Web から API 経由で一度だけ消費します。平文ログインコードは保存しません。

## カラム

| カラム | 型 | NULL | 既定値 | 内容 |
|---|---|---|---|---|
| `challenge_id` | `UNIQUEIDENTIFIER` | NO |  | 主キー |
| `user_id` | `UNIQUEIDENTIFIER` | NO |  | `dbo.user(uuid)` |
| `login_code_hash` | `NVARCHAR(256)` | NO |  | 正規化コードの SHA-256 ハッシュ |
| `issued_at` | `DATETIME2(3)` | NO |  | 発行日時 |
| `expires_at` | `DATETIME2(3)` | NO |  | 有効期限 |
| `consumed_at` | `DATETIME2(3)` | YES |  | 消費日時 |
| `revoked_at` | `DATETIME2(3)` | YES |  | 失効日時 |
| `failed_attempts` | `INT` | NO | `0` | 失敗回数 |
| `issued_by_server` | `NVARCHAR(100)` | NO |  | 発行元サーバー ID |
| `created_at` | `DATETIME2(3)` | NO |  | 作成日時 |

## 制約

- `PK_web_login_challenge`: `challenge_id`
- `FK_web_login_challenge_user`: `user_id` → `dbo.user(uuid)`
- `CK_web_login_challenge_hash_not_blank`: ログインコードハッシュを空文字にしない。
- `CK_web_login_challenge_server_not_blank`: 発行元サーバー ID を空文字にしない。
- `CK_web_login_challenge_failed_attempts`: `failed_attempts >= 0`
- `CK_web_login_challenge_expiry`: `expires_at > issued_at`

## インデックス

- `UX_web_login_challenge_login_code_hash`: コードハッシュを一意にし、消費時の検索に使用する。
- `IX_web_login_challenge_user_expires`: ユーザーと有効期限によるチャレンジ検索用。

## 関連設計書

- `00_docs/20_API設計書/feature/24-web-auth`
- `00_docs/10_Plugin設計書/feature/24-web-auth`
- `00_docs/30_WEB設計書/feature/01-web-auth`
