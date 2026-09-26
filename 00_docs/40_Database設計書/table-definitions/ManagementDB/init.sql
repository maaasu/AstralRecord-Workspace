-- 長期保持する運営管理DB。ゲームDBの初期化・リセットから独立する。
-- 再実行しても既存データを削除しない。既存スキーマ変更は個別migrationで行う。
USE [master];
GO
IF DB_ID(N'ManagementDB') IS NULL
    CREATE DATABASE [ManagementDB];
GO
USE [ManagementDB];
GO
-- Filtered indexの作成と更新に必要な接続設定を呼出元に依存させない。
SET ANSI_NULLS ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET QUOTED_IDENTIFIER ON;
SET NUMERIC_ROUNDABORT OFF;
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
GO

SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF OBJECT_ID(N'dbo.donation_ledger', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.donation_ledger (
        user_uuid UNIQUEIDENTIFIER NOT NULL CONSTRAINT PK_donation_ledger PRIMARY KEY,
        total_approved_amount BIGINT NOT NULL CHECK (total_approved_amount >= 0),
        revision INT NOT NULL
    );
END;
IF OBJECT_ID(N'dbo.donation_request', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.donation_request (
        id UNIQUEIDENTIFIER NOT NULL CONSTRAINT PK_donation_request PRIMARY KEY,
        user_uuid UNIQUEIDENTIFIER NOT NULL,
        mcid NVARCHAR(100) NOT NULL,
        declared_amount INT NOT NULL CHECK (declared_amount BETWEEN 500 AND 1000000),
        approved_amount INT NULL CHECK (approved_amount BETWEEN 1 AND 1000000),
        approved_through_amount BIGINT NULL,
        status NVARCHAR(20) NOT NULL CHECK (status IN ('Pending','Reviewing','Approved','Rejected','Cancelled')),
        protected_entries NVARCHAR(MAX) NOT NULL,
        terms_version NVARCHAR(32) NOT NULL,
        discord_user_id NVARCHAR(32) NOT NULL,
        discord_name NVARCHAR(128) NOT NULL,
        reason NVARCHAR(1000) NULL,
        reviewer_uuid UNIQUEIDENTIFIER NULL,
        created_at_utc DATETIME2(7) NOT NULL,
        review_started_at_utc DATETIME2(7) NULL,
        decided_at_utc DATETIME2(7) NULL,
        CONSTRAINT CK_donation_approved CHECK ((status='Approved' AND approved_amount IS NOT NULL) OR (status<>'Approved' AND approved_amount IS NULL))
    );
    CREATE INDEX IX_donation_request_user ON dbo.donation_request(user_uuid,created_at_utc);
    CREATE INDEX IX_donation_request_status ON dbo.donation_request(status);
END;
IF OBJECT_ID(N'dbo.donation_entry_fingerprint', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.donation_entry_fingerprint (
        fingerprint NVARCHAR(64) NOT NULL CONSTRAINT PK_donation_entry_fingerprint PRIMARY KEY,
        request_id UNIQUEIDENTIFIER NOT NULL
    );
    CREATE INDEX IX_donation_fingerprint_request ON dbo.donation_entry_fingerprint(request_id);
END;
IF OBJECT_ID(N'dbo.donation_grant', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.donation_grant (
        id UNIQUEIDENTIFIER NOT NULL CONSTRAINT PK_donation_grant PRIMARY KEY,
        user_uuid UNIQUEIDENTIFIER NOT NULL,
        account_uuid UNIQUEIDENTIFIER NOT NULL,
        through_amount BIGINT NOT NULL CHECK (through_amount > 0),
        amount INT NOT NULL CHECK (amount BETWEEN 1 AND 1000000),
        message NVARCHAR(2000) NOT NULL,
        created_at_utc DATETIME2(7) NOT NULL,
        delivered_at_utc DATETIME2(7) NULL
    );
    CREATE UNIQUE INDEX UX_donation_grant_account_total ON dbo.donation_grant(account_uuid,through_amount);
    CREATE INDEX IX_donation_grant_user_delivery ON dbo.donation_grant(user_uuid,delivered_at_utc);
END;
IF OBJECT_ID(N'dbo.donation_notification', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.donation_notification (
        id UNIQUEIDENTIFIER NOT NULL CONSTRAINT PK_donation_notification PRIMARY KEY,
        user_uuid UNIQUEIDENTIFIER NOT NULL,
        kind NVARCHAR(32) NOT NULL,
        amount INT NOT NULL,
        message NVARCHAR(2000) NOT NULL,
        created_at_utc DATETIME2(7) NOT NULL,
        acknowledged_at_utc DATETIME2(7) NULL
    );
    CREATE INDEX IX_donation_notification_user ON dbo.donation_notification(user_uuid,acknowledged_at_utc);
END;
IF OBJECT_ID(N'dbo.donation_discord_link', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.donation_discord_link (
        user_uuid UNIQUEIDENTIFIER NOT NULL CONSTRAINT PK_donation_discord_link PRIMARY KEY,
        discord_user_id NVARCHAR(32) NOT NULL,
        discord_name NVARCHAR(128) NOT NULL,
        protected_access_token NVARCHAR(MAX) NOT NULL,
        protected_refresh_token NVARCHAR(MAX) NOT NULL,
        is_guild_member BIT NOT NULL,
        verified_at_utc DATETIME2(7) NOT NULL,
        revision INT NOT NULL
    );
    CREATE UNIQUE INDEX UX_donation_discord_user ON dbo.donation_discord_link(discord_user_id);
END;
IF NOT EXISTS (SELECT 1 FROM dbo.schema_migration WHERE migration_id=N'20260926_donations')
    INSERT dbo.schema_migration(migration_id) VALUES (N'20260926_donations');
COMMIT;
GO
