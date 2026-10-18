using AstralRecordControl.Contracts;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Data;

public sealed partial class ControlDbContext
{
    public DbSet<InventoryHost> Hosts => Set<InventoryHost>();
    public DbSet<InventoryServer> Servers => Set<InventoryServer>();
    public DbSet<InventoryEnvironment> Environments => Set<InventoryEnvironment>();
    public DbSet<InventoryCredential> AgentCredentials => Set<InventoryCredential>();
    public DbSet<InventoryVersion> InventoryVersions => Set<InventoryVersion>();
    public DbSet<InventoryRequest> InventoryRequests => Set<InventoryRequest>();
    public DbSet<InventoryEvent> AgentEvents => Set<InventoryEvent>();
    // Reserved metadata only: P0 never creates or moves transfer bytes.
    public DbSet<InventoryTransferScope> TransferScopes => Set<InventoryTransferScope>();

    partial void ConfigureInventory(ModelBuilder builder)
    {
        builder.Entity<InventoryHost>().HasKey(x => x.HostId);
        builder.Entity<InventoryHost>().HasIndex(x => x.HostKey).IsUnique();
        builder.Entity<InventoryHost>().Property(x => x.Revision).IsConcurrencyToken();
        builder.Entity<InventoryServer>().HasKey(x => x.ServerId);
        builder.Entity<InventoryServer>().HasIndex(x => x.ServerKey).IsUnique();
        builder.Entity<InventoryServer>().Property(x => x.DesiredRevision).IsConcurrencyToken();
        builder.Entity<InventoryServer>().HasOne<InventoryHost>().WithMany().HasForeignKey(x => x.HostId).OnDelete(DeleteBehavior.Restrict);
        builder.Entity<InventoryServer>().HasOne<InventoryEnvironment>().WithMany().HasForeignKey(x => x.EnvironmentId).OnDelete(DeleteBehavior.Restrict);
        builder.Entity<InventoryEnvironment>().HasKey(x => x.EnvironmentId);
        builder.Entity<InventoryEnvironment>().HasIndex(x => x.EnvironmentKey).IsUnique();
        builder.Entity<InventoryEnvironment>().Property(x => x.Revision).IsConcurrencyToken();
        builder.Entity<InventoryCredential>().HasKey(x => x.CredentialId);
        builder.Entity<InventoryCredential>().HasIndex(x => x.TokenHashHex).IsUnique();
        builder.Entity<InventoryCredential>().HasOne<InventoryHost>().WithMany().HasForeignKey(x => x.HostId).OnDelete(DeleteBehavior.Restrict);
        builder.Entity<InventoryVersion>().HasKey(x => new { x.HostId, x.Revision });
        builder.Entity<InventoryVersion>().HasOne<InventoryHost>().WithMany().HasForeignKey(x => x.HostId).OnDelete(DeleteBehavior.Restrict);
        builder.Entity<InventoryRequest>().HasKey(x => x.IdempotencyKey);
        builder.Entity<InventoryEvent>().HasKey(x => new { x.HostId, x.EventSeq });
        builder.Entity<InventoryEvent>().HasOne<InventoryHost>().WithMany().HasForeignKey(x => x.HostId).OnDelete(DeleteBehavior.Restrict);
        builder.Entity<InventoryTransferScope>().HasKey(x => x.TransferId);
    }
}

public sealed class InventoryHost
{
    public Guid HostId { get; set; }
    public string HostKey { get; set; } = "";
    public string DisplayName { get; set; } = "";
    public bool Enabled { get; set; } = true;
    public long Revision { get; set; } = 1;
    public string CapabilitiesJson { get; set; } = "[]";
    public string ResourcesJson { get; set; } = "{\"roots\":[],\"executables\":[]}";
    public DateTimeOffset? LastSeenAtUtc { get; set; }
    public Guid? ConnectionEpoch { get; set; }
    public Guid? AgentStoreId { get; set; }
    public Guid? RunnerEpoch { get; set; }
    public long AckAgentEventSeq { get; set; }
    public HostConnectionState ConnectionState { get; set; }
    public long DesiredInventoryRevision { get; set; }
    public long AppliedInventoryRevision { get; set; }
    public string? InventoryErrorCode { get; set; }
    public bool RecoveryRequired { get; set; }
    public string RuntimeSnapshotJson { get; set; } = "[]";
    public DateTimeOffset CreatedAtUtc { get; set; }
    public DateTimeOffset UpdatedAtUtc { get; set; }
}
public sealed class InventoryServer
{
    public Guid ServerId { get; set; }
    public Guid HostId { get; set; }
    public Guid? EnvironmentId { get; set; }
    public string ServerKey { get; set; } = "";
    public string DisplayName { get; set; } = "";
    public string? RuntimeServerId { get; set; }
    public long DesiredRevision { get; set; }
    public long AppliedRevision { get; set; }
    public string DesiredConfigJson { get; set; } = "";
    public string ConfigSha256 { get; set; } = "";
    public bool ReadOnlyPilot { get; set; } = true;
    public DateTimeOffset CreatedAtUtc { get; set; }
    public DateTimeOffset UpdatedAtUtc { get; set; }
}
public sealed class InventoryEnvironment
{
    public Guid EnvironmentId { get; set; }
    public string EnvironmentKey { get; set; } = "";
    public string DisplayName { get; set; } = "";
    public long Revision { get; set; } = 1;
    public DateTimeOffset CreatedAtUtc { get; set; }
    public DateTimeOffset UpdatedAtUtc { get; set; }
}
public sealed class InventoryCredential
{
    public Guid CredentialId { get; set; }
    public Guid HostId { get; set; }
    public string TokenHashHex { get; set; } = "";
    public DateTimeOffset IssuedAtUtc { get; set; }
    public DateTimeOffset? RevokedAtUtc { get; set; }
}
public sealed class InventoryVersion
{
    public Guid HostId { get; set; }
    public long Revision { get; set; }
    public string SnapshotJson { get; set; } = "";
    public string Sha256 { get; set; } = "";
    public DateTimeOffset CreatedAtUtc { get; set; }
}
public sealed class InventoryRequest
{
    public Guid IdempotencyKey { get; set; }
    public string RequestSha256 { get; set; } = "";
    public Guid EntityId { get; set; }
    public string EntityKind { get; set; } = "";
    public DateTimeOffset CreatedAtUtc { get; set; }
}
public sealed class InventoryEvent
{
    public Guid HostId { get; set; }
    public long EventSeq { get; set; }
    public string Json { get; set; } = "";
    public string Sha256 { get; set; } = "";
    public DateTimeOffset CreatedAtUtc { get; set; }
}
public sealed class InventoryTransferScope
{
    public Guid TransferId { get; set; }
    public Guid? SourceHostId { get; set; }
    public Guid? DestinationHostId { get; set; }
}
