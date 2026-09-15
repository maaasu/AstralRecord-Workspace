using AstralRecordApi.Data.Entities;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Data;

/// <summary>Web サイト専用のプレイヤー情報を管理する DB コンテキストです。</summary>
public class WebSiteDbContext(DbContextOptions<WebSiteDbContext> options) : DbContext(options)
{
    public DbSet<WebUserEntity> WebUsers => Set<WebUserEntity>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.Entity<WebUserEntity>(entity =>
        {
            entity.ToTable("web_user", "dbo");
            entity.HasKey(user => user.UserUuid);

            entity.Property(user => user.UserUuid).HasColumnName("user_uuid");
            entity.Property(user => user.Mcid).HasColumnName("mcid").HasMaxLength(100);
            entity.Property(user => user.WebAdmin).HasColumnName("web_admin");
            entity.Property(user => user.FirstLoginAt).HasColumnName("first_login_at");
            entity.Property(user => user.LastLoginAt).HasColumnName("last_login_at");
        });
    }
}
