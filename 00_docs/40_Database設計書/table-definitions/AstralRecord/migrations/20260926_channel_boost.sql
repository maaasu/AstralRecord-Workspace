-- Channel boost state, idempotent activation ledger and cross-channel announcement log.
SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF OBJECT_ID(N'dbo.channel_boost', N'U') IS NULL
CREATE TABLE [dbo].[channel_boost] (
    [channel_id] NVARCHAR(64) NOT NULL CONSTRAINT [PK_channel_boost] PRIMARY KEY,
    [exp_multiplier] FLOAT NULL,
    [exp_expires_at] DATETIME2(3) NULL,
    [exp_operation_id] UNIQUEIDENTIFIER NULL,
    [exp_activator_name] NVARCHAR(100) NULL,
    [drop_multiplier] FLOAT NULL,
    [drop_expires_at] DATETIME2(3) NULL,
    [drop_operation_id] UNIQUEIDENTIFIER NULL,
    [drop_activator_name] NVARCHAR(100) NULL
);
IF OBJECT_ID(N'dbo.channel_boost_operation', N'U') IS NULL
CREATE TABLE [dbo].[channel_boost_operation] (
    [operation_id] UNIQUEIDENTIFIER NOT NULL CONSTRAINT [PK_channel_boost_operation] PRIMARY KEY,
    [account_id] UNIQUEIDENTIFIER NOT NULL,
    [channel_id] NVARCHAR(64) NOT NULL,
    [inventory_entry_id] UNIQUEIDENTIFIER NULL,
    [request_hash] NVARCHAR(64) NOT NULL,
    [status] NVARCHAR(16) NOT NULL,
    [reason] NVARCHAR(100) NULL,
    [boost_kind] NVARCHAR(16) NULL,
    [multiplier] FLOAT NULL,
    [expires_at] DATETIME2(3) NULL,
    [event_cursor] BIGINT NULL,
    [created_at] DATETIME2(3) NOT NULL
);
IF OBJECT_ID(N'dbo.channel_boost_event', N'U') IS NULL
CREATE TABLE [dbo].[channel_boost_event] (
    [event_cursor] BIGINT NOT NULL CONSTRAINT [PK_channel_boost_event] PRIMARY KEY,
    [operation_id] UNIQUEIDENTIFIER NOT NULL CONSTRAINT [UQ_channel_boost_event_operation] UNIQUE,
    [account_id] UNIQUEIDENTIFIER NOT NULL,
    [channel_id] NVARCHAR(64) NOT NULL,
    [account_name] NVARCHAR(100) NOT NULL,
    [vip_tier] NVARCHAR(16) NOT NULL,
    [boost_kind] NVARCHAR(16) NOT NULL,
    [multiplier] FLOAT NOT NULL,
    [expires_at] DATETIME2(3) NOT NULL,
    [created_at] DATETIME2(3) NOT NULL
);
IF OBJECT_ID(N'dbo.channel_boost_cursor', N'U') IS NULL
BEGIN
CREATE TABLE [dbo].[channel_boost_cursor] (
    [id] INT NOT NULL CONSTRAINT [PK_channel_boost_cursor] PRIMARY KEY,
    [last_event_cursor] BIGINT NOT NULL
);
INSERT INTO [dbo].[channel_boost_cursor] ([id], [last_event_cursor]) VALUES (1, 0);
END;
COMMIT;
GO
