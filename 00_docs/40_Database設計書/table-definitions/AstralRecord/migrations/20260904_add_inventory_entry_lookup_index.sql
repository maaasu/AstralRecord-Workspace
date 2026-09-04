SET XACT_ABORT ON;
GO

BEGIN TRANSACTION;

-- ReplaceEntriesAsync は inventory_id 単位で entry をロックするため、主キー全走査を避ける。
IF OBJECT_ID(N'[dbo].[inventory_entry]', N'U') IS NOT NULL
   AND NOT EXISTS (
       SELECT 1
       FROM sys.indexes
       WHERE [name] = N'IX_inventory_entry_inventory_id'
         AND [object_id] = OBJECT_ID(N'[dbo].[inventory_entry]')
   )
BEGIN
    CREATE NONCLUSTERED INDEX [IX_inventory_entry_inventory_id]
        ON [dbo].[inventory_entry] ([inventory_id]);
END;

COMMIT TRANSACTION;
GO
