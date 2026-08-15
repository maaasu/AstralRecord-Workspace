SET XACT_ABORT ON;
GO

BEGIN TRANSACTION;

IF OBJECT_ID(N'[dbo].[trade_commit]', N'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[trade_commit] (
        [operation_id]          UNIQUEIDENTIFIER NOT NULL,
        [player_a_account_id]   UNIQUEIDENTIFIER NOT NULL,
        [player_b_account_id]   UNIQUEIDENTIFIER NOT NULL,
        [request_hash]          CHAR(64)         NOT NULL,
        [result_payload_json]   NVARCHAR(MAX)    NOT NULL,
        [completed_at]          DATETIME2(3)     NOT NULL,
        [created_at]            DATETIME2(3)     NOT NULL,
        [created_by]            UNIQUEIDENTIFIER NOT NULL,

        CONSTRAINT [PK_trade_commit] PRIMARY KEY CLUSTERED ([operation_id]),
        CONSTRAINT [FK_trade_commit_player_a_account] FOREIGN KEY ([player_a_account_id])
            REFERENCES [dbo].[account] ([uuid]) ON DELETE NO ACTION ON UPDATE NO ACTION,
        CONSTRAINT [FK_trade_commit_player_b_account] FOREIGN KEY ([player_b_account_id])
            REFERENCES [dbo].[account] ([uuid]) ON DELETE NO ACTION ON UPDATE NO ACTION,
        CONSTRAINT [CK_trade_commit_different_accounts]
            CHECK ([player_a_account_id] <> [player_b_account_id]),
        CONSTRAINT [CK_trade_commit_result_payload_json]
            CHECK (ISJSON([result_payload_json]) = 1)
    );
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE [name] = N'IX_trade_commit_accounts_completed'
      AND [object_id] = OBJECT_ID(N'[dbo].[trade_commit]')
)
BEGIN
    CREATE NONCLUSTERED INDEX [IX_trade_commit_accounts_completed]
        ON [dbo].[trade_commit] ([player_a_account_id], [player_b_account_id], [completed_at]);
END;

COMMIT TRANSACTION;
GO
