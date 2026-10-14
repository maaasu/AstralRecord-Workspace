using System.Security.Cryptography;
using System.Text;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Services;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>新規取得した通常オーブのストレージ収納先を、呼出元のSerializable transaction内で決定します。</summary>
internal sealed class OrbStorageDestination(AstralRecordDbContext dbContext)
{
    private static readonly StringComparer KeyComparer = StringComparer.OrdinalIgnoreCase;
    private const string OrbCategory = "orb";
    private const string StorageInventoryType = "STORAGE";
    private const string OrbRemoteAccessTokenItemId = "99a00023";
    private const string StorageExpansionTokenItemId = "99a00009";
    private const int StoragePageSize = 45;
    private const int StorageBasePageCount = 5;

    /// <summary>自動収納対象である非個体のオーブcategoryかを判定します。</summary>
    /// <param name="category">取得品のcategory。</param>
    /// <param name="instanceType">個体種別。通常stackではnull。</param>
    /// <param name="instanceId">個体ID。通常stackではnull。</param>
    /// <returns>通常オーブstackの場合はtrue。</returns>
    internal static bool IsOrbStack(string category, string? instanceType, Guid? instanceId) =>
        KeyComparer.Equals(category, OrbCategory) && instanceType is null && !instanceId.HasValue;

    /// <summary>STORAGEの新規受取に、追跡中の未保存entryも含む収納順の末尾slotを割り当てます。</summary>
    /// <param name="inventory">FindAsyncが選択済みの受取先inventory。</param>
    /// <returns>STORAGEは既存最大slot+1、BAGは未配置を表すnull。</returns>
    /// <exception cref="OverflowException">STORAGEのslot番号がint上限へ達している場合。</exception>
    internal int? ResolveNewEntrySlot(InventoryEntity inventory)
    {
        if (!KeyComparer.Equals(inventory.InventoryType, StorageInventoryType))
            return null;
        var maxSlot = dbContext.InventoryEntries.Local
            .Where(entry => entry.InventoryId == inventory.InventoryId && !entry.IsDeleted)
            .Select(entry => Math.Max(0, entry.SlotIndex ?? 0)).DefaultIfEmpty(0).Max();
        return checked(maxSlot + 1);
    }

    /// <summary>
    /// 新規取得オーブの収納先を同じ Serializable transaction 内で確定します。
    /// 有効な GAME/CURRENCY の遠隔アクセストークンがない場合、または新種用の空き枠がない場合は
    /// null を返し、呼出元が従来の BAG 付与へ戻します。同種の通常 stack は枠数にかかわらず併合できます。
    /// 未作成の STORAGE は作成しますが、無効・削除済みの inventory は使用しません。
    /// </summary>
    /// <param name="accountId">受取人のアカウントID。</param>
    /// <param name="itemId">取得する通常オーブのitem ID。</param>
    /// <param name="updatedBy">新規inventoryの監査更新者。</param>
    /// <returns>収納可能なSTORAGE、収納できない場合はnull。</returns>
    internal async Task<InventoryEntity?> FindAsync(
        Guid accountId, string itemId, Guid updatedBy)
    {
        List<InventoryEntryEntity> tokens;
        if (dbContext.Database.IsSqlServer())
        {
            tokens = await dbContext.InventoryEntries.FromSqlInterpolated($"""
                SELECT entry.*
                FROM [dbo].[inventory_entry] AS entry WITH (UPDLOCK, HOLDLOCK)
                INNER JOIN [dbo].[inventory] AS inventory WITH (HOLDLOCK)
                    ON inventory.[inventory_id] = entry.[inventory_id]
                WHERE inventory.[account_id] = {accountId}
                  AND inventory.[inventory_profile] = {MarketListingExpansion.GameInventoryProfile}
                  AND inventory.[inventory_type] = {MarketListingExpansion.CurrencyInventoryType}
                  AND inventory.[is_enabled] = 1 AND inventory.[is_deleted] = 0
                  AND entry.[item_category] = 'currency'
                  AND entry.[instance_type] IS NULL AND entry.[instance_id] IS NULL
                  AND entry.[is_deleted] = 0 AND entry.[quantity] > 0
                  AND entry.[item_id] IN ({OrbRemoteAccessTokenItemId}, {StorageExpansionTokenItemId})
                """).AsNoTracking().ToListAsync();
        }
        else
        {
            tokens = await (from entry in dbContext.InventoryEntries.AsNoTracking()
                            join inventory in dbContext.Inventories.AsNoTracking()
                                on entry.InventoryId equals inventory.InventoryId
                            where inventory.AccountId == accountId
                                  && inventory.InventoryProfile == MarketListingExpansion.GameInventoryProfile
                                  && inventory.InventoryType == MarketListingExpansion.CurrencyInventoryType
                                  && inventory.IsEnabled && !inventory.IsDeleted
                                  && entry.ItemCategory == "currency"
                                  && entry.InstanceType == null && entry.InstanceId == null
                                  && !entry.IsDeleted && entry.Quantity > 0
                                  && (entry.ItemId == OrbRemoteAccessTokenItemId
                                      || entry.ItemId == StorageExpansionTokenItemId)
                            select entry).ToListAsync();
        }
        if (!tokens.Any(entry => KeyComparer.Equals(entry.ItemId, OrbRemoteAccessTokenItemId)))
            return null;

        var storage = dbContext.Inventories.Local.FirstOrDefault(inventory =>
            inventory.AccountId == accountId
            && inventory.InventoryProfile == MarketListingExpansion.GameInventoryProfile
            && inventory.InventoryType == StorageInventoryType);
        storage ??= dbContext.Database.IsSqlServer()
            ? await dbContext.Inventories.FromSqlInterpolated($"""
                SELECT * FROM [dbo].[inventory] WITH (UPDLOCK, HOLDLOCK)
                WHERE [account_id] = {accountId}
                  AND [inventory_profile] = {MarketListingExpansion.GameInventoryProfile}
                  AND [inventory_type] = {StorageInventoryType}
                """).SingleOrDefaultAsync()
            : await dbContext.Inventories.SingleOrDefaultAsync(inventory =>
                inventory.AccountId == accountId
                && inventory.InventoryProfile == MarketListingExpansion.GameInventoryProfile
                && inventory.InventoryType == StorageInventoryType);
        if (storage is not null && (!storage.IsEnabled || storage.IsDeleted))
            return null;
        if (storage is null)
        {
            var now = DateTime.UtcNow;
            storage = new InventoryEntity
            {
                InventoryId = ResolveInventoryId(accountId), AccountId = accountId,
                InventoryType = StorageInventoryType, InventoryProfile = MarketListingExpansion.GameInventoryProfile,
                IsEnabled = true, CreatedAt = now, UpdatedAt = now,
                CreatedBy = updatedBy, UpdatedBy = updatedBy,
            };
            dbContext.Inventories.Add(storage);
            return storage;
        }

        if (dbContext.Database.IsSqlServer())
            await dbContext.InventoryEntries.FromSqlInterpolated($"""
                SELECT * FROM [dbo].[inventory_entry] WITH (UPDLOCK, HOLDLOCK)
                WHERE [inventory_id] = {storage.InventoryId} AND [is_deleted] = 0
                """).LoadAsync();
        else
            await dbContext.InventoryEntries.Where(entry =>
                entry.InventoryId == storage.InventoryId && !entry.IsDeleted).LoadAsync();
        // 未保存の新規受取・移動・削除を含む現在値から、同一transactionの容量を判定します。
        var entries = dbContext.InventoryEntries.Local.Where(entry =>
            entry.InventoryId == storage.InventoryId && !entry.IsDeleted).ToList();
        if (entries.Any(entry => entry.InstanceType is null && entry.InstanceId is null
            && KeyComparer.Equals(entry.ItemId, itemId)))
            return storage;

        long expansionCount = 0;
        foreach (var token in tokens.Where(entry => KeyComparer.Equals(entry.ItemId, StorageExpansionTokenItemId)))
            expansionCount = Math.Min(int.MaxValue, expansionCount + Math.Min(int.MaxValue, token.Quantity));
        var capacity = Math.Min(int.MaxValue, (StorageBasePageCount + expansionCount) * StoragePageSize);
        return entries.Count < capacity ? storage : null;
    }

    /// <summary>
    /// Plugin の UUID.nameUUIDFromBytes と同じ UTF-8 identity から新規 GAME/STORAGE の ID を決定します。
    /// MD5 はUUID v3の名前変換だけに使用し、認証・署名には使用しません。
    /// </summary>
    /// <param name="accountId">所有アカウント ID。</param>
    /// <returns>Plugin と API の初回同時作成でも一致する inventory ID。</returns>
    internal static Guid ResolveInventoryId(Guid accountId)
    {
        var identity = $"astralrecord:inventory:v1:{accountId:D}:{MarketListingExpansion.GameInventoryProfile}:{StorageInventoryType}";
        var digest = MD5.HashData(Encoding.UTF8.GetBytes(identity));
        digest[6] = (byte)((digest[6] & 0x0f) | 0x30);
        digest[8] = (byte)((digest[8] & 0x3f) | 0x80);
        return new Guid(digest, bigEndian: true);
    }
}
