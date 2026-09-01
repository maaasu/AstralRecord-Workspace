# dbo.player_mail_delivery テーブル設計

特定アカウントへ個別配信する動的メールを保持する。マスタ変更で無効になった装着済みシジルの返却など、filebase の静的メールでは表現できない補償に使用する。既読・削除状態は `dbo.player_mail_state` で管理する。

## テーブル情報

| 項目 | 値 |
|:--|:--|
| スキーマ名 | `dbo` |
| テーブル名 | `player_mail_delivery` |
| 主キー | `player_mail_delivery_id` |
| 外部キー参照先 | `dbo.account.uuid` |

## カラム定義

| カラム名 | データ型 | PK | NotNull | デフォルト | 説明 |
|:--|:--|:-:|:-:|:--|:--|
| `player_mail_delivery_id` | `UNIQUEIDENTIFIER` | ✓ | ✓ |  | 配信レコード UUID |
| `account_id` | `UNIQUEIDENTIFIER` |  | ✓ |  | 配信先アカウント UUID |
| `mail_id` | `NVARCHAR(128)` |  | ✓ |  | アカウント内で一意な動的メール ID |
| `payload_json` | `NVARCHAR(MAX)` |  | ✓ |  | `MailResponse` 形式の本文・報酬 JSON |
| `version` | `INT` |  | ✓ | `1` | 更新バージョン |
| `created_at` | `DATETIME2(3)` |  | ✓ |  | 作成日時 |
| `updated_at` | `DATETIME2(3)` |  | ✓ |  | 更新日時 |
| `created_by` | `UNIQUEIDENTIFIER` |  | ✓ |  | 作成者 UUID |
| `updated_by` | `UNIQUEIDENTIFIER` |  | ✓ |  | 更新者 UUID |
| `is_deleted` | `BIT` |  | ✓ | `0` | 論理削除フラグ |

## 制約・索引

| 名前 | 対象 | 種別 | 用途 |
|:--|:--|:--|:--|
| `PK_player_mail_delivery` | `player_mail_delivery_id` | PK | 主キー検索 |
| `FK_player_mail_delivery_account` | `account_id` | FK | 配信先アカウントの整合性 |
| `CK_player_mail_delivery_payload_json` | `payload_json` | CHECK | JSON 形式を保証 |
| `UX_player_mail_delivery_account_mail` | `account_id`, `mail_id` | UNIQUE | 同一メールの重複配信を防止 |
| `IX_player_mail_delivery_account_id` | `account_id` | INDEX | アカウント別メール一覧 |
| `IX_player_mail_delivery_is_deleted` | `is_deleted` | INDEX | 有効配信の抽出 |

## 運用ルール

- API が補償発生時にメール本文と返却アイテムを1トランザクションで登録する。
- `mail_id` は配信ごとに新しい決定的衝突のない ID を発行する。
- 添付シジルがマスタ上で論理削除済みでも、返却を完了できるようアイテム詳細 API は該当シジルを解決する。
- メールを開いて添付を受領した後の状態は `dbo.player_mail_state` に保存する。
