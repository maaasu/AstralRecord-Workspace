SET XACT_ABORT ON;
BEGIN TRANSACTION;

-- Instance-backed entries may carry the API-resolved authoritative item ID.
IF EXISTS (SELECT 1 FROM sys.check_constraints
    WHERE [name] = N'CK_inventory_entry_payload'
      AND [parent_object_id] = OBJECT_ID(N'[dbo].[inventory_entry]'))
    ALTER TABLE [dbo].[inventory_entry] DROP CONSTRAINT [CK_inventory_entry_payload];
ALTER TABLE [dbo].[inventory_entry] WITH CHECK
    ADD CONSTRAINT [CK_inventory_entry_payload] CHECK (
        ([item_id] IS NOT NULL AND [instance_type] IS NULL AND [instance_id] IS NULL)
        OR ([instance_type] IS NOT NULL AND [instance_id] IS NOT NULL)
    );

IF EXISTS (SELECT 1 FROM sys.indexes
    WHERE [name] = N'UX_inventory_entry_inventory_item'
      AND [object_id] = OBJECT_ID(N'[dbo].[inventory_entry]'))
    DROP INDEX [UX_inventory_entry_inventory_item] ON [dbo].[inventory_entry];
CREATE UNIQUE NONCLUSTERED INDEX [UX_inventory_entry_inventory_item]
    ON [dbo].[inventory_entry] ([inventory_id], [item_id])
    WHERE [slot_index] IS NULL
      AND [item_id] IS NOT NULL
      AND [instance_type] IS NULL
      AND [instance_id] IS NULL
      AND [is_deleted] = 0;

IF COL_LENGTH(N'[dbo].[account]', N'progress_version') IS NULL
BEGIN
    ALTER TABLE [dbo].[account]
        ADD [progress_version] INT NOT NULL
            CONSTRAINT [DF_account_progress_version] DEFAULT (1) WITH VALUES;
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.check_constraints
    WHERE [name] = N'CK_account_progress_version'
      AND [parent_object_id] = OBJECT_ID(N'[dbo].[account]')
)
BEGIN
    ALTER TABLE [dbo].[account]
        ADD CONSTRAINT [CK_account_progress_version] CHECK ([progress_version] >= 1);
END;

IF OBJECT_ID(N'[dbo].[player_state_snapshot]', N'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[player_state_snapshot] (
        [snapshot_id]      UNIQUEIDENTIFIER NOT NULL,
        [account_id]       UNIQUEIDENTIFIER NOT NULL,
        [request_hash]     CHAR(64)         NOT NULL,
        [ack_payload_json] NVARCHAR(MAX)    NOT NULL,
        [created_at]       DATETIME2(3)     NOT NULL,
        [completed_at]     DATETIME2(3)     NOT NULL,
        [created_by]       UNIQUEIDENTIFIER NOT NULL,
        CONSTRAINT [PK_player_state_snapshot] PRIMARY KEY CLUSTERED ([snapshot_id]),
        CONSTRAINT [FK_player_state_snapshot_account] FOREIGN KEY ([account_id])
            REFERENCES [dbo].[account] ([uuid]) ON DELETE NO ACTION ON UPDATE NO ACTION,
        CONSTRAINT [CK_player_state_snapshot_request_hash]
            CHECK ([request_hash] LIKE '[0-9A-Fa-f]' + REPLICATE('[0-9A-Fa-f]', 63)),
        CONSTRAINT [CK_player_state_snapshot_ack_payload_json]
            CHECK (ISJSON([ack_payload_json]) = 1)
    );
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE [name] = N'IX_player_state_snapshot_account_completed'
      AND [object_id] = OBJECT_ID(N'[dbo].[player_state_snapshot]')
)
BEGIN
    CREATE NONCLUSTERED INDEX [IX_player_state_snapshot_account_completed]
        ON [dbo].[player_state_snapshot] ([account_id], [completed_at]);
END;

COMMIT TRANSACTION;
SET XACT_ABORT OFF;
GO
