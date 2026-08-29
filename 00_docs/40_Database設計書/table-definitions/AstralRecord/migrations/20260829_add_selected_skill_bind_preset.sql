USE [AstralRecord];
GO

IF OBJECT_ID(N'[dbo].[skill_bind_preset]', N'U') IS NULL
    THROW 50000, N'dbo.skill_bind_preset が存在しません。', 1;
GO

IF COL_LENGTH(N'dbo.skill_bind_preset', N'is_selected') IS NULL
BEGIN
    ALTER TABLE [dbo].[skill_bind_preset]
        ADD [is_selected] BIT NOT NULL
            CONSTRAINT [DF_skill_bind_preset_is_selected] DEFAULT (0);
END;
GO

IF NOT EXISTS (
    SELECT 1
    FROM sys.indexes
    WHERE [name] = N'UX_skill_bind_preset_account_selected'
      AND [object_id] = OBJECT_ID(N'[dbo].[skill_bind_preset]')
)
BEGIN
    CREATE UNIQUE NONCLUSTERED INDEX [UX_skill_bind_preset_account_selected]
        ON [dbo].[skill_bind_preset] ([account_id])
        WHERE [is_deleted] = 0 AND [is_selected] = 1;
END;
GO
