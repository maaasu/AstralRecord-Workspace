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
IF EXISTS (SELECT 1 FROM dbo.schema_migration WHERE migration_id = N'20260917_web_credentials')
BEGIN
    COMMIT;
    RETURN;
END;
IF OBJECT_ID(N'dbo.web_credential', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.web_credential (
        player_uuid UNIQUEIDENTIFIER NOT NULL CONSTRAINT PK_web_credential PRIMARY KEY,
        login_id NVARCHAR(64) NULL,
        password_hash NVARCHAR(512) NULL,
        enabled BIT NOT NULL CONSTRAINT DF_web_credential_enabled DEFAULT (0),
        session_version UNIQUEIDENTIFIER NOT NULL,
        created_at_utc DATETIME2(3) NOT NULL CONSTRAINT DF_web_credential_created DEFAULT SYSUTCDATETIME(),
        updated_at_utc DATETIME2(3) NOT NULL CONSTRAINT DF_web_credential_updated DEFAULT SYSUTCDATETIME(),
        CONSTRAINT CK_web_credential_enabled_hash CHECK ((enabled = 0 AND password_hash IS NULL) OR (enabled = 1 AND login_id IS NOT NULL AND password_hash IS NOT NULL)),
        CONSTRAINT CK_web_credential_updated CHECK (updated_at_utc >= created_at_utc)
    );
    CREATE UNIQUE INDEX UX_web_credential_login_id ON dbo.web_credential(login_id) WHERE login_id IS NOT NULL;
END;
IF OBJECT_ID(N'dbo.web_credential_login_attempt', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.web_credential_login_attempt (
        login_id NVARCHAR(64) NOT NULL CONSTRAINT PK_web_credential_login_attempt PRIMARY KEY,
        failed_attempts INT NOT NULL,
        window_started_at_utc DATETIME2(3) NOT NULL,
        locked_until_utc DATETIME2(3) NULL,
        revision INT NOT NULL,
        CONSTRAINT CK_web_credential_login_attempt_count CHECK (failed_attempts > 0),
        CONSTRAINT CK_web_credential_login_attempt_revision CHECK (revision > 0)
    );
END;
INSERT INTO dbo.schema_migration(migration_id) VALUES(N'20260917_web_credentials');
COMMIT;
GO
