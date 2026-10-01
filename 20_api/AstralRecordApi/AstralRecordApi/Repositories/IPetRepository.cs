using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IPetRepository
{
    Task<PetMasterResponse?> GetMasterAsync();
    Task<PetAccountResponse> GetByAccountAsync(Guid accountId);
    Task<PetInstanceResponse?> GetInstanceAsync(Guid accountId, Guid instanceId);
    Task<PetMutationResult?> GetOperationAsync(Guid accountId, Guid operationId);
    Task<PetMutationResult> CreateEggAsync(Guid accountId, PetEggCreateRequest request);
    Task<PetMutationResult> HatchAsync(Guid accountId, Guid instanceId, PetFacilityRequest request);
    Task<PetMutationResult> BreedAsync(Guid accountId, PetBreedRequest request);
    Task<PetMutationResult> ProgressAsync(Guid accountId, Guid instanceId, PetProgressRequest request);
    Task<PetMutationResult> ReviveAsync(Guid accountId, Guid instanceId, PetReviveRequest request);
    Task<PetMutationResult> EquipAsync(Guid accountId, PetEquipRequest request);
    Task<PetMutationResult> RenameAsync(Guid accountId, Guid instanceId, PetRenameRequest request);
}
