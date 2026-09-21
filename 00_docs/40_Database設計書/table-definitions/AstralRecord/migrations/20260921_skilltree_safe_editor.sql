/*
  Safe skill-tree editor runtime ledger.
  Apply before deploying Plugin/Web/API versions that use /api/skilltree/runtime.
  Existing account_skilltree_state rows intentionally remain definition_generation_id = NULL.
*/
SET XACT_ABORT ON;
BEGIN TRANSACTION;

ALTER TABLE [dbo].[account_skilltree_state]
    ADD [definition_generation_id] NVARCHAR(64) NULL;

CREATE TABLE [dbo].[skilltree_definition_generation] (
    [definition_generation_id] NVARCHAR(64) NOT NULL,
    [canonical_snapshot_json] NVARCHAR(MAX) NOT NULL,
    [created_at_utc] DATETIME2(3) NOT NULL,
    CONSTRAINT [PK_skilltree_definition_generation] PRIMARY KEY CLUSTERED ([definition_generation_id]),
    CONSTRAINT [CK_skilltree_definition_generation_id] CHECK (LEN([definition_generation_id]) = 64),
    CONSTRAINT [CK_skilltree_definition_generation_snapshot_json] CHECK (ISJSON([canonical_snapshot_json]) = 1)
);

CREATE TABLE [dbo].[skilltree_server_runtime] (
    [server_id] NVARCHAR(64) NOT NULL,
    [server_session_id] UNIQUEIDENTIFIER NOT NULL,
    [server_started_at_utc] DATETIME2(3) NOT NULL,
    [plugin_version] NVARCHAR(100) NOT NULL,
    [compatibility_version] NVARCHAR(100) NOT NULL,
    [definition_generation_id] NVARCHAR(64) NOT NULL,
    [ready] BIT NOT NULL,
    [last_seen_utc] DATETIME2(3) NOT NULL,
    CONSTRAINT [PK_skilltree_server_runtime] PRIMARY KEY CLUSTERED ([server_id]),
    CONSTRAINT [FK_skilltree_server_runtime_generation] FOREIGN KEY ([definition_generation_id]) REFERENCES [dbo].[skilltree_definition_generation] ([definition_generation_id])
);

CREATE TABLE [dbo].[skilltree_server_player_view] (
    [server_id] NVARCHAR(64) NOT NULL,
    [account_id] UNIQUEIDENTIFIER NOT NULL,
    [server_session_id] UNIQUEIDENTIFIER NOT NULL,
    [definition_generation_id] NVARCHAR(64) NOT NULL,
    [player_state_version] INT NOT NULL,
    [evaluation_fingerprint] NVARCHAR(64) NOT NULL,
    [edit_eligible] BIT NOT NULL,
    [offline_confirmed] BIT NOT NULL CONSTRAINT [DF_skilltree_server_player_view_offline_confirmed] DEFAULT (0),
    [view_json] NVARCHAR(MAX) NOT NULL,
    [last_seen_utc] DATETIME2(3) NOT NULL,
    CONSTRAINT [PK_skilltree_server_player_view] PRIMARY KEY CLUSTERED ([server_id], [account_id]),
    CONSTRAINT [FK_skilltree_server_player_view_account] FOREIGN KEY ([account_id]) REFERENCES [dbo].[account] ([uuid]) ON DELETE CASCADE,
    CONSTRAINT [FK_skilltree_server_player_view_generation] FOREIGN KEY ([definition_generation_id]) REFERENCES [dbo].[skilltree_definition_generation] ([definition_generation_id]),
    CONSTRAINT [CK_skilltree_server_player_view_version] CHECK ([player_state_version] >= 0),
    CONSTRAINT [CK_skilltree_server_player_view_json] CHECK (ISJSON([view_json]) = 1)
);
CREATE NONCLUSTERED INDEX [IX_skilltree_server_player_view_seen] ON [dbo].[skilltree_server_player_view] ([last_seen_utc]);

CREATE TABLE [dbo].[skilltree_operation] (
    [operation_id] UNIQUEIDENTIFIER NOT NULL,
    [account_id] UNIQUEIDENTIFIER NOT NULL,
    [actor_user_id] UNIQUEIDENTIFIER NOT NULL,
    [request_hash] NVARCHAR(64) NOT NULL,
    [target_server_id] NVARCHAR(64) NOT NULL,
    [expected_definition_generation_id] NVARCHAR(64) NOT NULL,
    [expected_player_state_version] INT NOT NULL,
    [expected_evaluation_fingerprint] NVARCHAR(64) NOT NULL,
    [action] NVARCHAR(16) NOT NULL,
    [node_id] NVARCHAR(200) NOT NULL,
    [source_class_id] NVARCHAR(100) NULL,
    [status] NVARCHAR(32) NOT NULL,
    [reason] NVARCHAR(500) NULL,
    [claimed_server_session_id] UNIQUEIDENTIFIER NULL,
    [lease_token_hash] NVARCHAR(64) NULL,
    [lease_expires_at_utc] DATETIME2(3) NULL,
    [created_at_utc] DATETIME2(3) NOT NULL,
    [expires_at_utc] DATETIME2(3) NOT NULL,
    [completed_at_utc] DATETIME2(3) NULL,
    CONSTRAINT [PK_skilltree_operation] PRIMARY KEY CLUSTERED ([operation_id]),
    CONSTRAINT [FK_skilltree_operation_account] FOREIGN KEY ([account_id]) REFERENCES [dbo].[account] ([uuid]),
    CONSTRAINT [FK_skilltree_operation_generation] FOREIGN KEY ([expected_definition_generation_id]) REFERENCES [dbo].[skilltree_definition_generation] ([definition_generation_id]),
    CONSTRAINT [CK_skilltree_operation_version] CHECK ([expected_player_state_version] >= 0),
    CONSTRAINT [CK_skilltree_operation_action] CHECK ([action] IN ('UNLOCK', 'RELOCK'))
);
CREATE NONCLUSTERED INDEX [IX_skilltree_operation_account_status] ON [dbo].[skilltree_operation] ([account_id], [status]);
CREATE NONCLUSTERED INDEX [IX_skilltree_operation_server_status] ON [dbo].[skilltree_operation] ([target_server_id], [status]);

CREATE TABLE [dbo].[skilltree_migration_operation] (
    [operation_id] UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
    [account_id] UNIQUEIDENTIFIER NOT NULL,
    [request_hash] NVARCHAR(64) NOT NULL,
    [expected_state_version] INT NOT NULL,
    [from_generation_id] NVARCHAR(64) NULL,
    [to_generation_id] NVARCHAR(64) NOT NULL,
    [baseline_node_ids_json] NVARCHAR(MAX) NOT NULL,
    [removed_node_ids_json] NVARCHAR(MAX) NOT NULL,
    [status] NVARCHAR(32) NOT NULL,
    [completed_at_utc] DATETIME2(3) NOT NULL,
    CONSTRAINT [FK_skilltree_migration_operation_account] FOREIGN KEY ([account_id]) REFERENCES [dbo].[account] ([uuid])
);

COMMIT TRANSACTION;
