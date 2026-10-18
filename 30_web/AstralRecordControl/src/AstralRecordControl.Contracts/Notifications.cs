namespace AstralRecordControl.Contracts;

// Public Webhook payload. It deliberately contains no raw Agent body, command,
// path, credential, or destination URL.
public sealed record NotificationPayload(
    Guid EventId,
    string EventType,
    DateTimeOffset OccurredAtUtc,
    Guid? HostId,
    Guid? ServerId,
    Guid? OperationId,
    string? Outcome);

// Persisted by Agent before transmission; both snapshots refer to the same Server.
// UnexpectedExit is set only when Runner observes an exit without a requested stop.
public sealed record RuntimeChangedBody(
    ServerRuntimeSnapshot? Previous,
    ServerRuntimeSnapshot Current,
    bool UnexpectedExit = false,
    bool RecoveryRequired = false);

public static class NotificationEventTypes
{
    public const string Started = "Started";
    public const string Completed = "Completed";
    public const string Crashed = "Crashed";
    public const string BackupSucceeded = "BackupSucceeded";
    public const string BackupFailed = "BackupFailed";
    public const string RecoveryRequired = "RecoveryRequired";
}
