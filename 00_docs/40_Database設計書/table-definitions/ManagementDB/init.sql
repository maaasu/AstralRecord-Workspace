-- 長期保持する運営管理DB。ゲームDBの初期化・リセットから独立する。
-- 再実行しても既存データを削除しない。既存スキーマ変更は個別migrationで行う。
USE [master];
GO
IF DB_ID(N'ManagementDB') IS NULL
    CREATE DATABASE [ManagementDB];
GO
USE [ManagementDB];
GO
IF OBJECT_ID(N'dbo.player', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.player (
        player_uuid UNIQUEIDENTIFIER NOT NULL CONSTRAINT PK_management_player PRIMARY KEY,
        mcid NVARCHAR(100) NOT NULL,
        web_admin BIT NOT NULL CONSTRAINT DF_management_player_web_admin DEFAULT (0),
        created_at DATETIME2(3) NOT NULL CONSTRAINT DF_management_player_created_at DEFAULT SYSUTCDATETIME(),
        updated_at DATETIME2(3) NOT NULL CONSTRAINT DF_management_player_updated_at DEFAULT SYSUTCDATETIME(),
        first_web_login_at DATETIME2(3) NULL,
        last_web_login_at DATETIME2(3) NULL,
        CONSTRAINT CK_management_player_mcid CHECK (LEN(LTRIM(RTRIM(mcid))) > 0),
        CONSTRAINT CK_management_player_updated CHECK (updated_at >= created_at),
        CONSTRAINT CK_management_player_web_login CHECK (
            (first_web_login_at IS NULL AND last_web_login_at IS NULL) OR
            (first_web_login_at IS NOT NULL AND last_web_login_at IS NOT NULL AND last_web_login_at >= first_web_login_at))
    );
END;
GO
IF OBJECT_ID(N'dbo.schema_migration', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.schema_migration (
        migration_id NVARCHAR(150) NOT NULL CONSTRAINT PK_management_schema_migration PRIMARY KEY,
        applied_at DATETIME2(3) NOT NULL CONSTRAINT DF_management_schema_migration_applied DEFAULT SYSUTCDATETIME()
    );
END;
GO
