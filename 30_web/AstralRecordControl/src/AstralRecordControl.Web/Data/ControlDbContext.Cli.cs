using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Data;

public sealed partial class ControlDbContext
{
    public DbSet<CliCredential> CliCredentials => Set<CliCredential>();
    partial void ConfigureCli(ModelBuilder builder)
    {
        builder.Entity<CliCredential>().HasKey(x => x.CredentialId);
        builder.Entity<CliCredential>().HasIndex(x => x.TokenHashHex).IsUnique();
        builder.Entity<CliCredential>().HasOne<ControlUser>().WithMany().HasForeignKey(x => x.OwnerUserId).OnDelete(DeleteBehavior.Restrict);
    }
}
public sealed class CliCredential
{
    public Guid CredentialId { get; set; }
    public Guid OwnerUserId { get; set; }
    public string TokenHashHex { get; set; } = "";
    public string SecurityStamp { get; set; } = "";
    public DateTime ExpiresAtUtc { get; set; }
    public DateTime? RevokedAtUtc { get; set; }
    public DateTime CreatedAtUtc { get; set; }
}
