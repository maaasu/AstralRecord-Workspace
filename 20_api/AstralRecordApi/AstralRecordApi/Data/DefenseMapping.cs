using AstralRecordApi.Data.Entities;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Data;

internal static class DefenseMapping
{
    internal static void Configure(ModelBuilder modelBuilder)
    {
        modelBuilder.Entity<DefenseChannelEntity>(e =>
        {
            e.ToTable("defense_channel", "dbo"); e.HasKey(x => x.ServerId);
            e.Property(x => x.ServerId).HasColumnName("server_id").HasMaxLength(64);
            e.Property(x => x.Revision).HasColumnName("revision").IsConcurrencyToken();
            e.Property(x => x.DailyEnabled).HasColumnName("daily_enabled");
            e.Property(x => x.DailyStartTimeJst).HasColumnName("daily_start_time_jst").HasMaxLength(5);
            e.Property(x => x.ConfigurationJson).HasColumnName("configuration_json");
            e.Property(x => x.UpdatedAtUtc).HasColumnName("updated_at_utc");
            e.Property(x => x.UpdatedBy).HasColumnName("updated_by");
            e.Property(x => x.LastRuntimePollAtUtc).HasColumnName("last_runtime_poll_at_utc");
            e.Property(x => x.LastRuntimeInstanceId).HasColumnName("last_runtime_instance_id");
            e.Property(x => x.RegistrationStatus).HasColumnName("registration_status").HasMaxLength(16);
            e.Property(x => x.RegisteredCount).HasColumnName("registered_count");
            e.Property(x => x.RegistrationReportedAtUtc).HasColumnName("registration_reported_at_utc");
        });
        modelBuilder.Entity<DefenseRunEntity>(e =>
        {
            e.ToTable("defense_run", "dbo"); e.HasKey(x => x.RunId);
            e.Property(x => x.RunId).HasColumnName("run_id");
            e.Property(x => x.ServerId).HasColumnName("server_id").HasMaxLength(64);
            e.Property(x => x.Source).HasColumnName("source").HasMaxLength(16);
            e.Property(x => x.Status).HasColumnName("status").HasMaxLength(16);
            e.Property(x => x.OperationId).HasColumnName("operation_id");
            e.Property(x => x.ScheduledAtUtc).HasColumnName("scheduled_at_utc");
            e.Property(x => x.ScheduledDateJst).HasColumnName("scheduled_date_jst").HasColumnType("date");
            e.Property(x => x.CreatedAtUtc).HasColumnName("created_at_utc");
            e.Property(x => x.StartedAtUtc).HasColumnName("started_at_utc");
            e.Property(x => x.EndedAtUtc).HasColumnName("ended_at_utc");
            e.Property(x => x.InstanceId).HasColumnName("instance_id");
            e.Property(x => x.ConfigurationJson).HasColumnName("configuration_json");
            e.Property(x => x.DefenseLevel).HasColumnName("defense_level");
            e.Property(x => x.ParticipantCount).HasColumnName("participant_count");
            e.Property(x => x.Reason).HasColumnName("reason").HasMaxLength(500);
            e.Property(x => x.LastHeartbeatAtUtc).HasColumnName("last_heartbeat_at_utc");
            e.HasIndex(x => x.OperationId).IsUnique();
            e.HasIndex(x => new { x.ServerId, x.ScheduledDateJst }).IsUnique()
                .HasFilter("[source] = 'DAILY'");
            e.HasIndex(x => x.ServerId).IsUnique().HasFilter("[status] IN ('PENDING','RUNNING','STOP_REQUESTED')");
        });
        modelBuilder.Entity<DefenseParticipantEntity>(e =>
        {
            e.ToTable("defense_participant", "dbo"); e.HasKey(x => new { x.RunId, x.AccountId });
            e.Property(x => x.RunId).HasColumnName("run_id");
            e.Property(x => x.AccountId).HasColumnName("account_id");
            e.Property(x => x.UserUuid).HasColumnName("user_uuid");
            e.Property(x => x.ActiveSeconds).HasColumnName("active_seconds");
            e.Property(x => x.IsAfk).HasColumnName("is_afk");
            e.Property(x => x.Eliminated).HasColumnName("eliminated");
            e.HasIndex(x => new { x.RunId, x.UserUuid }).IsUnique();
        });
        modelBuilder.Entity<DefenseRewardEntity>(e =>
        {
            e.ToTable("defense_reward", "dbo"); e.HasKey(x => new { x.RunId, x.AccountId, x.DefenseLevel });
            e.Property(x => x.RunId).HasColumnName("run_id");
            e.Property(x => x.AccountId).HasColumnName("account_id");
            e.Property(x => x.DefenseLevel).HasColumnName("defense_level");
            e.Property(x => x.UserUuid).HasColumnName("user_uuid");
            e.Property(x => x.OrbItemId).HasColumnName("orb_item_id").HasMaxLength(150);
            e.Property(x => x.OrbQuantity).HasColumnName("orb_quantity");
            e.Property(x => x.MailId).HasColumnName("mail_id").HasMaxLength(200);
            e.Property(x => x.CreatedAtUtc).HasColumnName("created_at_utc");
            e.Property(x => x.DeliveredAtUtc).HasColumnName("delivered_at_utc");
            e.HasIndex(x => x.MailId).IsUnique();
        });
        modelBuilder.Entity<DefenseReportEntity>(e =>
        {
            e.ToTable("defense_report", "dbo"); e.HasKey(x => x.OperationId);
            e.Property(x => x.OperationId).HasColumnName("operation_id");
            e.Property(x => x.RunId).HasColumnName("run_id");
            e.Property(x => x.Kind).HasColumnName("kind").HasMaxLength(16);
            e.Property(x => x.RequestHash).HasColumnName("request_hash").HasColumnType("char(64)");
            e.Property(x => x.CreatedAtUtc).HasColumnName("created_at_utc");
        });
    }
}
