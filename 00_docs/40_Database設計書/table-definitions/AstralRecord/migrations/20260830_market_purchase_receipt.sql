SET XACT_ABORT ON;
GO

BEGIN TRANSACTION;

IF COL_LENGTH(N'dbo.market_transaction', N'affected_inventory_entry_ids_json') IS NULL
BEGIN
    ALTER TABLE [dbo].[market_transaction]
        ADD [affected_inventory_entry_ids_json] NVARCHAR(MAX) NULL;
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.check_constraints
    WHERE [name] = N'CK_market_transaction_affected_entries_json'
      AND [parent_object_id] = OBJECT_ID(N'[dbo].[market_transaction]')
)
BEGIN
    ALTER TABLE [dbo].[market_transaction]
        ADD CONSTRAINT [CK_market_transaction_affected_entries_json]
            CHECK ([affected_inventory_entry_ids_json] IS NULL
                OR ISJSON([affected_inventory_entry_ids_json]) = 1);
END;

COMMIT TRANSACTION;
GO
