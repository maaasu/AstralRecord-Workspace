using AstralRecordControl.Contracts;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Data;

public sealed partial class ControlDbContext
{
    public DbSet<ControlTransfer> Transfers => Set<ControlTransfer>();
    public DbSet<ControlTransferChunk> TransferChunks => Set<ControlTransferChunk>();

    partial void ConfigureTransfers(ModelBuilder builder)
    {
        builder.Entity<ControlTransfer>().HasKey(x => x.TransferId);
        builder.Entity<ControlTransfer>().Property(x => x.Status).HasConversion<string>();
        builder.Entity<ControlTransfer>().HasIndex(x => x.IdempotencyKey).IsUnique();
        builder.Entity<ControlTransfer>().HasIndex(x => x.ExpiresAtUtc);
        builder.Entity<ControlTransfer>().HasOne<InventoryServer>().WithMany().HasForeignKey(x => x.ServerId).OnDelete(DeleteBehavior.Restrict);
        builder.Entity<ControlTransferChunk>().HasKey(x => new { x.TransferId, x.Offset });
        builder.Entity<ControlTransferChunk>().HasOne<ControlTransfer>().WithMany().HasForeignKey(x => x.TransferId).OnDelete(DeleteBehavior.Cascade);
    }
}

public sealed class ControlTransfer
{
    public Guid TransferId { get; set; }
    public Guid IdempotencyKey { get; set; }
    public string RequestSha256 { get; set; } = "";
    public Guid? SourceHostId { get; set; }
    public Guid? DestinationHostId { get; set; }
    public Guid OperationId { get; set; }
    public Guid ServerId { get; set; }
    public Guid? ConsumingOperationId { get; set; }
    public TransferStatus Status { get; set; }
    public long? SizeBytes { get; set; }
    public string? Sha256 { get; set; }
    public long CommittedOffset { get; set; }
    public DateTimeOffset CreatedAtUtc { get; set; }
    public DateTimeOffset ExpiresAtUtc { get; set; }
    public DateTimeOffset? VerifiedAtUtc { get; set; }
    public DateTimeOffset? ConsumedAtUtc { get; set; }
}

public sealed class ControlTransferChunk
{
    public Guid TransferId { get; set; }
    public long Offset { get; set; }
    public int SizeBytes { get; set; }
    public string Sha256 { get; set; } = "";
}
