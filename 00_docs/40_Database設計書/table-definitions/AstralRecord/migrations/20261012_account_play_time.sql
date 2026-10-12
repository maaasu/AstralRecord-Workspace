-- API/Plugin更新前にAstralRecordへ適用する。過去のBukkit統計は移行しない。
SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF COL_LENGTH(N'dbo.account', N'play_time_seconds') IS NULL
    ALTER TABLE dbo.account ADD play_time_seconds BIGINT NOT NULL
        CONSTRAINT DF_account_play_time_seconds DEFAULT (0) WITH VALUES;
IF NOT EXISTS (SELECT 1 FROM sys.check_constraints
               WHERE parent_object_id = OBJECT_ID(N'dbo.account') AND name = N'CK_account_play_time_seconds')
    -- 追加した列を同batchの初期コンパイルで参照しない。
    EXEC sys.sp_executesql N'ALTER TABLE dbo.account WITH CHECK ADD CONSTRAINT CK_account_play_time_seconds CHECK (play_time_seconds >= 0)';
COMMIT;
