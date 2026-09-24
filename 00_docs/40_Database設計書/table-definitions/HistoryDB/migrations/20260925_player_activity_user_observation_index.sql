/* After 20260921_player_activity. Supports latest observed account lookup per user. */
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID(N'dbo.player_ip_observation') AND name = N'IX_player_ip_observation_user_observed')
    CREATE INDEX IX_player_ip_observation_user_observed
        ON dbo.player_ip_observation(user_uuid, observed_at DESC, event_id DESC);
GO
