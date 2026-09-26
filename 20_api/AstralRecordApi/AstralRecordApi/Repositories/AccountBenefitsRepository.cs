using System.Data;
using System.Security.Cryptography;
using System.Text;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Services;
using AstralRecordApi.Utilities;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public sealed class AccountBenefitsRepository(AstralRecordDbContext db, IItemRepository items, TimeProvider clock)
    : IAccountBenefitsRepository
{
    public async Task<AccountBenefitsResponse?> GetAsync(Guid accountId)
    {
        if (!await db.Accounts.AnyAsync(a => a.Uuid == accountId && !a.IsDeleted)) return null;
        var state = await db.AccountBenefits.AsNoTracking().SingleOrDefaultAsync(a => a.AccountId == accountId)
            ?? new AccountBenefitsEntity { AccountId = accountId };
        return AccountBenefitsPolicy.Describe(state, clock.GetUtcNow().UtcDateTime);
    }

    public async Task<IReadOnlyList<VipSupporterResponse>> ListSupportersAsync()
    {
        var now = clock.GetUtcNow().UtcDateTime;
        var rows = await (from state in db.AccountBenefits.AsNoTracking()
                          join account in db.Accounts.AsNoTracking() on state.AccountId equals account.Uuid
                          where !account.IsDeleted && (state.DonerExpiresAt > now || state.AstralderExpiresAt > now)
                          orderby account.AccountName
                          select new { State = state, account.AccountName }).ToListAsync();
        return rows.Select(row =>
        {
            var response = AccountBenefitsPolicy.Describe(row.State, now);
            return new VipSupporterResponse(response.AccountId, row.AccountName, response.VipTier,
                response.VipExpiresAt, response.RemainingDays);
        }).ToArray();
    }

    public async Task<AccountBenefitOperationResponse?> ExecuteAsync(Guid accountId, string kind, AccountBenefitOperationRequest request)
    {
        var itemRequest = request as AccountBenefitItemRequest;
        var ledgerKind = kind == "REFUND" ? "PRIORITY" : kind;
        var hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(
            $"{accountId:D}|{ledgerKind}|{itemRequest?.InventoryEntryId:D}|{itemRequest?.ExpectedUpdatedAt.Ticks}")));
        return await db.Database.CreateExecutionStrategy().ExecuteAsync(async () =>
        {
            db.ChangeTracker.Clear();
            await using var transaction = await db.Database.BeginTransactionAsync(IsolationLevel.Serializable);
            var account = db.Database.IsSqlServer()
                ? await db.Accounts.FromSqlInterpolated($"SELECT * FROM [dbo].[account] WITH (UPDLOCK, HOLDLOCK) WHERE [uuid] = {accountId}").SingleOrDefaultAsync()
                : await db.Accounts.SingleOrDefaultAsync(a => a.Uuid == accountId);
            if (account is null || account.IsDeleted) return null;
            var state = await db.AccountBenefits.SingleOrDefaultAsync(a => a.AccountId == accountId);
            if (state is null) { state = new AccountBenefitsEntity { AccountId = accountId }; db.AccountBenefits.Add(state); }
            var now = clock.GetUtcNow().UtcDateTime;
            now = new DateTime(now.Ticks - now.Ticks % TimeSpan.TicksPerMillisecond, DateTimeKind.Utc);
            var operation = db.Database.IsSqlServer()
                ? await db.AccountBenefitOperations.FromSqlInterpolated($"SELECT * FROM [dbo].[account_benefit_operation] WITH (UPDLOCK, HOLDLOCK) WHERE [operation_id] = {request.OperationId}").SingleOrDefaultAsync()
                : await db.AccountBenefitOperations.SingleOrDefaultAsync(o => o.OperationId == request.OperationId);
            if (operation is not null && (operation.AccountId != accountId || operation.RequestHash != hash))
                return new AccountBenefitOperationResponse(request.OperationId, "REJECTED", "operation_conflict", AccountBenefitsPolicy.Describe(state, now));
            if (kind == "REFUND")
            {
                if (operation is null)
                {
                    // Cancel may arrive before the original delayed HTTP request. Persist a tombstone.
                    operation = NewOperation("REJECTED", "reservation_cancelled");
                    operation.Refunded = true;
                    db.AccountBenefitOperations.Add(operation);
                }
                else if (operation.Status == "COMPLETED" && !operation.Refunded)
                {
                    state.InstancePriorityUses = checked(state.InstancePriorityUses + 1);
                    operation.Refunded = true;
                }
            }
            else if (operation is null)
            {
                operation = NewOperation("COMPLETED", null);
                db.AccountBenefitOperations.Add(operation);
                if (kind == "PRIORITY")
                {
                    if (state.InstancePriorityUses > 0) state.InstancePriorityUses--;
                    else Reject("insufficient_priority_uses");
                }
                else if (kind == "LOGIN")
                    operation.AwardedPriorityUses = AccountBenefitsPolicy.ClaimDaily(state, now);
                else if (kind == "ITEM" && itemRequest is not null)
                {
                    // Same account lock order as player-state snapshots; parent inventory before entry.
                    var inventoryId = await db.InventoryEntries.AsNoTracking()
                        .Where(e => e.InventoryEntryId == itemRequest.InventoryEntryId).Select(e => (Guid?)e.InventoryId).SingleOrDefaultAsync();
                    var inventory = inventoryId is null ? null : db.Database.IsSqlServer()
                        ? await db.Inventories.FromSqlInterpolated($"SELECT * FROM [dbo].[inventory] WITH (UPDLOCK, HOLDLOCK) WHERE [inventory_id] = {inventoryId}").SingleOrDefaultAsync()
                        : await db.Inventories.SingleOrDefaultAsync(i => i.InventoryId == inventoryId);
                    var entry = inventory?.AccountId != accountId || inventory.IsDeleted || !inventory.IsEnabled
                        || inventory.InventoryProfile != "GAME" || inventory.InventoryType is not ("BAG" or "HOTBAR") ? null : db.Database.IsSqlServer()
                        ? await db.InventoryEntries.FromSqlInterpolated($"SELECT * FROM [dbo].[inventory_entry] WITH (UPDLOCK, HOLDLOCK) WHERE [inventory_entry_id] = {itemRequest.InventoryEntryId}").SingleOrDefaultAsync()
                        : await db.InventoryEntries.SingleOrDefaultAsync(e => e.InventoryEntryId == itemRequest.InventoryEntryId);
                    var master = entry?.ItemId is { } itemId ? items.GetById(itemId) : null;
                    var effect = master?.Consumable?.Effects.Count == 1 ? master.Consumable.Effects[0] : null;
                    if (entry is null || entry.IsDeleted || entry.Quantity <= 0 || entry.InstanceId is not null
                        || !string.Equals(entry.ItemCategory, "consumable", StringComparison.OrdinalIgnoreCase) || entry.UpdatedAt != itemRequest.ExpectedUpdatedAt)
                        Reject("inventory_conflict");
                    else if (master is null || !master.UnTradeable || !master.UnSellable || effect is null
                        || effect.Type is not ("INSTANCE_PRIORITY" or "VIP_DONER" or "VIP_ASTRALDER")
                        || effect.Rate != 100 || effect.IsPercent || effect.Value is not double value
                        || !double.IsFinite(value) || value < 1 || value > 10000 || value != Math.Truncate(value))
                        Reject("invalid_benefit_item");
                    else
                    {
                        var amount = (int)effect.Value!.Value;
                        if (effect.Type == "INSTANCE_PRIORITY")
                        {
                            state.InstancePriorityUses = checked(state.InstancePriorityUses + amount);
                            operation.AwardedPriorityUses = amount;
                        }
                        else AccountBenefitsPolicy.AddVip(state, effect.Type == "VIP_DONER" ? "DONER" : "ASTRALDER", amount, now);
                        entry.Quantity--;
                        entry.IsDeleted = entry.Quantity == 0;
                        entry.UpdatedAt = now;
                        entry.UpdatedBy = accountId;
                    }
                }
                else Reject("invalid_operation");
            }
            await db.SaveChangesAsync();
            var inventorySnapshot = operation.InventoryEntryId is Guid affected
                ? await InventoryOperationSnapshotReader.ReadAsync(db, accountId, [affected]) : null;
            var status = kind == "REFUND" ? "COMPLETED" : operation.Refunded ? "REJECTED" : operation.Status;
            var reason = kind == "REFUND" ? null : operation.Refunded ? "reservation_cancelled" : operation.Reason;
            var result = new AccountBenefitOperationResponse(request.OperationId, status, reason,
                AccountBenefitsPolicy.Describe(state, now), operation.AwardedPriorityUses, inventorySnapshot);
            await transaction.CommitAsync();
            return result;

            AccountBenefitOperationEntity NewOperation(string status, string? reason) => new()
            {
                OperationId = request.OperationId, AccountId = accountId, Kind = ledgerKind, RequestHash = hash,
                Status = status, Reason = reason, InventoryEntryId = itemRequest?.InventoryEntryId, CreatedAtUtc = now,
            };
            void Reject(string reason) { operation!.Status = "REJECTED"; operation.Reason = reason; }
        });
    }
}
