using AstralRecordApi.Models;

namespace AstralRecordApi.Services;

public interface INetworkRuntimeService
{
    NetworkPlayerPresenceResponse UpsertPlayer(NetworkPlayerHeartbeatRequest request);
    bool RemovePlayer(Guid uuid);
    IReadOnlyList<NetworkPlayerPresenceResponse> GetPlayers();
    NetworkServerPresenceResponse UpsertServer(NetworkServerHeartbeatRequest request);
    IReadOnlyList<NetworkServerPresenceResponse> GetServers();
    NetworkChatMessageResponse PublishChat(NetworkChatPublishRequest request);
    NetworkChatBatchResponse GetChatAfter(long afterSequence, string? source);
}
