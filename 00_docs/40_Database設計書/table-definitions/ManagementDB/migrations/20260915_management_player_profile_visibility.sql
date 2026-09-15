USE [ManagementDB];
GO
SET XACT_ABORT ON;
BEGIN TRANSACTION;
DECLARE @lockResult INT;
EXEC @lockResult = sys.sp_getapplock
    @Resource = N'ManagementDB.schema_migration', @LockMode = N'Exclusive',
    @LockOwner = N'Transaction', @LockTimeout = 60000;
IF @lockResult < 0
    THROW 51000, 'ManagementDB migration lock unavailable.', 1;

IF OBJECT_ID(N'dbo.schema_migration', N'U') IS NULL
    THROW 51010, 'dbo.schema_migration is missing. Run ManagementDB init.sql first.', 1;
IF EXISTS (SELECT 1 FROM dbo.schema_migration WHERE migration_id = N'20260915_management_player_profile_visibility')
BEGIN
    COMMIT;
    RETURN;
END;

IF COL_LENGTH(N'dbo.player', N'is_profile_public') IS NULL
BEGIN
    ALTER TABLE dbo.player ADD is_profile_public BIT NOT NULL
        CONSTRAINT DF_management_player_is_profile_public DEFAULT (0) WITH VALUES;
END;

INSERT INTO dbo.schema_migration (migration_id) VALUES (N'20260915_management_player_profile_visibility');
COMMIT;
GO
