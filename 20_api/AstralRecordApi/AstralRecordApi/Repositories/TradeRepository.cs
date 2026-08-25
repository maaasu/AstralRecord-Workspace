using System.Data;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.AspNetCore.Http;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>
/// プレイヤー間トレードの item・個体所有権・Gold を同一 transaction で確定します。
/// </summary>
public sealed class TradeRepository(AstralRecordDbContext dbContext) : ITradeRepository
{
    private const string GameProfile = "GAME";

    public async Task<TradeOperationResult<TradeCommitResponse>> CommitAsync(TradeCommitRequest request)
    {
        if (!IsValidRequest(request))
        {
            return TradeOperationResult<TradeCommitResponse>.Failure(
                StatusCodes.Status400BadRequest,
                "trade.invalid_request",
                "Trade request is invalid.");
        }

        var requestHash = ComputeRequestHash(request);
        var strategy = dbContext.Database.CreateExecutionStrategy();
        return await strategy.ExecuteAsync(async () =>
        {
            dbContext.ChangeTracker.Clear();
            await using var transactionScope = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);

            async Task<TradeOperationResult<TradeCommitResponse>> RollbackFailureAsync(
                int statusCode,
                string errorCode,
                string detail)
            {
                await transactionScope.RollbackAsync();
                dbContext.ChangeTracker.Clear();
                return TradeOperationResult<TradeCommitResponse>.Failure(statusCode, errorCode, detail);
            }

            var existing = await FindCommitForUpdateAsync(request.OperationId);
            if (existing is not null)
            {
                if (existing.PlayerAAccountId != request.PlayerAAccountId
                    || existing.PlayerBAccountId != request.PlayerBAccountId
                    || !string.Equals(existing.RequestHash, requestHash, StringComparison.Ordinal))
                {
                    return await RollbackFailureAsync(
                        StatusCodes.Status409Conflict,
                        "trade.idempotency_conflict",
                        "Operation ID was already used for a different trade request.");
                }
                var replay = JsonSerializer.Deserialize<TradeCommitResponse>(existing.ResultPayloadJson);
                if (replay is null)
                    return await RollbackFailureAsync(409, "trade.idempotency_corrupt", "Stored trade response is invalid.");

                await transactionScope.CommitAsync();
                return TradeOperationResult<TradeCommitResponse>.Success(replay);
            }

            var accountIds = new[] { request.PlayerAAccountId, request.PlayerBAccountId }.Order().ToArray();
            var accounts = await dbContext.Accounts
                .Where(account => accountIds.Contains(account.Uuid) && !account.IsDeleted)
                .Select(account => account.Uuid)
                .ToListAsync();
            if (accounts.Count != 2)
                return await RollbackFailureAsync(404, "trade.account_not_found", "Trade account was not found.");

            var playerABag = await FindBagForUpdateAsync(request.PlayerAAccountId);
            var playerBBag = await FindBagForUpdateAsync(request.PlayerBAccountId);
            if (playerABag is null || playerBBag is null)
                return await RollbackFailureAsync(409, "trade.inventory_missing", "Trade BAG inventory was not found.");

            var now = DateTime.UtcNow;
            var affectedA = new HashSet<Guid>();
            var affectedB = new HashSet<Guid>();
            var aToB = await TransferItemsAsync(
                request.PlayerAAccountId,
                playerBBag,
                request.PlayerAItems,
                request.UpdatedBy,
                now);
            if (!aToB.Succeeded)
                return await RollbackFailureAsync(aToB.StatusCode, aToB.ErrorCode!, aToB.Detail!);
            foreach (var entryId in aToB.Value!.SourceEntryIds) affectedA.Add(entryId);
            foreach (var entryId in aToB.Value.DestinationEntryIds) affectedB.Add(entryId);

            var bToA = await TransferItemsAsync(
                request.PlayerBAccountId,
                playerABag,
                request.PlayerBItems,
                request.UpdatedBy,
                now);
            if (!bToA.Succeeded)
                return await RollbackFailureAsync(bToA.StatusCode, bToA.ErrorCode!, bToA.Detail!);
            foreach (var entryId in bToA.Value!.SourceEntryIds) affectedB.Add(entryId);
            foreach (var entryId in bToA.Value.DestinationEntryIds) affectedA.Add(entryId);

            var gold = await TransferGoldAsync(request, now);
            if (!gold.Succeeded)
                return await RollbackFailureAsync(gold.StatusCode, gold.ErrorCode!, gold.Detail!);
            foreach (var entryId in gold.Value!.PlayerAEntryIds) affectedA.Add(entryId);
            foreach (var entryId in gold.Value.PlayerBEntryIds) affectedB.Add(entryId);

            var response = new TradeCommitResponse
            {
                OperationId = request.OperationId,
                PlayerAAffectedInventoryEntryIds = affectedA.Order().ToArray(),
                PlayerBAffectedInventoryEntryIds = affectedB.Order().ToArray(),
                CompletedAt = now,
            };
            await dbContext.TradeCommits.AddAsync(new TradeCommitEntity
            {
                OperationId = request.OperationId,
                PlayerAAccountId = request.PlayerAAccountId,
                PlayerBAccountId = request.PlayerBAccountId,
                RequestHash = requestHash,
                ResultPayloadJson = JsonSerializer.Serialize(response),
                CompletedAt = now,
                CreatedAt = now,
                CreatedBy = request.UpdatedBy,
            });
            await dbContext.SaveChangesAsync();
            await transactionScope.CommitAsync();
            return TradeOperationResult<TradeCommitResponse>.Success(response);
        });
    }

    private async Task<TradeOperationResult<ItemTransferResult>> TransferItemsAsync(
        Guid sourceAccountId,
        InventoryEntity destinationBag,
        IReadOnlyList<TradeCommitItemRequest> items,
        Guid updatedBy,
        DateTime now)
    {
        var result = new ItemTransferResult();
        foreach (var item in items)
        {
            var source = await FindNormalEntryForUpdateAsync(item.SourceInventoryEntryId, sourceAccountId);
            if (source is null)
                return TradeOperationResult<ItemTransferResult>.Failure(409, "trade.source_entry_unavailable", "Trade source entry is unavailable.");
            if (item.Quantity < 1 || source.Quantity < item.Quantity)
                return TradeOperationResult<ItemTransferResult>.Failure(409, "trade.source_quantity_changed", "Trade source quantity changed.");

            var isInstance = source.InstanceId.HasValue && !string.IsNullOrWhiteSpace(source.InstanceType);
            if (isInstance && item.Quantity != 1)
                return TradeOperationResult<ItemTransferResult>.Failure(400, "trade.instance_quantity_invalid", "Instance item quantity must be one.");

            result.SourceEntryIds.Add(source.InventoryEntryId);
            if (isInstance)
            {
                var transfer = await TransferInstanceAsync(source, sourceAccountId, destinationBag, updatedBy, now);
                if (!transfer.Succeeded)
                    return TradeOperationResult<ItemTransferResult>.Failure(transfer.StatusCode, transfer.ErrorCode!, transfer.Detail!);
                result.DestinationEntryIds.Add(source.InventoryEntryId);
                continue;
            }

            if (string.IsNullOrWhiteSpace(source.ItemId))
                return TradeOperationResult<ItemTransferResult>.Failure(409, "trade.source_item_invalid", "Trade source item is invalid.");

            source.Quantity -= item.Quantity;
            source.UpdatedAt = now;
            source.UpdatedBy = updatedBy;
            if (source.Quantity == 0)
                source.IsDeleted = true;

            var target = await dbContext.InventoryEntries.FirstOrDefaultAsync(entry =>
                entry.InventoryId == destinationBag.InventoryId
                && !entry.IsDeleted
                && entry.ItemCategory == source.ItemCategory
                && entry.ItemId == source.ItemId
                && entry.InstanceId == null);
            if (target is null)
            {
                target = new InventoryEntryEntity
                {
                    InventoryEntryId = Guid.NewGuid(),
                    InventoryId = destinationBag.InventoryId,
                    SlotIndex = null,
                    ItemCategory = source.ItemCategory,
                    ItemId = source.ItemId,
                    Quantity = item.Quantity,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = updatedBy,
                    UpdatedBy = updatedBy,
                    IsDeleted = false,
                };
                await dbContext.InventoryEntries.AddAsync(target);
            }
            else
            {
                try
                {
                    target.Quantity = checked(target.Quantity + item.Quantity);
                }
                catch (OverflowException)
                {
                    return TradeOperationResult<ItemTransferResult>.Failure(409, "trade.destination_quantity_overflow", "Trade destination quantity overflowed.");
                }
                target.UpdatedAt = now;
                target.UpdatedBy = updatedBy;
            }
            result.DestinationEntryIds.Add(target.InventoryEntryId);
        }
        return TradeOperationResult<ItemTransferResult>.Success(result);
    }

    private async Task<TradeOperationResult<bool>> TransferInstanceAsync(
        InventoryEntryEntity source,
        Guid sourceAccountId,
        InventoryEntity destinationBag,
        Guid updatedBy,
        DateTime now)
    {
        if (!source.InstanceId.HasValue)
            return TradeOperationResult<bool>.Failure(409, "trade.instance_missing", "Trade instance is missing.");

        if (string.Equals(source.InstanceType, "EQUIPMENT", StringComparison.OrdinalIgnoreCase))
        {
            var equipment = await FindEquipmentForUpdateAsync(source.InstanceId.Value);
            if (equipment is null)
                return TradeOperationResult<bool>.Failure(404, "trade.instance_not_found", "Equipment instance was not found.");
            if (equipment.AccountId != sourceAccountId)
                return TradeOperationResult<bool>.Failure(409, "trade.instance_owner_changed", "Equipment owner changed.");
            equipment.AccountId = destinationBag.AccountId;
            equipment.UpdatedAt = now;
            equipment.UpdatedBy = updatedBy;
        }
        else
        {
            return TradeOperationResult<bool>.Failure(400, "trade.instance_type_invalid", "Trade instance type is invalid.");
        }

        source.InventoryId = destinationBag.InventoryId;
        source.SlotIndex = null;
        source.Quantity = 1;
        source.UpdatedAt = now;
        source.UpdatedBy = updatedBy;
        source.IsDeleted = false;
        return TradeOperationResult<bool>.Success(true);
    }

    private async Task<TradeOperationResult<GoldTransferResult>> TransferGoldAsync(
        TradeCommitRequest request,
        DateTime now)
    {
        var firstAccountId = request.PlayerAAccountId.CompareTo(request.PlayerBAccountId) <= 0
            ? request.PlayerAAccountId : request.PlayerBAccountId;
        var secondAccountId = firstAccountId == request.PlayerAAccountId
            ? request.PlayerBAccountId : request.PlayerAAccountId;
        var first = await GoldCurrencyBalanceSupport.LoadForUpdateAsync(dbContext, firstAccountId);
        var second = await GoldCurrencyBalanceSupport.LoadForUpdateAsync(dbContext, secondAccountId);
        var playerA = firstAccountId == request.PlayerAAccountId ? first : second;
        var playerB = firstAccountId == request.PlayerBAccountId ? first : second;
        if (playerA is null || playerB is null)
            return TradeOperationResult<GoldTransferResult>.Failure(409, "trade.currency_inventory_missing", "Trade currency inventory was not found.");

        var playerAGold = GoldCurrencyBalanceSupport.TotalGold(playerA.Entries);
        var playerBGold = GoldCurrencyBalanceSupport.TotalGold(playerB.Entries);
        if (playerAGold < request.PlayerAGold || playerBGold < request.PlayerBGold)
            return TradeOperationResult<GoldTransferResult>.Failure(409, "trade.insufficient_gold", "Trade Gold balance changed.");

        long nextA;
        long nextB;
        try
        {
            nextA = checked(playerAGold - request.PlayerAGold + request.PlayerBGold);
            nextB = checked(playerBGold - request.PlayerBGold + request.PlayerAGold);
        }
        catch (OverflowException)
        {
            return TradeOperationResult<GoldTransferResult>.Failure(409, "trade.gold_overflow", "Trade Gold balance overflowed.");
        }

        var affectedA = await GoldCurrencyBalanceSupport.RewriteBalanceAsync(
            dbContext, playerA, nextA, request.UpdatedBy, now);
        var affectedB = await GoldCurrencyBalanceSupport.RewriteBalanceAsync(
            dbContext, playerB, nextB, request.UpdatedBy, now);
        return TradeOperationResult<GoldTransferResult>.Success(new GoldTransferResult(affectedA, affectedB));
    }

    private async Task<InventoryEntity?> FindBagForUpdateAsync(Guid accountId)
    {
        if (dbContext.Database.IsSqlServer())
        {
            return await dbContext.Inventories.FromSqlInterpolated($"""
                SELECT * FROM [dbo].[inventory] WITH (UPDLOCK, HOLDLOCK)
                WHERE [account_id] = {accountId}
                  AND [inventory_profile] = {GameProfile}
                  AND [inventory_type] = 'BAG'
                  AND [is_enabled] = 1
                  AND [is_deleted] = 0
                """).SingleOrDefaultAsync();
        }
        return await dbContext.Inventories.SingleOrDefaultAsync(inventory =>
            inventory.AccountId == accountId
            && inventory.InventoryProfile == GameProfile
            && inventory.InventoryType == "BAG"
            && inventory.IsEnabled
            && !inventory.IsDeleted);
    }

    private async Task<TradeCommitEntity?> FindCommitForUpdateAsync(Guid operationId)
    {
        if (dbContext.Database.IsSqlServer())
        {
            return await dbContext.TradeCommits.FromSqlInterpolated($"""
                SELECT * FROM [dbo].[trade_commit] WITH (UPDLOCK, HOLDLOCK)
                WHERE [operation_id] = {operationId}
                """).SingleOrDefaultAsync();
        }
        return await dbContext.TradeCommits.SingleOrDefaultAsync(commit => commit.OperationId == operationId);
    }

    private async Task<InventoryEntryEntity?> FindNormalEntryForUpdateAsync(Guid entryId, Guid accountId)
    {
        if (dbContext.Database.IsSqlServer())
        {
            return await dbContext.InventoryEntries.FromSqlInterpolated($"""
                SELECT entry.* FROM [dbo].[inventory_entry] AS entry WITH (UPDLOCK, HOLDLOCK)
                INNER JOIN [dbo].[inventory] AS inventory WITH (HOLDLOCK)
                    ON inventory.[inventory_id] = entry.[inventory_id]
                WHERE entry.[inventory_entry_id] = {entryId}
                  AND inventory.[account_id] = {accountId}
                  AND inventory.[inventory_profile] = {GameProfile}
                  AND inventory.[inventory_type] IN ('BAG', 'HOTBAR')
                  AND inventory.[is_enabled] = 1
                  AND inventory.[is_deleted] = 0
                  AND entry.[is_deleted] = 0
                """).SingleOrDefaultAsync();
        }
        return await (from entry in dbContext.InventoryEntries
                      join inventory in dbContext.Inventories on entry.InventoryId equals inventory.InventoryId
                      where entry.InventoryEntryId == entryId
                            && inventory.AccountId == accountId
                            && inventory.InventoryProfile == GameProfile
                            && (inventory.InventoryType == "BAG" || inventory.InventoryType == "HOTBAR")
                            && inventory.IsEnabled
                            && !inventory.IsDeleted
                            && !entry.IsDeleted
                      select entry).SingleOrDefaultAsync();
    }

    private async Task<EquipmentInstanceEntity?> FindEquipmentForUpdateAsync(Guid instanceId)
    {
        if (dbContext.Database.IsSqlServer())
        {
            return await dbContext.EquipmentInstances.FromSqlInterpolated($"""
                SELECT * FROM [dbo].[equipment_instance] WITH (UPDLOCK, HOLDLOCK)
                WHERE [equipment_instance_id] = {instanceId} AND [is_deleted] = 0
                """).SingleOrDefaultAsync();
        }
        return await dbContext.EquipmentInstances.SingleOrDefaultAsync(instance =>
            instance.EquipmentInstanceId == instanceId && !instance.IsDeleted);
    }

    private static bool IsValidRequest(TradeCommitRequest request)
        => request.OperationId != Guid.Empty
           && request.PlayerAAccountId != Guid.Empty
           && request.PlayerBAccountId != Guid.Empty
           && request.PlayerAAccountId != request.PlayerBAccountId
           && request.UpdatedBy != Guid.Empty
           && request.PlayerAGold >= 0L
           && request.PlayerBGold >= 0L
           && request.PlayerAItems.All(item => item.SourceInventoryEntryId != Guid.Empty && item.Quantity > 0L)
           && request.PlayerBItems.All(item => item.SourceInventoryEntryId != Guid.Empty && item.Quantity > 0L)
           && request.PlayerAItems.Select(item => item.SourceInventoryEntryId).Distinct().Count() == request.PlayerAItems.Count
           && request.PlayerBItems.Select(item => item.SourceInventoryEntryId).Distinct().Count() == request.PlayerBItems.Count;

    private static string ComputeRequestHash(TradeCommitRequest request)
    {
        var canonical = string.Join('|',
            request.OperationId.ToString("D"),
            request.PlayerAAccountId.ToString("D"),
            request.PlayerBAccountId.ToString("D"),
            request.PlayerAGold,
            request.PlayerBGold,
            request.UpdatedBy.ToString("D"),
            string.Join(',', request.PlayerAItems
                .OrderBy(item => item.SourceInventoryEntryId)
                .Select(item => $"{item.SourceInventoryEntryId:D}:{item.Quantity}")),
            string.Join(',', request.PlayerBItems
                .OrderBy(item => item.SourceInventoryEntryId)
                .Select(item => $"{item.SourceInventoryEntryId:D}:{item.Quantity}")));
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(canonical)));
    }

    private sealed class ItemTransferResult
    {
        public List<Guid> SourceEntryIds { get; } = [];
        public List<Guid> DestinationEntryIds { get; } = [];
    }

    private sealed record GoldTransferResult(
        IReadOnlyList<Guid> PlayerAEntryIds,
        IReadOnlyList<Guid> PlayerBEntryIds);
}
