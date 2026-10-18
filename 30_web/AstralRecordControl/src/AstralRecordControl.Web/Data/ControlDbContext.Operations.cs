using AstralRecordControl.Contracts;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Data;

public sealed partial class ControlDbContext
{
    public DbSet<ControlOperation> Operations => Set<ControlOperation>();
    public DbSet<ControlWorkflow> Workflows => Set<ControlWorkflow>();
    public DbSet<ControlReservation> Reservations => Set<ControlReservation>();
    public DbSet<ControlReservationLease> ReservationLeases => Set<ControlReservationLease>();

    partial void ConfigureOperations(ModelBuilder builder)
    {
        builder.Entity<ControlOperation>().HasKey(x => x.OperationId);
        builder.Entity<ControlOperation>().HasIndex(x => x.IdempotencyKey).IsUnique();
        builder.Entity<ControlOperation>().HasOne<InventoryHost>().WithMany().HasForeignKey(x => x.HostId).OnDelete(DeleteBehavior.Restrict);
        builder.Entity<ControlOperation>().HasOne<InventoryServer>().WithMany().HasForeignKey(x => x.ServerId).OnDelete(DeleteBehavior.Restrict);
        builder.Entity<ControlOperation>().HasOne<ControlWorkflow>().WithMany().HasForeignKey(x => x.WorkflowId).OnDelete(DeleteBehavior.Restrict);
        builder.Entity<ControlWorkflow>().HasKey(x => x.WorkflowId);
        builder.Entity<ControlReservation>().HasKey(x => x.ResourceKey);
        builder.Entity<ControlReservation>().HasIndex(x => x.ReservationId);
        builder.Entity<ControlReservation>().HasIndex(x => new { x.ReservationId, x.OwnerWorkflowId });
        builder.Entity<ControlReservationLease>().HasKey(x => x.ReservationId);
        builder.Entity<ControlReservationLease>().HasIndex(x => x.WorkflowId).IsUnique();
    }
}

public sealed class ControlOperation
{
    public Guid OperationId { get; set; }
    public Guid IdempotencyKey { get; set; }
    public Guid HostId { get; set; }
    public Guid? ServerId { get; set; }
    public Guid WorkflowId { get; set; }
    public Guid ReservationId { get; set; }
    public OperationKind Kind { get; set; }
    public string RequestSha256 { get; set; } = "";
    public string CommandJson { get; set; } = "";
    public string CommandSha256 { get; set; } = "";
    public OperationStatus Status { get; set; } = OperationStatus.Queued;
    public long StatusVersion { get; set; }
    public long EventSeq { get; set; }
    public DateTimeOffset CreatedAtUtc { get; set; }
    public DateTimeOffset? AcceptedAtUtc { get; set; }
    public DateTimeOffset? StartedAtUtc { get; set; }
    public DateTimeOffset? FinishedAtUtc { get; set; }
    public string? ResultJson { get; set; }
    public string? ErrorCode { get; set; }
}

public sealed class ControlWorkflow
{
    public Guid WorkflowId { get; set; }
    public string Kind { get; set; } = "SingleOperation";
    public WorkflowStatus Status { get; set; } = WorkflowStatus.Planned;
    public string RequestJson { get; set; } = "";
    public string ResourceKeysJson { get; set; } = "[]";
    public long Revision { get; set; } = 1;
    public bool RecoveryRequired { get; set; }
    public DateTimeOffset CreatedAtUtc { get; set; }
    public DateTimeOffset UpdatedAtUtc { get; set; }
}

public sealed class ControlReservation
{
    public string ResourceKey { get; set; } = "";
    public Guid ReservationId { get; set; }
    public Guid OwnerWorkflowId { get; set; }
    public HoldMode Mode { get; set; }
    public long Fence { get; set; }
    public ReservationStatus State { get; set; } = ReservationStatus.Preparing;
    public Guid? HostId { get; set; }
    public DateTimeOffset CreatedAtUtc { get; set; }
    public DateTimeOffset? ReleasedAtUtc { get; set; }
}

// Resource rows carry the current fence. This immutable identity retains released
// leases after those rows are reused by a later workflow.
public sealed class ControlReservationLease
{
    public Guid ReservationId { get; set; }
    public Guid WorkflowId { get; set; }
    public string ResourceKeysJson { get; set; } = "[]";
    public string FencesJson { get; set; } = "[]";
    public ReservationStatus Status { get; set; } = ReservationStatus.Preparing;
    public string? AdapterId { get; set; }
    public string? RunReference { get; set; }
    public DateTimeOffset CreatedAtUtc { get; set; }
    public DateTimeOffset? ReleasedAtUtc { get; set; }
}
