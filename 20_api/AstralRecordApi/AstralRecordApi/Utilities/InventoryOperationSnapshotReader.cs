using AstralRecordApi.Data;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Utilities;

/// <summary>呼出元のtransaction内で、再送時も現在の所有者限定正本をまとめて取得する。</summary>
internal static class InventoryOperationSnapshotReader
{
    /// <summary>指定entryと、必要な場合はGAME通貨全体を読む。台帳へのsnapshot保存は行わない。</summary>
    internal static async Task<InventoryOperationSnapshotResponse> ReadAsync(
        AstralRecordDbContext db, Guid accountId, IEnumerable<Guid> entryIds, bool includeCurrency = false)
    {
        var ids = entryIds.Distinct().Order().ToArray();
        var currencyId = includeCurrency
            ? await db.Inventories.AsNoTracking()
                .Where(i => i.AccountId == accountId && !i.IsDeleted && i.IsEnabled
                    && i.InventoryProfile == "GAME" && i.InventoryType == "CURRENCY")
                .Select(i => (Guid?)i.InventoryId).SingleOrDefaultAsync()
            : null;
        var rows = await (from entry in db.InventoryEntries.AsNoTracking()
                          join inventory in db.Inventories.AsNoTracking()
                              on entry.InventoryId equals inventory.InventoryId
                          where inventory.AccountId == accountId && !inventory.IsDeleted && inventory.IsEnabled
                              && !entry.IsDeleted
                              && (ids.Contains(entry.InventoryEntryId)
                                  || (currencyId != null && entry.InventoryId == currencyId))
                          select new InventoryEntryResponse
                          {
                              InventoryEntryId = entry.InventoryEntryId, InventoryId = entry.InventoryId,
                              SlotIndex = entry.SlotIndex, ItemCategory = entry.ItemCategory, ItemId = entry.ItemId,
                              InstanceType = entry.InstanceType, InstanceId = entry.InstanceId,
                              Quantity = entry.Quantity, MetadataJson = entry.MetadataJson,
                              CreatedAt = entry.CreatedAt, UpdatedAt = entry.UpdatedAt,
                              CreatedBy = entry.CreatedBy, UpdatedBy = entry.UpdatedBy, IsDeleted = entry.IsDeleted,
                          }).ToListAsync();
        return new InventoryOperationSnapshotResponse
        {
            AccountId = accountId, CoveredEntryIds = ids,
            Entries = rows.Where(e => ids.Contains(e.InventoryEntryId)).ToArray(),
            CurrencyInventoryId = currencyId,
            CurrencyEntries = rows.Where(e => e.InventoryId == currencyId).ToArray(),
        };
    }
}
