-- 既存の定義だけを導入前の基準(0)として記録する。プレイヤーデータは変更しない。
IF COL_LENGTH(N'dbo.skilltree_definition_generation', N'patch_version') IS NULL
BEGIN
    ALTER TABLE [dbo].[skilltree_definition_generation] ADD [patch_version] BIGINT NULL;
    -- 同一batchで追加した列を静的に参照しない。
    EXEC(N'UPDATE [dbo].[skilltree_definition_generation] SET [patch_version] = 0;');
END;
GO
IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = N'CK_skilltree_definition_generation_patch' AND parent_object_id = OBJECT_ID(N'dbo.skilltree_definition_generation'))
    ALTER TABLE [dbo].[skilltree_definition_generation] WITH CHECK ADD CONSTRAINT [CK_skilltree_definition_generation_patch] CHECK ([patch_version] >= 0);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'UX_skilltree_definition_generation_patch' AND object_id = OBJECT_ID(N'dbo.skilltree_definition_generation'))
    CREATE UNIQUE INDEX [UX_skilltree_definition_generation_patch] ON [dbo].[skilltree_definition_generation] ([patch_version]) WHERE [patch_version] > 0;
GO

IF COL_LENGTH(N'dbo.skilltree_definition_generation', N'introduced_after_patch_version') IS NULL
    ALTER TABLE [dbo].[skilltree_definition_generation] ADD [introduced_after_patch_version] BIGINT NOT NULL CONSTRAINT [DF_skilltree_definition_generation_introduced] DEFAULT(0) WITH VALUES;
GO
IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = N'CK_skilltree_definition_generation_introduced' AND parent_object_id = OBJECT_ID(N'dbo.skilltree_definition_generation'))
    ALTER TABLE [dbo].[skilltree_definition_generation] WITH CHECK ADD CONSTRAINT [CK_skilltree_definition_generation_introduced] CHECK ([introduced_after_patch_version] >= 0);
GO
