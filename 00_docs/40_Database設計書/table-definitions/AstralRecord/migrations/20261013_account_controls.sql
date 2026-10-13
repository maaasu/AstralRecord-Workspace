-- API/Plugin更新前にAstralRecordへ適用する。vanilla player-dataは移行しない。
SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF COL_LENGTH(N'dbo.account', N'selected_hotbar_slot') IS NULL
    ALTER TABLE dbo.account ADD selected_hotbar_slot INT NOT NULL
        CONSTRAINT DF_account_selected_hotbar_slot DEFAULT (0) WITH VALUES;
IF COL_LENGTH(N'dbo.account', N'view_yaw') IS NULL
    ALTER TABLE dbo.account ADD view_yaw REAL NOT NULL
        CONSTRAINT DF_account_view_yaw DEFAULT (0) WITH VALUES;
IF COL_LENGTH(N'dbo.account', N'view_pitch') IS NULL
    ALTER TABLE dbo.account ADD view_pitch REAL NOT NULL
        CONSTRAINT DF_account_view_pitch DEFAULT (0) WITH VALUES;
IF COL_LENGTH(N'dbo.account', N'controls_version') IS NULL
    ALTER TABLE dbo.account ADD controls_version INT NOT NULL
        CONSTRAINT DF_account_controls_version DEFAULT (0) WITH VALUES;
IF NOT EXISTS (SELECT 1 FROM sys.check_constraints
               WHERE parent_object_id = OBJECT_ID(N'dbo.account') AND name = N'CK_account_controls')
    EXEC sys.sp_executesql N'ALTER TABLE dbo.account WITH CHECK ADD CONSTRAINT CK_account_controls CHECK (selected_hotbar_slot BETWEEN 0 AND 8 AND view_yaw >= -180 AND view_yaw < 180 AND view_pitch BETWEEN -90 AND 90 AND controls_version >= 0)';
COMMIT;
