# dbo.player_mail_state テーブル設計

アカウント単位のメール既読状態と削除状態を保持するテーブル。
メール本文、題名、アイコン、報酬、公開期間は filebase の `mail` マスタデータで管理し、このテーブルには保存しない。

---

## テーブル情報

| 項目 | 値 |
|:--|:--|
| スキーマ名 | `dbo` |
| テーブル名 | `player_mail_state` |
| 主キー | `player_mail_state_id` |
| 外部キー参照先 | `dbo.account.uuid` |

---

## カラム定義

| カラム名 | データ型 | PK | NotNull | デフォルト制約 | 説明 |
|:--|:--|:-:|:-:|:-:|:--|
| `player_mail_state_id` | `UNIQUEIDENTIFIER` | ✓ | ✓ |  | メール状態レコード UUID |
| `account_id` | `UNIQUEIDENTIFIER` |  | ✓ |  | 対象アカウント UUID |
| `mail_id` | `NVARCHAR(100)` |  | ✓ |  | filebase `mail.id` |
| `is_read` | `BIT` |  | ✓ | `0` | 既読フラグ |
| `read_at` | `DATETIME2(3)` |  |  |  | 既読日時 |
| `version` | `INT` |  | ✓ | `1` | 楽観ロック用バージョン |
| `created_at` | `DATETIME2(3)` |  | ✓ |  | レコード作成日時 |
| `updated_at` | `DATETIME2(3)` |  | ✓ |  | レコード更新日時 |
| `created_by` | `UNIQUEIDENTIFIER` |  | ✓ |  | 作成者 UUID |
| `updated_by` | `UNIQUEIDENTIFIER` |  | ✓ |  | 更新者 UUID |
| `is_deleted` | `BIT` |  | ✓ | `0` | アカウント単位の一覧非表示フラグ |
| `deleted_at` | `DATETIME2(3)` |  |  |  | 削除日時 |

---

## 制約・索引

| 名前 | 対象 | 種別 | 用途 |
|:--|:--|:--|:--|
| `PK_player_mail_state` | `player_mail_state_id` | PK | 主キー検索 |
| `FK_player_mail_state_account` | `account_id` | FK | `dbo.account(uuid)` 参照 |
| `UX_player_mail_state_account_mail` | `account_id`, `mail_id` | UNIQUE | 1 アカウント 1 メール 1 状態に制限 |
| `IX_player_mail_state_account_id` | `account_id` | NONCLUSTERED | アカウント単位の一覧取得 |
| `IX_player_mail_state_mail_id` | `mail_id` | NONCLUSTERED | メール単位の状態確認 |
| `IX_player_mail_state_is_deleted` | `is_deleted` | NONCLUSTERED | 削除状態フィルタ |

---

## DDL

```sql
CREATE TABLE [dbo].[player_mail_state] (
    [player_mail_state_id] UNIQUEIDENTIFIER NOT NULL,
    [account_id]           UNIQUEIDENTIFIER NOT NULL,
    [mail_id]              NVARCHAR(100)    NOT NULL,
    [is_read]              BIT              NOT NULL CONSTRAINT [DF_player_mail_state_is_read] DEFAULT (0),
    [read_at]              DATETIME2(3)         NULL,
    [version]              INT              NOT NULL CONSTRAINT [DF_player_mail_state_version] DEFAULT (1),
    [created_at]           DATETIME2(3)     NOT NULL,
    [updated_at]           DATETIME2(3)     NOT NULL,
    [created_by]           UNIQUEIDENTIFIER NOT NULL,
    [updated_by]           UNIQUEIDENTIFIER NOT NULL,
    [is_deleted]           BIT              NOT NULL CONSTRAINT [DF_player_mail_state_is_deleted] DEFAULT (0),
    [deleted_at]           DATETIME2(3)         NULL,

    CONSTRAINT [PK_player_mail_state] PRIMARY KEY CLUSTERED ([player_mail_state_id]),
    CONSTRAINT [FK_player_mail_state_account] FOREIGN KEY ([account_id])
        REFERENCES [dbo].[account] ([uuid])
        ON DELETE NO ACTION
        ON UPDATE NO ACTION,
    CONSTRAINT [CK_player_mail_state_mail_id_not_blank] CHECK (LEN(LTRIM(RTRIM([mail_id]))) > 0),
    CONSTRAINT [CK_player_mail_state_version] CHECK ([version] >= 1)
);
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_player_mail_state_account_mail]
    ON [dbo].[player_mail_state] ([account_id], [mail_id]);
GO

CREATE NONCLUSTERED INDEX [IX_player_mail_state_account_id]
    ON [dbo].[player_mail_state] ([account_id]);
GO

CREATE NONCLUSTERED INDEX [IX_player_mail_state_mail_id]
    ON [dbo].[player_mail_state] ([mail_id]);
GO

CREATE NONCLUSTERED INDEX [IX_player_mail_state_is_deleted]
    ON [dbo].[player_mail_state] ([is_deleted]);
GO
```
