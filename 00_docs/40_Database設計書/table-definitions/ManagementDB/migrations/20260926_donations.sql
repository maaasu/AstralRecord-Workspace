-- 寄付履歴はゲームDBのリセット対象外。API/Web配置前にManagementDBへ適用する。
USE [ManagementDB];
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
