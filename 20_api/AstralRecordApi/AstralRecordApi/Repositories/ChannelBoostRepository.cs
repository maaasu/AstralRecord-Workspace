using System.Data;
using System.Security.Cryptography;
using System.Text;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Utilities;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public sealed class ChannelBoostRepository(
    AstralRecordDbContext db, INetworkManagementRepository network,
    IItemRepository items, TimeProvider clock) : IChannelBoostRepository
{
    public async Task<ChannelBoostSnapshotResponse> GetSnapshotAsync()
    {
        var now = clock.GetUtcNow().UtcDateTime;
        var cursor = await LatestCursorAsync();
        var configured = ((await network.GetSettingsAsync())?.Channels ?? [])
            .Where(c => c.IsGame).ToDictionary(c => c.ServerId.Trim().ToLowerInvariant(),
                StringComparer.OrdinalIgnoreCase);
        var rows = await db.ChannelBoosts.AsNoTracking().ToDictionaryAsync(x => x.ChannelId);
        var channels = configured.Keys.Concat(rows.Keys).Distinct(StringComparer.OrdinalIgnoreCase)
            .OrderBy(x => x, StringComparer.OrdinalIgnoreCase)
            .Select(x => Describe(rows.GetValueOrDefault(x) ?? new ChannelBoostEntity { ChannelId = x }, now) with
            {
                NetworkBoostEnabled = configured.GetValueOrDefault(x)?.NetworkBoostEnabled == true,
                DisplayName = configured.GetValueOrDefault(x)?.DisplayName ?? x,
            }).ToArray();
        return new(cursor, channels);
    }

    public async Task<ChannelBoostEventsResponse> GetEventsAsync(long after)
    {
        var rows = await db.ChannelBoostEvents.AsNoTracking().Where(x => x.EventCursor > after)
            .OrderBy(x => x.EventCursor).Take(100).ToArrayAsync();
        // A bounded page advances only to its final event; callers poll until empty.
        var cursor = rows.Length == 0 ? after : rows[^1].EventCursor;
        return new(cursor, rows.Select(x => new ChannelBoostEventResponse(x.EventCursor, x.ChannelId,
            x.OperationId, x.AccountId, x.AccountName, x.VipTier, x.BoostKind, x.Multiplier, Utc(x.ExpiresAt))).ToArray());
    }

    public async Task<ChannelBoostActivateResponse> ActivateAsync(string channelId, ChannelBoostActivateRequest request)
    {
        channelId = channelId.Trim().ToLowerInvariant();
        if (channelId.Length is < 1 or > 64 || request.OperationId == Guid.Empty || request.AccountId == Guid.Empty
            || request.InventoryEntryId == Guid.Empty || request.ExpectedUpdatedAt == default)
            return new(request.OperationId, "REJECTED", "invalid_request", null, null);
        var hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(
            $"{request.AccountId:D}|{channelId}|{request.InventoryEntryId:D}|{request.ExpectedUpdatedAt.Ticks}")));
        return await db.Database.CreateExecutionStrategy().ExecuteAsync(async () =>
        {
            db.ChangeTracker.Clear();
            await using var transaction = await db.Database.BeginTransactionAsync(IsolationLevel.Serializable);
            var now = clock.GetUtcNow().UtcDateTime;
            now = new DateTime(now.Ticks - now.Ticks % TimeSpan.TicksPerMillisecond, DateTimeKind.Utc);
            var account = db.Database.IsSqlServer()
                ? await db.Accounts.FromSqlInterpolated($"SELECT * FROM [dbo].[account] WITH (UPDLOCK, HOLDLOCK) WHERE [uuid] = {request.AccountId}").SingleOrDefaultAsync()
                : await db.Accounts.SingleOrDefaultAsync(x => x.Uuid == request.AccountId);
            if (account is null || account.IsDeleted)
                return new ChannelBoostActivateResponse(request.OperationId, "REJECTED", "account_missing", null, null);
            var operation = db.Database.IsSqlServer()
                ? await db.ChannelBoostOperations.FromSqlInterpolated($"SELECT * FROM [dbo].[channel_boost_operation] WITH (UPDLOCK, HOLDLOCK) WHERE [operation_id] = {request.OperationId}").SingleOrDefaultAsync()
                : await db.ChannelBoostOperations.SingleOrDefaultAsync(x => x.OperationId == request.OperationId);
            if (operation is not null && operation.RequestHash != hash)
                return new ChannelBoostActivateResponse(request.OperationId, "REJECTED", "operation_conflict", null, null);
            var boost = db.Database.IsSqlServer()
                ? await db.ChannelBoosts.FromSqlInterpolated($"SELECT * FROM [dbo].[channel_boost] WITH (UPDLOCK, HOLDLOCK) WHERE [channel_id] = {channelId}").SingleOrDefaultAsync()
                : await db.ChannelBoosts.SingleOrDefaultAsync(x => x.ChannelId == channelId);
            var newBoost = boost is null;
            boost ??= new ChannelBoostEntity { ChannelId = channelId };
            if (operation is not null)
                return new ChannelBoostActivateResponse(request.OperationId, operation.Status, operation.Reason,
                    Describe(boost, now), operation.EventCursor,
                    operation.Status == "COMPLETED" && operation.InventoryEntryId is Guid id
                        ? await InventoryOperationSnapshotReader.ReadAsync(db, request.AccountId, [id]) : null);
            var channels = (await network.GetSettingsAsync())?.Channels;
            if (channels is null || !channels.Any(x => x.IsGame && string.Equals(x.ServerId, channelId, StringComparison.OrdinalIgnoreCase)))
                return new ChannelBoostActivateResponse(request.OperationId, "REJECTED", "unknown_channel", Describe(boost, now), null);
            operation = new ChannelBoostOperationEntity
            {
                OperationId = request.OperationId, AccountId = request.AccountId, ChannelId = channelId,
                InventoryEntryId = request.InventoryEntryId, RequestHash = hash,
                Status = "REJECTED", CreatedAt = now,
            };
            db.ChannelBoostOperations.Add(operation);
            var inventoryId = await db.InventoryEntries.AsNoTracking()
                .Where(x => x.InventoryEntryId == request.InventoryEntryId).Select(x => (Guid?)x.InventoryId).SingleOrDefaultAsync();
            var inventory = inventoryId is null ? null : db.Database.IsSqlServer()
                ? await db.Inventories.FromSqlInterpolated($"SELECT * FROM [dbo].[inventory] WITH (UPDLOCK, HOLDLOCK) WHERE [inventory_id] = {inventoryId}").SingleOrDefaultAsync()
                : await db.Inventories.SingleOrDefaultAsync(x => x.InventoryId == inventoryId);
            var entry = inventory?.AccountId != request.AccountId || inventory.IsDeleted || !inventory.IsEnabled
                || inventory.InventoryProfile != "GAME" || inventory.InventoryType is not ("BAG" or "HOTBAR") ? null : db.Database.IsSqlServer()
                ? await db.InventoryEntries.FromSqlInterpolated($"SELECT * FROM [dbo].[inventory_entry] WITH (UPDLOCK, HOLDLOCK) WHERE [inventory_entry_id] = {request.InventoryEntryId}").SingleOrDefaultAsync()
                : await db.InventoryEntries.SingleOrDefaultAsync(x => x.InventoryEntryId == request.InventoryEntryId);
            var item = entry?.ItemId is { } itemId ? items.GetById(itemId) : null;
            var effect = item?.Consumable?.Effects.Count == 1 ? item.Consumable.Effects[0] : null;
            var kind = effect?.Type switch
            {
                "CHANNEL_EXP_BOOST" => "EXP", "CHANNEL_DROP_BOOST" => "DROP",
                "CHANNEL_SPECIAL_BOOST" => "SPECIAL", _ => null,
            };
            if (entry is null || entry.IsDeleted || entry.Quantity <= 0 || entry.InstanceId is not null
                || entry.ItemCategory != "consumable" || entry.UpdatedAt != request.ExpectedUpdatedAt)
                operation.Reason = "inventory_conflict";
            else if (kind is null || item is null || !item.UnTradeable || !item.UnSellable
                || item.Consumable?.OnUse?.Amount != 1 || effect!.Rate != 100 || effect.IsPercent
                || effect.DurationSeconds != 3600 || effect.Value is not double multiplier
                || !double.IsFinite(multiplier) || multiplier < 1.1 || multiplier > 2.0
                || kind == "SPECIAL" && multiplier != 2.0)
                operation.Reason = "invalid_boost_item";
            else if (kind is "EXP" or "SPECIAL" && boost.ExpExpiresAt > now
                || kind is "DROP" or "SPECIAL" && boost.DropExpiresAt > now)
                operation.Reason = "boost_already_active";
            else
            {
                if (newBoost) db.ChannelBoosts.Add(boost);
                var expiry = now.AddSeconds(effect.DurationSeconds.Value);
                if (kind is "EXP" or "SPECIAL")
                {
                    boost.ExpMultiplier = multiplier; boost.ExpExpiresAt = expiry;
                    boost.ExpOperationId = request.OperationId; boost.ExpActivatorName = account.AccountName;
                }
                if (kind is "DROP" or "SPECIAL")
                {
                    boost.DropMultiplier = multiplier; boost.DropExpiresAt = expiry;
                    boost.DropOperationId = request.OperationId; boost.DropActivatorName = account.AccountName;
                }
                entry.Quantity--;
                entry.IsDeleted = entry.Quantity == 0;
                entry.UpdatedAt = now;
                entry.UpdatedBy = request.AccountId;
                operation.Status = "COMPLETED";
                operation.BoostKind = kind;
                operation.Multiplier = multiplier;
                operation.ExpiresAt = expiry;
                // A single row serializes event sequence allocation through commit.
                var sequence = db.Database.IsSqlServer()
                    ? await db.ChannelBoostCursors.FromSqlRaw(
                        "SELECT * FROM [dbo].[channel_boost_cursor] WITH (UPDLOCK, HOLDLOCK) WHERE [id] = 1")
                        .SingleAsync()
                    : await db.ChannelBoostCursors.SingleAsync(x => x.Id == 1);
                sequence.LastEventCursor++;
                var eventRow = new ChannelBoostEventEntity
                {
                    EventCursor = sequence.LastEventCursor,
                    OperationId = request.OperationId, AccountId = request.AccountId,
                    ChannelId = channelId, AccountName = account.AccountName,
                    VipTier = await db.AccountBenefits.Where(x => x.AccountId == request.AccountId)
                        .Select(x => x.AstralderExpiresAt > now ? "ASTRALDER" : x.DonerExpiresAt > now ? "DONER" : "NONE")
                        .SingleOrDefaultAsync() ?? "NONE",
                    BoostKind = kind, Multiplier = multiplier, ExpiresAt = expiry, CreatedAt = now,
                };
                db.ChannelBoostEvents.Add(eventRow);
                await db.SaveChangesAsync();
                operation.EventCursor = eventRow.EventCursor;
            }
            await db.SaveChangesAsync();
            var snapshot = operation.Status == "COMPLETED"
                ? await InventoryOperationSnapshotReader.ReadAsync(db, request.AccountId, [request.InventoryEntryId]) : null;
            await transaction.CommitAsync();
            return new ChannelBoostActivateResponse(request.OperationId, operation.Status, operation.Reason,
                Describe(boost, now), operation.EventCursor, snapshot);
        });
    }

    private async Task<long> LatestCursorAsync() =>
        await db.ChannelBoostCursors.AsNoTracking().Where(x => x.Id == 1)
            .Select(x => (long?)x.LastEventCursor).SingleOrDefaultAsync() ?? 0L;
    private static DateTime Utc(DateTime value) => DateTime.SpecifyKind(value, DateTimeKind.Utc);
    private static ChannelBoostResponse Describe(ChannelBoostEntity row, DateTime now) => new(row.ChannelId,
        row.ExpExpiresAt > now && row.ExpMultiplier is double exp && row.ExpOperationId is Guid expId
            ? new(exp, Utc(row.ExpExpiresAt.Value), row.ExpActivatorName ?? "", expId) : null,
        row.DropExpiresAt > now && row.DropMultiplier is double drop && row.DropOperationId is Guid dropId
            ? new(drop, Utc(row.DropExpiresAt.Value), row.DropActivatorName ?? "", dropId) : null);
}
