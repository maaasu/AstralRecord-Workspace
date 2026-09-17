-- 長期保持する運営管理DB。ゲームDBの初期化・リセットから独立する。
-- 再実行しても既存データを削除しない。既存スキーマ変更は個別migrationで行う。
USE [master];
GO
IF DB_ID(N'ManagementDB') IS NULL
    CREATE DATABASE [ManagementDB];
GO
USE [ManagementDB];
GO
IF OBJECT_ID(N'dbo.player', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.player (
        player_uuid UNIQUEIDENTIFIER NOT NULL CONSTRAINT PK_management_player PRIMARY KEY,
        mcid NVARCHAR(100) NOT NULL,
        web_admin BIT NOT NULL CONSTRAINT DF_management_player_web_admin DEFAULT (0),
        is_profile_public BIT NOT NULL CONSTRAINT DF_management_player_is_profile_public DEFAULT (0),
        created_at DATETIME2(3) NOT NULL CONSTRAINT DF_management_player_created_at DEFAULT SYSUTCDATETIME(),
        updated_at DATETIME2(3) NOT NULL CONSTRAINT DF_management_player_updated_at DEFAULT SYSUTCDATETIME(),
        first_web_login_at DATETIME2(3) NULL,
        last_web_login_at DATETIME2(3) NULL,
        CONSTRAINT CK_management_player_mcid CHECK (LEN(LTRIM(RTRIM(mcid))) > 0),
        CONSTRAINT CK_management_player_updated CHECK (updated_at >= created_at),
        CONSTRAINT CK_management_player_web_login CHECK (
            (first_web_login_at IS NULL AND last_web_login_at IS NULL) OR
            (first_web_login_at IS NOT NULL AND last_web_login_at IS NOT NULL AND last_web_login_at >= first_web_login_at))
    );
END;
GO
IF OBJECT_ID(N'dbo.schema_migration', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.schema_migration (
        migration_id NVARCHAR(150) NOT NULL CONSTRAINT PK_management_schema_migration PRIMARY KEY,
        applied_at DATETIME2(3) NOT NULL CONSTRAINT DF_management_schema_migration_applied DEFAULT SYSUTCDATETIME()
    );
END;
GO

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
GO
