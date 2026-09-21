using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface ISkillTreeOperationRepository
{
    Task<SkillTreeServerRuntimeResponse?> RegisterServerAsync(string serverId, SkillTreeServerRegistrationRequest request);
    Task<SkillTreeServerRuntimeResponse?> HeartbeatServerAsync(string serverId, SkillTreeServerHeartbeatRequest request);
    Task<SkillTreeEditorResponse?> GetEditorAsync(Guid accountId, Guid actorUserId, string? targetServerId = null);
    Task<SkillTreeEditorResponse?> RegisterPlayerViewAsync(string serverId, Guid accountId, SkillTreePlayerViewRegistrationRequest request);
    Task<SkillTreeOperationResponse?> CreateAsync(Guid accountId, SkillTreeOperationCreateRequest request);
    Task<SkillTreeOperationResponse?> FindAsync(Guid accountId, Guid operationId, Guid actorUserId);
    Task<SkillTreeOperationResponse?> CancelAsync(Guid accountId, Guid operationId, Guid actorUserId);
    Task<IReadOnlyList<SkillTreeOperationResponse>?> GetClaimableAsync(string serverId, Guid serverSessionId, Guid accountId);
    Task<SkillTreeOperationClaimResponse?> ClaimAsync(string serverId, Guid operationId, SkillTreeOperationClaimRequest request);
    Task<bool> ValidateRuntimeStateSaveAsync(string serverId, Guid serverSessionId, string definitionGenerationId);
    Task<bool> CompleteFromSnapshotAsync(Guid accountId, PlayerStateSkillTreeOperationSection section, DateTime now);
    Task<SkillTreeMigrationResponse?> MigrateAsync(string serverId, Guid sessionId, Guid accountId, SkillTreeMigrationRequest request);
}
