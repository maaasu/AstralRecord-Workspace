namespace AstralRecordWeb.Models;

public sealed class ManagedNetworkSettings
{
    public int Revision { get; init; }
    public string LobbyServerId { get; init; } = "lobby";
    public int TransferCooldownSeconds { get; init; } = 30;
    public int TabRefreshSeconds { get; init; } = 2;
    public int PresenceHeartbeatSeconds { get; init; } = 10;
    public IReadOnlyList<Guid> AuthorityUsers { get; init; } = [];
    public IReadOnlyList<ManagedNetworkChannel> Channels { get; init; } = [];
    // Response-only display metadata. UUID lists remain the persisted authority.
    public IReadOnlyList<NetworkManagedPlayer> Players { get; init; } = [];
}

public sealed class ManagedNetworkChannel
{
    public string ServerId { get; init; } = string.Empty;
    public string DisplayName { get; init; } = string.Empty;
    public bool IsGame { get; init; }
    public int MaxPlayers { get; init; } = 30;
    public int DonorExtraPlayers { get; init; }
    public int AdminExtraPlayers { get; init; }
    public bool DiscordEnabled { get; init; } = true;
    public bool WhitelistEnabled { get; init; }
    public bool DonorOnly { get; init; }
    public IReadOnlyList<Guid> DebugUsers { get; init; } = [];
    public IReadOnlyList<Guid> WhitelistUsers { get; init; } = [];
}

public sealed record NetworkManagedPlayer(Guid UserUuid, string Mcid);

public sealed class NetworkBanStateResponse
{
    public Guid UserUuid { get; init; }
    public string Mcid { get; init; } = string.Empty;
    public int Revision { get; init; }
    public bool IsBanned { get; init; }
    public bool IsActive { get; init; }
    public bool IsIndefinite { get; init; }
    public DateTimeOffset? ExpiresAtUtc { get; init; }
    public string? Reason { get; init; }
    public DateTimeOffset ServerTimeUtc { get; init; }
}

public sealed class NetworkBanUpdateRequest
{
    public int ExpectedRevision { get; init; }
    public bool IsBanned { get; init; }
    public DateTimeOffset? ExpiresAtUtc { get; init; }
    public string? Reason { get; init; }
}

public sealed record NetworkChannelAccessResponse(
    Guid UserUuid, string ServerId, bool ChannelKnown, bool IsAuthority,
    bool DebugUser, bool Whitelisted, bool WhitelistEnabled, bool Allowed, int Permission);
