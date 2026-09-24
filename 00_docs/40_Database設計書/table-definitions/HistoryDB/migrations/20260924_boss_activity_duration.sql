/* After 20260921_player_activity. Each step can be retried if migration recording is interrupted. */
IF COL_LENGTH(N'dbo.dungeon_clear_activity', N'duration_milliseconds') IS NULL
    ALTER TABLE dbo.dungeon_clear_activity
    ADD duration_milliseconds AS DATEDIFF_BIG(MILLISECOND, started_at, cleared_at) PERSISTED NOT NULL;
GO
IF OBJECT_ID(N'dbo.boss_clear_activity', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.boss_clear_activity (
        event_id UNIQUEIDENTIFIER NOT NULL,
        boss_id NVARCHAR(100) NOT NULL,
        boss_name NVARCHAR(200) NOT NULL,
        started_at DATETIME2(3) NOT NULL,
        cleared_at DATETIME2(3) NOT NULL,
        duration_milliseconds AS DATEDIFF_BIG(MILLISECOND, started_at, cleared_at) PERSISTED NOT NULL,
        CONSTRAINT PK_boss_clear_activity PRIMARY KEY CLUSTERED (event_id),
        CONSTRAINT CK_boss_clear_activity_time CHECK (cleared_at >= started_at)
    );
END;
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID(N'dbo.boss_clear_activity') AND name = N'IX_boss_clear_activity_cleared_boss')
    CREATE INDEX IX_boss_clear_activity_cleared_boss ON dbo.boss_clear_activity(cleared_at DESC, boss_id);
GO
IF OBJECT_ID(N'dbo.boss_clear_participant', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.boss_clear_participant (
        event_id UNIQUEIDENTIFIER NOT NULL,
        account_id UNIQUEIDENTIFIER NOT NULL,
        user_uuid UNIQUEIDENTIFIER NOT NULL,
        mcid NVARCHAR(20) NOT NULL,
        account_name NVARCHAR(50) NOT NULL,
        damage_dealt DECIMAL(18,3) NOT NULL,
        death_count INT NOT NULL,
        CONSTRAINT PK_boss_clear_participant PRIMARY KEY CLUSTERED (event_id, account_id),
        CONSTRAINT FK_boss_clear_participant_event FOREIGN KEY (event_id) REFERENCES dbo.boss_clear_activity(event_id) ON DELETE CASCADE,
        CONSTRAINT CK_boss_clear_participant_damage CHECK (damage_dealt >= 0),
        CONSTRAINT CK_boss_clear_participant_deaths CHECK (death_count >= 0)
    );
END;
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID(N'dbo.boss_clear_participant') AND name = N'IX_boss_clear_participant_account_event')
    CREATE INDEX IX_boss_clear_participant_account_event ON dbo.boss_clear_participant(account_id, event_id);
GO
