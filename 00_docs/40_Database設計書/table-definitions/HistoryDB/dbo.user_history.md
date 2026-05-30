# dbo.user_history テーブル定義

ユーザーの行動履歴を永続化する履歴用テーブル。  
本テーブルは `HistoryDB` に配置し、`AstralRecord` DB とは物理 DB を分離する。

---

## テーブル情報

| 項目 | 値 |
|:--|:--|
| DB | `HistoryDB` |
| スキーマ | `dbo` |
| テーブル名 | `user_history` |
| 完全修飾名 | `HistoryDB.dbo.user_history` |
| 主キー | `history_id` |
| 外部キー | なし |

---

## カラム定義

| カラム名 | 型 | PK | NotNull | Default | 説明 |
|:--|:--|:--:|:--:|:--|:--|
| `history_id` | `BIGINT` | ○ | ○ | `IDENTITY(1,1)` | 履歴 ID |
| `user_uuid` | `UNIQUEIDENTIFIER` |  |  |  | 対象ユーザー UUID |
| `event_time` | `DATETIME2(3)` |  | ○ |  | イベント発生日時 |
| `event_type` | `NVARCHAR(50)` |  | ○ |  | イベント種別（例: `PLAYER_LOGIN`, `PLAYER_LOGOUT`, `PARTY_CREATED`, `PARTY_LEFT_LOGOUT`） |
| `source` | `NVARCHAR(50)` |  | ○ | `N'PLUGIN'` | 発生元 |
| `message` | `NVARCHAR(MAX)` |  | ○ |  | 履歴メッセージ |
| `payload_json` | `NVARCHAR(MAX)` |  |  |  | 任意の詳細情報（JSON） |
| `created_at` | `DATETIME2(3)` |  | ○ | `SYSUTCDATETIME()` | レコード作成日時 |

---

## 制約定義

### 主キー制約

| 制約名 | カラム | 種別 |
|:--|:--|:--|
| `PK_user_history` | `history_id` | PK |

### CHECK 制約

| 制約名 | カラム | 条件 | 説明 |
|:--|:--|:--|:--|
| `CK_user_history_payload_json` | `payload_json` | `payload_json IS NULL OR ISJSON(payload_json) = 1` | JSON 妥当性チェック |

---

## インデックス定義

| インデックス名 | カラム | 種別 | 用途 |
|:--|:--|:--|:--|
| `PK_user_history` | `history_id` | CLUSTERED | 主キー |
| `IX_user_history_user_time` | `user_uuid, event_time DESC` | NONCLUSTERED | ユーザー別履歴取得 |
| `IX_user_history_event_time` | `event_time DESC` | NONCLUSTERED | 時系列検索 |

---

## 補足

- `user_uuid` は `AstralRecord.dbo.user.uuid` に対応するが、SQL Server の外部キーは DB をまたがないため定義しない。
- API は履歴登録時に `AstralRecord` DB 側でユーザー存在確認を行う。

---

## DDL

```sql
CREATE TABLE [dbo].[user_history] (
    [history_id]   BIGINT             NOT NULL IDENTITY(1,1),
    [user_uuid]    UNIQUEIDENTIFIER       NULL,
    [event_time]   DATETIME2(3)       NOT NULL,
    [event_type]   NVARCHAR(50)       NOT NULL,
    [source]       NVARCHAR(50)       NOT NULL CONSTRAINT [DF_user_history_source] DEFAULT (N'PLUGIN'),
    [message]      NVARCHAR(MAX)      NOT NULL,
    [payload_json] NVARCHAR(MAX)          NULL,
    [created_at]   DATETIME2(3)       NOT NULL CONSTRAINT [DF_user_history_created_at] DEFAULT (SYSUTCDATETIME()),

    CONSTRAINT [PK_user_history] PRIMARY KEY CLUSTERED ([history_id]),
    CONSTRAINT [CK_user_history_payload_json]
        CHECK ([payload_json] IS NULL OR ISJSON([payload_json]) = 1)
);
GO

CREATE NONCLUSTERED INDEX [IX_user_history_user_time]
    ON [dbo].[user_history] ([user_uuid], [event_time] DESC);
GO

CREATE NONCLUSTERED INDEX [IX_user_history_event_time]
    ON [dbo].[user_history] ([event_time] DESC);
GO
```
