SET XACT_ABORT ON;
GO

BEGIN TRANSACTION;

IF OBJECT_ID(N'[dbo].[account_dungeon_record]', N'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[account_dungeon_record] (
        [account_dungeon_record_id] UNIQUEIDENTIFIER NOT NULL,
        [account_id]                 UNIQUEIDENTIFIER NOT NULL,
        [dungeon_id]                 NVARCHAR(100)    NOT NULL,
        [clear_count]                BIGINT           NOT NULL CONSTRAINT [DF_account_dungeon_record_clear_count] DEFAULT (1),
        [first_cleared_at]           DATETIME2(3)     NOT NULL,
        [last_cleared_at]            DATETIME2(3)     NOT NULL,
        [created_at]                 DATETIME2(3)     NOT NULL,
        [updated_at]                 DATETIME2(3)     NOT NULL,
        [created_by]                 UNIQUEIDENTIFIER NOT NULL,
        [updated_by]                 UNIQUEIDENTIFIER NOT NULL,
        [is_deleted]                 BIT              NOT NULL CONSTRAINT [DF_account_dungeon_record_is_deleted] DEFAULT (0),
        CONSTRAINT [PK_account_dungeon_record] PRIMARY KEY CLUSTERED ([account_dungeon_record_id]),
        CONSTRAINT [FK_account_dungeon_record_account] FOREIGN KEY ([account_id])
            REFERENCES [dbo].[account] ([uuid]) ON DELETE CASCADE ON UPDATE NO ACTION,
        CONSTRAINT [UX_account_dungeon_record_account_dungeon] UNIQUE ([account_id], [dungeon_id]),
        CONSTRAINT [CK_account_dungeon_record_clear_count] CHECK ([clear_count] >= 1)
    );
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE [name] = N'IX_account_dungeon_record_account_last_cleared'
      AND [object_id] = OBJECT_ID(N'[dbo].[account_dungeon_record]')
)
BEGIN
    CREATE NONCLUSTERED INDEX [IX_account_dungeon_record_account_last_cleared]
        ON [dbo].[account_dungeon_record] ([account_id], [last_cleared_at] DESC);
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE [name] = N'IX_account_dungeon_record_is_deleted'
      AND [object_id] = OBJECT_ID(N'[dbo].[account_dungeon_record]')
)
BEGIN
    CREATE NONCLUSTERED INDEX [IX_account_dungeon_record_is_deleted]
        ON [dbo].[account_dungeon_record] ([is_deleted]);
END;

COMMIT TRANSACTION;
GO
