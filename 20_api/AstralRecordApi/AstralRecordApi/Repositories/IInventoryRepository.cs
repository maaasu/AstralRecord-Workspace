using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IInventoryRepository
{
    Task<IReadOnlyList<InventoryResponse>> GetByAccountIdAsync(Guid accountId);
    Task<InventoryResponse?> GetByIdAsync(Guid inventoryId);
    Task<InventoryResponse> CreateAsync(InventoryCreateRequest request);
    Task<InventoryResponse?> UpdateAsync(Guid inventoryId, InventoryUpdateRequest request);
    Task<IReadOnlyList<InventoryEntryResponse>> GetEntriesByInventoryIdAsync(Guid inventoryId);
    Task<InventoryEntryResponse?> GetEntryByIdAsync(Guid inventoryEntryId);
    Task<InventoryEntryResponse?> CreateEntryAsync(Guid inventoryId, InventoryEntryCreateRequest request);
    Task<InventoryEntryResponse?> UpdateEntryAsync(Guid inventoryEntryId, InventoryEntryUpdateRequest request);
    Task<bool?> DeleteEntryAsync(Guid inventoryEntryId, Guid updatedBy);
}
