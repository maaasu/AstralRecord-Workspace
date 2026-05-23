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
