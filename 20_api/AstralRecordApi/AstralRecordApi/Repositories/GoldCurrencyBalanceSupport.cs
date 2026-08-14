using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>
/// Plugin の Gold 額面を通貨インベントリ上で原子的に読み書きする共通処理です。
/// </summary>
internal static class GoldCurrencyBalanceSupport
{
    private const string GameProfile = "GAME";

    internal const string MarketCurrencyId = "gold";

    private static readonly IReadOnlyDictionary<string, long> GoldValues =
        new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase)
        {
            ["gold"] = 1L,
            ["ast_gold"] = 1L,
            ["gold_coin"] = 10L,
            ["gold_ingot"] = 100L,
            ["gold_block"] = 1_000L,
            ["gold_diamond"] = 10_000L,
            ["gold_diamond_block"] = 100_000L,
            ["yggdrasil_star_core"] = 1_000_000L,
        };

    private static readonly (string ItemId, long Value)[] CanonicalGold =
    [
        ("yggdrasil_star_core", 1_000_000L),
        ("gold_diamond_block", 100_000L),
        ("gold_diamond", 10_000L),
        ("gold_block", 1_000L),
        ("gold_ingot", 100L),
        ("gold_coin", 10L),
        ("gold", 1L),
    ];

    internal static bool IsMarketGoldCurrency(string? currencyId)
        => string.Equals(currencyId?.Trim(), MarketCurrencyId, StringComparison.OrdinalIgnoreCase);

    internal static async Task<GoldBalanceSnapshot?> LoadForUpdateAsync(
        AstralRecordDbContext dbContext,
        Guid accountId)
    {
        var inventory = await FindCurrencyInventoryForUpdateAsync(dbContext, accountId);
        if (inventory is null)
            return null;

        var entries = await FindGoldEntriesForUpdateAsync(dbContext, accountId);
        return new GoldBalanceSnapshot(inventory, entries);
    }

    internal static long TotalGold(IEnumerable<InventoryEntryEntity> entries)
    {
        long total = 0L;
        foreach (var entry in entries)
        {
            if (entry.ItemId is null || !GoldValues.TryGetValue(entry.ItemId, out var value))
                continue;
            try
            {
                total = checked(total + checked(entry.Quantity * value));
            }
            catch (OverflowException)
            {
                return long.MaxValue;
            }
        }
        return total;
    }

    internal static async Task<IReadOnlyList<Guid>> RewriteBalanceAsync(
        AstralRecordDbContext dbContext,
        GoldBalanceSnapshot snapshot,
        long targetGold,
        Guid updatedBy,
        DateTime now)
    {
        if (targetGold < 0L)
            throw new InvalidOperationException("Gold balance cannot become negative.");

        var remainingValue = targetGold;
        var entries = snapshot.Entries;
        var unused = new Queue<InventoryEntryEntity>(entries);
        var affectedEntryIds = new List<Guid>();

        foreach (var denomination in CanonicalGold)
        {
            var quantity = remainingValue / denomination.Value;
            remainingValue %= denomination.Value;
            if (quantity <= 0)
                continue;

            var matching = entries.FirstOrDefault(entry => !entry.IsDeleted
                && IdEquals(entry.ItemId, denomination.ItemId)
                && unused.Contains(entry));
            InventoryEntryEntity target;
            if (matching is not null)
            {
                target = matching;
                unused = new Queue<InventoryEntryEntity>(unused.Where(entry => entry != matching));
            }
            else if (unused.Count > 0)
            {
                target = unused.Dequeue();
            }
            else
            {
                target = new InventoryEntryEntity
                {
                    InventoryEntryId = Guid.NewGuid(),
                    InventoryId = snapshot.CurrencyInventory.InventoryId,
                    ItemCategory = "currency",
                    CreatedAt = now,
                    CreatedBy = updatedBy,
                };
                await dbContext.InventoryEntries.AddAsync(target);
            }

            target.ItemCategory = "currency";
            target.ItemId = denomination.ItemId;
            target.InstanceType = null;
            target.InstanceId = null;
            target.Quantity = quantity;
            target.MetadataJson = null;
            target.IsDeleted = false;
            target.UpdatedAt = now;
            target.UpdatedBy = updatedBy;
            affectedEntryIds.Add(target.InventoryEntryId);
        }

        foreach (var entry in unused)
        {
            entry.IsDeleted = true;
            entry.UpdatedAt = now;
            entry.UpdatedBy = updatedBy;
            affectedEntryIds.Add(entry.InventoryEntryId);
        }

        return affectedEntryIds;
    }

    private static async Task<InventoryEntity?> FindCurrencyInventoryForUpdateAsync(
        AstralRecordDbContext dbContext,
        Guid accountId)
    {
        if (dbContext.Database.IsSqlServer())
        {
            return await dbContext.Inventories.FromSqlInterpolated($"""
                    SELECT * FROM [dbo].[inventory] WITH (UPDLOCK, HOLDLOCK)
                    WHERE [account_id] = {accountId}
                      AND [is_deleted] = 0
                      AND [is_enabled] = 1
                      AND [inventory_profile] = {GameProfile}
                      AND [inventory_type] = 'CURRENCY'
                    """)
                .SingleOrDefaultAsync();
        }

        return await dbContext.Inventories.SingleOrDefaultAsync(inventory =>
            inventory.AccountId == accountId
            && !inventory.IsDeleted
            && inventory.IsEnabled
            && inventory.InventoryProfile == GameProfile
            && inventory.InventoryType == "CURRENCY");
    }

    private static async Task<List<InventoryEntryEntity>> FindGoldEntriesForUpdateAsync(
        AstralRecordDbContext dbContext,
        Guid accountId)
    {
        List<InventoryEntryEntity> entries;
        if (dbContext.Database.IsSqlServer())
        {
            entries = await dbContext.InventoryEntries.FromSqlInterpolated($"""
                    SELECT entry.*
                    FROM [dbo].[inventory_entry] AS entry WITH (UPDLOCK, HOLDLOCK)
                    INNER JOIN [dbo].[inventory] AS inventory WITH (HOLDLOCK)
                        ON inventory.[inventory_id] = entry.[inventory_id]
                    WHERE inventory.[account_id] = {accountId}
                      AND inventory.[is_deleted] = 0
                      AND inventory.[is_enabled] = 1
                      AND inventory.[inventory_profile] = {GameProfile}
                      AND inventory.[inventory_type] = 'CURRENCY'
                      AND entry.[is_deleted] = 0
                      AND entry.[quantity] > 0
                    """)
                .OrderBy(entry => entry.InventoryEntryId)
                .ToListAsync();
        }
        else
        {
            entries = await (from entry in dbContext.InventoryEntries
                             join inventory in dbContext.Inventories on entry.InventoryId equals inventory.InventoryId
                             where inventory.AccountId == accountId
                                   && !inventory.IsDeleted
                                   && inventory.IsEnabled
                                   && inventory.InventoryProfile == GameProfile
                                   && inventory.InventoryType == "CURRENCY"
                                   && !entry.IsDeleted
                                   && entry.Quantity > 0
                             orderby entry.InventoryEntryId
                             select entry).ToListAsync();
        }

        return entries.Where(entry => entry.ItemId is not null && GoldValues.ContainsKey(entry.ItemId)).ToList();
    }

    private static bool IdEquals(string? left, string right)
        => string.Equals(left, right, StringComparison.OrdinalIgnoreCase);
}

internal sealed record GoldBalanceSnapshot(
    InventoryEntity CurrencyInventory,
    List<InventoryEntryEntity> Entries);
