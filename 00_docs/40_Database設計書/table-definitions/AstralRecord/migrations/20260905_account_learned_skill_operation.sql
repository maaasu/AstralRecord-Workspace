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

-- A schema created by EF Core before this migration may have request_hash as
-- NVARCHAR(64). Normalize it to the CHAR(64) contract before validation.
IF EXISTS (
    SELECT 1
    FROM sys.columns AS c
    INNER JOIN sys.types AS t
        ON t.user_type_id = c.user_type_id
    WHERE c.[object_id] = OBJECT_ID(N'[dbo].[account_learned_skill_operation]')
      AND c.[name] = N'request_hash'
      AND (
          t.[name] <> N'char'
          OR c.[max_length] <> 64
          OR c.[is_nullable] <> 0
      )
)
BEGIN
    ALTER TABLE [dbo].[account_learned_skill_operation]
        ALTER COLUMN [request_hash] CHAR(64) NOT NULL;
END;

-- SQL Server does not allow altering a column used by an index. Recreate the
-- account/time index after normalizing the DATETIME2 precision below.
IF EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE [name] = N'IX_account_learned_skill_operation_account_created_at'
      AND [object_id] = OBJECT_ID(N'[dbo].[account_learned_skill_operation]')
)
BEGIN
    DROP INDEX [IX_account_learned_skill_operation_account_created_at]
        ON [dbo].[account_learned_skill_operation];
END;

-- EF Core's default DateTime mapping is DATETIME2(7). Normalize existing
-- schemas to the DATETIME2(3) contract before validation.
IF EXISTS (
    SELECT 1
    FROM sys.columns AS c
    INNER JOIN sys.types AS t
        ON t.user_type_id = c.user_type_id
    WHERE c.[object_id] = OBJECT_ID(N'[dbo].[account_learned_skill_operation]')
      AND c.[name] = N'created_at'
      AND (
          t.[name] <> N'datetime2'
          OR c.[precision] <> 23
          OR c.[scale] <> 3
          OR c.[is_nullable] <> 0
      )
)
BEGIN
    ALTER TABLE [dbo].[account_learned_skill_operation]
        ALTER COLUMN [created_at] DATETIME2(3) NOT NULL;
END;

IF EXISTS (
    SELECT 1
    FROM sys.columns AS c
    INNER JOIN sys.types AS t
        ON t.user_type_id = c.user_type_id
    WHERE c.[object_id] = OBJECT_ID(N'[dbo].[account_learned_skill_operation]')
      AND c.[name] = N'completed_at'
      AND (
          t.[name] <> N'datetime2'
          OR c.[precision] <> 23
          OR c.[scale] <> 3
          OR c.[is_nullable] <> 0
      )
)
BEGIN
    ALTER TABLE [dbo].[account_learned_skill_operation]
        ALTER COLUMN [completed_at] DATETIME2(3) NOT NULL;
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.check_constraints
    WHERE [name] = N'CK_account_learned_skill_operation_result_payload_json'
      AND [parent_object_id] = OBJECT_ID(N'[dbo].[account_learned_skill_operation]')
)
BEGIN
    ALTER TABLE [dbo].[account_learned_skill_operation]
        ADD CONSTRAINT [CK_account_learned_skill_operation_result_payload_json]
        CHECK (ISJSON([result_payload_json]) = 1);
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
