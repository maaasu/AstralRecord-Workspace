using AstralRecordApi.Data.Entities;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Data;

/// <summary>プレイヤー識別と運営情報をゲームDBから独立して長期保持するコンテキストです。</summary>
public class ManagementDbContext(DbContextOptions<ManagementDbContext> options) : DbContext(options)
{
    public DbSet<ManagementPlayerEntity> Players => Set<ManagementPlayerEntity>();
    public DbSet<ManagedNetworkSettingsEntity> NetworkSettings => Set<ManagedNetworkSettingsEntity>();
    public DbSet<ManagedNetworkBanEntity> NetworkBans => Set<ManagedNetworkBanEntity>();
    public DbSet<NetworkManagementAuditEntity> NetworkAudits => Set<NetworkManagementAuditEntity>();
    public DbSet<WebCredentialEntity> WebCredentials => Set<WebCredentialEntity>();
    public DbSet<WebCredentialLoginAttemptEntity> WebCredentialLoginAttempts => Set<WebCredentialLoginAttemptEntity>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.Entity<ManagedNetworkSettingsEntity>(entity =>
        {
            entity.ToTable("network_settings", "dbo");
            entity.HasKey(x => x.Id);
            entity.Property(x => x.Id).HasColumnName("id").ValueGeneratedNever();
            entity.Property(x => x.Revision).HasColumnName("revision").IsConcurrencyToken();
            entity.Property(x => x.SettingsJson).HasColumnName("settings_json");
            entity.Property(x => x.UpdatedAtUtc).HasColumnName("updated_at_utc");
            entity.Property(x => x.UpdatedBy).HasColumnName("updated_by");
        });
        modelBuilder.Entity<ManagedNetworkBanEntity>(entity =>
        {
            entity.ToTable("network_ban", "dbo");
            entity.HasKey(x => x.UserUuid);
            entity.Property(x => x.UserUuid).HasColumnName("user_uuid");
            entity.Property(x => x.Revision).HasColumnName("revision").IsConcurrencyToken();
            entity.Property(x => x.IsBanned).HasColumnName("is_banned");
            entity.Property(x => x.ExpiresAtUtc).HasColumnName("expires_at_utc");
            entity.Property(x => x.Reason).HasColumnName("reason").HasMaxLength(500);
            entity.Property(x => x.UpdatedAtUtc).HasColumnName("updated_at_utc");
            entity.Property(x => x.UpdatedBy).HasColumnName("updated_by");
        });
        modelBuilder.Entity<NetworkManagementAuditEntity>(entity =>
        {
            entity.ToTable("network_management_audit", "dbo");
            entity.HasKey(x => x.AuditId);
            entity.Property(x => x.AuditId).HasColumnName("audit_id");
            entity.Property(x => x.Operation).HasColumnName("operation").HasMaxLength(50);
            entity.Property(x => x.ActorUuid).HasColumnName("actor_uuid");
            entity.Property(x => x.TargetUuid).HasColumnName("target_uuid");
            entity.Property(x => x.BeforeJson).HasColumnName("before_json");
            entity.Property(x => x.AfterJson).HasColumnName("after_json");
            entity.Property(x => x.OccurredAtUtc).HasColumnName("occurred_at_utc");
        });
        modelBuilder.Entity<ManagementPlayerEntity>(entity =>
        {
            entity.ToTable("player", "dbo");
            entity.HasKey(user => user.PlayerUuid);

            entity.Property(user => user.PlayerUuid).HasColumnName("player_uuid");
            entity.Property(user => user.Mcid).HasColumnName("mcid").HasMaxLength(100);
            entity.Property(user => user.WebAdmin).HasColumnName("web_admin");
            entity.Property(user => user.IsProfilePublic).HasColumnName("is_profile_public");
            entity.Property(user => user.CreatedAt).HasColumnName("created_at");
            entity.Property(user => user.UpdatedAt).HasColumnName("updated_at");
            entity.Property(user => user.FirstWebLoginAt).HasColumnName("first_web_login_at");
            entity.Property(user => user.LastWebLoginAt).HasColumnName("last_web_login_at");
        });
        modelBuilder.Entity<WebCredentialEntity>(entity =>
        {
            entity.ToTable("web_credential", "dbo");
            entity.HasKey(x => x.PlayerUuid);
            entity.Property(x => x.PlayerUuid).HasColumnName("player_uuid");
            entity.Property(x => x.LoginId).HasColumnName("login_id").HasMaxLength(64);
            entity.Property(x => x.PasswordHash).HasColumnName("password_hash").HasMaxLength(512);
            entity.Property(x => x.Enabled).HasColumnName("enabled");
            entity.Property(x => x.SessionVersion).HasColumnName("session_version").IsConcurrencyToken();
            entity.Property(x => x.CreatedAtUtc).HasColumnName("created_at_utc");
            entity.Property(x => x.UpdatedAtUtc).HasColumnName("updated_at_utc");
            entity.HasIndex(x => x.LoginId).IsUnique().HasFilter("[login_id] IS NOT NULL");
        });
        modelBuilder.Entity<WebCredentialLoginAttemptEntity>(entity =>
        {
            entity.ToTable("web_credential_login_attempt", "dbo");
            entity.HasKey(x => x.LoginId);
            entity.Property(x => x.LoginId).HasColumnName("login_id").HasMaxLength(64);
            entity.Property(x => x.FailedAttempts).HasColumnName("failed_attempts");
            entity.Property(x => x.WindowStartedAtUtc).HasColumnName("window_started_at_utc");
            entity.Property(x => x.LockedUntilUtc).HasColumnName("locked_until_utc");
            entity.Property(x => x.Revision).HasColumnName("revision").IsConcurrencyToken();
        });
    }
}
