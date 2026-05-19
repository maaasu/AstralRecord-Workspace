using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IEquipmentLoadoutRepository
{
    Task<IReadOnlyList<EquipmentLoadoutResponse>> GetByAccountIdAsync(Guid accountId, string? loadoutProfile);
    Task<EquipmentLoadoutResponse?> GetByIdAsync(Guid loadoutId);
    Task<EquipmentLoadoutResponse> CreateAsync(EquipmentLoadoutCreateRequest request);
    Task<EquipmentLoadoutResponse?> UpdateAsync(Guid loadoutId, EquipmentLoadoutUpdateRequest request);
    Task<bool> DeleteAsync(Guid loadoutId, Guid updatedBy);
    Task<EquipmentLoadoutResponse?> ActivateAsync(Guid loadoutId, Guid updatedBy);
    Task<IReadOnlyList<EquipmentLoadoutSlotResponse>?> GetSlotsAsync(Guid loadoutId);
    Task<EquipmentLoadoutSlotResponse?> UpsertSlotAsync(Guid loadoutId, EquipmentLoadoutSlotUpsertRequest request);
    Task<bool?> DeleteSlotAsync(Guid loadoutId, string slotType, int slotIndex, Guid updatedBy);
}
