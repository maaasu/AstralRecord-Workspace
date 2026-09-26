using AstralRecordApi.Data.Entities;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Data;

/// <summary>ManagementDB内の寄付台帳・配布指示を定義します。</summary>
public static class DonationMapping
{
    public static void Configure(ModelBuilder modelBuilder)
    {
        var ledger = modelBuilder.Entity<DonationLedgerEntity>();
        ledger.ToTable("donation_ledger", "dbo");
        ledger.HasKey(x => x.UserUuid);
        ledger.Property(x => x.Revision).IsConcurrencyToken();
        var request = modelBuilder.Entity<DonationRequestEntity>();
        request.ToTable("donation_request", "dbo");
        request.HasKey(x => x.Id);
        request.Property(x => x.Mcid).HasMaxLength(100);
        request.Property(x => x.Status).HasMaxLength(20);
        request.Property(x => x.TermsVersion).HasMaxLength(32);
        request.Property(x => x.DiscordUserId).HasMaxLength(32);
        request.Property(x => x.DiscordName).HasMaxLength(128);
        request.Property(x => x.Reason).HasMaxLength(1000);
        request.HasIndex(x => new { x.UserUuid, x.CreatedAtUtc });
        request.HasIndex(x => x.Status);
        var fingerprint = modelBuilder.Entity<DonationEntryFingerprintEntity>();
        fingerprint.ToTable("donation_entry_fingerprint", "dbo");
        fingerprint.HasKey(x => x.Fingerprint);
        fingerprint.Property(x => x.Fingerprint).HasMaxLength(64);
        fingerprint.HasIndex(x => x.RequestId);
        var grant = modelBuilder.Entity<DonationGrantEntity>();
        grant.ToTable("donation_grant", "dbo");
        grant.HasKey(x => x.Id);
        grant.Property(x => x.Message).HasMaxLength(2000);
        grant.HasIndex(x => new { x.AccountUuid, x.ThroughAmount }).IsUnique();
        grant.HasIndex(x => new { x.UserUuid, x.DeliveredAtUtc });
        var notification = modelBuilder.Entity<DonationNotificationEntity>();
        notification.ToTable("donation_notification", "dbo");
        notification.HasKey(x => x.Id);
        notification.Property(x => x.Kind).HasMaxLength(32);
        notification.Property(x => x.Message).HasMaxLength(2000);
        notification.HasIndex(x => new { x.UserUuid, x.AcknowledgedAtUtc });
        foreach (var type in new[] { typeof(DonationLedgerEntity), typeof(DonationRequestEntity),
                     typeof(DonationEntryFingerprintEntity), typeof(DonationGrantEntity), typeof(DonationNotificationEntity) })
        {
            foreach (var property in modelBuilder.Entity(type).Metadata.GetProperties())
                property.SetColumnName(System.Text.RegularExpressions.Regex.Replace(property.Name, "(?<!^)([A-Z])", "_$1").ToLowerInvariant());
        }
    }
}
