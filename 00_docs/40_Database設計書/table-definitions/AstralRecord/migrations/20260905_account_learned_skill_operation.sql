SET XACT_ABORT ON;
BEGIN TRANSACTION;

IF OBJECT_ID(N'[dbo].[account_learned_skill_operation]', N'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[account_learned_skill_operation] (
        [operation_id]        UNIQUEIDENTIFIER NOT NULL,
        [account_id]          UNIQUEIDENTIFIER NOT NULL,
        [operation_type]      NVARCHAR(32)     NOT NULL,
        [request_hash]        CHAR(64)         NOT NULL,
        [result_payload_json] NVARCHAR(MAX)    NOT NULL,
        [created_at]          DATETIME2(3)     NOT NULL,
        [completed_at]        DATETIME2(3)     NOT NULL,
        [created_by]          UNIQUEIDENTIFIER NOT NULL,
        CONSTRAINT [PK_account_learned_skill_operation] PRIMARY KEY CLUSTERED ([operation_id]),
        CONSTRAINT [CK_account_learned_skill_operation_result_payload_json]
            CHECK (ISJSON([result_payload_json]) = 1)
    );
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE [name] = N'IX_account_learned_skill_operation_account_created_at'
      AND [object_id] = OBJECT_ID(N'[dbo].[account_learned_skill_operation]')
)
BEGIN
    CREATE NONCLUSTERED INDEX [IX_account_learned_skill_operation_account_created_at]
        ON [dbo].[account_learned_skill_operation] ([account_id], [created_at]);
END;

COMMIT TRANSACTION;
SET XACT_ABORT OFF;
GO
