using AstralRecordApi.Data.Entities;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Data;

public static class DonationDiscordMapping
{
    public static void Configure(ModelBuilder modelBuilder)
    {
        modelBuilder.Entity<DonationDiscordLinkEntity>(entity =>
        {
            entity.ToTable("donation_discord_link", "dbo");
            entity.HasKey(x => x.UserUuid);
            entity.Property(x => x.UserUuid).HasColumnName("user_uuid").ValueGeneratedNever();
            entity.Property(x => x.DiscordUserId).HasColumnName("discord_user_id").HasMaxLength(32).IsRequired();
            entity.Property(x => x.DiscordName).HasColumnName("discord_name").HasMaxLength(128).IsRequired();
            entity.Property(x => x.ProtectedAccessToken).HasColumnName("protected_access_token").HasMaxLength(4096).IsRequired();
            entity.Property(x => x.ProtectedRefreshToken).HasColumnName("protected_refresh_token").HasMaxLength(4096).IsRequired();
            entity.Property(x => x.IsGuildMember).HasColumnName("is_guild_member");
            entity.Property(x => x.VerifiedAtUtc).HasColumnName("verified_at_utc");
            entity.Property(x => x.Revision).HasColumnName("revision").IsConcurrencyToken();
            entity.HasIndex(x => x.DiscordUserId).IsUnique();
        });
    }
}
