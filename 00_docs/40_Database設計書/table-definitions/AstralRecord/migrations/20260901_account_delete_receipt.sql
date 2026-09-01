SET XACT_ABORT ON;
GO

BEGIN TRANSACTION;

IF OBJECT_ID(N'[dbo].[account_delete_receipt]', N'U') IS NULL
BEGIN
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
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE [name] = N'IX_account_delete_receipt_user_completed'
      AND [object_id] = OBJECT_ID(N'[dbo].[account_delete_receipt]')
)
BEGIN
    CREATE NONCLUSTERED INDEX [IX_account_delete_receipt_user_completed]
        ON [dbo].[account_delete_receipt] ([user_id], [completed_at]);
END;

COMMIT TRANSACTION;
GO
