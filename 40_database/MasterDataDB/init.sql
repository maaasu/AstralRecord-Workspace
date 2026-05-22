-- ============================================================
-- MasterDataDB initialization script
-- Created : 2026-05-21
-- Target  : MasterDataDB
-- Purpose : Filebase YAML を SQL Server 上の配信用マスタへ変換して保持する
-- ============================================================

USE [master];
GO

IF DB_ID(N'MasterDataDB') IS NULL
BEGIN
    CREATE DATABASE [MasterDataDB];
END
GO

USE [MasterDataDB];
GO

-- ============================================================
-- STEP 1 : dbo.master_data_source
-- filebase/config.yml の database 定義を正規化して保持する。
-- ============================================================

CREATE TABLE [dbo].[master_data_source] (
    [source_id]        UNIQUEIDENTIFIER  NOT NULL,
    [source_key]       NVARCHAR(80)      NOT NULL,
    [source_path]      NVARCHAR(400)     NOT NULL,
    [source_kind]      NVARCHAR(30)      NOT NULL,
    [schema_version]   INT                   NULL,
    [is_enabled]       BIT               NOT NULL  CONSTRAINT [DF_master_data_source_is_enabled] DEFAULT (1),
    [created_at]       DATETIME2(3)      NOT NULL,
    [updated_at]       DATETIME2(3)      NOT NULL,
    [created_by]       UNIQUEIDENTIFIER  NOT NULL,
    [updated_by]       UNIQUEIDENTIFIER  NOT NULL,
    [is_deleted]       BIT               NOT NULL  CONSTRAINT [DF_master_data_source_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_master_data_source] PRIMARY KEY CLUSTERED ([source_id]),
    CONSTRAINT [UQ_master_data_source_key] UNIQUE ([source_key]),
    CONSTRAINT [CK_master_data_source_kind] CHECK ([source_kind] IN (N'FEATURE', N'SHARED', N'META', N'SYSTEM')),
    CONSTRAINT [CK_master_data_source_schema_version] CHECK ([schema_version] IS NULL OR [schema_version] >= 1)
);
GO

CREATE NONCLUSTERED INDEX [IX_master_data_source_is_enabled]
    ON [dbo].[master_data_source] ([is_enabled], [is_deleted]);
GO

-- ============================================================
-- STEP 2 : dbo.master_data_entry
-- 各 YAML ファイルを API 配信用 JSON ペイロードとして保持する。
-- ============================================================

CREATE TABLE [dbo].[master_data_entry] (
    [entry_id]          UNIQUEIDENTIFIER  NOT NULL,
    [source_id]         UNIQUEIDENTIFIER  NOT NULL,
    [master_type]       NVARCHAR(80)      NOT NULL,
    [master_id]         NVARCHAR(120)     NOT NULL,
    [category]          NVARCHAR(60)          NULL,
    [type]              NVARCHAR(60)          NULL,
    [schema_version]    INT               NOT NULL,
    [display_name]      NVARCHAR(200)         NULL,
    [source_file_path]  NVARCHAR(500)     NOT NULL,
    [source_file_hash]  CHAR(64)          NOT NULL,
    [payload_json]      NVARCHAR(MAX)     NOT NULL,
    [payload_version]   BIGINT            NOT NULL  CONSTRAINT [DF_master_data_entry_payload_version] DEFAULT (1),
    [effective_from]    DATETIME2(3)      NOT NULL  CONSTRAINT [DF_master_data_entry_effective_from] DEFAULT (SYSUTCDATETIME()),
    [created_at]        DATETIME2(3)      NOT NULL,
    [updated_at]        DATETIME2(3)      NOT NULL,
    [created_by]        UNIQUEIDENTIFIER  NOT NULL,
    [updated_by]        UNIQUEIDENTIFIER  NOT NULL,
    [is_deleted]        BIT               NOT NULL  CONSTRAINT [DF_master_data_entry_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_master_data_entry] PRIMARY KEY CLUSTERED ([entry_id]),
    CONSTRAINT [FK_master_data_entry_source] FOREIGN KEY ([source_id])
        REFERENCES [dbo].[master_data_source] ([source_id])
        ON DELETE NO ACTION
        ON UPDATE NO ACTION,
    CONSTRAINT [CK_master_data_entry_schema_version] CHECK ([schema_version] >= 1),
    CONSTRAINT [CK_master_data_entry_payload_json] CHECK (ISJSON([payload_json]) = 1),
    CONSTRAINT [CK_master_data_entry_payload_version] CHECK ([payload_version] >= 1)
);
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_master_data_entry_type_id_active]
    ON [dbo].[master_data_entry] ([master_type], [master_id])
    WHERE [is_deleted] = 0;
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_master_data_entry_source_file_active]
    ON [dbo].[master_data_entry] ([source_file_path])
    WHERE [is_deleted] = 0;
GO

CREATE NONCLUSTERED INDEX [IX_master_data_entry_source]
    ON [dbo].[master_data_entry] ([source_id], [is_deleted]);
GO

CREATE NONCLUSTERED INDEX [IX_master_data_entry_category]
    ON [dbo].[master_data_entry] ([master_type], [category], [is_deleted]);
GO

CREATE NONCLUSTERED INDEX [IX_master_data_entry_type]
    ON [dbo].[master_data_entry] ([master_type], [type], [is_deleted]);
GO

CREATE NONCLUSTERED INDEX [IX_master_data_entry_hash]
    ON [dbo].[master_data_entry] ([source_file_hash]);
GO

-- ============================================================
-- STEP 3 : dbo.master_data_reference
-- YAML 内の ref: 参照を展開し、Seeder/API の整合性検証に使う。
-- ============================================================

CREATE TABLE [dbo].[master_data_reference] (
    [reference_id]       UNIQUEIDENTIFIER  NOT NULL,
    [from_entry_id]      UNIQUEIDENTIFIER  NOT NULL,
    [from_master_type]   NVARCHAR(80)      NOT NULL,
    [from_master_id]     NVARCHAR(120)     NOT NULL,
    [reference_type]     NVARCHAR(80)      NOT NULL,
    [reference_id_value] NVARCHAR(120)     NOT NULL,
    [reference_path]     NVARCHAR(300)         NULL,
    [is_required]        BIT               NOT NULL  CONSTRAINT [DF_master_data_reference_is_required] DEFAULT (1),
    [sort_order]         INT               NOT NULL  CONSTRAINT [DF_master_data_reference_sort_order] DEFAULT (0),
    [created_at]         DATETIME2(3)      NOT NULL,
    [updated_at]         DATETIME2(3)      NOT NULL,
    [created_by]         UNIQUEIDENTIFIER  NOT NULL,
    [updated_by]         UNIQUEIDENTIFIER  NOT NULL,
    [is_deleted]         BIT               NOT NULL  CONSTRAINT [DF_master_data_reference_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_master_data_reference] PRIMARY KEY CLUSTERED ([reference_id]),
    CONSTRAINT [FK_master_data_reference_entry] FOREIGN KEY ([from_entry_id])
        REFERENCES [dbo].[master_data_entry] ([entry_id])
        ON DELETE CASCADE
        ON UPDATE NO ACTION
);
GO

CREATE NONCLUSTERED INDEX [IX_master_data_reference_from_entry]
    ON [dbo].[master_data_reference] ([from_entry_id], [is_deleted]);
GO

CREATE NONCLUSTERED INDEX [IX_master_data_reference_target]
    ON [dbo].[master_data_reference] ([reference_type], [reference_id_value], [is_deleted]);
GO

-- ============================================================
-- STEP 4 : dbo.master_data_seed_run
-- API 起動時または Seeder API 実行時の投入結果を監査する。
-- ============================================================

CREATE TABLE [dbo].[master_data_seed_run] (
    [seed_run_id]      UNIQUEIDENTIFIER  NOT NULL,
    [trigger_type]     NVARCHAR(30)      NOT NULL,
    [status]           NVARCHAR(30)      NOT NULL,
    [source_root_path] NVARCHAR(500)     NOT NULL,
    [started_at]       DATETIME2(3)      NOT NULL,
    [finished_at]      DATETIME2(3)          NULL,
    [file_count]       INT               NOT NULL  CONSTRAINT [DF_master_data_seed_run_file_count] DEFAULT (0),
    [upserted_count]   INT               NOT NULL  CONSTRAINT [DF_master_data_seed_run_upserted_count] DEFAULT (0),
    [deleted_count]    INT               NOT NULL  CONSTRAINT [DF_master_data_seed_run_deleted_count] DEFAULT (0),
    [skipped_count]    INT               NOT NULL  CONSTRAINT [DF_master_data_seed_run_skipped_count] DEFAULT (0),
    [error_message]    NVARCHAR(MAX)         NULL,
    [created_at]       DATETIME2(3)      NOT NULL,
    [updated_at]       DATETIME2(3)      NOT NULL,
    [created_by]       UNIQUEIDENTIFIER  NOT NULL,
    [updated_by]       UNIQUEIDENTIFIER  NOT NULL,

    CONSTRAINT [PK_master_data_seed_run] PRIMARY KEY CLUSTERED ([seed_run_id]),
    CONSTRAINT [CK_master_data_seed_run_trigger_type] CHECK ([trigger_type] IN (N'STARTUP', N'SEEDER_API', N'MANUAL')),
    CONSTRAINT [CK_master_data_seed_run_status] CHECK ([status] IN (N'RUNNING', N'SUCCEEDED', N'FAILED')),
    CONSTRAINT [CK_master_data_seed_run_counts] CHECK (
        [file_count] >= 0
        AND [upserted_count] >= 0
        AND [deleted_count] >= 0
        AND [skipped_count] >= 0
    )
);
GO

CREATE NONCLUSTERED INDEX [IX_master_data_seed_run_started_at]
    ON [dbo].[master_data_seed_run] ([started_at] DESC);
GO

CREATE NONCLUSTERED INDEX [IX_master_data_seed_run_status]
    ON [dbo].[master_data_seed_run] ([status], [started_at] DESC);
GO
