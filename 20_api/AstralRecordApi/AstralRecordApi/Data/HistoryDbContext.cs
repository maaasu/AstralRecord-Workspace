using AstralRecordApi.Data.Entities;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Data;

public class HistoryDbContext(DbContextOptions<HistoryDbContext> options) : DbContext(options)
{
    public DbSet<UserHistoryEntity> UserHistories => Set<UserHistoryEntity>();

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
    }
}
