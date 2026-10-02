-- ゲームDBの確定済み操作台帳から投影する、長期保持の管理者編集監査。
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
IF EXISTS (SELECT 1 FROM dbo.schema_migration WHERE migration_id = N'20261002_player_admin_edit')
BEGIN
    COMMIT;
    RETURN;
END;

IF OBJECT_ID(N'dbo.player_admin_edit_audit', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.player_admin_edit_audit (
        operation_id UNIQUEIDENTIFIER NOT NULL CONSTRAINT PK_player_admin_edit_audit PRIMARY KEY,
        edit_session_id UNIQUEIDENTIFIER NOT NULL,
        actor_user_uuid UNIQUEIDENTIFIER NOT NULL,
        target_user_uuid UNIQUEIDENTIFIER NOT NULL,
        account_id UNIQUEIDENTIFIER NOT NULL,
        reason NVARCHAR(500) NOT NULL,
        action VARCHAR(16) NOT NULL,
        request_hash CHAR(64) NOT NULL,
        before_json NVARCHAR(MAX) NOT NULL,
        after_json NVARCHAR(MAX) NOT NULL,
        occurred_at_utc DATETIME2(3) NOT NULL,
        projection_status VARCHAR(16) NOT NULL,
        CONSTRAINT CK_player_admin_edit_audit_action CHECK (action IN ('APPLY', 'CANCEL')),
        CONSTRAINT CK_player_admin_edit_audit_hash CHECK (LEN(request_hash) = 64),
        CONSTRAINT CK_player_admin_edit_audit_json CHECK (ISJSON(before_json) = 1 AND ISJSON(after_json) = 1),
        CONSTRAINT CK_player_admin_edit_audit_projection CHECK (projection_status = 'PROJECTED')
    );
END;
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE [object_id] = OBJECT_ID(N'dbo.player_admin_edit_audit') AND [name] = N'IX_player_admin_edit_audit_target_time')
    CREATE INDEX IX_player_admin_edit_audit_target_time ON dbo.player_admin_edit_audit(target_user_uuid, occurred_at_utc);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE [object_id] = OBJECT_ID(N'dbo.player_admin_edit_audit') AND [name] = N'IX_player_admin_edit_audit_session')
    CREATE INDEX IX_player_admin_edit_audit_session ON dbo.player_admin_edit_audit(edit_session_id);

INSERT INTO dbo.schema_migration(migration_id) VALUES(N'20261002_player_admin_edit');
COMMIT;
GO
