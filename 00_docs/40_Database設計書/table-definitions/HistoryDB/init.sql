-- ============================================================
-- HistoryDB initialization script
-- Generated from 00_docs/40_Database設計書/table-definitions
-- ============================================================

USE [master];
GO

IF DB_ID(N'HistoryDB') IS NULL
BEGIN
    CREATE DATABASE [HistoryDB];
END
GO

USE [HistoryDB];
GO

-- ============================================================
-- dbo.user_history
-- ============================================================

CREATE TABLE [dbo].[user_history] (
    [history_id]   BIGINT             NOT NULL IDENTITY(1,1),
    [user_uuid]    UNIQUEIDENTIFIER       NULL,
    [event_time]   DATETIME2(3)       NOT NULL,
    [event_type]   NVARCHAR(50)       NOT NULL,
    [source]       NVARCHAR(50)       NOT NULL CONSTRAINT [DF_user_history_source] DEFAULT (N'PLUGIN'),
    [message]      NVARCHAR(MAX)      NOT NULL,
    [payload_json] NVARCHAR(MAX)          NULL,
    [created_at]   DATETIME2(3)       NOT NULL CONSTRAINT [DF_user_history_created_at] DEFAULT (SYSUTCDATETIME()),

    CONSTRAINT [PK_user_history] PRIMARY KEY CLUSTERED ([history_id]),
    CONSTRAINT [CK_user_history_payload_json]
        CHECK ([payload_json] IS NULL OR ISJSON([payload_json]) = 1)
);
GO

CREATE NONCLUSTERED INDEX [IX_user_history_user_time]
    ON [dbo].[user_history] ([user_uuid], [event_time] DESC);
GO

CREATE NONCLUSTERED INDEX [IX_user_history_event_time]
    ON [dbo].[user_history] ([event_time] DESC);
GO

CREATE TABLE [dbo].[player_activity_batch] ([batch_id] UNIQUEIDENTIFIER NOT NULL, [received_at] DATETIME2(3) NOT NULL, CONSTRAINT [PK_player_activity_batch] PRIMARY KEY CLUSTERED ([batch_id]));
GO
CREATE TABLE [dbo].[player_ip_observation] ([event_id] UNIQUEIDENTIFIER NOT NULL, [observed_at] DATETIME2(3) NOT NULL, [global_ip] NVARCHAR(45) NOT NULL, [user_uuid] UNIQUEIDENTIFIER NOT NULL, [account_id] UNIQUEIDENTIFIER NOT NULL, [mcid] NVARCHAR(20) NOT NULL, [account_name] NVARCHAR(50) NOT NULL, CONSTRAINT [PK_player_ip_observation] PRIMARY KEY CLUSTERED ([event_id]));
CREATE INDEX [IX_player_ip_observation_ip_observed] ON [dbo].[player_ip_observation] ([global_ip], [observed_at] DESC);
GO
CREATE TABLE [dbo].[player_trade_activity] ([event_id] UNIQUEIDENTIFIER NOT NULL, [completed_at] DATETIME2(3) NOT NULL, [source_user_uuid] UNIQUEIDENTIFIER NOT NULL, [source_account_id] UNIQUEIDENTIFIER NOT NULL, [source_mcid] NVARCHAR(20) NOT NULL, [source_account_name] NVARCHAR(50) NOT NULL, [destination_user_uuid] UNIQUEIDENTIFIER NOT NULL, [destination_account_id] UNIQUEIDENTIFIER NOT NULL, [destination_mcid] NVARCHAR(20) NOT NULL, [destination_account_name] NVARCHAR(50) NOT NULL, [gold] BIGINT NOT NULL, CONSTRAINT [PK_player_trade_activity] PRIMARY KEY CLUSTERED ([event_id]), CONSTRAINT [CK_player_trade_activity_gold] CHECK ([gold] >= 0));
CREATE INDEX [IX_player_trade_activity_completed_accounts] ON [dbo].[player_trade_activity] ([completed_at] DESC, [source_account_id], [destination_account_id]);
CREATE TABLE [dbo].[player_trade_activity_item] ([event_id] UNIQUEIDENTIFIER NOT NULL, [line_number] INT NOT NULL, [item_id] NVARCHAR(100) NOT NULL, [item_name] NVARCHAR(200) NOT NULL, [quantity] BIGINT NOT NULL, CONSTRAINT [PK_player_trade_activity_item] PRIMARY KEY CLUSTERED ([event_id], [line_number]), CONSTRAINT [FK_player_trade_activity_item_event] FOREIGN KEY ([event_id]) REFERENCES [dbo].[player_trade_activity]([event_id]) ON DELETE CASCADE, CONSTRAINT [CK_player_trade_activity_item_quantity] CHECK ([quantity] >= 1));
GO
CREATE TABLE [dbo].[dungeon_clear_activity] ([event_id] UNIQUEIDENTIFIER NOT NULL, [dungeon_id] NVARCHAR(100) NOT NULL, [dungeon_name] NVARCHAR(200) NOT NULL, [started_at] DATETIME2(3) NOT NULL, [cleared_at] DATETIME2(3) NOT NULL, [duration_milliseconds] AS DATEDIFF_BIG(MILLISECOND, [started_at], [cleared_at]) PERSISTED NOT NULL, CONSTRAINT [PK_dungeon_clear_activity] PRIMARY KEY CLUSTERED ([event_id]), CONSTRAINT [CK_dungeon_clear_activity_time] CHECK ([cleared_at] >= [started_at]));
CREATE INDEX [IX_dungeon_clear_activity_cleared_dungeon] ON [dbo].[dungeon_clear_activity] ([cleared_at] DESC, [dungeon_id]);
CREATE TABLE [dbo].[dungeon_clear_participant] ([event_id] UNIQUEIDENTIFIER NOT NULL, [account_id] UNIQUEIDENTIFIER NOT NULL, [user_uuid] UNIQUEIDENTIFIER NOT NULL, [mcid] NVARCHAR(20) NOT NULL, [account_name] NVARCHAR(50) NOT NULL, [distance_meters] DECIMAL(18,3) NULL, [movement_sample_count] INT NOT NULL, CONSTRAINT [PK_dungeon_clear_participant] PRIMARY KEY CLUSTERED ([event_id], [account_id]), CONSTRAINT [FK_dungeon_clear_participant_event] FOREIGN KEY ([event_id]) REFERENCES [dbo].[dungeon_clear_activity]([event_id]) ON DELETE CASCADE, CONSTRAINT [CK_dungeon_clear_participant_distance] CHECK ([distance_meters] IS NULL OR [distance_meters] >= 0), CONSTRAINT [CK_dungeon_clear_participant_samples] CHECK ([movement_sample_count] >= 0));
CREATE INDEX [IX_dungeon_clear_participant_account_event] ON [dbo].[dungeon_clear_participant] ([account_id], [event_id]);
GO
CREATE TABLE [dbo].[boss_clear_activity] ([event_id] UNIQUEIDENTIFIER NOT NULL, [boss_id] NVARCHAR(100) NOT NULL, [boss_name] NVARCHAR(200) NOT NULL, [started_at] DATETIME2(3) NOT NULL, [cleared_at] DATETIME2(3) NOT NULL, [duration_milliseconds] AS DATEDIFF_BIG(MILLISECOND, [started_at], [cleared_at]) PERSISTED NOT NULL, CONSTRAINT [PK_boss_clear_activity] PRIMARY KEY CLUSTERED ([event_id]), CONSTRAINT [CK_boss_clear_activity_time] CHECK ([cleared_at] >= [started_at]));
CREATE INDEX [IX_boss_clear_activity_cleared_boss] ON [dbo].[boss_clear_activity] ([cleared_at] DESC, [boss_id]);
CREATE TABLE [dbo].[boss_clear_participant] ([event_id] UNIQUEIDENTIFIER NOT NULL, [account_id] UNIQUEIDENTIFIER NOT NULL, [user_uuid] UNIQUEIDENTIFIER NOT NULL, [mcid] NVARCHAR(20) NOT NULL, [account_name] NVARCHAR(50) NOT NULL, [damage_dealt] DECIMAL(18,3) NOT NULL, [death_count] INT NOT NULL, CONSTRAINT [PK_boss_clear_participant] PRIMARY KEY CLUSTERED ([event_id], [account_id]), CONSTRAINT [FK_boss_clear_participant_event] FOREIGN KEY ([event_id]) REFERENCES [dbo].[boss_clear_activity]([event_id]) ON DELETE CASCADE, CONSTRAINT [CK_boss_clear_participant_damage] CHECK ([damage_dealt] >= 0), CONSTRAINT [CK_boss_clear_participant_deaths] CHECK ([death_count] >= 0));
CREATE INDEX [IX_boss_clear_participant_account_event] ON [dbo].[boss_clear_participant] ([account_id], [event_id]);
GO
CREATE TABLE [dbo].[mob_damage_summary] ([event_id] UNIQUEIDENTIFIER NOT NULL, [mob_id] NVARCHAR(100) NOT NULL, [mob_name] NVARCHAR(200) NOT NULL, [window_started_at] DATETIME2(3) NOT NULL, [window_ended_at] DATETIME2(3) NOT NULL, [victim_user_uuid] UNIQUEIDENTIFIER NOT NULL, [victim_account_id] UNIQUEIDENTIFIER NOT NULL, [victim_mcid] NVARCHAR(20) NOT NULL, [victim_account_name] NVARCHAR(50) NOT NULL, [damage] DECIMAL(18,3) NOT NULL, [hit_count] INT NOT NULL, CONSTRAINT [PK_mob_damage_summary] PRIMARY KEY CLUSTERED ([event_id]), CONSTRAINT [CK_mob_damage_summary_time] CHECK ([window_ended_at] >= [window_started_at]), CONSTRAINT [CK_mob_damage_summary_damage] CHECK ([damage] >= 0), CONSTRAINT [CK_mob_damage_summary_hits] CHECK ([hit_count] >= 0));
CREATE INDEX [IX_mob_damage_summary_mob_window] ON [dbo].[mob_damage_summary] ([mob_id], [window_ended_at] DESC);
CREATE TABLE [dbo].[mob_player_death] ([event_id] UNIQUEIDENTIFIER NOT NULL, [occurred_at] DATETIME2(3) NOT NULL, [mob_id] NVARCHAR(100) NOT NULL, [mob_name] NVARCHAR(200) NOT NULL, [victim_user_uuid] UNIQUEIDENTIFIER NOT NULL, [victim_account_id] UNIQUEIDENTIFIER NOT NULL, [victim_mcid] NVARCHAR(20) NOT NULL, [victim_account_name] NVARCHAR(50) NOT NULL, CONSTRAINT [PK_mob_player_death] PRIMARY KEY CLUSTERED ([event_id]));
CREATE INDEX [IX_mob_player_death_mob_occurred] ON [dbo].[mob_player_death] ([mob_id], [occurred_at] DESC);
GO
