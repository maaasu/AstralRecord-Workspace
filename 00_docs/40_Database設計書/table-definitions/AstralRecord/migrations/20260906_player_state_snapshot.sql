SET XACT_ABORT ON;
BEGIN TRANSACTION;

-- Instance-backed entries may carry the API-resolved authoritative item ID.
IF EXISTS (SELECT 1 FROM sys.check_constraints
    WHERE [name] = N'CK_inventory_entry_payload'
      AND [parent_object_id] = OBJECT_ID(N'[dbo].[inventory_entry]'))
    ALTER TABLE [dbo].[inventory_entry] DROP CONSTRAINT [CK_inventory_entry_payload];

-- Legacy databases may have item_id as NVARCHAR(MAX)/TEXT. Normalize it before
-- creating the stack-item unique index; SQL Server does not allow those types
-- as index key columns. Never truncate an existing item ID silently.
IF EXISTS (
    SELECT 1
    FROM sys.columns AS c
    INNER JOIN sys.types AS t
        ON t.user_type_id = c.user_type_id
    WHERE c.[object_id] = OBJECT_ID(N'[dbo].[inventory_entry]')
      AND c.[name] = N'item_id'
      AND (
          t.[name] IN (N'text', N'ntext', N'image')
          OR (t.[name] IN (N'varchar', N'nvarchar', N'varbinary') AND c.[max_length] = -1)
      )
)
BEGIN
    IF EXISTS (
        SELECT 1
        FROM sys.columns AS c
        INNER JOIN sys.types AS t
            ON t.user_type_id = c.user_type_id
        WHERE c.[object_id] = OBJECT_ID(N'[dbo].[inventory_entry]')
          AND c.[name] = N'item_id'
          AND t.[name] = N'image'
    )
        THROW 51003, N'dbo.inventory_entry.item_id uses IMAGE. Convert it to a text type before applying this migration.', 1;

    IF EXISTS (
        SELECT 1
        FROM [dbo].[inventory_entry]
        WHERE [item_id] IS NOT NULL
          AND DATALENGTH(CONVERT(NVARCHAR(MAX), [item_id])) > 200
    )
        THROW 51002, N'dbo.inventory_entry.item_id contains a value longer than NVARCHAR(100).', 1;

    IF EXISTS (
        SELECT 1
        FROM sys.columns AS c
        INNER JOIN sys.types AS t
            ON t.user_type_id = c.user_type_id
        WHERE c.[object_id] = OBJECT_ID(N'[dbo].[inventory_entry]')
          AND c.[name] = N'item_id'
          AND t.[name] IN (N'text', N'ntext', N'image')
    )
    BEGIN
        EXEC sys.sp_executesql N'
            ALTER TABLE [dbo].[inventory_entry]
                ADD [item_id_migration] NVARCHAR(100) NULL;';
        EXEC sys.sp_executesql N'
            UPDATE [dbo].[inventory_entry]
                SET [item_id_migration] = CONVERT(NVARCHAR(100), [item_id]);';
        EXEC sys.sp_executesql N'
            ALTER TABLE [dbo].[inventory_entry]
                DROP COLUMN [item_id];';
        EXEC sys.sp_executesql N'
            EXEC sys.sp_rename N''dbo.inventory_entry.item_id_migration'', N''item_id'', N''COLUMN'';';
    END;
    ELSE
    BEGIN
        EXEC sys.sp_executesql N'
            ALTER TABLE [dbo].[inventory_entry]
                ALTER COLUMN [item_id] NVARCHAR(100) NULL;';
    END;
END;

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
    EXEC sys.sp_executesql N'
        ALTER TABLE [dbo].[account]
            ADD CONSTRAINT [CK_account_progress_version] CHECK ([progress_version] >= 1);';
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
