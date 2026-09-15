-- ============================================================
-- WebSiteDB initialization script
-- Target  : WebSiteDB
-- Purpose : Web サイトの利用者情報とWeb管理権限を保持する
-- Re-run  : 既存DB・既存テーブルを削除または変更しない
-- ============================================================

USE [master];
GO

IF DB_ID(N'WebSiteDB') IS NULL
BEGIN
    CREATE DATABASE [WebSiteDB];
END
GO

USE [WebSiteDB];
GO

IF OBJECT_ID(N'[dbo].[web_user]', N'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[web_user] (
        [user_uuid]      UNIQUEIDENTIFIER NOT NULL,
        [mcid]           NVARCHAR(100)    NOT NULL,
        [web_admin]      BIT              NOT NULL CONSTRAINT [DF_web_user_web_admin] DEFAULT (0),
        [first_login_at] DATETIME2(3)     NOT NULL,
        [last_login_at]  DATETIME2(3)     NOT NULL,

        CONSTRAINT [PK_web_user] PRIMARY KEY CLUSTERED ([user_uuid]),
        CONSTRAINT [CK_web_user_mcid_not_blank] CHECK (LEN(LTRIM(RTRIM([mcid]))) > 0),
        CONSTRAINT [CK_web_user_login_order] CHECK ([last_login_at] >= [first_login_at])
    );
END
GO

IF NOT EXISTS (
    SELECT 1
    FROM [sys].[indexes]
    WHERE [object_id] = OBJECT_ID(N'[dbo].[web_user]')
      AND [name] = N'IX_web_user_web_admin'
)
BEGIN
    CREATE NONCLUSTERED INDEX [IX_web_user_web_admin]
        ON [dbo].[web_user] ([web_admin], [user_uuid]);
END
GO
