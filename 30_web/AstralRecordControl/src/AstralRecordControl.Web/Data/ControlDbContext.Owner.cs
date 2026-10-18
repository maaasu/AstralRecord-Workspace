using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Data;

public sealed partial class ControlDbContext
{
    public DbSet<OwnerSession> OwnerSessions => Set<OwnerSession>();
    public DbSet<OwnerSettings> OwnerSettings => Set<OwnerSettings>();
    partial void ConfigureOwner(ModelBuilder builder)
    {
        builder.Entity<ControlUser>().Property<int>("OwnerSlot").HasDefaultValue(1);
        builder.Entity<ControlUser>().HasIndex("OwnerSlot").IsUnique();
        builder.Entity<ControlUser>().ToTable(t => t.HasCheckConstraint("CK_SingleOwner", "OwnerSlot = 1"));
        builder.Entity<OwnerSettings>(b =>
        {
            b.ToTable("ControlSettings", t => t.HasCheckConstraint("CK_SettingsSingleton", "Id = 1"));
            b.HasKey(x => x.Id);
            b.HasOne<ControlUser>().WithOne().HasForeignKey<OwnerSettings>(x => x.OwnerUserId).OnDelete(DeleteBehavior.Restrict);
        });
        builder.Entity<OwnerSession>(b =>
        {
            b.HasKey(x => x.SessionId);
            b.HasOne<ControlUser>().WithMany().HasForeignKey(x => x.OwnerUserId).OnDelete(DeleteBehavior.Restrict);
        });
    }
}

public sealed class OwnerSettings
{
    public int Id { get; set; } = 1;
    public Guid OwnerUserId { get; set; }
    public int SchemaVersion { get; set; } = 1;
}

public sealed class OwnerSession
{
    public Guid SessionId { get; set; }
    public Guid OwnerUserId { get; set; }
    public string SecurityStamp { get; set; } = "";
    public DateTime CreatedAtUtc { get; set; }
    public DateTime ExpiresAtUtc { get; set; }
    public DateTime? RevokedAtUtc { get; set; }
    public DateTime ReauthenticatedAtUtc { get; set; }
}
