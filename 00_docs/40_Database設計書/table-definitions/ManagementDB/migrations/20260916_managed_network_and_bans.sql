USE [ManagementDB];
GO
SET XACT_ABORT ON;
BEGIN TRANSACTION;
DECLARE @lockResult INT;
EXEC @lockResult = sys.sp_getapplock
    @Resource = N'ManagementDB.schema_migration', @LockMode = N'Exclusive',
    @LockOwner = N'Transaction', @LockTimeout = 60000;
IF @lockResult < 0 THROW 51000, 'ManagementDB migration lock unavailable.', 1;
IF OBJECT_ID(N'dbo.schema_migration', N'U') IS NULL THROW 51010, 'Run ManagementDB init.sql first.', 1;
IF EXISTS (SELECT 1 FROM dbo.schema_migration WHERE migration_id = N'20260916_managed_network_and_bans')
BEGIN
    COMMIT;
    RETURN;
END;
IF OBJECT_ID(N'dbo.network_settings', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.network_settings (
        id INT NOT NULL CONSTRAINT PK_network_settings PRIMARY KEY,
        revision INT NOT NULL,
        settings_json NVARCHAR(MAX) NOT NULL,
        updated_at_utc DATETIME2(3) NOT NULL,
        updated_by UNIQUEIDENTIFIER NULL,
        CONSTRAINT CK_network_settings_singleton CHECK (id = 1),
        CONSTRAINT CK_network_settings_revision CHECK (revision > 0),
        CONSTRAINT CK_network_settings_json CHECK (ISJSON(settings_json) = 1)
    );
END;
IF OBJECT_ID(N'dbo.network_ban', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.network_ban (
        user_uuid UNIQUEIDENTIFIER NOT NULL CONSTRAINT PK_network_ban PRIMARY KEY,
        revision INT NOT NULL,
        is_banned BIT NOT NULL,
        expires_at_utc DATETIME2(3) NULL,
        reason NVARCHAR(500) NULL,
        updated_at_utc DATETIME2(3) NOT NULL,
        updated_by UNIQUEIDENTIFIER NOT NULL,
        CONSTRAINT CK_network_ban_revision CHECK (revision > 0),
        CONSTRAINT CK_network_ban_clear CHECK (is_banned = 1 OR expires_at_utc IS NULL)
    );
    CREATE INDEX IX_network_ban_active ON dbo.network_ban(is_banned, expires_at_utc);
END;
IF OBJECT_ID(N'dbo.network_management_audit', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.network_management_audit (
        audit_id UNIQUEIDENTIFIER NOT NULL CONSTRAINT PK_network_management_audit PRIMARY KEY,
        operation NVARCHAR(50) NOT NULL,
        actor_uuid UNIQUEIDENTIFIER NULL,
        target_uuid UNIQUEIDENTIFIER NULL,
        before_json NVARCHAR(MAX) NULL,
        after_json NVARCHAR(MAX) NOT NULL,
        occurred_at_utc DATETIME2(3) NOT NULL,
        CONSTRAINT CK_network_management_audit_before CHECK (before_json IS NULL OR ISJSON(before_json) = 1),
        CONSTRAINT CK_network_management_audit_after CHECK (ISJSON(after_json) = 1)
    );
    CREATE INDEX IX_network_management_audit_time ON dbo.network_management_audit(occurred_at_utc);
END;
INSERT INTO dbo.schema_migration(migration_id) VALUES(N'20260916_managed_network_and_bans');
COMMIT;
GO
