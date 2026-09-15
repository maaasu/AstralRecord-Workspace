-- 前提: ManagementDB/init.sql適用済み。旧APIへの書込みを停止してから実行する。
-- 旧DBは削除しない。完了台帳により再実行時の古い管理権限の復活を防ぐ。
USE [ManagementDB];
GO
SET XACT_ABORT ON;
BEGIN TRANSACTION;
DECLARE @lockResult int;
EXEC @lockResult = sys.sp_getapplock
    @Resource = N'ManagementDB.schema_migration', @LockMode = N'Exclusive',
    @LockOwner = N'Transaction', @LockTimeout = 15000;
IF @lockResult < 0
    THROW 51000, 'ManagementDB migration lock unavailable.', 1;

IF EXISTS (SELECT 1 FROM dbo.schema_migration WHERE migration_id = N'20260915-import-website')
BEGIN
    COMMIT;
    RETURN;
END;
IF DB_ID(N'WebSiteDB') IS NULL OR OBJECT_ID(N'WebSiteDB.dbo.web_user', N'U') IS NULL
    THROW 51001, 'Legacy WebSiteDB.web_user is missing. Fresh installations do not need this import.', 1;

-- 移行前に独立した管理レコードが存在し内容が異なる場合は、上書きせず照合を要求する。
IF EXISTS (
    SELECT 1 FROM [WebSiteDB].dbo.web_user source
    JOIN dbo.player target ON target.player_uuid = source.user_uuid
    WHERE target.mcid <> source.mcid OR target.web_admin <> source.web_admin
       OR target.first_web_login_at IS NULL OR target.last_web_login_at IS NULL
       OR target.first_web_login_at <> source.first_login_at OR target.last_web_login_at <> source.last_login_at
)
    THROW 51002, 'Existing management player conflicts with legacy data; no data overwritten.', 1;

INSERT INTO dbo.player (player_uuid, mcid, web_admin, created_at, updated_at, first_web_login_at, last_web_login_at)
SELECT source.user_uuid, source.mcid, source.web_admin, source.first_login_at, source.last_login_at,
       source.first_login_at, source.last_login_at
FROM [WebSiteDB].dbo.web_user source
WHERE NOT EXISTS (SELECT 1 FROM dbo.player target WITH (UPDLOCK, HOLDLOCK) WHERE target.player_uuid = source.user_uuid);

INSERT INTO dbo.schema_migration (migration_id) VALUES (N'20260915-import-website');
COMMIT;
GO
