-- API更新前にAstralRecordへ適用する。既存装備個体は未保護とする。
SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF COL_LENGTH(N'dbo.equipment_instance', N'is_protected') IS NULL
    ALTER TABLE dbo.equipment_instance ADD is_protected BIT NOT NULL
        CONSTRAINT DF_equipment_instance_is_protected DEFAULT (0) WITH VALUES;
COMMIT;
