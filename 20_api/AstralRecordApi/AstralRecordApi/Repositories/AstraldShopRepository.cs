using System.Data;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Services;
using AstralRecordApi.Utilities;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public sealed class AstraldShopRepository(AstralRecordDbContext db, MasterDataDbContext masterDb,
    INetworkManagementRepository network, IItemRepository items, TimeProvider clock) : IAstraldShopRepository
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);
    private sealed record Offer(AstraldShopItemResponse Display, ItemConsumableEffectResponse Effect);

    public async Task<AstraldShopCatalogResponse> GetCatalogAsync()
    {
        var offers = await ReadOffersAsync();
        var channels = (await network.GetSettingsAsync())?.Channels.Where(x => x.IsGame)
            .Select(x => new AstraldShopChannelResponse(x.ServerId.ToLowerInvariant(), x.DisplayName)).ToArray() ?? [];
        return new(offers.Values.Select(x => x.Display).ToArray(), channels);
    }

    public async Task<AstraldShopPurchaseResponse> CreateAsync(Guid actor, AstraldShopPurchaseRequest request)
    {
        var channel = request.ChannelId?.Trim().ToLowerInvariant();
        var itemId = request.ItemId?.Trim() ?? "";
        var hash = Hash(actor, request.AccountId, itemId, request.ExpectedPricePaidAstrald, channel);
        if (actor == Guid.Empty || request.OperationId == Guid.Empty || request.AccountId == Guid.Empty)
            return Rejected("invalid_request");
        return await db.Database.CreateExecutionStrategy().ExecuteAsync(async () =>
        {
            db.ChangeTracker.Clear();
            await using var tx = await db.Database.BeginTransactionAsync(IsolationLevel.Serializable);
            var account = await LockedAccountAsync(request.AccountId);
            if (account is null || account.IsDeleted || account.UserId != actor)
                return Rejected("account_not_current");
            var existing = await LockedPurchaseAsync(request.OperationId);
            if (existing is not null)
            {
                if (existing.ActorUserUuid != actor || existing.RequestHash != hash)
                    return Rejected("operation_conflict");
                return Receipt(existing);
            }
            if (!await db.Users.AnyAsync(x => x.Uuid == actor && !x.IsDeleted && x.AccountId == request.AccountId))
                return Rejected("account_not_current");
            var offers = await ReadOffersAsync();
            if (!offers.TryGetValue(itemId, out var offer)) return Rejected("item_unavailable");
            if (request.ExpectedPricePaidAstrald != offer.Display.PricePaidAstrald) return Rejected("price_changed");
            if (offer.Display.RequiresChannel)
            {
                if (string.IsNullOrEmpty(channel) || !await IsGameChannelAsync(channel)) return Rejected("unknown_channel");
            }
            else if (channel is not null) return Rejected("channel_not_allowed");
            var now = Now();
            var operation = new AstraldShopPurchaseEntity
            {
                OperationId = request.OperationId, ActorUserUuid = actor, AccountId = request.AccountId,
                ItemId = itemId, ChannelId = channel, RequestHash = hash,
                PricePaidAstrald = offer.Display.PricePaidAstrald, Status = "PENDING",
                EffectType = offer.Effect.Type, EffectValue = offer.Effect.Value!.Value,
                DurationSeconds = offer.Effect.DurationSeconds,
                CreatedAt = now, UpdatedAt = now,
            };
            db.AstraldShopPurchases.Add(operation);
            if (!await IsOnlineAsync(request.AccountId))
                await ApplyAsync(operation, account, now);
            await db.SaveChangesAsync();
            await tx.CommitAsync();
            return Receipt(operation);
        });

        AstraldShopPurchaseResponse Rejected(string reason) =>
            new(request.OperationId, "REJECTED", reason, request.AccountId, itemId, channel);
    }

    public async Task<AstraldShopPurchaseResponse?> GetAsync(Guid actor, Guid operationId)
    {
        var row = await db.AstraldShopPurchases.AsNoTracking().SingleOrDefaultAsync(x => x.OperationId == operationId && x.ActorUserUuid == actor);
        return row is null ? null : Receipt(row);
    }

    public async Task<IReadOnlyList<AstraldShopPendingResponse>> PendingAsync(Guid accountId) =>
        await db.AstraldShopPurchases.AsNoTracking().Where(x => x.AccountId == accountId && x.Status == "PENDING")
            .OrderBy(x => x.CreatedAt).Select(x => new AstraldShopPendingResponse(x.OperationId, x.AccountId)).ToArrayAsync();

    public async Task<AstraldShopPurchaseResponse?> ProcessAsync(Guid operationId, AstraldShopProcessRequest request)
    {
        if (!request.PreparedOnline || request.AccountId == Guid.Empty) return null;
        return await db.Database.CreateExecutionStrategy().ExecuteAsync(async () =>
        {
            db.ChangeTracker.Clear();
            await using var tx = await db.Database.BeginTransactionAsync(IsolationLevel.Serializable);
            var account = await LockedAccountAsync(request.AccountId);
            if (account is null || account.IsDeleted) return null;
            var operation = await LockedPurchaseAsync(operationId);
            if (operation is null || operation.AccountId != request.AccountId || account.UserId != operation.ActorUserUuid)
                return null;
            if (operation.Status == "PENDING")
            {
                if (!await db.Users.AnyAsync(x => x.Uuid == operation.ActorUserUuid && !x.IsDeleted && x.AccountId == account.Uuid))
                    Fail(operation, "account_not_current");
                else await ApplyAsync(operation, account, Now());
                await db.SaveChangesAsync();
            }
            await tx.CommitAsync();
            return await FreshProcessReceiptAsync(operation);
        });
    }

    private async Task ApplyAsync(AstraldShopPurchaseEntity operation, AccountEntity account, DateTime now)
    {
        var paidInventory = db.Database.IsSqlServer()
            ? await db.Inventories.FromSqlInterpolated($"SELECT * FROM [dbo].[inventory] WITH (UPDLOCK,HOLDLOCK) WHERE [account_id] = {account.Uuid} AND [inventory_profile] = 'GAME' AND [inventory_type] = 'CURRENCY' AND [is_deleted] = 0 AND [is_enabled] = 1").SingleOrDefaultAsync()
            : await db.Inventories.SingleOrDefaultAsync(x => x.AccountId == account.Uuid && x.InventoryProfile == "GAME" && x.InventoryType == "CURRENCY" && !x.IsDeleted && x.IsEnabled);
        if (paidInventory is null) { Fail(operation, "currency_inventory_missing"); return; }
        var entries = db.Database.IsSqlServer()
            ? await db.InventoryEntries.FromSqlInterpolated($"SELECT * FROM [dbo].[inventory_entry] WITH (UPDLOCK,HOLDLOCK) WHERE [inventory_id] = {paidInventory.InventoryId} AND [item_id] = {DonationRules.PaidAstraldItemId} AND [is_deleted] = 0 ORDER BY [inventory_entry_id]").ToListAsync()
            : await db.InventoryEntries.Where(x => x.InventoryId == paidInventory.InventoryId && x.ItemId == DonationRules.PaidAstraldItemId && !x.IsDeleted).OrderBy(x => x.InventoryEntryId).ToListAsync();
        var balance = entries.Sum(x => x.Quantity);
        if (balance < operation.PricePaidAstrald) { Fail(operation, "insufficient_paid_astrald"); return; }
        var effectType = operation.EffectType;
        var effectValue = operation.EffectValue;
        var benefit = await db.AccountBenefits.SingleOrDefaultAsync(x => x.AccountId == account.Uuid);
        benefit ??= new AccountBenefitsEntity { AccountId = account.Uuid };
        ChannelBoostResponse? boostResponse = null;
        long? eventCursor = null;
        if (operation.ChannelId is not null)
        {
            var channel = operation.ChannelId!;
            var boost = db.Database.IsSqlServer()
                ? await db.ChannelBoosts.FromSqlInterpolated($"SELECT * FROM [dbo].[channel_boost] WITH (UPDLOCK,HOLDLOCK) WHERE [channel_id] = {channel}").SingleOrDefaultAsync()
                : await db.ChannelBoosts.SingleOrDefaultAsync(x => x.ChannelId == channel);
            var newBoost = boost is null;
            boost ??= new ChannelBoostEntity { ChannelId = channel };
            var kind = effectType switch
            {
                "CHANNEL_EXP_BOOST" => "EXP", "CHANNEL_DROP_BOOST" => "DROP",
                "CHANNEL_SPECIAL_BOOST" => "SPECIAL", _ => null,
            };
            if (kind is null || operation.DurationSeconds != 3600 || (double?)effectValue is not double multiplier
                || (kind is "EXP" or "SPECIAL") && boost.ExpExpiresAt > now
                || (kind is "DROP" or "SPECIAL") && boost.DropExpiresAt > now)
            { Fail(operation, "boost_already_active"); return; }
            if (newBoost) db.ChannelBoosts.Add(boost);
            var expires = now.AddSeconds(operation.DurationSeconds.Value);
            if (kind is "EXP" or "SPECIAL")
            { boost.ExpMultiplier = multiplier; boost.ExpExpiresAt = expires; boost.ExpOperationId = operation.OperationId; boost.ExpActivatorName = account.AccountName; }
            if (kind is "DROP" or "SPECIAL")
            { boost.DropMultiplier = multiplier; boost.DropExpiresAt = expires; boost.DropOperationId = operation.OperationId; boost.DropActivatorName = account.AccountName; }
            var sequence = db.Database.IsSqlServer()
                ? await db.ChannelBoostCursors.FromSqlRaw("SELECT * FROM [dbo].[channel_boost_cursor] WITH (UPDLOCK,HOLDLOCK) WHERE [id] = 1").SingleAsync()
                : await db.ChannelBoostCursors.SingleAsync(x => x.Id == 1);
            sequence.LastEventCursor++;
            eventCursor = sequence.LastEventCursor;
            db.ChannelBoostEvents.Add(new ChannelBoostEventEntity
            {
                EventCursor = sequence.LastEventCursor, OperationId = operation.OperationId,
                AccountId = account.Uuid, ChannelId = channel, AccountName = account.AccountName,
                VipTier = AccountBenefitsPolicy.Describe(benefit, now).VipTier,
                BoostKind = kind, Multiplier = multiplier, ExpiresAt = expires, CreatedAt = now,
            });
            boostResponse = new ChannelBoostResponse(channel,
                boost.ExpExpiresAt > now && boost.ExpMultiplier is double exp && boost.ExpOperationId is Guid expId
                    ? new(exp, boost.ExpExpiresAt.Value, boost.ExpActivatorName ?? "", expId) : null,
                boost.DropExpiresAt > now && boost.DropMultiplier is double drop && boost.DropOperationId is Guid dropId
                    ? new(drop, boost.DropExpiresAt.Value, boost.DropActivatorName ?? "", dropId) : null);
        }
        else if (effectType == "INSTANCE_PRIORITY" && effectValue is double uses && uses >= 1 && uses == Math.Truncate(uses))
            benefit.InstancePriorityUses = checked(benefit.InstancePriorityUses + (int)uses);
        else if (effectType is "VIP_DONER" or "VIP_ASTRALDER" && effectValue is double days && days >= 1 && days == Math.Truncate(days))
            AccountBenefitsPolicy.AddVip(benefit, effectType == "VIP_DONER" ? "DONER" : "ASTRALDER", (int)days, now);
        else { Fail(operation, "unsupported_effect"); return; }

        if (db.Entry(benefit).State == EntityState.Detached) db.AccountBenefits.Add(benefit);

        var remaining = operation.PricePaidAstrald;
        var affected = new List<Guid>();
        foreach (var entry in entries)
        {
            if (remaining == 0) break;
            var debit = Math.Min(entry.Quantity, remaining);
            entry.Quantity -= debit;
            remaining -= checked((int)debit);
            entry.IsDeleted = entry.Quantity == 0;
            entry.UpdatedAt = now;
            entry.UpdatedBy = account.Uuid;
            affected.Add(entry.InventoryEntryId);
        }
        operation.Status = "COMPLETED";
        operation.Reason = null;
        operation.UpdatedAt = now;
        await db.SaveChangesAsync();
        var snapshot = await InventoryOperationSnapshotReader.ReadAsync(db, account.Uuid, affected, includeCurrency: true);
        var result = new AstraldShopPurchaseResponse(operation.OperationId, operation.Status, null,
            account.Uuid, operation.ItemId, operation.ChannelId, balance - operation.PricePaidAstrald,
            AccountBenefitsPolicy.Describe(benefit, now), boostResponse, affected, snapshot, eventCursor);
        operation.ResponseJson = JsonSerializer.Serialize(result, JsonOptions);
    }

    private async Task<Dictionary<string, Offer>> ReadOffersAsync()
    {
        var payload = await masterDb.Entries.AsNoTracking().Where(x => x.MasterType == "shop"
            && x.MasterId == "astrald_shop" && !x.IsDeleted).Select(x => x.PayloadJson).SingleOrDefaultAsync();
        var shop = payload is null ? null : JsonNode.Parse(payload)?.AsObject();
        var result = new Dictionary<string, Offer>(StringComparer.OrdinalIgnoreCase);
        foreach (var line in shop?["items"]?.AsArray() ?? [])
        {
            if (line is not JsonObject row || row["requiredItems"] is not JsonArray required || required.Count != 1
                || row["priceGold"]?.GetValue<int>() != 0 || row["amount"]?.GetValue<int>() != 1)
                continue;
            var cost = required[0] as JsonObject;
            var paidId = ReadRef(cost?["itemId"]);
            var itemId = ReadRef(row["itemId"]);
            if (paidId != DonationRules.PaidAstraldItemId || string.IsNullOrWhiteSpace(itemId)
                || cost?["amount"]?.GetValue<int>() is not int price || price < 1) continue;
            var item = items.GetById(itemId);
            var effect = item?.Consumable?.Effects.Count == 1 ? item.Consumable.Effects[0] : null;
            if (item is null || !item.UnTradeable || !item.UnSellable || effect is null
                || effect.Rate != 100 || effect.IsPercent || effect.Value is not double value || !double.IsFinite(value)) continue;
            var requiresChannel = effect.Type is "CHANNEL_EXP_BOOST" or "CHANNEL_DROP_BOOST" or "CHANNEL_SPECIAL_BOOST";
            if (!requiresChannel && effect.Type is not ("INSTANCE_PRIORITY" or "VIP_DONER" or "VIP_ASTRALDER")) continue;
            if (requiresChannel && effect.DurationSeconds != 3600) continue;
            result[itemId] = new(new(itemId, System.Text.RegularExpressions.Regex.Replace(item.Name,
                    "[&§][0-9A-FK-ORX]", "", System.Text.RegularExpressions.RegexOptions.IgnoreCase), price, effect.Type, value,
                effect.DurationSeconds, requiresChannel), effect);
        }
        return result;
    }

    private static string? ReadRef(JsonNode? node)
    {
        var raw = node is JsonObject obj ? obj["ref"]?.GetValue<string>() : node?.GetValue<string>();
        return raw?.StartsWith("item:", StringComparison.OrdinalIgnoreCase) == true ? raw[5..] : raw;
    }
    private async Task<bool> IsGameChannelAsync(string channel) => (await network.GetSettingsAsync())?.Channels
        .Any(x => x.IsGame && string.Equals(x.ServerId, channel, StringComparison.OrdinalIgnoreCase)) == true;
    private Task<bool> IsOnlineAsync(Guid accountId) => db.SkillTreeAccountSessions.AsNoTracking()
        .AnyAsync(x => x.AccountId == accountId && !x.Closed);

    private async Task<AstraldShopPurchaseResponse> FreshProcessReceiptAsync(AstraldShopPurchaseEntity operation)
    {
        var receipt = Receipt(operation);
        if (receipt.Status != "COMPLETED" || receipt.AffectedInventoryEntryIds is null) return receipt;
        var snapshot = await InventoryOperationSnapshotReader.ReadAsync(db, operation.AccountId,
            receipt.AffectedInventoryEntryIds, includeCurrency: true);
        var now = Now();
        var benefit = await db.AccountBenefits.AsNoTracking().SingleOrDefaultAsync(x => x.AccountId == operation.AccountId)
            ?? new AccountBenefitsEntity { AccountId = operation.AccountId };
        ChannelBoostResponse? boost = null;
        if (operation.ChannelId is { } channel)
        {
            var state = await db.ChannelBoosts.AsNoTracking().SingleOrDefaultAsync(x => x.ChannelId == channel);
            boost = state is null ? new(channel, null, null) : new(channel,
                state.ExpExpiresAt > now && state.ExpMultiplier is double exp && state.ExpOperationId is Guid expId
                    ? new(exp, DateTime.SpecifyKind(state.ExpExpiresAt.Value, DateTimeKind.Utc), state.ExpActivatorName ?? "", expId) : null,
                state.DropExpiresAt > now && state.DropMultiplier is double drop && state.DropOperationId is Guid dropId
                    ? new(drop, DateTime.SpecifyKind(state.DropExpiresAt.Value, DateTimeKind.Utc), state.DropActivatorName ?? "", dropId) : null);
        }
        return receipt with
        {
            InventorySnapshot = snapshot,
            PaidAstraldBalance = snapshot.CurrencyEntries.Where(x => x.ItemId == DonationRules.PaidAstraldItemId)
                .Sum(x => x.Quantity),
            Benefits = AccountBenefitsPolicy.Describe(benefit, now),
            Boost = boost,
        };
    }
    private async Task<AccountEntity?> LockedAccountAsync(Guid accountId) => db.Database.IsSqlServer()
        ? await db.Accounts.FromSqlInterpolated($"SELECT * FROM [dbo].[account] WITH (UPDLOCK,HOLDLOCK) WHERE [uuid] = {accountId}").SingleOrDefaultAsync()
        : await db.Accounts.SingleOrDefaultAsync(x => x.Uuid == accountId);
    private async Task<AstraldShopPurchaseEntity?> LockedPurchaseAsync(Guid operationId) => db.Database.IsSqlServer()
        ? await db.AstraldShopPurchases.FromSqlInterpolated($"SELECT * FROM [dbo].[astrald_shop_purchase] WITH (UPDLOCK,HOLDLOCK) WHERE [operation_id] = {operationId}").SingleOrDefaultAsync()
        : await db.AstraldShopPurchases.SingleOrDefaultAsync(x => x.OperationId == operationId);
    private DateTime Now()
    {
        var now = clock.GetUtcNow().UtcDateTime;
        return new DateTime(now.Ticks - now.Ticks % TimeSpan.TicksPerMillisecond, DateTimeKind.Utc);
    }
    private static string Hash(Guid actor, Guid accountId, string itemId, int price, string? channel) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes($"{actor:D}|{accountId:D}|{itemId}|{price}|{channel}")));
    private static void Fail(AstraldShopPurchaseEntity operation, string reason)
    { operation.Status = "REJECTED"; operation.Reason = reason; operation.UpdatedAt = DateTime.UtcNow; }
    private static AstraldShopPurchaseResponse Receipt(AstraldShopPurchaseEntity row) =>
        row.ResponseJson is { } json ? JsonSerializer.Deserialize<AstraldShopPurchaseResponse>(json, JsonOptions)!
            : new(row.OperationId, row.Status, row.Reason, row.AccountId, row.ItemId, row.ChannelId);
}
