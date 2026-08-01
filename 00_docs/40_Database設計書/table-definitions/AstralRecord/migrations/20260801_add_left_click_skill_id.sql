USE [AstralRecord];
GO

IF OBJECT_ID(N'[dbo].[skill_bind_preset]', N'U') IS NULL
    THROW 50000, N'dbo.skill_bind_preset が存在しません。', 1;
GO

IF COL_LENGTH(N'dbo.skill_bind_preset', N'left_click_skill_id') IS NULL
BEGIN
    ALTER TABLE [dbo].[skill_bind_preset]
        ADD [left_click_skill_id] NVARCHAR(128) NULL;

    ALTER TABLE [dbo].[skill_bind_preset]
        ADD CONSTRAINT [DF_skill_bind_preset_left_click_skill_id]
        DEFAULT (N'__weapon_normal_attack__') FOR [left_click_skill_id];
END;
GO

UPDATE [dbo].[skill_bind_preset]
SET [left_click_skill_id] = N'__weapon_normal_attack__'
WHERE [left_click_skill_id] IS NULL;
GO
