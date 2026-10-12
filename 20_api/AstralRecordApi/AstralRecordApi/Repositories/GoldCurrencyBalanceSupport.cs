using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using Microsoft.EntityFrameworkCore;
using System.Linq.Expressions;

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
            ["99a00001"] = 1L,
            ["99a00002"] = 10L,
            ["99a00003"] = 100L,
            ["99a00004"] = 1_000L,
            ["99a00005"] = 10_000L,
            ["99a00006"] = 100_000L,
            ["99a00007"] = 1_000_000L,
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
        ("99a00007", 1_000_000L),
        ("99a00006", 100_000L),
        ("99a00005", 10_000L),
        ("99a00004", 1_000L),
        ("99a00003", 100L),
        ("99a00002", 10L),
        ("99a00001", 1L),
    ];

    /// <summary>Plugin が組み込みで提供する正規の Gold 額面 ID かを判定します。</summary>
    internal static bool IsCanonicalGoldItem(string itemId)
        => CanonicalGold.Any(denomination => IdEquals(itemId, denomination.ItemId));

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

    /// <summary>共通の額面表をSQL CASEへ変換し、ランキング集計も残高表示と同じ換算を使います。</summary>
    internal static IQueryable<GoldAccountAmount> GoldAmounts(AstralRecordDbContext dbContext)
    {
        if (dbContext.Database.IsSqlServer())
        {
            // EFの既定decimal(18,2) castでは大きなBIGINT quantityを表現できない。
            // 額面表も値パラメーターとして渡す。
            var parameters = GoldValues.SelectMany(pair => new object[] { pair.Key, pair.Value }).ToArray();
            var denominations = string.Join(" ", GoldValues.Select((_, index) => "WHEN {" + index * 2 + "} THEN {" + (index * 2 + 1) + "}"));
            var sql = $"""
                SELECT inventory.[account_id] AS [AccountId],
                    CAST(entry.[quantity] AS decimal(38,0)) *
                    CASE LOWER(entry.[item_id]) {denominations} ELSE 0 END AS [Amount]
                FROM [dbo].[inventory_entry] AS entry
                INNER JOIN [dbo].[inventory] AS inventory ON entry.[inventory_id] = inventory.[inventory_id]
                WHERE entry.[is_deleted] = 0 AND entry.[quantity] > 0 AND entry.[item_id] IS NOT NULL
                    AND inventory.[is_deleted] = 0 AND inventory.[is_enabled] = 1
                    AND inventory.[inventory_type] = 'CURRENCY' AND inventory.[inventory_profile] = 'GAME'
                """;
            return dbContext.Database.SqlQuery<GoldAccountAmount>(System.Runtime.CompilerServices.FormattableStringFactory.Create(sql, parameters));
        }
        var entry = Expression.Parameter(typeof(InventoryEntryEntity), "entry");
        var itemId = Expression.Property(entry, nameof(InventoryEntryEntity.ItemId));
        var normalizedId = Expression.Call(itemId, nameof(string.ToLower), Type.EmptyTypes);
        Expression denomination = Expression.Constant(0m);
        foreach (var pair in GoldValues)
            denomination = Expression.Condition(Expression.Equal(normalizedId, Expression.Constant(pair.Key)),
                Expression.Constant((decimal)pair.Value), denomination);
        var amount = Expression.Multiply(Expression.Convert(Expression.Property(entry, nameof(InventoryEntryEntity.Quantity)), typeof(decimal)), denomination);
        var projection = Expression.MemberInit(Expression.New(typeof(GoldEntryAmount)),
            Expression.Bind(typeof(GoldEntryAmount).GetProperty(nameof(GoldEntryAmount.InventoryId))!, Expression.Property(entry, nameof(InventoryEntryEntity.InventoryId))),
            Expression.Bind(typeof(GoldEntryAmount).GetProperty(nameof(GoldEntryAmount.Amount))!, amount));
        var amounts = dbContext.InventoryEntries.AsNoTracking().Where(row => !row.IsDeleted && row.Quantity > 0 && row.ItemId != null)
            .Select(Expression.Lambda<Func<InventoryEntryEntity, GoldEntryAmount>>(projection, entry));
        return amounts.Join(dbContext.Inventories.AsNoTracking().Where(inventory => !inventory.IsDeleted && inventory.IsEnabled
                && inventory.InventoryType == "CURRENCY" && inventory.InventoryProfile == GameProfile),
            row => row.InventoryId, inventory => inventory.InventoryId,
            (row, inventory) => new GoldAccountAmount { AccountId = inventory.AccountId, Amount = row.Amount });
    }

    private sealed class GoldEntryAmount
    {
        public Guid InventoryId { get; init; }
        public decimal Amount { get; init; }
    }

    internal sealed class GoldAccountAmount
    {
        public Guid AccountId { get; init; }
        public decimal Amount { get; init; }
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
