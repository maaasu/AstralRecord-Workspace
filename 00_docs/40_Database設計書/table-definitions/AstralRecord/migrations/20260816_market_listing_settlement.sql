SET XACT_ABORT ON;
GO

BEGIN TRANSACTION;

IF COL_LENGTH(N'dbo.market_listing', N'remaining_quantity') IS NULL
BEGIN
    ALTER TABLE [dbo].[market_listing]
        ADD [remaining_quantity] INT NULL;

    UPDATE [dbo].[market_listing]
    SET [remaining_quantity] = CASE
        WHEN [is_deleted] = 0 AND [status] IN (N'ACTIVE', N'SUSPENDED') THEN [quantity]
        ELSE 0
    END;

    ALTER TABLE [dbo].[market_listing]
        ALTER COLUMN [remaining_quantity] INT NOT NULL;

    ALTER TABLE [dbo].[market_listing]
        ADD CONSTRAINT [DF_market_listing_remaining_quantity] DEFAULT (0) FOR [remaining_quantity];

    -- remaining_quantity が無かった旧仕様では購入時に売上を即時入金済みだった。
    -- この旧データ変換は初回だけ実行し、新仕様で作られた未受取 SOLD には触れない。
    UPDATE [dbo].[market_listing]
    SET [is_deleted] = 1,
        [status_reason] = COALESCE([status_reason], N'LEGACY_SOLD_SETTLED'),
        [remaining_quantity] = 0
    WHERE [is_deleted] = 0
      AND [status] = N'SOLD';

    -- 旧仕様の取り下げ済み出品は既に返却済みなので、初回変換時だけ枠を解放する。
    UPDATE [dbo].[market_listing]
    SET [is_deleted] = 1,
        [remaining_quantity] = 0
    WHERE [is_deleted] = 0
      AND [status] = N'CANCELED';
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.check_constraints
    WHERE [name] = N'CK_market_listing_remaining_quantity'
      AND [parent_object_id] = OBJECT_ID(N'[dbo].[market_listing]')
)
BEGIN
    ALTER TABLE [dbo].[market_listing]
        ADD CONSTRAINT [CK_market_listing_remaining_quantity]
            CHECK ([remaining_quantity] >= 0 AND [remaining_quantity] <= [quantity]);
END;

IF COL_LENGTH(N'dbo.market_listing', N'proceeds_claim_idempotency_key') IS NULL
BEGIN
    ALTER TABLE [dbo].[market_listing]
        ADD [proceeds_claim_idempotency_key] NVARCHAR(128) NULL;
END;

IF COL_LENGTH(N'dbo.market_listing', N'proceeds_claim_amount') IS NULL
BEGIN
    ALTER TABLE [dbo].[market_listing]
        ADD [proceeds_claim_amount] BIGINT NULL;
END;

IF COL_LENGTH(N'dbo.market_listing', N'proceeds_claim_affected_entry_ids_json') IS NULL
BEGIN
    ALTER TABLE [dbo].[market_listing]
        ADD [proceeds_claim_affected_entry_ids_json] NVARCHAR(MAX) NULL;
END;

IF COL_LENGTH(N'dbo.market_listing', N'proceeds_claimed_at') IS NULL
BEGIN
    ALTER TABLE [dbo].[market_listing]
        ADD [proceeds_claimed_at] DATETIME2(3) NULL;
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.check_constraints
    WHERE [name] = N'CK_market_listing_proceeds_claim_amount'
      AND [parent_object_id] = OBJECT_ID(N'[dbo].[market_listing]')
)
BEGIN
    ALTER TABLE [dbo].[market_listing]
        ADD CONSTRAINT [CK_market_listing_proceeds_claim_amount]
            CHECK ([proceeds_claim_amount] IS NULL OR [proceeds_claim_amount] >= 1);
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.check_constraints
    WHERE [name] = N'CK_market_listing_proceeds_claim_entries_json'
      AND [parent_object_id] = OBJECT_ID(N'[dbo].[market_listing]')
)
BEGIN
    ALTER TABLE [dbo].[market_listing]
        ADD CONSTRAINT [CK_market_listing_proceeds_claim_entries_json]
            CHECK ([proceeds_claim_affected_entry_ids_json] IS NULL
                OR ISJSON([proceeds_claim_affected_entry_ids_json]) = 1);
END;

IF OBJECT_ID(N'[dbo].[market_listing_source]', N'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[market_listing_source] (
        [listing_id]         UNIQUEIDENTIFIER NOT NULL,
        [inventory_entry_id] UNIQUEIDENTIFIER NOT NULL,
        [quantity]           INT              NOT NULL,

        CONSTRAINT [PK_market_listing_source] PRIMARY KEY CLUSTERED ([listing_id], [inventory_entry_id]),
        CONSTRAINT [FK_market_listing_source_listing] FOREIGN KEY ([listing_id])
            REFERENCES [dbo].[market_listing] ([listing_id]) ON DELETE NO ACTION ON UPDATE NO ACTION,
        CONSTRAINT [FK_market_listing_source_inventory_entry] FOREIGN KEY ([inventory_entry_id])
            REFERENCES [dbo].[inventory_entry] ([inventory_entry_id]) ON DELETE NO ACTION ON UPDATE NO ACTION,
        CONSTRAINT [CK_market_listing_source_quantity] CHECK ([quantity] >= 1)
    );
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE [name] = N'IX_market_listing_source_inventory_entry'
      AND [object_id] = OBJECT_ID(N'[dbo].[market_listing_source]')
)
BEGIN
    CREATE NONCLUSTERED INDEX [IX_market_listing_source_inventory_entry]
        ON [dbo].[market_listing_source] ([inventory_entry_id]);
END;

INSERT INTO [dbo].[market_listing_source] ([listing_id], [inventory_entry_id], [quantity])
SELECT [listing_id], [source_inventory_entry_id], [quantity]
FROM [dbo].[market_listing] AS listing
WHERE [source_inventory_entry_id] IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM [dbo].[market_listing_source] AS source
      WHERE source.[listing_id] = listing.[listing_id]
        AND source.[inventory_entry_id] = listing.[source_inventory_entry_id]
  );

IF EXISTS (
    SELECT 1 FROM sys.key_constraints
    WHERE [name] = N'UQ_market_transaction_listing'
      AND [parent_object_id] = OBJECT_ID(N'[dbo].[market_transaction]')
)
BEGIN
    ALTER TABLE [dbo].[market_transaction]
        DROP CONSTRAINT [UQ_market_transaction_listing];
END;

IF EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE [name] = N'UQ_market_transaction_listing'
      AND [object_id] = OBJECT_ID(N'[dbo].[market_transaction]')
)
BEGIN
    DROP INDEX [UQ_market_transaction_listing] ON [dbo].[market_transaction];
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE [name] = N'IX_market_transaction_listing'
      AND [object_id] = OBJECT_ID(N'[dbo].[market_transaction]')
)
BEGIN
    CREATE NONCLUSTERED INDEX [IX_market_transaction_listing]
        ON [dbo].[market_transaction] ([listing_id]);
END;

COMMIT TRANSACTION;
GO
