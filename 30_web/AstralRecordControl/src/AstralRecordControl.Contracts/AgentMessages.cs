using System.Text.Json;
using System.Text.Json.Serialization;

namespace AstralRecordControl.Contracts;

public sealed record AgentHello
{
    public string AgentVersion { get; init; } = "";
    public int[] ProtocolVersions { get; init; } = [1];
    public Guid AgentStoreId { get; init; }
    public long MaxLocalEventSeq { get; init; }
    public Guid RunnerEpoch { get; init; }
    public long AppliedInventoryRevision { get; init; }
    public string[] Capabilities { get; init; } = ["P0.Inventory"];
    public HostResources LocalResources { get; init; } = new();
}
public sealed record HelloAck(int ProtocolVersion, Guid ConnectionEpoch, long ControllerAckEventSeq, long DesiredInventoryRevision, int HeartbeatSeconds = 10);
public sealed record ServerRuntimeSnapshot
{
    public Guid ServerId { get; init; }
    public long ConfigRevision { get; init; }
    public long StateRevision { get; init; }
    public ProcessState ProcessState { get; init; }
    public Guid? CurrentSessionId { get; init; }
    public GameReadiness Readiness { get; init; }
}
public sealed record ResourceHold
{
    public Guid ReservationId { get; init; }
    public Guid OwnerWorkflowId { get; init; }
    public string[] ResourceKeys { get; init; } = [];
    public long Fence { get; init; }
    public HoldMode Mode { get; init; }
}
public sealed record RunnerSnapshot
{
    public Guid RunnerEpoch { get; init; }
    public long AppliedInventoryRevision { get; init; }
    public ServerRuntimeSnapshot[] Servers { get; init; } = [];
    public ResourceHold[] Holds { get; init; } = [];
}
public sealed record ReconcileRequest
{
    public Guid ConnectionEpoch { get; init; }
    public Guid RunnerEpoch { get; init; }
    public long AppliedInventoryRevision { get; init; }
    public ServerRuntimeSnapshot[] Servers { get; init; } = [];
    public ResourceHold[] Holds { get; init; } = [];
    public JsonElement[] Operations { get; init; } = [];
}
public sealed record ReconcileAck(long AckEventSeq, Guid[] OperationIds, bool InventoryRequired);
public sealed record InventoryApplied(Guid ConnectionEpoch, long InventoryRevision, string Sha256, bool Success, string? ErrorCode = null);
public sealed record InventoryChanged(Guid ConnectionEpoch, long InventoryRevision, string Sha256);
public sealed record AgentHeartbeat
{
    public Guid ConnectionEpoch { get; init; }
    public Guid RunnerEpoch { get; init; }
    public DateTimeOffset ObservedAtUtc { get; init; }
    public bool RunnerAvailable { get; init; }
    public ServerRuntimeSnapshot[] ServerSnapshots { get; init; } = [];
    public JsonElement[] Metrics { get; init; } = [];
}
public sealed record AgentEvent(long EventSeq, Guid? OperationId, long? StatusVersion, string EventType, DateTimeOffset OccurredAtUtc, JsonElement Body)
{
    [JsonConstructor]
    public AgentEvent(long eventSeq, string eventType, DateTimeOffset occurredAtUtc, JsonElement body, Guid? operationId = null, long? statusVersion = null)
        : this(eventSeq, operationId, statusVersion, eventType, occurredAtUtc, body) { }
}
public sealed record EventBatch(Guid ConnectionEpoch, AgentEvent[] Events);
public sealed record EventAck(long AckEventSeq);
public sealed record IpcRequest(int ProtocolVersion, Guid RequestId, string Method, JsonElement Body);
public sealed record IpcResponse(Guid RequestId, bool Ok, JsonElement Body, string? ErrorCode = null);
public sealed record ApplyInventoryRequest(InventoryEnvelope Inventory);
public sealed record AcquireHoldRequest(Guid ReservationId, Guid OwnerWorkflowId, string[] ResourceKeys, HoldMode Mode);
public sealed record ReleaseHoldRequest(Guid ReservationId, Guid OwnerWorkflowId, long ExpectedFence);

public sealed record HostCreateRequest(string HostKey, string DisplayName);
public sealed record HostUpdateRequest(string DisplayName, bool Enabled);
public sealed record ServerCreateRequest(Guid HostId, string ServerKey, string DisplayName, Guid? EnvironmentId, string? RuntimeServerId, ServerConfig Config)
{
    [JsonConstructor]
    public ServerCreateRequest(Guid hostId, string serverKey, string displayName, ServerConfig config, Guid? environmentId = null, string? runtimeServerId = null)
        : this(hostId, serverKey, displayName, environmentId, runtimeServerId, config) { }
}
public sealed record ServerUpdateRequest(string DisplayName, Guid? EnvironmentId, string? RuntimeServerId, ServerConfig Config)
{
    [JsonConstructor]
    public ServerUpdateRequest(string displayName, ServerConfig config, Guid? environmentId = null, string? runtimeServerId = null)
        : this(displayName, environmentId, runtimeServerId, config) { }
}
public sealed record CredentialIssued(Guid CredentialId, string Token);
