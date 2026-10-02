using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public sealed record PlayerAdminResult<T>(int StatusCode, T? Value = default, string? Error = null);

public interface IPlayerAdminEditRepository
{
    Task<bool> IsUserLockedAsync(Guid userUuid);
    Task<PlayerAdminResult<PlayerAdminEditSessionResponse>> StartAsync(Guid accountId, Guid actor, PlayerAdminEditStartRequest request);
    Task<PlayerAdminResult<PlayerAdminEditSessionResponse>> GetActiveAsync(Guid accountId, Guid actor);
    Task<PlayerAdminResult<PlayerAdminEditSessionResponse>> GetByIdAsync(Guid editSessionId, Guid actor);
    Task<PlayerAdminResult<PlayerAdminEditSessionResponse>> RefreshAsync(Guid editSessionId, Guid actor);
    Task<PlayerAdminResult<PlayerAdminEditorResponse>> GetEditorAsync(Guid editSessionId, Guid actor);
    Task<PlayerAdminResult<PlayerAdminEditSessionResponse>> ApplyAsync(Guid editSessionId, Guid actor, PlayerAdminEditOperationRequest request);
    Task<PlayerAdminResult<PlayerAdminEditSessionResponse>> CancelAsync(Guid editSessionId, Guid actor, PlayerAdminEditCancelRequest request);
    Task<PlayerAdminResult<PlayerAdminRuntimeRegistrationResponse>> RegisterServerAsync(string serverId, PlayerAdminRuntimeRegistrationRequest request);
    Task<PlayerAdminResult<IReadOnlyList<PlayerAdminDrainResponse>>> GetDrainsAsync(string serverId, Guid serverSessionId);
    Task<PlayerAdminResult<PlayerAdminEditSessionResponse>> AcknowledgeDrainAsync(Guid editSessionId, PlayerAdminDrainAckRequest request);
}
