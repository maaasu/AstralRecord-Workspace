using AstralRecordApi.Data.Entities;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Data;

public class HistoryDbContext(DbContextOptions<HistoryDbContext> options) : DbContext(options)
{
    public DbSet<UserHistoryEntity> UserHistories => Set<UserHistoryEntity>();
    public DbSet<PlayerActivityBatchEntity> PlayerActivityBatches => Set<PlayerActivityBatchEntity>();
    public DbSet<PlayerIpObservationEntity> PlayerIpObservations => Set<PlayerIpObservationEntity>();
    public DbSet<PlayerTradeActivityEntity> PlayerTradeActivities => Set<PlayerTradeActivityEntity>();
    public DbSet<PlayerTradeActivityItemEntity> PlayerTradeActivityItems => Set<PlayerTradeActivityItemEntity>();
    public DbSet<DungeonClearActivityEntity> DungeonClearActivities => Set<DungeonClearActivityEntity>();
    public DbSet<DungeonParticipantActivityEntity> DungeonParticipantActivities => Set<DungeonParticipantActivityEntity>();
    public DbSet<MobDamageSummaryEntity> MobDamageSummaries => Set<MobDamageSummaryEntity>();
    public DbSet<MobPlayerDeathEntity> MobPlayerDeaths => Set<MobPlayerDeathEntity>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.Entity<UserHistoryEntity>(entity =>
        {
            entity.ToTable("user_history", "dbo");
            entity.HasKey(history => history.HistoryId);

            entity.Property(history => history.HistoryId).HasColumnName("history_id");
            entity.Property(history => history.UserUuid).HasColumnName("user_uuid");
            entity.Property(history => history.EventTime).HasColumnName("event_time");
            entity.Property(history => history.EventType).HasColumnName("event_type");
            entity.Property(history => history.Source).HasColumnName("source");
            entity.Property(history => history.Message).HasColumnName("message");
            entity.Property(history => history.PayloadJson).HasColumnName("payload_json");
            entity.Property(history => history.CreatedAt).HasColumnName("created_at");
        });

        modelBuilder.Entity<PlayerActivityBatchEntity>(entity =>
        {
            entity.ToTable("player_activity_batch", "dbo"); entity.HasKey(x => x.BatchId);
            entity.Property(x => x.BatchId).HasColumnName("batch_id"); entity.Property(x => x.ReceivedAt).HasColumnName("received_at");
        });
        modelBuilder.Entity<PlayerIpObservationEntity>(entity =>
        {
            entity.ToTable("player_ip_observation", "dbo"); entity.HasKey(x => x.EventId);
            entity.Property(x => x.EventId).HasColumnName("event_id"); entity.Property(x => x.ObservedAt).HasColumnName("observed_at"); entity.Property(x => x.GlobalIp).HasColumnName("global_ip").HasMaxLength(45);
            Player(entity); entity.HasIndex(x => new { x.GlobalIp, x.ObservedAt }).HasDatabaseName("IX_player_ip_observation_ip_observed");
        });
        modelBuilder.Entity<PlayerTradeActivityEntity>(entity =>
        {
            entity.ToTable("player_trade_activity", "dbo"); entity.HasKey(x => x.EventId);
            entity.Property(x => x.EventId).HasColumnName("event_id"); entity.Property(x => x.CompletedAt).HasColumnName("completed_at"); entity.Property(x => x.Gold).HasColumnName("gold");
            entity.Property(x => x.SourceUserUuid).HasColumnName("source_user_uuid"); entity.Property(x => x.SourceAccountId).HasColumnName("source_account_id"); entity.Property(x => x.SourceMcid).HasColumnName("source_mcid").HasMaxLength(20); entity.Property(x => x.SourceAccountName).HasColumnName("source_account_name").HasMaxLength(50);
            entity.Property(x => x.DestinationUserUuid).HasColumnName("destination_user_uuid"); entity.Property(x => x.DestinationAccountId).HasColumnName("destination_account_id"); entity.Property(x => x.DestinationMcid).HasColumnName("destination_mcid").HasMaxLength(20); entity.Property(x => x.DestinationAccountName).HasColumnName("destination_account_name").HasMaxLength(50);
            entity.HasMany(x => x.Items).WithOne().HasForeignKey(x => x.EventId).OnDelete(DeleteBehavior.Cascade); entity.HasIndex(x => new { x.CompletedAt, x.SourceAccountId, x.DestinationAccountId }).HasDatabaseName("IX_player_trade_activity_completed_accounts");
        });
        modelBuilder.Entity<PlayerTradeActivityItemEntity>(entity =>
        {
            entity.ToTable("player_trade_activity_item", "dbo"); entity.HasKey(x => new { x.EventId, x.LineNumber });
            entity.Property(x => x.EventId).HasColumnName("event_id"); entity.Property(x => x.LineNumber).HasColumnName("line_number"); entity.Property(x => x.ItemId).HasColumnName("item_id").HasMaxLength(100); entity.Property(x => x.ItemName).HasColumnName("item_name").HasMaxLength(200); entity.Property(x => x.Quantity).HasColumnName("quantity");
        });
        modelBuilder.Entity<DungeonClearActivityEntity>(entity =>
        {
            entity.ToTable("dungeon_clear_activity", "dbo"); entity.HasKey(x => x.EventId);
            entity.Property(x => x.EventId).HasColumnName("event_id"); entity.Property(x => x.DungeonId).HasColumnName("dungeon_id").HasMaxLength(100); entity.Property(x => x.DungeonName).HasColumnName("dungeon_name").HasMaxLength(200); entity.Property(x => x.StartedAt).HasColumnName("started_at"); entity.Property(x => x.ClearedAt).HasColumnName("cleared_at");
            entity.HasMany(x => x.Participants).WithOne().HasForeignKey(x => x.EventId).OnDelete(DeleteBehavior.Cascade); entity.HasIndex(x => new { x.ClearedAt, x.DungeonId }).HasDatabaseName("IX_dungeon_clear_activity_cleared_dungeon");
        });
        modelBuilder.Entity<DungeonParticipantActivityEntity>(entity =>
        {
            entity.ToTable("dungeon_clear_participant", "dbo"); entity.HasKey(x => new { x.EventId, x.AccountId }); entity.Property(x => x.EventId).HasColumnName("event_id"); Player(entity, ""); entity.Property(x => x.DistanceMeters).HasColumnName("distance_meters").HasPrecision(18, 3); entity.Property(x => x.MovementSampleCount).HasColumnName("movement_sample_count"); entity.HasIndex(x => new { x.AccountId, x.EventId }).HasDatabaseName("IX_dungeon_clear_participant_account_event");
        });
        modelBuilder.Entity<MobDamageSummaryEntity>(entity =>
        {
            entity.ToTable("mob_damage_summary", "dbo"); entity.HasKey(x => x.EventId); entity.Property(x => x.EventId).HasColumnName("event_id"); entity.Property(x => x.MobId).HasColumnName("mob_id").HasMaxLength(100); entity.Property(x => x.MobName).HasColumnName("mob_name").HasMaxLength(200); entity.Property(x => x.WindowStartedAt).HasColumnName("window_started_at"); entity.Property(x => x.WindowEndedAt).HasColumnName("window_ended_at"); Victim(entity); entity.Property(x => x.Damage).HasColumnName("damage").HasPrecision(18, 3); entity.Property(x => x.HitCount).HasColumnName("hit_count"); entity.HasIndex(x => new { x.MobId, x.WindowEndedAt }).HasDatabaseName("IX_mob_damage_summary_mob_window");
        });
        modelBuilder.Entity<MobPlayerDeathEntity>(entity =>
        {
            entity.ToTable("mob_player_death", "dbo"); entity.HasKey(x => x.EventId); entity.Property(x => x.EventId).HasColumnName("event_id"); entity.Property(x => x.OccurredAt).HasColumnName("occurred_at"); entity.Property(x => x.MobId).HasColumnName("mob_id").HasMaxLength(100); entity.Property(x => x.MobName).HasColumnName("mob_name").HasMaxLength(200); Victim(entity); entity.HasIndex(x => new { x.MobId, x.OccurredAt }).HasDatabaseName("IX_mob_player_death_mob_occurred");
        });
    }

    private static void Player(Microsoft.EntityFrameworkCore.Metadata.Builders.EntityTypeBuilder<PlayerIpObservationEntity> entity)
    { entity.Property(x => x.UserUuid).HasColumnName("user_uuid"); entity.Property(x => x.AccountId).HasColumnName("account_id"); entity.Property(x => x.Mcid).HasColumnName("mcid").HasMaxLength(20); entity.Property(x => x.AccountName).HasColumnName("account_name").HasMaxLength(50); }
    private static void Player(Microsoft.EntityFrameworkCore.Metadata.Builders.EntityTypeBuilder<DungeonParticipantActivityEntity> entity, string _)
    { entity.Property(x => x.UserUuid).HasColumnName("user_uuid"); entity.Property(x => x.AccountId).HasColumnName("account_id"); entity.Property(x => x.Mcid).HasColumnName("mcid").HasMaxLength(20); entity.Property(x => x.AccountName).HasColumnName("account_name").HasMaxLength(50); }
    private static void Victim<TEntity>(Microsoft.EntityFrameworkCore.Metadata.Builders.EntityTypeBuilder<TEntity> entity) where TEntity : class
    {
        entity.Property<Guid>("VictimUserUuid").HasColumnName("victim_user_uuid"); entity.Property<Guid>("VictimAccountId").HasColumnName("victim_account_id"); entity.Property<string>("VictimMcid").HasColumnName("victim_mcid").HasMaxLength(20); entity.Property<string>("VictimAccountName").HasColumnName("victim_account_name").HasMaxLength(50);
    }
}
