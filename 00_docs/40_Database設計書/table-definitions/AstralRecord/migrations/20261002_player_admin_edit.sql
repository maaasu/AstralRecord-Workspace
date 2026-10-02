-- 管理者によるプレイヤー編集の排他セッション、サーバー退避確認、冪等操作台帳。
SET XACT_ABORT ON;
BEGIN TRANSACTION;

IF OBJECT_ID(N'dbo.player_admin_server_runtime', N'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[player_admin_server_runtime] (
        [server_id] NVARCHAR(64) NOT NULL,
        [server_session_id] UNIQUEIDENTIFIER NOT NULL,
        [role] VARCHAR(16) NOT NULL,
        [enabled] BIT NOT NULL,
        [item_catalog_hash] CHAR(64) NULL,
        [class_catalog_hash] CHAR(64) NULL,
        [registered_at_utc] DATETIME2(3) NOT NULL,
        [last_seen_utc] DATETIME2(3) NOT NULL,
        CONSTRAINT [PK_player_admin_server_runtime] PRIMARY KEY CLUSTERED ([server_id]),
        CONSTRAINT [CK_player_admin_server_runtime_role] CHECK ([role] IN ('PROXY', 'LOBBY', 'RPG')),
        CONSTRAINT [CK_player_admin_server_runtime_seen] CHECK ([last_seen_utc] >= [registered_at_utc]),
        CONSTRAINT [CK_player_admin_server_runtime_catalog_hash] CHECK ([item_catalog_hash] IS NULL OR LEN([item_catalog_hash]) = 64),
        CONSTRAINT [CK_player_admin_server_runtime_class_catalog_hash] CHECK ([class_catalog_hash] IS NULL OR LEN([class_catalog_hash]) = 64)
    );
END;

IF OBJECT_ID(N'dbo.player_admin_edit_session', N'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[player_admin_edit_session] (
        [edit_session_id] UNIQUEIDENTIFIER NOT NULL,
        [user_uuid] UNIQUEIDENTIFIER NOT NULL,
        [account_id] UNIQUEIDENTIFIER NOT NULL,
        [actor_user_uuid] UNIQUEIDENTIFIER NOT NULL,
        [reason] NVARCHAR(500) NOT NULL,
        [status] VARCHAR(32) NOT NULL,
        [revision] BIGINT NOT NULL,
        [expected_server_count] INT NOT NULL,
        [item_catalog_hash] CHAR(64) NULL,
        [class_catalog_hash] CHAR(64) NULL,
        [created_at_utc] DATETIME2(3) NOT NULL,
        [updated_at_utc] DATETIME2(3) NOT NULL,
        [expires_at_utc] DATETIME2(3) NOT NULL,
        [completed_at_utc] DATETIME2(3) NULL,
        CONSTRAINT [PK_player_admin_edit_session] PRIMARY KEY CLUSTERED ([edit_session_id]),
        CONSTRAINT [FK_player_admin_edit_session_user] FOREIGN KEY ([user_uuid]) REFERENCES [dbo].[user] ([uuid]),
        CONSTRAINT [FK_player_admin_edit_session_account] FOREIGN KEY ([account_id]) REFERENCES [dbo].[account] ([uuid]),
        CONSTRAINT [CK_player_admin_edit_session_status] CHECK ([status] IN ('DRAINING', 'READY', 'APPLYING', 'COMPLETED', 'CANCELED', 'RECOVERY_REQUIRED')),
        CONSTRAINT [CK_player_admin_edit_session_revision] CHECK ([revision] >= 1),
        CONSTRAINT [CK_player_admin_edit_session_expected_servers] CHECK ([expected_server_count] >= 0),
        CONSTRAINT [CK_player_admin_edit_session_catalog_hash] CHECK ([item_catalog_hash] IS NULL OR LEN([item_catalog_hash]) = 64),
        CONSTRAINT [CK_player_admin_edit_session_class_catalog_hash] CHECK ([class_catalog_hash] IS NULL OR LEN([class_catalog_hash]) = 64),
        CONSTRAINT [CK_player_admin_edit_session_times] CHECK ([updated_at_utc] >= [created_at_utc] AND [expires_at_utc] > [created_at_utc] AND ([completed_at_utc] IS NULL OR [completed_at_utc] >= [created_at_utc]))
    );
END;

IF OBJECT_ID(N'dbo.player_admin_edit_drain', N'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[player_admin_edit_drain] (
        [edit_session_id] UNIQUEIDENTIFIER NOT NULL,
        [server_id] NVARCHAR(64) NOT NULL,
        [server_session_id] UNIQUEIDENTIFIER NOT NULL,
        [saved] BIT NOT NULL,
        [offline] BIT NOT NULL,
        [ack_id] UNIQUEIDENTIFIER NULL,
        [acknowledged_at_utc] DATETIME2(3) NULL,
        CONSTRAINT [PK_player_admin_edit_drain] PRIMARY KEY CLUSTERED ([edit_session_id], [server_id]),
        CONSTRAINT [FK_player_admin_edit_drain_session] FOREIGN KEY ([edit_session_id]) REFERENCES [dbo].[player_admin_edit_session] ([edit_session_id]),
        CONSTRAINT [CK_player_admin_edit_drain_ack] CHECK (([ack_id] IS NULL AND [acknowledged_at_utc] IS NULL) OR ([ack_id] IS NOT NULL AND [acknowledged_at_utc] IS NOT NULL))
    );
END;

IF OBJECT_ID(N'dbo.player_admin_edit_operation', N'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[player_admin_edit_operation] (
        [operation_id] UNIQUEIDENTIFIER NOT NULL,
        [edit_session_id] UNIQUEIDENTIFIER NOT NULL,
        [request_hash] CHAR(64) NOT NULL,
        [action] VARCHAR(16) NOT NULL,
        [response_json] NVARCHAR(MAX) NOT NULL,
        [before_json] NVARCHAR(MAX) NOT NULL,
        [after_json] NVARCHAR(MAX) NOT NULL,
        [created_at_utc] DATETIME2(3) NOT NULL,
        [audit_projected_at_utc] DATETIME2(3) NULL,
        CONSTRAINT [PK_player_admin_edit_operation] PRIMARY KEY CLUSTERED ([operation_id]),
        CONSTRAINT [FK_player_admin_edit_operation_session] FOREIGN KEY ([edit_session_id]) REFERENCES [dbo].[player_admin_edit_session] ([edit_session_id]),
        CONSTRAINT [CK_player_admin_edit_operation_hash] CHECK (LEN([request_hash]) = 64),
        CONSTRAINT [CK_player_admin_edit_operation_action] CHECK ([action] IN ('APPLY', 'CANCEL')),
        CONSTRAINT [CK_player_admin_edit_operation_json] CHECK (ISJSON([response_json]) = 1 AND ISJSON([before_json]) = 1 AND ISJSON([after_json]) = 1),
        CONSTRAINT [CK_player_admin_edit_operation_projection_time] CHECK ([audit_projected_at_utc] IS NULL OR [audit_projected_at_utc] >= [created_at_utc])
    );
END;

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE [object_id] = OBJECT_ID(N'dbo.player_admin_edit_session') AND [name] = N'UX_player_admin_edit_session_active_user')
    CREATE UNIQUE INDEX [UX_player_admin_edit_session_active_user] ON [dbo].[player_admin_edit_session] ([user_uuid]) WHERE [status] IN ('DRAINING', 'READY', 'APPLYING', 'RECOVERY_REQUIRED');
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE [object_id] = OBJECT_ID(N'dbo.player_admin_edit_session') AND [name] = N'IX_player_admin_edit_session_account_status')
    CREATE INDEX [IX_player_admin_edit_session_account_status] ON [dbo].[player_admin_edit_session] ([account_id], [status]);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE [object_id] = OBJECT_ID(N'dbo.player_admin_edit_drain') AND [name] = N'IX_player_admin_edit_drain_server_ack')
    CREATE INDEX [IX_player_admin_edit_drain_server_ack] ON [dbo].[player_admin_edit_drain] ([server_id], [server_session_id], [acknowledged_at_utc]);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE [object_id] = OBJECT_ID(N'dbo.player_admin_edit_operation') AND [name] = N'IX_player_admin_edit_operation_projection')
    CREATE INDEX [IX_player_admin_edit_operation_projection] ON [dbo].[player_admin_edit_operation] ([created_at_utc]) WHERE [audit_projected_at_utc] IS NULL;

COMMIT TRANSACTION;
