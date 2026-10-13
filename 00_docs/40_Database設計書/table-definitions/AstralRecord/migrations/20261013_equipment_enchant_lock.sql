-- API/Plugin更新前にAstralRecordへ適用する。既存エンチャントは未固定とする。
SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF COL_LENGTH(N'dbo.equipment_instance_enchant', N'is_locked') IS NULL
    ALTER TABLE dbo.equipment_instance_enchant ADD is_locked BIT NOT NULL
        CONSTRAINT DF_equipment_instance_enchant_is_locked DEFAULT (0) WITH VALUES;
COMMIT;
