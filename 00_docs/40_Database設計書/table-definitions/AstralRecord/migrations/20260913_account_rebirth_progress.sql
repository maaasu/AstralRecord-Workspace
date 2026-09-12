SET XACT_ABORT ON;
BEGIN TRANSACTION;

IF COL_LENGTH(N'dbo.account', N'highest_level') IS NULL
BEGIN
    ALTER TABLE [dbo].[account]
        ADD [highest_level] INT NOT NULL
            CONSTRAINT [DF_account_highest_level] DEFAULT (1);
END;

IF COL_LENGTH(N'dbo.account', N'rebirth_original_level') IS NULL
BEGIN
    ALTER TABLE [dbo].[account]
        ADD [rebirth_original_level] INT NULL;
END;

IF COL_LENGTH(N'dbo.account', N'rebirth_experience_remainder') IS NULL
BEGIN
    ALTER TABLE [dbo].[account]
        ADD [rebirth_experience_remainder] SMALLINT NOT NULL
            CONSTRAINT [DF_account_rebirth_experience_remainder] DEFAULT (0);
END;

GO

UPDATE [dbo].[account]
    SET [highest_level] = CASE WHEN [level] < 1 THEN 1 ELSE [level] END
    WHERE [highest_level] < [level];

IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE [name] = N'CK_account_highest_level')
    ALTER TABLE [dbo].[account] ADD CONSTRAINT [CK_account_highest_level]
        CHECK ([highest_level] >= [level]);

IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE [name] = N'CK_account_rebirth_original_level')
    ALTER TABLE [dbo].[account] ADD CONSTRAINT [CK_account_rebirth_original_level]
        CHECK ([rebirth_original_level] IS NULL OR ([rebirth_original_level] >= 2
            AND [rebirth_original_level] > [level] AND [rebirth_original_level] <= [highest_level]));

IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE [name] = N'CK_account_rebirth_experience_remainder')
    ALTER TABLE [dbo].[account] ADD CONSTRAINT [CK_account_rebirth_experience_remainder]
        CHECK ([rebirth_experience_remainder] BETWEEN 0 AND 9
            AND ([rebirth_original_level] IS NOT NULL OR [rebirth_experience_remainder] = 0));

COMMIT TRANSACTION;
