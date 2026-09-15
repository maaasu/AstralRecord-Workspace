using AstralRecordApi.Data.Entities;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Data;

/// <summary>プレイヤー識別と運営情報をゲームDBから独立して長期保持するコンテキストです。</summary>
public class ManagementDbContext(DbContextOptions<ManagementDbContext> options) : DbContext(options)
{
    public DbSet<ManagementPlayerEntity> Players => Set<ManagementPlayerEntity>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.Entity<ManagementPlayerEntity>(entity =>
        {
            entity.ToTable("player", "dbo");
            entity.HasKey(user => user.PlayerUuid);

            entity.Property(user => user.PlayerUuid).HasColumnName("player_uuid");
            entity.Property(user => user.Mcid).HasColumnName("mcid").HasMaxLength(100);
            entity.Property(user => user.WebAdmin).HasColumnName("web_admin");
            entity.Property(user => user.CreatedAt).HasColumnName("created_at");
            entity.Property(user => user.UpdatedAt).HasColumnName("updated_at");
            entity.Property(user => user.FirstWebLoginAt).HasColumnName("first_web_login_at");
            entity.Property(user => user.LastWebLoginAt).HasColumnName("last_web_login_at");
        });
    }
}
