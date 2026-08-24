# dbo.release_note テーブル定義

Web に公開するリリースノートのメタデータと、正本 Markdown のハッシュを管理します。本文は Web のデプロイ成果物に含まれる Markdown を正本とし、DB には保存しません。

## カラム

| カラム | 型 | NULL | 既定値 | 内容 |
|---|---|---|---|---|
| `release_note_id` | `UNIQUEIDENTIFIER` | NO |  | 主キー |
| `slug` | `NVARCHAR(80)` | NO |  | URLに使用する一意識別子 |
| `version` | `NVARCHAR(64)` | NO |  | リリースバージョン |
| `title` | `NVARCHAR(200)` | NO |  | 表示タイトル |
| `summary` | `NVARCHAR(500)` | NO |  | 一覧表示用概要 |
| `release_url` | `NVARCHAR(512)` | NO |  | 公開ページURL |
| `source_path` | `NVARCHAR(260)` | NO |  | Markdownのリポジトリ相対パス |
| `content_sha256` | `NVARCHAR(64)` | NO |  | Markdown正規化内容のSHA-256 |
| `published_at_utc` | `DATETIME2(3)` | NO |  | 公開日時（UTC） |
| `is_published` | `BIT` | NO |  | 公開状態 |
| `notify_discord` | `BIT` | NO |  | Discord通知対象か |
| `created_at_utc` | `DATETIME2(3)` | NO |  | 作成日時（UTC） |
| `updated_at_utc` | `DATETIME2(3)` | NO |  | 更新日時（UTC） |

## 制約

- `PK_release_note`: `release_note_id`
- `UX_release_note_slug`: `slug` の一意制約
- `CK_release_note_slug_not_blank`: `slug` を空文字にしない。
- `CK_release_note_url_not_blank`: `release_url` を空文字にしない。
- `CK_release_note_sha256`: SHA-256 文字列を64文字にする。

## インデックス

- `IX_release_note_published_at`: 公開状態と公開日時による検索用。

## 関連

- `[[dbo.release_notification_outbox]]`
- `[[20260824_release_note]]`
