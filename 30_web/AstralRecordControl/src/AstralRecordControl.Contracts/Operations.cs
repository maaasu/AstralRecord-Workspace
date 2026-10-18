using System.Text;
using System.Text.Json;

namespace AstralRecordControl.Contracts;

public enum OperationStatus { Queued, Accepted, Running, Succeeded, Failed, Rejected, Expired, Cancelled, NeedsAttention }
public enum WorkflowStatus { Planned, Reserving, Running, WaitingForStartup, Succeeded, Failed, RecoveryRequired, Cancelled }
public enum ReservationStatus { Preparing, Prepared, Releasing, Released, RecoveryRequired }
public enum ExecutionPhase { Accepted, Starting, Running, Stopping, Dispatching, Sent, Completed, Recovering, Unknown }
public enum ProcessRequirement { Stopped, Running }
public enum WorkflowRecoveryAction { Resume, AbortAfterInspection, KillAfterInspection }
public enum GateOutcome { Succeeded, Failed }

public sealed record OperationCommand
{
    public int SchemaVersion { get; init; } = 1;
    public Guid OperationId { get; init; }
    public Guid HostId { get; init; }
    public Guid? ServerId { get; init; }
    public Guid? WorkflowId { get; init; }
    public Guid? ReservationId { get; init; }
    public long? ReservationFence { get; init; }
    public OperationKind Kind { get; init; }
    public long? ConfigRevision { get; init; }
    public long? ExpectedStateRevision { get; init; }
    public Guid? ExpectedServerSessionId { get; init; }
    public DateTimeOffset StartBeforeUtc { get; init; }
    public int ExecutionTimeoutSeconds { get; init; } = 120;
    public JsonElement Payload { get; init; } = ControlJson.Element(new { });
}

public sealed record DispatchOperationRequest(int ProtocolVersion, Guid ConnectionEpoch, Guid OperationId, string CommandJson, string CommandSha256);
public sealed record ExecuteRequest(Guid OperationId, string CommandJson, string CommandSha256);
public sealed record GetExecutionRequest(Guid OperationId);
public sealed record ExecutionSnapshot
{
    public Guid OperationId { get; init; }
    public string CommandSha256 { get; init; } = "";
    public OperationStatus Status { get; init; }
    public long StatusVersion { get; init; }
    public ExecutionPhase Phase { get; init; }
    public Guid? ServerSessionId { get; init; }
    public string? ErrorCode { get; init; }
    public JsonElement Result { get; init; } = ControlJson.Element(new { });
    public DateTimeOffset UpdatedAtUtc { get; init; }
    public bool IsTerminal => Status is OperationStatus.Succeeded or OperationStatus.Failed or OperationStatus.Rejected or OperationStatus.Expired or OperationStatus.Cancelled;
}

public sealed record PrepareHoldRequest(Guid ConnectionEpoch, AcquireHoldRequest Hold);
public sealed record PreparedHold(ResourceHold Hold, RunnerSnapshot Snapshot);
public sealed record ReleasePreparedHoldRequest(Guid ConnectionEpoch, ReleaseHoldRequest Hold);
public sealed record ReleasePreparedHoldResponse(Guid ReservationId, bool Released);
public sealed record AgentExecutionRequest(Guid ConnectionEpoch, Guid OperationId);
public sealed record AgentReadLogsRequest(Guid ConnectionEpoch, ReadLogsRequest Query);
public sealed record AgentSnapshotRequest(Guid ConnectionEpoch);
public sealed record AgentInspectRecoveryRequest(Guid ConnectionEpoch, Guid ServerId);

public sealed record ReadLogsRequest
{
    public Guid ServerId { get; init; }
    public Guid? SessionId { get; init; }
    public string? SegmentId { get; init; }
    public long AfterSequence { get; init; }
    public int Limit { get; init; } = 200;
}
public sealed record LogRow(long Sequence, DateTimeOffset OccurredAtUtc, string Stream, string Text, bool Truncated);
public sealed record LogBatch
{
    public Guid ServerId { get; init; }
    public Guid? SessionId { get; init; }
    public string? SegmentId { get; init; }
    public long NextCursor { get; init; }
    public long EarliestAvailableCursor { get; init; }
    public bool Gap { get; init; }
    public LogRow[] Rows { get; init; } = [];
}
public sealed record ServerSessionSnapshot
{
    public Guid ServerSessionId { get; init; }
    public Guid ServerId { get; init; }
    public Guid StartOperationId { get; init; }
    public Guid RunnerEpoch { get; init; }
    public int? ProcessId { get; init; }
    public DateTimeOffset? ProcessCreatedAtUtc { get; init; }
    public string ExecutableFingerprint { get; init; } = "";
    public long ConfigRevision { get; init; }
    public DateTimeOffset StartedAtUtc { get; init; }
    public DateTimeOffset? ExitedAtUtc { get; init; }
    public int? ExitCode { get; init; }
}
public sealed record InspectRecoveryRequest(Guid ServerId);
public sealed record RecoveryInspection(ServerRuntimeSnapshot Runtime, ServerSessionSnapshot? Session, bool ProcessAlive, bool IdentityMatches, bool RecoveryRequired);
public sealed record RunnerRecoveryRequest(Guid ServerId, Guid? ExpectedServerSessionId, WorkflowRecoveryAction Action, string Reason);

public sealed record ServerOperationRequest
{
    public OperationKind Kind { get; init; }
    public JsonElement Payload { get; init; } = ControlJson.Element(new { });
    public long ConfigRevision { get; init; }
    public long ExpectedStateRevision { get; init; }
    public Guid? ExpectedServerSessionId { get; init; }
    public Guid? WorkflowId { get; init; }
    public Guid? ReservationId { get; init; }
    public DateTimeOffset? StartBeforeUtc { get; init; }
    public int? ExecutionTimeoutSeconds { get; init; }
}
public sealed record GateServerCondition(Guid ServerId, ProcessRequirement RequiredState);
public sealed record ReservationAcquireRequest
{
    public Guid WorkflowId { get; init; }
    public string[] ResourceKeys { get; init; } = [];
    public HoldMode Mode { get; init; } = HoldMode.Maintenance;
    public string? AdapterId { get; init; }
    public string? RunReference { get; init; }
    public GateServerCondition[] ServerConditions { get; init; } = [];
}
public sealed record HostReservationFence(Guid HostId, long Fence);
public sealed record GateLease
{
    public Guid WorkflowId { get; init; }
    public Guid ReservationId { get; init; }
    public string[] ResourceKeys { get; init; } = [];
    public HostReservationFence[] Fences { get; init; } = [];
    public ReservationStatus Status { get; init; }
    public string? AdapterId { get; init; }
    public string? RunReference { get; init; }
}
public sealed record GateValidateRequest(Guid WorkflowId, HostReservationFence[] ExpectedFences, string StepKey, string RunReference);
public sealed record GateReleaseRequest(Guid WorkflowId, HostReservationFence[] ExpectedFences, GateOutcome Outcome, string RunReference);
public sealed record WorkflowRecoveryRequest(WorkflowRecoveryAction Action, string Reason);
public sealed record CliLoginRequest(string LoginName, string Password, string TotpOrRecoveryCode);
public sealed record CliTokenIssued(Guid CredentialId, string Token, DateTimeOffset ExpiresAtUtc);

public static class OperationValidation
{
    public static bool IsP1Kind(OperationKind kind) => kind is OperationKind.Start or OperationKind.Stop or OperationKind.Restart or OperationKind.Kill or OperationKind.ConsoleCommand;
    public static bool IsTerminal(OperationStatus status) => status is OperationStatus.Succeeded or OperationStatus.Failed or OperationStatus.Rejected or OperationStatus.Expired or OperationStatus.Cancelled;
    public static OperationCommand Read(ExecuteRequest request, Guid hostId)
    {
        if (request is null || request.CommandJson is null || Encoding.UTF8.GetByteCount(request.CommandJson) > ControlProtocol.MaxInventoryBytes || !ControlProtocol.IsHash(request.CommandSha256) || ControlProtocol.Hash(request.CommandJson) != request.CommandSha256) throw new ArgumentException("Invalid operation hash.");
        var command = ControlJson.Deserialize<OperationCommand>(request.CommandJson);
        Validate(command, hostId);
        if (command.OperationId != request.OperationId) throw new ArgumentException("Operation identity mismatch.");
        return command;
    }
    public static void Validate(OperationCommand command, Guid hostId)
    {
        if (command.SchemaVersion != 1 || command.OperationId == Guid.Empty || command.HostId != hostId || hostId == Guid.Empty || command.ServerId is null || command.ServerId == Guid.Empty || command.WorkflowId is null || command.WorkflowId == Guid.Empty || command.ReservationId is null || command.ReservationId == Guid.Empty || command.ReservationFence is null or < 1 || command.ConfigRevision is null or < 1 || command.ExpectedStateRevision is null or < 0 || command.ExpectedServerSessionId == Guid.Empty) throw new ArgumentException("Invalid operation identity/revision.");
        if (!IsP1Kind(command.Kind) || command.StartBeforeUtc.Offset != TimeSpan.Zero || command.StartBeforeUtc == default || command.ExecutionTimeoutSeconds is < 1 or > 86400 || command.Payload.ValueKind != JsonValueKind.Object) throw new ArgumentException("Invalid operation capability/limits.");
        var properties = command.Payload.EnumerateObject().ToArray();
        if (command.Kind is OperationKind.Start or OperationKind.Stop or OperationKind.Restart) { if (properties.Length != 0) throw new ArgumentException("Unexpected operation payload."); }
        else
        {
            var name = command.Kind == OperationKind.Kill ? "reason" : "command";
            if (properties.Length != 1 || properties[0].Name != name || properties[0].Value.ValueKind != JsonValueKind.String) throw new ArgumentException("Invalid operation payload.");
            var value = properties[0].Value.GetString();
            if (string.IsNullOrWhiteSpace(value) || Encoding.UTF8.GetByteCount(value) > (name == "command" ? 4096 : 1024) || value.Any(c => c is '\r' or '\n' or '\0')) throw new ArgumentException("Invalid operation text.");
        }
    }
}
