SET XACT_ABORT ON;
GO

BEGIN TRANSACTION;

IF OBJECT_ID(N'[dbo].[account]', N'U') IS NOT NULL
BEGIN
    IF EXISTS (
        SELECT 1
        FROM sys.check_constraints
        WHERE [name] = N'CK_account_rebirth_experience_remainder'
          AND [parent_object_id] = OBJECT_ID(N'[dbo].[account]')
    )
    BEGIN
        ALTER TABLE [dbo].[account]
            DROP CONSTRAINT [CK_account_rebirth_experience_remainder];
    END;

    ALTER TABLE [dbo].[account] WITH CHECK
        ADD CONSTRAINT [CK_account_rebirth_experience_remainder]
        CHECK ([rebirth_experience_remainder] BETWEEN 0 AND 99
            AND ([rebirth_original_level] IS NOT NULL OR [rebirth_experience_remainder] = 0));

    ALTER TABLE [dbo].[account]
        CHECK CONSTRAINT [CK_account_rebirth_experience_remainder];
END;

COMMIT TRANSACTION;
GO
