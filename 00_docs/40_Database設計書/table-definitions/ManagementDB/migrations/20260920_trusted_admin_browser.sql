USE [ManagementDB];
GO
SET ANSI_NULLS ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET QUOTED_IDENTIFIER ON;
SET NUMERIC_ROUNDABORT OFF;
GO
SET XACT_ABORT ON;
BEGIN TRANSACTION;
DECLARE @lockResult INT;
EXEC @lockResult = sys.sp_getapplock
    @Resource = N'ManagementDB.schema_migration', @LockMode = N'Exclusive',
    @LockOwner = N'Transaction', @LockTimeout = 60000;
IF @lockResult < 0 THROW 51000, 'ManagementDB migration lock unavailable.', 1;
IF OBJECT_ID(N'dbo.schema_migration', N'U') IS NULL THROW 51010, 'Run ManagementDB init.sql first.', 1;
IF EXISTS (SELECT 1 FROM dbo.schema_migration WHERE migration_id = N'20260920_trusted_admin_browser')
BEGIN
    COMMIT;
    RETURN;
END;
IF OBJECT_ID(N'dbo.web_trusted_browser', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.web_trusted_browser (
        trusted_browser_id UNIQUEIDENTIFIER NOT NULL CONSTRAINT PK_web_trusted_browser PRIMARY KEY,
        player_uuid UNIQUEIDENTIFIER NOT NULL,
        session_version UNIQUEIDENTIFIER NOT NULL,
        token_hash NVARCHAR(128) NOT NULL,
        created_at_utc DATETIME2(3) NOT NULL CONSTRAINT DF_web_trusted_browser_created DEFAULT SYSUTCDATETIME(),
        last_used_at_utc DATETIME2(3) NOT NULL CONSTRAINT DF_web_trusted_browser_last_used DEFAULT SYSUTCDATETIME(),
        revoked_at_utc DATETIME2(3) NULL,
        CONSTRAINT CK_web_trusted_browser_last_used CHECK (last_used_at_utc >= created_at_utc)
    );
    CREATE UNIQUE INDEX UX_web_trusted_browser_token_hash ON dbo.web_trusted_browser(token_hash);
    CREATE INDEX IX_web_trusted_browser_player_session ON dbo.web_trusted_browser(player_uuid, session_version);
END;
INSERT INTO dbo.schema_migration(migration_id) VALUES(N'20260920_trusted_admin_browser');
COMMIT;
GO
