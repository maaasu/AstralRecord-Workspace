using System.Data;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Services;
using AstralRecordApi.Utilities;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public class PetRepository(AstralRecordDbContext db, MasterDataDbContext masters) : IPetRepository
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);
    private const int PetEquipSlotIndex = 7;

    public async Task<PetMasterResponse?> GetMasterAsync()
    {
        var payload = await masters.Entries.AsNoTracking()
            .Where(e => e.MasterType == "pet" && e.MasterId == "pets" && !e.IsDeleted)
            .Select(e => e.PayloadJson).SingleOrDefaultAsync();
        if (payload is null) return null;
        var master = MasterDataPayloadJson.Deserialize<PetMasterResponse>(payload)!;
        PetMasterValidator.Validate(master);
        master.Rules.ReviveOrbItemId = ItemId(master.Rules.ReviveOrbItemId);
        foreach (var material in master.Rules.BreedMaterials.Concat(master.Rules.ReviveMaterials)) material.ItemId = ItemId(material.ItemId);
        foreach (var species in master.Species)
        {
            species.EggItemId = ItemId(species.EggItemId); species.PetItemId = ItemId(species.PetItemId);
            foreach (var material in species.HatchMaterials) material.ItemId = ItemId(material.ItemId);
        }
        return master;
    }

    public async Task<PetAccountResponse> GetByAccountAsync(Guid accountId) => new()
    {
        AccountId = accountId,
        EquippedPetId = await db.AccountPetStates.AsNoTracking().Where(s => s.AccountId == accountId)
            .Select(s => s.EquippedPetId).SingleOrDefaultAsync(),
        Instances = (await db.PetInstances.AsNoTracking().Where(p => p.AccountId == accountId && !p.IsDeleted)
            .OrderBy(p => p.CreatedAt).ToArrayAsync()).Select(Map).ToArray(),
    };

    public async Task<PetInstanceResponse?> GetInstanceAsync(Guid accountId, Guid instanceId)
    {
        var entity = await db.PetInstances.AsNoTracking().SingleOrDefaultAsync(p => p.AccountId == accountId && p.InstanceId == instanceId && !p.IsDeleted);
        return entity is null ? null : Map(entity);
    }

    public async Task<PetMutationResult?> GetOperationAsync(Guid accountId, Guid operationId)
    {
        var receipt = await db.PetOperations.AsNoTracking().SingleOrDefaultAsync(o => o.AccountId == accountId && o.OperationId == operationId);
        return receipt is null ? null : new PetMutationResult(await ReplayAsync(receipt));
    }

    public Task<PetMutationResult> CreateEggAsync(Guid accountId, PetEggCreateRequest request)
        => ExecuteAsync(accountId, "egg", request, null, async (master, now, affected) =>
        {
            var species = FindSpecies(master, request.SpeciesId);
            if (species is null) return Failure("species_not_found");
            var bag = await FindBagAsync(accountId);
            if (bag is null) return Failure("bag_not_found");
            var egg = NewEgg(accountId, species, now, request.UpdatedBy);
            db.PetInstances.Add(egg);
            var entry = NewEntry(bag.InventoryId, egg, now, request.UpdatedBy);
            db.InventoryEntries.Add(entry);
            Touch(bag, now, request.UpdatedBy);
            affected.Add(entry.InventoryEntryId);
            return Success(egg);
        });

    public Task<PetMutationResult> HatchAsync(Guid accountId, Guid instanceId, PetFacilityRequest request)
        => ExecuteAsync(accountId, "hatch", request, instanceId, async (master, now, affected) =>
        {
            if (!AtFacility(master, request)) return Failure("facility_required");
            var egg = await FindOwnedAsync(accountId, instanceId);
            if (egg is null || !egg.IsEgg) return Failure("egg_not_found");
            var species = FindSpecies(master, egg.SpeciesId);
            if (species is null) return Failure("species_not_found");
            var entry = await FindOwnedBagEntryAsync(accountId, instanceId);
            if (entry is null) return Failure("egg_not_in_bag");
            if (!await ConsumeMaterialsAsync(accountId, species.HatchMaterials, now, request.UpdatedBy, affected))
                return Failure("insufficient_materials");
            var details = egg.Origin == "BRED" ? ReadDetails(egg) : PetGenetics.HatchWild(master, species, Random.Shared);
            egg.IsEgg = false;
            egg.ItemId = species.PetItemId;
            SaveDetails(egg, details, now, request.UpdatedBy);
            entry.ItemCategory = "pet";
            entry.ItemId = species.PetItemId;
            entry.InstanceType = "PET";
            entry.MetadataJson = null;
            entry.UpdatedAt = now;
            entry.UpdatedBy = request.UpdatedBy;
            affected.Add(entry.InventoryEntryId);
            return Success(egg);
        });

    public Task<PetMutationResult> BreedAsync(Guid accountId, PetBreedRequest request)
        => ExecuteAsync(accountId, "breed", request, null, async (master, now, affected) =>
        {
            if (!AtFacility(master, request)) return Failure("facility_required");
            if (request.MaleId == request.FemaleId) return Failure("parents_invalid");
            var male = await FindOwnedAsync(accountId, request.MaleId);
            var female = await FindOwnedAsync(accountId, request.FemaleId);
            if (male is null || female is null || male.IsEgg || female.IsEgg || male.SpeciesId != female.SpeciesId)
                return Failure("parents_invalid");
            var left = ReadDetails(male);
            var right = ReadDetails(female);
            if (left.Sex != "MALE" || right.Sex != "FEMALE") return Failure("sex_mismatch");
            if (left.IsDead || right.IsDead) return Failure("parent_dead");
            if (left.Level < master.Rules.BreedMinLevel || right.Level < master.Rules.BreedMinLevel) return Failure("growth_required");
            if (left.BreedAvailableAt > now || right.BreedAvailableAt > now) return Failure("breed_cooldown");
            if (await FindOwnedBagEntryAsync(accountId, male.InstanceId) is null || await FindOwnedBagEntryAsync(accountId, female.InstanceId) is null)
                return Failure("parent_not_in_bag");
            var species = FindSpecies(master, male.SpeciesId);
            var bag = await FindBagAsync(accountId);
            if (species is null || bag is null) return Failure("species_or_bag_not_found");
            if (!await ConsumeMaterialsAsync(accountId, master.Rules.BreedMaterials, now, request.UpdatedBy, affected))
                return Failure("insufficient_materials");
            var child = NewEgg(accountId, species, now, request.UpdatedBy);
            child.Origin = "BRED";
            child.MaleParentId = male.InstanceId;
            child.FemaleParentId = female.InstanceId;
            child.DetailsJson = JsonSerializer.Serialize(PetGenetics.Breed(master, species, left, right, Random.Shared), JsonOptions);
            db.PetInstances.Add(child);
            var entry = NewEntry(bag.InventoryId, child, now, request.UpdatedBy);
            db.InventoryEntries.Add(entry);
            affected.Add(entry.InventoryEntryId);
            left.BreedAvailableAt = right.BreedAvailableAt = now.AddHours(master.Rules.BreedCooldownHours);
            SaveDetails(male, left, now, request.UpdatedBy);
            SaveDetails(female, right, now, request.UpdatedBy);
            Touch(bag, now, request.UpdatedBy);
            return Success(child);
        });

    public Task<PetMutationResult> ProgressAsync(Guid accountId, Guid instanceId, PetProgressRequest request)
        => ExecuteAsync(accountId, "progress", request, instanceId, async (master, now, _) =>
        {
            if (request.Experience < 0 || !double.IsFinite(request.HealthRatio) || request.HealthRatio is < 0 or > 1
                || request.Cooldowns?.Any(c => c.Value < 0 || c.Key.Length > 128) == true) return Failure("progress_invalid");
            var pet = await FindOwnedAsync(accountId, instanceId);
            if (pet is null || pet.IsEgg) return Failure("pet_not_found");
            if (pet.Version != request.ExpectedVersion) return Failure("version_conflict");
            var state = await db.AccountPetStates.SingleOrDefaultAsync(s => s.AccountId == accountId);
            if (state?.EquippedPetId != pet.InstanceId) return Failure("pet_not_equipped");
            var details = ReadDetails(pet);
            if (details.IsDead) return Failure("pet_dead");
            var species = FindSpecies(master, pet.SpeciesId);
            if (species is null) return Failure("species_not_found");
            if (request.Cooldowns?.Keys.Any(id => id != "__basic_attack" && details.Skills.All(s => s.Id != id)) == true) return Failure("cooldown_skill_invalid");
            try { PetGenetics.Grow(master, species, details, request.Experience, Random.Shared); }
            catch (OverflowException) { return Failure("experience_overflow"); }
            details.IsDead = request.IsDead || request.HealthRatio <= 0;
            details.HealthRatio = details.IsDead ? 0 : request.HealthRatio;
            if (request.Cooldowns is not null) details.Cooldowns = request.Cooldowns;
            SaveDetails(pet, details, now, request.UpdatedBy);
            return Success(pet);
        });

    public Task<PetMutationResult> ReviveAsync(Guid accountId, Guid instanceId, PetReviveRequest request)
        => ExecuteAsync(accountId, "revive", request, instanceId, async (master, now, affected) =>
        {
            var pet = await FindOwnedAsync(accountId, instanceId);
            if (pet is null || pet.IsEgg) return Failure("pet_not_found");
            var state = await db.AccountPetStates.SingleOrDefaultAsync(s => s.AccountId == accountId);
            if (await FindOwnedBagEntryAsync(accountId, instanceId) is null
                && (state?.EquippedPetId != instanceId || await FindOwnedEquipEntryAsync(accountId, instanceId) is null))
                return Failure("pet_not_available");
            var details = ReadDetails(pet);
            if (!details.IsDead) return Failure("pet_alive");
            if (request.OrbInventoryEntryId is Guid orbId)
            {
                var orb = await FindOwnedNormalBagEntryAsync(accountId, orbId);
                if (orb is null || orb.ItemId != master.Rules.ReviveOrbItemId || orb.Quantity < 1) return Failure("revive_orb_missing");
                Consume(orb, 1, now, request.UpdatedBy);
                affected.Add(orb.InventoryEntryId);
            }
            else
            {
                if (!AtFacility(master, request)) return Failure("facility_or_orb_required");
                if (!await ConsumeMaterialsAsync(accountId, master.Rules.ReviveMaterials, now, request.UpdatedBy, affected)) return Failure("insufficient_materials");
            }
            details.IsDead = false;
            details.HealthRatio = 1;
            SaveDetails(pet, details, now, request.UpdatedBy);
            return Success(pet);
        });

    public Task<PetMutationResult> EquipAsync(Guid accountId, PetEquipRequest request)
        => ExecuteAsync(accountId, "equip", request, null, async (_, now, affected) =>
        {
            var bag = await FindBagAsync(accountId);
            if (bag is null) return Failure("bag_not_found");
            var equip = await db.Inventories.SingleOrDefaultAsync(i => i.AccountId == accountId
                && i.InventoryType == "EQUIP_SLOT" && i.InventoryProfile == "GAME" && !i.IsDeleted && i.IsEnabled);
            if (equip is null)
            {
                equip = new InventoryEntity
                {
                    InventoryId = Guid.NewGuid(), AccountId = accountId, InventoryType = "EQUIP_SLOT",
                    InventoryProfile = "GAME", SlotCapacity = PetEquipSlotIndex, IsEnabled = true,
                    CreatedAt = now, UpdatedAt = now, CreatedBy = request.UpdatedBy, UpdatedBy = request.UpdatedBy,
                };
                db.Inventories.Add(equip);
            }
            else if (equip.SlotCapacity is null or < PetEquipSlotIndex)
            {
                equip.SlotCapacity = PetEquipSlotIndex;
                Touch(equip, now, request.UpdatedBy);
            }

            var state = await db.AccountPetStates.SingleOrDefaultAsync(s => s.AccountId == accountId);
            var equippedEntry = await db.InventoryEntries.SingleOrDefaultAsync(e => e.InventoryId == equip.InventoryId
                && e.SlotIndex == PetEquipSlotIndex && !e.IsDeleted);
            if (equippedEntry is not null && (state?.EquippedPetId != equippedEntry.InstanceId
                || equippedEntry.InstanceType != "PET" || equippedEntry.ItemCategory != "pet"
                || equippedEntry.Quantity != 1))
                return Failure("pet_slot_conflict");
            if (equippedEntry is not null)
            {
                var selected = await FindOwnedAsync(accountId, equippedEntry.InstanceId!.Value);
                if (selected is null || selected.IsEgg || selected.ItemId != equippedEntry.ItemId)
                    return Failure("pet_slot_conflict");
            }
            if (state?.EquippedPetId is Guid oldId && equippedEntry is null
                && await FindOwnedBagEntryAsync(accountId, oldId) is null)
                return Failure("pet_slot_conflict");

            PetInstanceEntity? pet = null;
            InventoryEntryEntity? incoming = null;
            if (request.PetId.HasValue)
            {
                pet = await FindOwnedAsync(accountId, request.PetId.Value);
                if (pet is null || pet.IsEgg) return Failure("pet_not_available");
                if (equippedEntry?.InstanceId != pet.InstanceId)
                {
                    incoming = await FindOwnedBagEntryAsync(accountId, pet.InstanceId);
                    if (incoming is null || incoming.InstanceType != "PET" || incoming.ItemCategory != "pet"
                        || incoming.ItemId != pet.ItemId) return Failure("pet_not_available");
                }
            }

            if (equippedEntry is not null && equippedEntry.InstanceId != request.PetId)
            {
                if (request.ReturnBagSlotIndex is not > 0) return Failure("bag_slot_required");
                var incomingEntryId = incoming?.InventoryEntryId;
                var slotOccupied = db.InventoryEntries.Local.Any(e => e.InventoryId == bag.InventoryId
                    && e.SlotIndex == request.ReturnBagSlotIndex && !e.IsDeleted
                    && e.InventoryEntryId != incomingEntryId);
                if (slotOccupied) return Failure("bag_slot_occupied");

                // Exchange releases both filtered unique slots before the returning entry is reactivated.
                if (incoming is not null)
                {
                    equippedEntry.IsDeleted = true;
                    equippedEntry.UpdatedAt = Advance(equippedEntry.UpdatedAt, now);
                    equippedEntry.UpdatedBy = request.UpdatedBy;
                    await db.SaveChangesAsync();
                    Move(incoming, equip.InventoryId, PetEquipSlotIndex, now, request.UpdatedBy);
                    await db.SaveChangesAsync();
                    affected.Add(incoming.InventoryEntryId);
                }
                Move(equippedEntry, bag.InventoryId, request.ReturnBagSlotIndex.Value, now, request.UpdatedBy);
                equippedEntry.IsDeleted = false;
                affected.Add(equippedEntry.InventoryEntryId);
                Touch(bag, now, request.UpdatedBy);
                Touch(equip, now, request.UpdatedBy);
            }
            if (incoming is not null && incoming.InventoryId != equip.InventoryId)
            {
                Move(incoming, equip.InventoryId, PetEquipSlotIndex, now, request.UpdatedBy);
                affected.Add(incoming.InventoryEntryId);
                Touch(bag, now, request.UpdatedBy);
                Touch(equip, now, request.UpdatedBy);
            }
            if (state is null) { state = new AccountPetStateEntity { AccountId = accountId }; db.AccountPetStates.Add(state); }
            state.EquippedPetId = request.PetId;
            state.UpdatedAt = now;
            state.UpdatedBy = request.UpdatedBy;
            return new PetMutationResult(new PetMutationResponse { Instance = pet is null ? null : Map(pet), EquippedPetId = request.PetId });
        });

    public Task<PetMutationResult> RenameAsync(Guid accountId, Guid instanceId, PetRenameRequest request)
        => ExecuteAsync(accountId, "rename", request, instanceId, async (_, now, _) =>
        {
            var pet = await FindOwnedAsync(accountId, instanceId);
            if (pet is null || pet.IsEgg) return Failure("pet_not_found");
            var name = request.Name.Trim();
            if (name.Length is < 1 or > 24 || name.Any(char.IsControl) || name.Contains('§')) return Failure("name_invalid");
            var details = ReadDetails(pet);
            details.Name = name;
            SaveDetails(pet, details, now, request.UpdatedBy);
            return Success(pet);
        });

    private async Task<PetMutationResult> ExecuteAsync(Guid accountId, string kind, PetOperationRequest request, Guid? instanceId,
        Func<PetMasterResponse, DateTime, List<Guid>, Task<PetMutationResult>> action)
    {
        if (accountId == Guid.Empty || request.OperationId == Guid.Empty || request.UpdatedBy == Guid.Empty) return Failure("request_invalid");
        // Runtime authority gates the write but is not part of the gameplay operation.
        // Preserve receipts written before these optional proof fields existed.
        PetOperationRequest hashableRequest = request is PetProgressRequest progressRequest
            ? new PetProgressRequest
            {
                OperationId = progressRequest.OperationId, UpdatedBy = progressRequest.UpdatedBy,
                ExpectedVersion = progressRequest.ExpectedVersion, Experience = progressRequest.Experience,
                HealthRatio = progressRequest.HealthRatio, IsDead = progressRequest.IsDead,
                Cooldowns = progressRequest.Cooldowns,
            }
            : request;
        var requestHash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(
            accountId + ":" + kind + ":" + instanceId + ":" + JsonSerializer.Serialize(hashableRequest, hashableRequest.GetType(), JsonOptions))));
        var strategy = db.Database.CreateExecutionStrategy();
        return await strategy.ExecuteAsync(async () =>
        {
            db.ChangeTracker.Clear();
            await using var transaction = await db.Database.BeginTransactionAsync(db.Database.IsSqlServer() ? IsolationLevel.ReadCommitted : IsolationLevel.Serializable);
            // Start/Apply take the user lock first. A final progress flush may finish
            // only with the account session captured before that edit began.
            var editBlocked = await PlayerAdminEditWriteGuard.IsBlockedAsync(db, [accountId]);
            var account = db.Database.IsSqlServer()
                ? await db.Accounts.FromSqlInterpolated($"SELECT * FROM [dbo].[account] WITH (UPDLOCK,HOLDLOCK) WHERE [uuid]={accountId} AND [is_deleted]=0").SingleOrDefaultAsync()
                : await db.Accounts.SingleOrDefaultAsync(a => a.Uuid == accountId && !a.IsDeleted);
            if (account is null) return Failure("account_not_found");
            var receipt = await db.PetOperations.SingleOrDefaultAsync(o => o.OperationId == request.OperationId);
            if (receipt is not null)
            {
                if (receipt.AccountId != accountId || receipt.RequestHash != requestHash) return Failure("operation_conflict");
                return new PetMutationResult(await ReplayAsync(receipt));
            }
            if (editBlocked && (kind != "progress" || request is not PetProgressRequest progress
                || !await PlayerAdminEditWriteGuard.AllowsCapturedRuntimeFinalizationAsync(db,
                    accountId, account.UserId, progress.ServerId, progress.ServerSessionId,
                    progress.AccountSessionId, progress.AccountLeaseToken)))
                return Failure("player_editing");
            var master = await GetMasterAsync();
            if (master is null) return Failure("master_not_found");
            // Keep the account -> inventory -> entry lock order used by player-state/trade.
            var needsInventory = kind is "egg" or "hatch" or "breed" or "revive" or "equip";
            InventoryEntity[] inventories = !needsInventory ? [] : db.Database.IsSqlServer()
                ? await db.Inventories.FromSqlInterpolated($"SELECT * FROM [dbo].[inventory] WITH (UPDLOCK,HOLDLOCK) WHERE [account_id]={accountId} AND [is_deleted]=0 ORDER BY [inventory_id]").ToArrayAsync()
                : await db.Inventories.Where(i => i.AccountId == accountId && !i.IsDeleted).ToArrayAsync();
            foreach (var inventory in inventories.OrderBy(i => i.InventoryId))
            {
                if (db.Database.IsSqlServer())
                    await db.InventoryEntries.FromSqlInterpolated($"SELECT * FROM [dbo].[inventory_entry] WITH (UPDLOCK,HOLDLOCK) WHERE [inventory_id]={inventory.InventoryId} ORDER BY [inventory_entry_id]").LoadAsync();
                else await db.InventoryEntries.Where(e => e.InventoryId == inventory.InventoryId).LoadAsync();
            }
            var now = DateTime.UtcNow;
            var affected = new List<Guid>();
            var result = await action(master, now, affected);
            if (!result.Succeeded) return result;
            if (kind != "equip")
                result.Response!.EquippedPetId = await db.AccountPetStates.Where(s => s.AccountId == accountId).Select(s => s.EquippedPetId).SingleOrDefaultAsync();
            foreach (var inventory in inventories.Where(i => kind != "equip" && affected.Any(id => db.InventoryEntries.Local.Any(e => e.InventoryEntryId == id && e.InventoryId == i.InventoryId))))
                Touch(inventory, now, request.UpdatedBy);
            db.PetOperations.Add(new PetOperationEntity
            {
                OperationId = request.OperationId, AccountId = accountId, RequestHash = requestHash,
                ResponseJson = JsonSerializer.Serialize(result.Response, JsonOptions),
                AffectedEntryIdsJson = JsonSerializer.Serialize(affected.Distinct().ToArray()), CompletedAt = now,
            });
            await db.SaveChangesAsync();
            result.Response!.InventorySnapshot = await InventoryOperationSnapshotReader.ReadAsync(db, accountId, affected);
            await transaction.CommitAsync();
            return result;
        });
    }

    private async Task<PetMutationResponse> ReplayAsync(PetOperationEntity receipt)
    {
        var response = JsonSerializer.Deserialize<PetMutationResponse>(receipt.ResponseJson, JsonOptions)!;
        var ids = JsonSerializer.Deserialize<Guid[]>(receipt.AffectedEntryIdsJson) ?? [];
        response.InventorySnapshot = await InventoryOperationSnapshotReader.ReadAsync(db, receipt.AccountId, ids);
        // Never return details of an instance subsequently transferred to another owner.
        if (response.Instance is { } instance)
            response.Instance = await GetInstanceAsync(receipt.AccountId, instance.InstanceId);
        response.EquippedPetId = await db.AccountPetStates.AsNoTracking().Where(s => s.AccountId == receipt.AccountId)
            .Select(s => s.EquippedPetId).SingleOrDefaultAsync();
        return response;
    }

    private async Task<InventoryEntity?> FindBagAsync(Guid accountId) => await db.Inventories.SingleOrDefaultAsync(i => i.AccountId == accountId
        && i.InventoryType == "BAG" && i.InventoryProfile == "GAME" && !i.IsDeleted && i.IsEnabled);
    private Task<PetInstanceEntity?> FindOwnedAsync(Guid accountId, Guid id) => db.PetInstances.SingleOrDefaultAsync(p => p.AccountId == accountId && p.InstanceId == id && !p.IsDeleted);
    private async Task<InventoryEntryEntity?> FindOwnedBagEntryAsync(Guid accountId, Guid instanceId)
        => await (from entry in db.InventoryEntries join bag in db.Inventories on entry.InventoryId equals bag.InventoryId
                  where bag.AccountId == accountId && bag.InventoryType == "BAG" && bag.InventoryProfile == "GAME" && bag.IsEnabled && !bag.IsDeleted
                       && entry.InstanceId == instanceId && (entry.InstanceType == "PET" || entry.InstanceType == "PET_EGG")
                       && !entry.IsDeleted && entry.Quantity == 1
                  select entry).SingleOrDefaultAsync();
    private async Task<InventoryEntryEntity?> FindOwnedEquipEntryAsync(Guid accountId, Guid instanceId)
        => await (from entry in db.InventoryEntries join equip in db.Inventories on entry.InventoryId equals equip.InventoryId
                  where equip.AccountId == accountId && equip.InventoryType == "EQUIP_SLOT" && equip.InventoryProfile == "GAME"
                      && equip.IsEnabled && !equip.IsDeleted && entry.SlotIndex == PetEquipSlotIndex
                      && entry.InstanceId == instanceId && entry.InstanceType == "PET" && entry.ItemCategory == "pet"
                      && !entry.IsDeleted && entry.Quantity == 1
                  select entry).SingleOrDefaultAsync();
    private async Task<InventoryEntryEntity?> FindOwnedNormalBagEntryAsync(Guid accountId, Guid entryId)
        => await (from entry in db.InventoryEntries join bag in db.Inventories on entry.InventoryId equals bag.InventoryId
                  where bag.AccountId == accountId && bag.InventoryType == "BAG" && bag.InventoryProfile == "GAME" && bag.IsEnabled && !bag.IsDeleted
                      && entry.InventoryEntryId == entryId && !entry.IsDeleted && entry.InstanceId == null && entry.InstanceType == null
                  select entry).SingleOrDefaultAsync();

    private async Task<bool> ConsumeMaterialsAsync(Guid accountId, IReadOnlyList<PetMaterialMaster> required, DateTime now, Guid actor, List<Guid> affected)
    {
        var bag = await FindBagAsync(accountId);
        if (bag is null) return false;
        var entries = await db.InventoryEntries.Where(e => e.InventoryId == bag.InventoryId && !e.IsDeleted && e.InstanceId == null && e.InstanceType == null)
            .OrderBy(e => e.InventoryEntryId).ToArrayAsync();
        var costs = required.GroupBy(r => r.ItemId, StringComparer.OrdinalIgnoreCase)
            .Select(g => new { ItemId = g.Key, Quantity = g.Sum(r => r.Quantity) }).ToArray();
        if (costs.Any(c => c.Quantity <= 0 || entries.Where(e => string.Equals(e.ItemId, c.ItemId, StringComparison.OrdinalIgnoreCase)).Sum(e => e.Quantity) < c.Quantity)) return false;
        foreach (var cost in costs)
        {
            var remaining = cost.Quantity;
            foreach (var entry in entries.Where(e => string.Equals(e.ItemId, cost.ItemId, StringComparison.OrdinalIgnoreCase)))
            {
                var amount = Math.Min(remaining, entry.Quantity);
                if (amount == 0) continue;
                Consume(entry, amount, now, actor);
                affected.Add(entry.InventoryEntryId);
                remaining -= amount;
                if (remaining == 0) break;
            }
        }
        return true;
    }

    private static void Consume(InventoryEntryEntity entry, long quantity, DateTime now, Guid actor)
    {
        var remaining = entry.Quantity - quantity;
        entry.IsDeleted = remaining == 0;
        // inventory_entryの既存CHECK(quantity >= 1)を削除済み行でも満たす。
        entry.Quantity = Math.Max(1, remaining); entry.UpdatedAt = Advance(entry.UpdatedAt, now); entry.UpdatedBy = actor;
    }
    private static void Touch(InventoryEntity inventory, DateTime now, Guid actor) { inventory.UpdatedAt = Advance(inventory.UpdatedAt, now); inventory.UpdatedBy = actor; }
    private static void Move(InventoryEntryEntity entry, Guid inventoryId, int slotIndex, DateTime now, Guid actor)
    {
        entry.InventoryId = inventoryId; entry.SlotIndex = slotIndex;
        entry.UpdatedAt = Advance(entry.UpdatedAt, now); entry.UpdatedBy = actor;
    }
    private static DateTime Advance(DateTime current, DateTime now)
    {
        var rounded = new DateTime(now.Ticks / TimeSpan.TicksPerMillisecond * TimeSpan.TicksPerMillisecond, DateTimeKind.Utc);
        return rounded > current ? rounded : current.AddMilliseconds(1);
    }
    private static PetSpeciesMaster? FindSpecies(PetMasterResponse master, string id) => master.Species.SingleOrDefault(s => s.Id == id);
    private static string ItemId(string id) => id.StartsWith("item:", StringComparison.OrdinalIgnoreCase) ? id[5..] : id;
    private static bool AtFacility(PetMasterResponse master, PetFacilityRequest request) => request.FacilityId == master.Rules.FacilityId;
    private static PetInstanceEntity NewEgg(Guid accountId, PetSpeciesMaster species, DateTime now, Guid actor) => new()
    {
        InstanceId = Guid.NewGuid(), AccountId = accountId, SpeciesId = species.Id, ItemId = species.EggItemId,
        IsEgg = true, CreatedAt = now, UpdatedAt = now, CreatedBy = actor, UpdatedBy = actor,
    };
    private static InventoryEntryEntity NewEntry(Guid inventoryId, PetInstanceEntity pet, DateTime now, Guid actor) => new()
    {
        InventoryEntryId = Guid.NewGuid(), InventoryId = inventoryId, ItemCategory = pet.IsEgg ? "pet_egg" : "pet",
        ItemId = pet.ItemId, InstanceType = pet.IsEgg ? "PET_EGG" : "PET", InstanceId = pet.InstanceId, Quantity = 1,
        CreatedAt = now, UpdatedAt = now, CreatedBy = actor, UpdatedBy = actor,
    };
    private static PetDetailsResponse ReadDetails(PetInstanceEntity pet) => JsonSerializer.Deserialize<PetDetailsResponse>(pet.DetailsJson, JsonOptions)!;
    private static void SaveDetails(PetInstanceEntity pet, PetDetailsResponse details, DateTime now, Guid actor)
    {
        pet.DetailsJson = JsonSerializer.Serialize(details, JsonOptions); pet.Version++; pet.UpdatedAt = now; pet.UpdatedBy = actor;
    }
    private static PetMutationResult Failure(string failure) => new(null, failure);
    private static PetMutationResult Success(PetInstanceEntity pet) => new(new PetMutationResponse { Instance = Map(pet) });
    private static PetInstanceResponse Map(PetInstanceEntity pet) => new()
    {
        InstanceId = pet.InstanceId, AccountId = pet.AccountId, SpeciesId = pet.SpeciesId, ItemId = pet.ItemId,
        IsEgg = pet.IsEgg, Origin = pet.Origin, TradeAllowed = pet.Origin == "WILD", Version = pet.Version,
        CreatedAt = DateTime.SpecifyKind(pet.CreatedAt, DateTimeKind.Utc), UpdatedAt = DateTime.SpecifyKind(pet.UpdatedAt, DateTimeKind.Utc),
        Details = pet.IsEgg ? null : ReadDetails(pet),
    };
}
