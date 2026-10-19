-- 防衛戦の設定、開催、参加者と確定報酬をゲームDBのリセットから独立して保持する。
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
IF EXISTS (SELECT 1 FROM dbo.schema_migration WHERE migration_id = N'20261019_defense')
BEGIN
    COMMIT;
    RETURN;
END;

CREATE TABLE dbo.defense_channel (
    server_id NVARCHAR(64) NOT NULL CONSTRAINT PK_defense_channel PRIMARY KEY,
    revision INT NOT NULL,
    daily_enabled BIT NOT NULL,
    daily_start_time_jst NVARCHAR(5) NULL,
    configuration_json NVARCHAR(MAX) NOT NULL,
    updated_at_utc DATETIME2(3) NOT NULL,
    updated_by UNIQUEIDENTIFIER NOT NULL,
    last_runtime_poll_at_utc DATETIME2(3) NULL,
    last_runtime_instance_id UNIQUEIDENTIFIER NULL,
    registration_status VARCHAR(16) NULL,
    registered_count INT NULL,
    registration_reported_at_utc DATETIME2(3) NULL,
    CONSTRAINT CK_defense_channel_revision CHECK (revision > 0),
    CONSTRAINT CK_defense_channel_json CHECK (ISJSON(configuration_json) = 1),
    CONSTRAINT CK_defense_channel_daily CHECK (daily_enabled = 0 OR daily_start_time_jst IS NOT NULL),
    CONSTRAINT CK_defense_channel_registration CHECK (registration_status IS NULL OR registration_status IN ('CLOSED','OPEN','PREPARING')),
    CONSTRAINT CK_defense_channel_registered_count CHECK (registered_count IS NULL OR registered_count BETWEEN 0 AND 30)
);
CREATE TABLE dbo.defense_run (
    run_id UNIQUEIDENTIFIER NOT NULL CONSTRAINT PK_defense_run PRIMARY KEY,
    server_id NVARCHAR(64) NOT NULL,
    source VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    operation_id UNIQUEIDENTIFIER NOT NULL,
    scheduled_at_utc DATETIME2(3) NULL,
    scheduled_date_jst DATE NULL,
    created_at_utc DATETIME2(3) NOT NULL,
    started_at_utc DATETIME2(3) NULL,
    ended_at_utc DATETIME2(3) NULL,
    last_heartbeat_at_utc DATETIME2(3) NULL,
    instance_id UNIQUEIDENTIFIER NULL,
    configuration_json NVARCHAR(MAX) NOT NULL,
    defense_level INT NULL,
    participant_count INT NULL,
    reason NVARCHAR(500) NULL,
    CONSTRAINT FK_defense_run_channel FOREIGN KEY (server_id) REFERENCES dbo.defense_channel(server_id),
    CONSTRAINT CK_defense_run_source CHECK (source IN ('MANUAL','DAILY')),
    CONSTRAINT CK_defense_run_status CHECK (status IN ('PENDING','RUNNING','STOP_REQUESTED','ENDED','ABORTED','SKIPPED')),
    CONSTRAINT CK_defense_run_json CHECK (ISJSON(configuration_json) = 1),
    CONSTRAINT CK_defense_run_participants CHECK (participant_count IS NULL OR participant_count BETWEEN 1 AND 30)
);
CREATE UNIQUE INDEX UX_defense_run_operation ON dbo.defense_run(operation_id);
CREATE UNIQUE INDEX UX_defense_run_daily ON dbo.defense_run(server_id,scheduled_date_jst) WHERE source='DAILY';
CREATE UNIQUE INDEX UX_defense_run_active ON dbo.defense_run(server_id)
    WHERE status IN ('PENDING','RUNNING','STOP_REQUESTED');
CREATE INDEX IX_defense_run_history ON dbo.defense_run(server_id,created_at_utc DESC);

CREATE TABLE dbo.defense_participant (
    run_id UNIQUEIDENTIFIER NOT NULL,
    account_id UNIQUEIDENTIFIER NOT NULL,
    user_uuid UNIQUEIDENTIFIER NOT NULL,
    active_seconds INT NOT NULL CONSTRAINT DF_defense_participant_active DEFAULT (0),
    is_afk BIT NOT NULL CONSTRAINT DF_defense_participant_afk DEFAULT (0),
    eliminated BIT NOT NULL CONSTRAINT DF_defense_participant_eliminated DEFAULT (0),
    CONSTRAINT PK_defense_participant PRIMARY KEY (run_id,account_id),
    CONSTRAINT FK_defense_participant_run FOREIGN KEY (run_id) REFERENCES dbo.defense_run(run_id),
    CONSTRAINT CK_defense_participant_active CHECK (active_seconds >= 0)
);
CREATE UNIQUE INDEX UX_defense_participant_user ON dbo.defense_participant(run_id,user_uuid);

CREATE TABLE dbo.defense_reward (
    run_id UNIQUEIDENTIFIER NOT NULL,
    account_id UNIQUEIDENTIFIER NOT NULL,
    defense_level INT NOT NULL,
    user_uuid UNIQUEIDENTIFIER NOT NULL,
    orb_item_id NVARCHAR(150) NOT NULL,
    orb_quantity INT NOT NULL,
    mail_id NVARCHAR(200) NOT NULL,
    created_at_utc DATETIME2(3) NOT NULL,
    delivered_at_utc DATETIME2(3) NULL,
    CONSTRAINT PK_defense_reward PRIMARY KEY (run_id,account_id,defense_level),
    CONSTRAINT FK_defense_reward_participant FOREIGN KEY (run_id,account_id)
        REFERENCES dbo.defense_participant(run_id,account_id),
    CONSTRAINT CK_defense_reward_level CHECK (defense_level >= 1),
    CONSTRAINT CK_defense_reward_quantity CHECK (orb_quantity >= 1)
);
CREATE UNIQUE INDEX UX_defense_reward_mail ON dbo.defense_reward(mail_id);
CREATE INDEX IX_defense_reward_pending ON dbo.defense_reward(created_at_utc) WHERE delivered_at_utc IS NULL;

CREATE TABLE dbo.defense_report (
    operation_id UNIQUEIDENTIFIER NOT NULL CONSTRAINT PK_defense_report PRIMARY KEY,
    run_id UNIQUEIDENTIFIER NOT NULL,
    kind VARCHAR(16) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    created_at_utc DATETIME2(3) NOT NULL,
    CONSTRAINT FK_defense_report_run FOREIGN KEY (run_id) REFERENCES dbo.defense_run(run_id),
    CONSTRAINT CK_defense_report_kind CHECK (kind IN ('STOP','STARTED','HEARTBEAT','ENDED','ABORTED','REWARD')),
    CONSTRAINT CK_defense_report_hash CHECK (LEN(request_hash) = 64)
);
INSERT dbo.schema_migration(migration_id) VALUES(N'20261019_defense');
COMMIT;
GO
