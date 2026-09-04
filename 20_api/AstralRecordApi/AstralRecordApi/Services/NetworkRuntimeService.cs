using System.Collections.Concurrent;
using AstralRecordApi.Models;

namespace AstralRecordApi.Services;

/// <summary>
/// 単一APIプロセス内でのみ有効なネットワーク実行時状態を管理します。
/// プレイヤー・サーバーのheartbeatはTTLで失効し、チャットは再送重複を除外したリングバッファに保持します。
/// </summary>
public sealed class NetworkRuntimeService(TimeProvider timeProvider) : INetworkRuntimeService
{
    private static readonly TimeSpan PresenceTtl = TimeSpan.FromSeconds(30);
    private const int ChatCapacity = 2_000;

    private readonly ConcurrentDictionary<Guid, NetworkPlayerPresenceResponse> players = new();
    private readonly ConcurrentDictionary<string, NetworkServerPresenceResponse> servers =
        new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<Guid, NetworkChatMessageResponse> chatsById = [];
    private readonly Queue<NetworkChatMessageResponse> chats = new();
    private readonly object chatLock = new();
    private readonly Guid generationId = Guid.NewGuid();
    private long nextChatSequence;

    public NetworkPlayerPresenceResponse UpsertPlayer(NetworkPlayerHeartbeatRequest request)
    {
        var now = timeProvider.GetUtcNow().UtcDateTime;
        var presence = new NetworkPlayerPresenceResponse(
            request.Uuid,
            request.Mcid.Trim(),
            request.ServerId.Trim(),
            request.Channel.Trim(),
            request.DisplayName.Trim(),
            request.Level,
            request.ClassName?.Trim(),
            request.Afk,
            now);
        players[request.Uuid] = presence;
        return presence;
    }

    public bool RemovePlayer(Guid uuid) => players.TryRemove(uuid, out _);

    public IReadOnlyList<NetworkPlayerPresenceResponse> GetPlayers()
    {
        PurgeExpiredPresence();
        return players.Values
            .OrderBy(value => value.Channel, StringComparer.OrdinalIgnoreCase)
            .ThenBy(value => value.Mcid, StringComparer.OrdinalIgnoreCase)
            .ToArray();
    }

    public NetworkServerPresenceResponse UpsertServer(NetworkServerHeartbeatRequest request)
    {
        var presence = new NetworkServerPresenceResponse(
            request.ServerId.Trim(),
            request.DisplayName.Trim(),
            request.State.Trim(),
            request.OnlineCount,
            request.Capacity,
            timeProvider.GetUtcNow().UtcDateTime);
        servers[presence.ServerId] = presence;
        return presence;
    }

    public IReadOnlyList<NetworkServerPresenceResponse> GetServers()
    {
        PurgeExpiredPresence();
        return servers.Values
            .OrderBy(value => value.ServerId, StringComparer.OrdinalIgnoreCase)
            .ToArray();
    }

    public NetworkChatMessageResponse PublishChat(NetworkChatPublishRequest request)
    {
        lock (chatLock)
        {
            if (chatsById.TryGetValue(request.MessageId, out var existing))
                return existing;

            var published = new NetworkChatMessageResponse(
                ++nextChatSequence,
                request.MessageId,
                request.Source.Trim().ToLowerInvariant(),
                request.SourceServerId.Trim(),
                request.AuthorName.Trim(),
                request.Message.Trim(),
                timeProvider.GetUtcNow().UtcDateTime);
            chats.Enqueue(published);
            chatsById[published.MessageId] = published;
            while (chats.Count > ChatCapacity)
            {
                var removed = chats.Dequeue();
                chatsById.Remove(removed.MessageId);
            }
            return published;
        }
    }

    public NetworkChatBatchResponse GetChatAfter(long afterSequence, string? source)
    {
        var normalizedSource = string.IsNullOrWhiteSpace(source)
            ? null
            : source.Trim().ToLowerInvariant();
        lock (chatLock)
        {
            if (afterSequence > nextChatSequence)
                afterSequence = 0;
            var messages = chats
                .Where(value => value.Sequence > afterSequence
                    && (normalizedSource is null || value.Source == normalizedSource))
                .Take(200)
                .ToArray();
            return new NetworkChatBatchResponse(generationId, messages);
        }
    }

    private void PurgeExpiredPresence()
    {
        var cutoff = timeProvider.GetUtcNow().UtcDateTime - PresenceTtl;
        foreach (var pair in players)
        {
            if (pair.Value.LastSeenUtc < cutoff)
                players.TryRemove(pair.Key, out _);
        }
        foreach (var pair in servers)
        {
            if (pair.Value.LastSeenUtc < cutoff)
                servers.TryRemove(pair.Key, out _);
        }
    }
}
