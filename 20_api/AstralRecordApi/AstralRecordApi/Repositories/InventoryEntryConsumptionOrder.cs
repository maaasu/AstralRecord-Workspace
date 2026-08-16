using AstralRecordApi.Data.Entities;

namespace AstralRecordApi.Repositories;

/// <summary>
/// itemId で通常 inventory の支払い entry を選ぶときの共通順序です。
/// inventoryEntryId を指定する処理や通貨額面の再構成には使用しません。
/// </summary>
internal static class InventoryEntryConsumptionOrder
{
    internal static IReadOnlyList<InventoryEntryEntity> OrderNormalEntries(
        IEnumerable<InventoryEntryEntity> entries,
        IReadOnlyDictionary<Guid, string> inventoryTypeById)
        => entries
            .OrderBy(entry => InventoryTypeOrder(
                inventoryTypeById.TryGetValue(entry.InventoryId, out var inventoryType)
                    ? inventoryType
                    : null))
            .ThenByDescending(entry => entry.SlotIndex.HasValue)
            .ThenByDescending(entry => entry.SlotIndex ?? int.MinValue)
            .ThenBy(entry => entry.InventoryEntryId)
            .ToList();

    private static int InventoryTypeOrder(string? inventoryType)
        => string.Equals(inventoryType, "BAG", StringComparison.OrdinalIgnoreCase)
            ? 0
            : string.Equals(inventoryType, "HOTBAR", StringComparison.OrdinalIgnoreCase)
                ? 1
                : 2;
}
