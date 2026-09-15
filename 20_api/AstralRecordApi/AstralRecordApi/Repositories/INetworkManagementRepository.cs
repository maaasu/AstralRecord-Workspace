using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface INetworkManagementRepository
{
    Task<ManagedNetworkSettings?> GetSettingsAsync(bool includePlayers = false);
    Task<(ManagedNetworkSettings Settings, bool Created)> BootstrapAsync(ManagedNetworkSettings request);
    Task<ManagedNetworkSettings> UpdateSettingsAsync(ManagedNetworkSettings request, Guid actorUuid);
    Task<IReadOnlyList<NetworkManagedPlayer>> SearchPlayersAsync(string? query);
    Task<NetworkChannelAccessResponse> GetChannelAccessAsync(Guid userUuid, string serverId);
    Task<NetworkBanStateResponse?> GetBanAsync(Guid userUuid);
    Task<IReadOnlyList<NetworkBanStateResponse>> GetActiveBansAsync();
    Task<NetworkBanStateResponse?> UpdateBanAsync(Guid userUuid, NetworkBanUpdateRequest request, Guid actorUuid);
    Task<bool> CanManageBanFromGameAsync(Guid actorUuid);
}

public sealed class NetworkManagementConflictException() : Exception("設定が他の操作で更新されました。再読み込みしてください。");
