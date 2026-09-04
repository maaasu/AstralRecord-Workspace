namespace AstralRecordApi.Models;

public sealed record NetworkAdmissionResponse(
    Guid Uuid,
    string Mcid,
    bool Registered,
    bool Admitted,
    string? DenyReason,
    int Permission,
    bool BanIndefinite,
    DateTime? BanDate,
    Guid? AccountId,
    DateTime ServerTimeUtc);

public sealed record NetworkPlayerHeartbeatRequest(
    Guid Uuid,
    string Mcid,
    string ServerId,
    string Channel,
    string DisplayName,
    int? Level,
    string? ClassName,
    bool Afk);

public sealed record NetworkPlayerPresenceResponse(
    Guid Uuid,
    string Mcid,
    string ServerId,
    string Channel,
    string DisplayName,
    int? Level,
    string? ClassName,
    bool Afk,
    DateTime LastSeenUtc);

public sealed record NetworkServerHeartbeatRequest(
    string ServerId,
    string DisplayName,
    string State,
    int OnlineCount,
    int Capacity);

public sealed record NetworkServerPresenceResponse(
    string ServerId,
    string DisplayName,
    string State,
    int OnlineCount,
    int Capacity,
    DateTime LastSeenUtc);

public sealed record NetworkChatPublishRequest(
    Guid MessageId,
    string Source,
    string SourceServerId,
    string AuthorName,
    string Message);

public sealed record NetworkChatMessageResponse(
    long Sequence,
    Guid MessageId,
    string Source,
    string SourceServerId,
    string AuthorName,
    string Message,
    DateTime CreatedAtUtc);

public sealed record NetworkChatBatchResponse(
    Guid GenerationId,
    IReadOnlyList<NetworkChatMessageResponse> Messages);
