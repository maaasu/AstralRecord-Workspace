# dbo.account_delete_receipt テーブル定義

アカウント削除の commit 結果不明時に、初回削除で確定した応答を再送するための冪等応答台帳。削除対象アカウント UUID をキーに、削除後の選択先と同一スロットの代替アカウント作成結果を保持します。

---

## テーブル情報

| 項目 | 値 |
|:--|:--|
| スキーマ名 | `dbo` |
| テーブル名 | `account_delete_receipt` |
| 完全修飾名 | `dbo.account_delete_receipt` |
| 主キー | `deleted_account_id` |

## カラム定義

| カラム名 | データ型 | PK | NotNull | 説明 |
|:--|:--|:--:|:--:|:--|
| `deleted_account_id` | `UNIQUEIDENTIFIER` | ○ | ○ | 削除対象アカウント UUID |
| `user_id` | `UNIQUEIDENTIFIER` |  | ○ | アカウント所有ユーザー UUID |
| `deleted_slot_index` | `INT` |  | ○ | 削除対象のスロット番号 |
| `selected_account_id` | `UNIQUEIDENTIFIER` |  | ○ | 削除後に選択されたアカウント UUID |
| `created_replacement` | `BIT` |  | ○ | 同一スロットの代替アカウントを作成したか |
| `deleted_by` | `UNIQUEIDENTIFIER` |  | ○ | 削除実行主体 UUID |
| `completed_at` | `DATETIME2(3)` |  | ○ | 削除処理の確定日時 |

## 制約・インデックス

- 主キー: `PK_account_delete_receipt (deleted_account_id)`
- 外部キー: `deleted_account_id` -> `dbo.account(uuid)`、`user_id` -> `dbo.user(uuid)`、`selected_account_id` -> `dbo.account(uuid)`。いずれも `ON DELETE NO ACTION`。
- インデックス: `IX_account_delete_receipt_user_completed (user_id, completed_at)`

## DDL

```sql
CREATE TABLE [dbo].[account_delete_receipt] (
    [deleted_account_id]  UNIQUEIDENTIFIER NOT NULL,
    [user_id]             UNIQUEIDENTIFIER NOT NULL,
    [deleted_slot_index]  INT              NOT NULL,
    [selected_account_id] UNIQUEIDENTIFIER NOT NULL,
    [created_replacement] BIT              NOT NULL,
    [deleted_by]          UNIQUEIDENTIFIER NOT NULL,
    [completed_at]        DATETIME2(3)     NOT NULL,

    CONSTRAINT [PK_account_delete_receipt] PRIMARY KEY CLUSTERED ([deleted_account_id]),
    CONSTRAINT [FK_account_delete_receipt_deleted_account] FOREIGN KEY ([deleted_account_id])
        REFERENCES [dbo].[account] ([uuid]) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT [FK_account_delete_receipt_user] FOREIGN KEY ([user_id])
        REFERENCES [dbo].[user] ([uuid]) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT [FK_account_delete_receipt_selected_account] FOREIGN KEY ([selected_account_id])
        REFERENCES [dbo].[account] ([uuid]) ON DELETE NO ACTION ON UPDATE NO ACTION
);
GO

CREATE NONCLUSTERED INDEX [IX_account_delete_receipt_user_completed]
    ON [dbo].[account_delete_receipt] ([user_id], [completed_at]);
GO
```

## 運用規則

- `dbo.account` の削除・従属データ削除・選択先更新と同じ transaction で 1 行を登録します。
- 同じ削除対象 UUID と同じ `deleted_by` の再送は、この台帳の値をそのまま返し、後続のアカウント切替によって応答を変えません。
- 既存の論理削除行に台帳がない場合は、API が旧データ互換の選択先再構築へフォールバックします。
