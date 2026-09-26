# dbo.account_benefit_operation

VIP/優先券アイテム消費(ITEM)、予約消費(PRIORITY)、ログイン(LOGIN)の冪等台帳。operation_idが主キー。account_idは削除後も保持するaccount.uuidへの外部キー。kind、request_hash、status、reason、inventory_entry_id、awarded_priority_uses、refunded、created_at_utcを記録する。request_hashはアカウント・操作種別・アイテムentry・読込時timestampのSHA-256。同じIDの別要求は409。

アカウント更新ロック→台帳→親inventory→entryの順でロックし、残高/期間、アイテム減算、台帳を同一SERIALIZABLE transactionで保存する。拒否も記録し、再送で後の残高を消費しない。再送応答のbenefits/inventorySnapshotは現在の正本を返す。

予約取消は元のoperation_idのrefundedを一度だけ立てて1回戻す。消費より先に取消が到着した場合はREJECTEDの取消記録を作成し、後着消費を抑止する。他種別操作の取消は不可。削除・cloneによって台帳を消去/コピーしない。

## DDL

```sql

CREATE TABLE [dbo].[account_benefit_operation] (
    [operation_id] UNIQUEIDENTIFIER NOT NULL CONSTRAINT [PK_account_benefit_operation] PRIMARY KEY,
    [account_id] UNIQUEIDENTIFIER NOT NULL,
    [kind] NVARCHAR(16) NOT NULL,
    [request_hash] NVARCHAR(64) NOT NULL,
    [status] NVARCHAR(16) NOT NULL,
    [reason] NVARCHAR(100) NULL,
    [inventory_entry_id] UNIQUEIDENTIFIER NULL,
    [awarded_priority_uses] INT NOT NULL,
    [refunded] BIT NOT NULL,
    [created_at_utc] DATETIME2(3) NOT NULL,
    CONSTRAINT [FK_account_benefit_operation_account] FOREIGN KEY ([account_id]) REFERENCES [dbo].[account] ([uuid])
);
```
