using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface ISkillTreeOperationRepository
{
    Task<bool> RequiresRuntimeAuthorityAsync(Guid accountId);
    Task<SkillTreeServerRuntimeResponse?> RegisterServerAsync(string serverId, SkillTreeServerRegistrationRequest request);
    Task<string?> GetDefinitionAsync(string generationId);
    Task<SkillTreeServerRuntimeResponse?> HeartbeatServerAsync(string serverId, SkillTreeServerHeartbeatRequest request);
    Task<SkillTreeEditorResponse?> GetEditorAsync(Guid accountId, Guid actorUserId, string? targetServerId = null);
    Task<SkillTreeEditorResponse?> RegisterPlayerViewAsync(string serverId, Guid accountId, SkillTreePlayerViewRegistrationRequest request);
    Task<SkillTreeOperationResponse?> CreateAsync(Guid accountId, SkillTreeOperationCreateRequest request);
    Task<SkillTreeOperationResponse?> FindAsync(Guid accountId, Guid operationId, Guid actorUserId);
    Task<SkillTreeOperationResponse?> CancelAsync(Guid accountId, Guid operationId, Guid actorUserId);
    Task<IReadOnlyList<SkillTreeOperationResponse>?> GetClaimableAsync(string serverId, Guid serverSessionId, Guid accountId);
    Task<SkillTreeOperationClaimResponse?> ClaimAsync(string serverId, Guid operationId, SkillTreeOperationClaimRequest request);
    Task<bool> ValidateRuntimeStateSaveAsync(Guid accountId, string serverId, Guid serverSessionId, string definitionGenerationId, Guid accountSessionId, string accountLeaseToken);
    Task<bool> AcquireAccountSessionAsync(string serverId, Guid accountId, SkillTreeAccountSessionRequest request);
    Task<bool> CloseAccountSessionAsync(string serverId, Guid accountId, SkillTreePlayerViewRegistrationRequest request);
    Task<bool> CompleteFromSnapshotAsync(Guid accountId, PlayerStateSkillTreeOperationSection section, DateTime now);
    Task<SkillTreeMigrationResponse?> MigrateAsync(string serverId, Guid sessionId, Guid accountId, SkillTreeMigrationRequest request);
}
