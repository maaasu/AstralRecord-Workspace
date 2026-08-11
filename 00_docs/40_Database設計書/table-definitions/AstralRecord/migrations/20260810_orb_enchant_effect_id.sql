IF EXISTS (
    SELECT 1
    FROM sys.key_constraints
    WHERE [name] = N'UQ_equipment_instance_enchant_pool_index'
)
BEGIN
    ALTER TABLE [dbo].[equipment_instance_enchant]
        DROP CONSTRAINT [UQ_equipment_instance_enchant_pool_index];
END;
GO

IF COL_LENGTH(N'dbo.equipment_instance_enchant', N'enchant_master_id') IS NULL
BEGIN
    ALTER TABLE [dbo].[equipment_instance_enchant]
        ADD [enchant_master_id] NVARCHAR(100) NULL;
END;
GO

IF COL_LENGTH(N'dbo.equipment_instance_enchant', N'effect_id') IS NULL
BEGIN
    ALTER TABLE [dbo].[equipment_instance_enchant]
        ADD [effect_id] NVARCHAR(100) NULL;
END;
GO

-- 旧 pool_index は共通エンチャントマスタの effect_id へ一意に対応付けできない。
-- 既存個体データは消去せず、付与済み数値を legacy 由来として保持する。
-- enchant_id 由来の effect_id にすることで、既存行同士の UNIQUE 衝突も防ぐ。
UPDATE [dbo].[equipment_instance_enchant]
SET [enchant_master_id] = COALESCE([enchant_master_id], N'legacy'),
    [effect_id] = COALESCE(
        [effect_id],
        N'legacy_' + CONVERT(NVARCHAR(36), [enchant_id])
    )
WHERE [enchant_master_id] IS NULL
   OR [effect_id] IS NULL;
GO

ALTER TABLE [dbo].[equipment_instance_enchant]
    ALTER COLUMN [enchant_master_id] NVARCHAR(100) NOT NULL;
ALTER TABLE [dbo].[equipment_instance_enchant]
    ALTER COLUMN [effect_id] NVARCHAR(100) NOT NULL;
GO

IF COL_LENGTH(N'dbo.equipment_instance_enchant', N'pool_index') IS NOT NULL
BEGIN
    ALTER TABLE [dbo].[equipment_instance_enchant]
        DROP COLUMN [pool_index];
END;
GO

IF NOT EXISTS (
    SELECT 1
    FROM sys.key_constraints
    WHERE [name] = N'UQ_equipment_instance_enchant_effect_id'
)
BEGIN
    ALTER TABLE [dbo].[equipment_instance_enchant]
        ADD CONSTRAINT [UQ_equipment_instance_enchant_effect_id]
            UNIQUE ([equipment_instance_id], [effect_id]);
END;
GO
