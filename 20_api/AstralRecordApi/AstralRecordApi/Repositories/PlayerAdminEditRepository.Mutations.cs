using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Utilities;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public sealed partial class PlayerAdminEditRepository
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);
    private static readonly string[] EditableCategories = ["material", "orb", "consumable", "rune", "sigil", "bundle"];

    public async Task<PlayerAdminResult<PlayerAdminEditorResponse>> GetEditorAsync(Guid editSessionId, Guid actor)
    {
        var session = await dbContext.PlayerAdminEditSessions.AsNoTracking()
            .SingleOrDefaultAsync(x => x.EditSessionId == editSessionId);
        if (session is null) return Fail<PlayerAdminEditorResponse>(404, "Edit session not found.");
        if (session.ActorUserUuid != actor) return Fail<PlayerAdminEditorResponse>(403, "Session owner mismatch.");
        if (session.Status != "READY" || session.ExpiresAtUtc <= DateTime.UtcNow
            || !await IsFullyDrainedAsync(session))
            return Fail<PlayerAdminEditorResponse>(409, "All servers have not safely drained.");
        if (session.ItemCatalogHash != ComputeCatalogHash())
            return Fail<PlayerAdminEditorResponse>(409, "Item catalog changed.");
        if (session.ClassCatalogHash != ComputeClassCatalogHash())
            return Fail<PlayerAdminEditorResponse>(409, "Class catalog changed.");
        var account = await dbContext.Accounts.AsNoTracking().SingleOrDefaultAsync(x =>
            x.Uuid == session.AccountId && x.UserId == session.UserUuid && !x.IsDeleted);
        if (account is null) return Fail<PlayerAdminEditorResponse>(409, "Account changed.");
        var inventories = await GetInventoryViewAsync(account.Uuid);
        var itemList = itemRepository.GetAllSummaries().OrderBy(x => x.Id, StringComparer.Ordinal)
            .Select(summary => itemRepository.GetById(summary.Id))
            .Where(item => item is not null && (item.Category == "equipment"
                || EditableCategories.Contains(item.Category) && item.MaxStack > 0))
            .Select(item => new PlayerAdminItemResponse(item!.Id, item.Name, item.Category, item.MaxStack))
            .ToArray();
        var classList = classRepository.GetAllSummaries().OrderBy(x => x.Id, StringComparer.Ordinal)
            .Select(summary => classRepository.GetById(summary.Id))
            .Where(definition => definition is not null)
            .Select(definition => new PlayerAdminClassResponse(definition!.Id, definition.Name,
                definition.MaxLevel)).ToArray();
        return new(200, new(await MapSessionAsync(session), MapAccount(account), inventories,
            itemList, classList, await ComputeStateHashAsync(account), session.ItemCatalogHash!));
    }

    public async Task<PlayerAdminResult<PlayerAdminEditSessionResponse>> ApplyAsync(
        Guid editSessionId, Guid actor, PlayerAdminEditOperationRequest request)
    {
        if (request.OperationId == Guid.Empty || request.ExpectedRevision < 1
            || !ValidHash(request.ExpectedStateHash) || !ValidHash(request.ExpectedCatalogVersion)
            || request.InventoryChanges is null || request.InventoryChanges.Count > 256
            || request.Level is < 1 or > PlayerAdminLevelExperience.MaximumLevel
            || request.ClassId is not null && (string.IsNullOrWhiteSpace(request.ClassId) || request.ClassId.Length > 128))
            return Fail<PlayerAdminEditSessionResponse>(400, "Invalid apply request.");
        var owner = await dbContext.PlayerAdminEditSessions.AsNoTracking()
            .Where(x => x.EditSessionId == editSessionId).Select(x => (Guid?)x.UserUuid).SingleOrDefaultAsync();
        if (!owner.HasValue) return Fail<PlayerAdminEditSessionResponse>(404, "Edit session not found.");
        var hash = HashJson(request);
        return await InTransactionAsync(async () =>
        {
            if (!await LockUserAsync(owner.Value)) return Fail<PlayerAdminEditSessionResponse>(404, "User not found.");
            var session = await dbContext.PlayerAdminEditSessions.SingleAsync(x => x.EditSessionId == editSessionId);
            if (session.ActorUserUuid != actor) return Fail<PlayerAdminEditSessionResponse>(403, "Session owner mismatch.");
            var replay = await dbContext.PlayerAdminEditOperations.AsNoTracking()
                .SingleOrDefaultAsync(x => x.OperationId == request.OperationId);
            if (replay is not null)
            {
                if (replay.EditSessionId != editSessionId || replay.Action != "APPLY" || replay.RequestHash != hash)
                    return Fail<PlayerAdminEditSessionResponse>(409, "Operation ID was reused.");
                var replayResponse = JsonSerializer.Deserialize<PlayerAdminEditSessionResponse>(replay.ResponseJson, JsonOptions);
                return replayResponse is null ? Fail<PlayerAdminEditSessionResponse>(409, "Stored receipt is invalid.")
                    : new(200, replayResponse);
            }
            if (session.Status != "READY" || session.ExpiresAtUtc <= DateTime.UtcNow
                || session.Revision != request.ExpectedRevision || !await IsFullyDrainedAsync(session))
                return Fail<PlayerAdminEditSessionResponse>(409, "Edit session is not ready or revision changed.");
            if (session.ItemCatalogHash != request.ExpectedCatalogVersion
                || session.ItemCatalogHash != ComputeCatalogHash())
                return Fail<PlayerAdminEditSessionResponse>(409, "Item catalog changed.");
            if (session.ClassCatalogHash != ComputeClassCatalogHash())
                return Fail<PlayerAdminEditSessionResponse>(409, "Class catalog changed.");
            var account = await LockAccountAsync(session.AccountId);
            if (account is null || account.UserId != session.UserUuid)
                return Fail<PlayerAdminEditSessionResponse>(409, "Account changed.");
            if (await ComputeStateHashAsync(account) != request.ExpectedStateHash)
                return Fail<PlayerAdminEditSessionResponse>(409, "Account or inventory changed after editor load.");
            if (account.RebirthOriginalLevel.HasValue && account.RebirthOriginalLevel > account.Level)
                return Fail<PlayerAdminEditSessionResponse>(409, "Active rebirth level cannot be edited.");

            var before = await CaptureAuditStateAsync(account);
            var mutation = await ApplyChangesAsync(account, actor, request);
            if (mutation is not null) return Fail<PlayerAdminEditSessionResponse>(409, mutation);
            if (session.ItemCatalogHash != ComputeCatalogHash()
                || session.ClassCatalogHash != ComputeClassCatalogHash())
                return Fail<PlayerAdminEditSessionResponse>(409, "Master definitions changed during apply.");
            var now = DateTime.UtcNow;
            session.Status = "COMPLETED";
            session.Revision++;
            session.UpdatedAtUtc = now;
            session.CompletedAtUtc = now;
            account.UpdatedAt = now;
            account.UpdatedBy = actor;
            await dbContext.SaveChangesAsync();
            var after = await CaptureAuditStateAsync(account);
            var response = await MapSessionAsync(session);
            dbContext.PlayerAdminEditOperations.Add(new PlayerAdminEditOperationEntity
            {
                OperationId = request.OperationId, EditSessionId = editSessionId,
                RequestHash = hash, Action = "APPLY", ResponseJson = JsonSerializer.Serialize(response, JsonOptions),
                BeforeJson = before, AfterJson = after, CreatedAtUtc = now,
            });
            await dbContext.SaveChangesAsync();
            return new PlayerAdminResult<PlayerAdminEditSessionResponse>(200, response);
        });
    }

    public async Task<PlayerAdminResult<PlayerAdminEditSessionResponse>> CancelAsync(
        Guid editSessionId, Guid actor, PlayerAdminEditCancelRequest request)
    {
        if (request.OperationId == Guid.Empty || request.ExpectedRevision < 1)
            return Fail<PlayerAdminEditSessionResponse>(400, "Invalid cancellation request.");
        var owner = await dbContext.PlayerAdminEditSessions.AsNoTracking()
            .Where(x => x.EditSessionId == editSessionId).Select(x => (Guid?)x.UserUuid).SingleOrDefaultAsync();
        if (!owner.HasValue) return Fail<PlayerAdminEditSessionResponse>(404, "Edit session not found.");
        var hash = HashJson(request);
        return await InTransactionAsync(async () =>
        {
            if (!await LockUserAsync(owner.Value)) return Fail<PlayerAdminEditSessionResponse>(404, "User not found.");
            var session = await dbContext.PlayerAdminEditSessions.SingleAsync(x => x.EditSessionId == editSessionId);
            if (session.ActorUserUuid != actor) return Fail<PlayerAdminEditSessionResponse>(403, "Session owner mismatch.");
            var replay = await dbContext.PlayerAdminEditOperations.AsNoTracking()
                .SingleOrDefaultAsync(x => x.OperationId == request.OperationId);
            if (replay is not null)
            {
                if (replay.EditSessionId != editSessionId || replay.Action != "CANCEL" || replay.RequestHash != hash)
                    return Fail<PlayerAdminEditSessionResponse>(409, "Operation ID was reused.");
                var replayResponse = JsonSerializer.Deserialize<PlayerAdminEditSessionResponse>(replay.ResponseJson, JsonOptions);
                return replayResponse is null ? Fail<PlayerAdminEditSessionResponse>(409, "Stored receipt is invalid.")
                    : new(200, replayResponse);
            }
            if (session.Revision != request.ExpectedRevision || !ActiveStatuses.Contains(session.Status)
                || !await IsFullyDrainedAsync(session))
                return Fail<PlayerAdminEditSessionResponse>(409, "Cancel requires confirmed offline state.");
            var account = await LockAccountAsync(session.AccountId);
            if (account is null || account.UserId != session.UserUuid)
                return Fail<PlayerAdminEditSessionResponse>(409, "Account changed.");
            var before = await CaptureAuditStateAsync(account);
            var now = DateTime.UtcNow;
            session.Status = "CANCELED";
            session.Revision++;
            session.UpdatedAtUtc = now;
            session.CompletedAtUtc = now;
            await dbContext.SaveChangesAsync();
            var response = await MapSessionAsync(session);
            dbContext.PlayerAdminEditOperations.Add(new PlayerAdminEditOperationEntity
            {
                OperationId = request.OperationId, EditSessionId = editSessionId,
                RequestHash = hash, Action = "CANCEL", ResponseJson = JsonSerializer.Serialize(response, JsonOptions),
                BeforeJson = before, AfterJson = before, CreatedAtUtc = now,
            });
            await dbContext.SaveChangesAsync();
            return new PlayerAdminResult<PlayerAdminEditSessionResponse>(200, response);
        });
    }

    private async Task<string?> ApplyChangesAsync(AccountEntity account, Guid actor, PlayerAdminEditOperationRequest request)
    {
        var now = DateTime.UtcNow;
        if (request.Level.HasValue)
        {
            account.Level = request.Level.Value;
            account.TotalExperience = PlayerAdminLevelExperience.TotalRequired(account.Uuid, account.Level);
            account.HighestLevel = Math.Max(account.HighestLevel, account.Level);
            account.ProgressVersion = checked(account.ProgressVersion + 1);
        }
        if (request.ClassId is not null)
        {
            var definition = classRepository.GetById(request.ClassId.Trim());
            if (definition is null) return "Class definition is missing.";
            var saved = await dbContext.AccountClassProgresses.SingleOrDefaultAsync(x =>
                x.AccountId == account.Uuid && x.ClassId == definition.Id);
            if (saved is null)
            {
                saved = new AccountClassProgressEntity
                {
                    AccountId = account.Uuid, ClassId = definition.Id, Level = 1,
                    Experience = 0, UpdatedAt = now, UpdatedBy = actor,
                };
                dbContext.AccountClassProgresses.Add(saved);
            }
            account.ClassId = saved.ClassId;
            account.ClassLevel = saved.Level;
            account.ClassExperience = saved.Experience;
            account.ProgressVersion = checked(account.ProgressVersion + 1);
        }
        if (request.InventoryChanges!.Count == 0) return null;
        var bag = await dbContext.Inventories.SingleOrDefaultAsync(x => x.AccountId == account.Uuid
            && x.InventoryProfile == "GAME" && x.InventoryType == "BAG" && x.IsEnabled && !x.IsDeleted);
        if (bag is null || bag.SlotCapacity is < 1 or > 1000)
            return "GAME BAG is missing or has invalid capacity.";
        var bagCapacity = bag.SlotCapacity.GetValueOrDefault();
        var entries = await dbContext.InventoryEntries.Where(x => x.InventoryId == bag.InventoryId && !x.IsDeleted)
            .OrderBy(x => x.SlotIndex).ToListAsync();
        if (entries.Any(x => x.SlotIndex is null || x.SlotIndex < 1 || x.SlotIndex > bagCapacity)
            || entries.Select(x => x.SlotIndex).Distinct().Count() != entries.Count)
            return "BAG contains invalid or duplicate slots.";
        var touched = new HashSet<Guid>();
        foreach (var change in request.InventoryChanges)
        {
            if (change is null) return "Invalid inventory change.";
            if (change.Action == "GRANT")
            {
                if (string.IsNullOrWhiteSpace(change.ItemId) || change.Quantity is null or < 1
                    || change.InventoryEntryId.HasValue || change.ExpectedUpdatedAt.HasValue)
                    return "Invalid grant payload.";
                var item = itemRepository.GetById(change.ItemId);
                if (item is null || item.Category != "equipment"
                    && (!EditableCategories.Contains(item.Category) || item.MaxStack < 1))
                    return "Item is not supported by the editor.";
                if (item.Category == "equipment" && change.Quantity.Value != 1)
                    return "Equipment must be granted one instance per operation.";
                var remaining = change.Quantity.Value;
                if (item.Category != "equipment")
                {
                    foreach (var entry in entries.Where(x => IsEditableStack(x, item.Category)
                        && x.ItemId == item.Id && x.Quantity < item.MaxStack))
                    {
                        var added = Math.Min(remaining, item.MaxStack - entry.Quantity);
                        entry.Quantity += added;
                        entry.UpdatedAt = now;
                        entry.UpdatedBy = actor;
                        remaining -= added;
                        if (remaining == 0) break;
                    }
                }
                var used = entries.Where(x => x.SlotIndex.HasValue)
                    .Select(x => x.SlotIndex!.Value).ToHashSet();
                if (item.Category == "equipment" && remaining > bagCapacity - used.Count)
                    return "BAG has insufficient capacity for equipment.";
                while (remaining > 0)
                {
                    var slot = Enumerable.Range(1, bagCapacity).FirstOrDefault(x => !used.Contains(x));
                    if (slot == 0) return "BAG has insufficient capacity.";
                    var amount = item.Category == "equipment" ? 1 : Math.Min(remaining, item.MaxStack);
                    Guid? instanceId = null;
                    string? instanceType = null;
                    if (item.Category == "equipment")
                    {
                        try
                        {
                            var (instance, rolls) = PlayerAdminEquipmentRoller.Create(item, account.Uuid, actor, now);
                            dbContext.EquipmentInstances.Add(instance);
                            dbContext.EquipmentInstanceStatRolls.AddRange(rolls);
                            instanceId = instance.EquipmentInstanceId;
                            instanceType = "EQUIPMENT";
                        }
                        catch (Exception failure) when (failure is FormatException or ArgumentException or OverflowException)
                        {
                            return "Equipment master has invalid random-roll definitions.";
                        }
                    }
                    var entry = new InventoryEntryEntity
                    {
                        InventoryEntryId = Guid.NewGuid(), InventoryId = bag.InventoryId,
                        SlotIndex = slot, ItemCategory = item.Category, ItemId = item.Id,
                        InstanceType = instanceType, InstanceId = instanceId,
                        Quantity = amount, CreatedAt = now, UpdatedAt = now,
                        CreatedBy = actor, UpdatedBy = actor,
                    };
                    dbContext.InventoryEntries.Add(entry);
                    entries.Add(entry);
                    used.Add(slot);
                    remaining -= amount;
                }
            }
            else if (change.Action is "SET_QUANTITY" or "DELETE")
            {
                if (!change.InventoryEntryId.HasValue || !change.ExpectedUpdatedAt.HasValue
                    || !touched.Add(change.InventoryEntryId.Value)
                    || change.ItemId is not null) return "Invalid entry mutation payload.";
                var entry = entries.SingleOrDefault(x => x.InventoryEntryId == change.InventoryEntryId.Value);
                if (entry is null || entry.UpdatedAt != change.ExpectedUpdatedAt.Value
                    || !IsEditableStack(entry, entry.ItemCategory)) return "Entry changed or is protected.";
                var item = itemRepository.GetById(entry.ItemId!);
                if (item is null || item.Category != entry.ItemCategory || !EditableCategories.Contains(item.Category)
                    || item.MaxStack < 1) return "Item definition changed or is protected.";
                if (await dbContext.MarketListingSources.AnyAsync(x => x.InventoryEntryId == entry.InventoryEntryId))
                    return "Entry is referenced by a market listing.";
                if (change.Action == "DELETE")
                {
                    if (change.Quantity.HasValue) return "Delete does not accept quantity.";
                    entry.IsDeleted = true;
                    entries.Remove(entry);
                }
                else
                {
                    if (change.Quantity is null or < 1 || change.Quantity > item.MaxStack)
                        return "Quantity exceeds item stack rules.";
                    entry.Quantity = change.Quantity.Value;
                }
                entry.UpdatedAt = now;
                entry.UpdatedBy = actor;
            }
            else return "Unsupported inventory action.";
        }
        bag.UpdatedAt = now;
        bag.UpdatedBy = actor;
        return null;
    }

    private static bool IsEditableStack(InventoryEntryEntity entry, string category) =>
        entry.SlotIndex is >= 1 && entry.InstanceType is null && entry.InstanceId is null
        && entry.MetadataJson is null && entry.ItemId is not null && entry.Quantity > 0
        && entry.ItemCategory == category;

    private async Task<IReadOnlyList<PlayerAdminInventoryResponse>> GetInventoryViewAsync(Guid accountId)
    {
        var inventories = await dbContext.Inventories.AsNoTracking()
            .Where(x => x.AccountId == accountId && !x.IsDeleted)
            .OrderBy(x => x.InventoryProfile).ThenBy(x => x.InventoryType).ToListAsync();
        var ids = inventories.Select(x => x.InventoryId).ToArray();
        var entries = await dbContext.InventoryEntries.AsNoTracking()
            .Where(x => ids.Contains(x.InventoryId) && !x.IsDeleted)
            .OrderBy(x => x.InventoryId).ThenBy(x => x.SlotIndex).ThenBy(x => x.InventoryEntryId)
            .ToListAsync();
        var instanceIds = entries.Where(x => x.InstanceType == "EQUIPMENT" && x.InstanceId.HasValue)
            .Select(x => x.InstanceId!.Value).Distinct().ToArray();
        var equipmentItems = await dbContext.EquipmentInstances.AsNoTracking()
            .Where(x => instanceIds.Contains(x.EquipmentInstanceId) && !x.IsDeleted)
            .ToDictionaryAsync(x => x.EquipmentInstanceId, x => x.ItemId);
        return inventories.Select(inventory => new PlayerAdminInventoryResponse(
            inventory.InventoryId, inventory.InventoryType, inventory.InventoryProfile,
            inventory.SlotCapacity, inventory.IsEnabled,
            entries.Where(x => x.InventoryId == inventory.InventoryId)
                .Select(x =>
                {
                    var itemId = x.ItemId ?? (x.InstanceId.HasValue
                        && equipmentItems.TryGetValue(x.InstanceId.Value, out var equipmentItemId)
                            ? equipmentItemId : null);
                    return new PlayerAdminInventoryEntryResponse(x.InventoryEntryId, x.SlotIndex,
                        x.ItemCategory, x.ItemId, x.InstanceType, x.InstanceId, x.Quantity,
                        x.MetadataJson, x.UpdatedAt, itemId,
                        itemId is null ? null : itemRepository.GetById(itemId)?.Name);
                }).ToArray())).ToArray();
    }

    private async Task<string> ComputeStateHashAsync(AccountEntity account)
    {
        var progress = await dbContext.AccountClassProgresses.AsNoTracking()
            .Where(x => x.AccountId == account.Uuid).OrderBy(x => x.ClassId).ToListAsync();
        var inventory = await GetInventoryViewAsync(account.Uuid);
        var equipment = await GetEquipmentAuditSnapshotAsync(account.Uuid);
        var state = new { account.Uuid, account.UserId, account.Level, account.TotalExperience,
            account.HighestLevel, account.RebirthOriginalLevel, account.RebirthExperienceRemainder,
            account.ClassId, account.ClassLevel, account.ClassExperience, account.ProgressVersion,
            account.UpdatedAt, Progress = progress.Select(x => new { x.ClassId, x.Level, x.Experience, x.UpdatedAt }),
            Inventory = inventory, Equipment = equipment };
        return HashJson(state);
    }

    private async Task<string> CaptureAuditStateAsync(AccountEntity account)
    {
        var inventory = await GetInventoryViewAsync(account.Uuid);
        var equipment = await GetEquipmentAuditSnapshotAsync(account.Uuid);
        return JsonSerializer.Serialize(new
        {
            Account = MapAccount(account), Inventory = inventory, Equipment = equipment,
        }, JsonOptions);
    }

    private sealed record EquipmentAuditSnapshot(
        IReadOnlyList<EquipmentInstanceEntity> Instances,
        IReadOnlyList<EquipmentInstanceStatRollEntity> StatRolls,
        IReadOnlyList<EquipmentInstanceRuneEntity> Runes,
        IReadOnlyList<EquipmentInstanceEnchantEntity> Enchants,
        IReadOnlyList<EquipmentLoadoutEntity> Loadouts,
        IReadOnlyList<EquipmentLoadoutSlotEntity> LoadoutSlots);

    private async Task<EquipmentAuditSnapshot> GetEquipmentAuditSnapshotAsync(Guid accountId)
    {
        var instances = await dbContext.EquipmentInstances.AsNoTracking()
            .Where(x => x.AccountId == accountId && !x.IsDeleted)
            .OrderBy(x => x.EquipmentInstanceId).ToArrayAsync();
        var ids = instances.Select(x => x.EquipmentInstanceId).ToArray();
        var rolls = await dbContext.EquipmentInstanceStatRolls.AsNoTracking()
            .Where(x => ids.Contains(x.EquipmentInstanceId))
            .OrderBy(x => x.EquipmentInstanceId).ThenBy(x => x.SortOrder)
            .ThenBy(x => x.StatRollId).ToArrayAsync();
        var runes = await dbContext.EquipmentInstanceRunes.AsNoTracking()
            .Where(x => ids.Contains(x.EquipmentInstanceId))
            .OrderBy(x => x.EquipmentInstanceId).ThenBy(x => x.SlotIndex)
            .ThenBy(x => x.RuneId).ToArrayAsync();
        var enchants = await dbContext.EquipmentInstanceEnchants.AsNoTracking()
            .Where(x => ids.Contains(x.EquipmentInstanceId))
            .OrderBy(x => x.EquipmentInstanceId).ThenBy(x => x.SlotIndex)
            .ThenBy(x => x.EnchantId).ToArrayAsync();
        var loadouts = await dbContext.EquipmentLoadouts.AsNoTracking()
            .Where(x => x.AccountId == accountId && !x.IsDeleted)
            .OrderBy(x => x.EquipmentLoadoutId).ToArrayAsync();
        var loadoutIds = loadouts.Select(x => x.EquipmentLoadoutId).ToArray();
        var loadoutSlots = await dbContext.EquipmentLoadoutSlots.AsNoTracking()
            .Where(x => loadoutIds.Contains(x.EquipmentLoadoutId) && !x.IsDeleted)
            .OrderBy(x => x.EquipmentLoadoutId).ThenBy(x => x.SlotType)
            .ThenBy(x => x.SlotIndex).ThenBy(x => x.EquipmentLoadoutSlotId).ToArrayAsync();
        return new(instances, rolls, runes, enchants, loadouts, loadoutSlots);
    }

    private static PlayerAdminAccountResponse MapAccount(AccountEntity account) => new(
        account.Uuid, account.UserId, account.Level, account.TotalExperience,
        account.HighestLevel, account.ClassId, account.ClassLevel,
        account.ClassExperience, account.ProgressVersion);

    private static string HashJson<T>(T value) => Convert.ToHexString(SHA256.HashData(
        Encoding.UTF8.GetBytes(JsonSerializer.Serialize(value, JsonOptions)))).ToLowerInvariant();

    private string HashCatalogJson<T>(T value) => Convert.ToHexString(SHA256.HashData(
        Encoding.UTF8.GetBytes(JsonSerializer.Serialize(value, catalogJsonOptions)))).ToLowerInvariant();
}
