using System.Text.Json;
using System.Security.Cryptography;
using System.Text;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using AstralRecordApi.Services;
using AstralRecordApi.Tests.TestSupport;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

/// <summary>設計契約: 39-pet。固定マスターで孵化非公開・原子消費・遺伝・死亡・移管禁止を検証する。</summary>
public class PetRepositoryTests
{
    [Fact]
    public async Task ProgressDuringEdit_RequiresCapturedRuntimeProofAndPreservesReplay()
    {
        await using var fixture = await Fixture.CreateAsync();
        var pet = await fixture.AddPetAsync("WILD", "MALE", 1);
        Assert.True((await fixture.Repository.EquipAsync(fixture.Account, new PetEquipRequest
            { OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account, PetId = pet.InstanceId })).Succeeded);
        // The editor targets another account owned by the same user; the online
        // account still has to finish its captured pet flush before the user drains.
        var proof = await fixture.StartEditWithRuntimeAsync(editAnotherOwnedAccount: true);
        var operationId = Guid.NewGuid();
        var missing = new PetProgressRequest { OperationId = operationId, UpdatedBy = fixture.Account,
            ExpectedVersion = pet.Version, Experience = 5, HealthRatio = 1 };
        Assert.Equal("player_editing", (await fixture.Repository.ProgressAsync(fixture.Account, pet.InstanceId, missing)).Failure);
        var authorized = new PetProgressRequest { OperationId = operationId, UpdatedBy = fixture.Account,
            ExpectedVersion = pet.Version, Experience = 5, HealthRatio = 1,
            ServerId = proof.ServerId, ServerSessionId = proof.Boot,
            AccountSessionId = proof.Session, AccountLeaseToken = proof.Token };
        Assert.Equal("player_editing", (await fixture.Repository.ProgressAsync(fixture.Account, pet.InstanceId,
            withWrongToken())).Failure);
        Assert.True((await fixture.Repository.ProgressAsync(fixture.Account, pet.InstanceId, authorized)).Succeeded);
        await fixture.Player.PlayerAdminEditSessions.ExecuteUpdateAsync(x => x.SetProperty(e => e.Status, "READY"));
        Assert.True((await fixture.Repository.ProgressAsync(fixture.Account, pet.InstanceId, missing)).Succeeded);
        Assert.Equal("player_editing", (await fixture.Repository.ProgressAsync(fixture.Account, pet.InstanceId,
            new PetProgressRequest { OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account,
                ExpectedVersion = pet.Version + 1, Experience = 6, HealthRatio = 1,
                ServerId = proof.ServerId, ServerSessionId = proof.Boot,
                AccountSessionId = proof.Session, AccountLeaseToken = proof.Token })).Failure);

        PetProgressRequest withWrongToken() => new() { OperationId = authorized.OperationId,
            UpdatedBy = authorized.UpdatedBy, ExpectedVersion = authorized.ExpectedVersion,
            Experience = authorized.Experience, HealthRatio = authorized.HealthRatio,
            ServerId = authorized.ServerId, ServerSessionId = authorized.ServerSessionId,
            AccountSessionId = authorized.AccountSessionId, AccountLeaseToken = new string('0', 64) };
    }

    [Fact]
    public async Task EggHatch_HidesPrivateDetailsAndReplaysWithoutRerollOrDoubleConsumption()
    {
        await using var fixture = await Fixture.CreateAsync();
        var egg = await fixture.EggAsync();
        Assert.True(egg.Succeeded);
        var json = JsonSerializer.Serialize(egg.Response!.Instance, new JsonSerializerOptions(JsonSerializerDefaults.Web));
        Assert.DoesNotContain("details", json);
        Assert.DoesNotContain("sex", json);
        Assert.DoesNotContain("stats", json);
        var request = new PetFacilityRequest { OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account, FacilityId = "pet_center" };
        var hatched = await fixture.Repository.HatchAsync(fixture.Account, egg.Response.Instance!.InstanceId, request);
        Assert.True(hatched.Succeeded);
        Assert.False(hatched.Response!.Instance!.IsEgg);
        Assert.NotNull(hatched.Response.Instance.Details);
        Assert.Equal(0, Assert.Single(hatched.Response.Instance.Details!.Skills).Tier);
        Assert.All(hatched.Response.Instance.Details.Stats.Values, stat => Assert.InRange(stat.Tier, 0, 4));
        Assert.Equal(144, (await fixture.Player.InventoryEntries.AsNoTracking().SingleAsync(e => e.InventoryEntryId == fixture.Material)).Quantity);
        var replay = await fixture.Repository.HatchAsync(fixture.Account, egg.Response.Instance.InstanceId, request);
        Assert.True(replay.Succeeded);
        Assert.Equal(JsonSerializer.Serialize(hatched.Response.Instance), JsonSerializer.Serialize(replay.Response!.Instance));
        Assert.Equal(144, (await fixture.Player.InventoryEntries.AsNoTracking().SingleAsync(e => e.InventoryEntryId == fixture.Material)).Quantity);
        Assert.Equal(1, await fixture.Player.PetInstances.CountAsync());
        Assert.Equal(2, await fixture.Player.PetOperations.CountAsync());
        Assert.Equal("PET", (await fixture.Player.InventoryEntries.AsNoTracking().SingleAsync(e => e.InstanceId == egg.Response.Instance.InstanceId)).InstanceType);
    }

    [Fact]
    public async Task Hatch_RejectsWrongFacilityAndInsufficientMaterialsWithoutPartialMutation()
    {
        await using var fixture = await Fixture.CreateAsync();
        var egg = await fixture.EggAsync();
        var wrong = await fixture.Repository.HatchAsync(fixture.Account, egg.Response!.Instance!.InstanceId,
            new PetFacilityRequest { OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account, FacilityId = "somewhere" });
        Assert.Equal("facility_required", wrong.Failure);
        var material = await fixture.Player.InventoryEntries.SingleAsync(e => e.InventoryEntryId == fixture.Material);
        material.Quantity = 255;
        await fixture.Player.SaveChangesAsync();
        var insufficient = await fixture.Repository.HatchAsync(fixture.Account, egg.Response.Instance.InstanceId,
            new PetFacilityRequest { OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account, FacilityId = "pet_center" });
        Assert.Equal("insufficient_materials", insufficient.Failure);
        Assert.True((await fixture.Player.PetInstances.AsNoTracking().SingleAsync()).IsEgg);
        Assert.Equal(255, (await fixture.Player.InventoryEntries.AsNoTracking().SingleAsync(e => e.InventoryEntryId == fixture.Material)).Quantity);
        Assert.Equal(1, await fixture.Player.PetOperations.CountAsync());
    }

    [Fact]
    public async Task Hatch_ConsumesExactStackWithoutViolatingExistingSqlQuantityConstraint()
    {
        await using var fixture = await Fixture.CreateAsync();
        var material = await fixture.Player.InventoryEntries.SingleAsync(e => e.InventoryEntryId == fixture.Material);
        material.Quantity = 256; await fixture.Player.SaveChangesAsync();
        var egg = await fixture.EggAsync();
        var result = await fixture.Repository.HatchAsync(fixture.Account, egg.Response!.Instance!.InstanceId,
            new PetFacilityRequest { OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account, FacilityId = "pet_center" });
        Assert.True(result.Succeeded);
        var deleted = await fixture.Player.InventoryEntries.AsNoTracking().SingleAsync(e => e.InventoryEntryId == fixture.Material);
        Assert.True(deleted.IsDeleted); Assert.True(deleted.Quantity >= 1);
        Assert.Contains(fixture.Material, result.Response!.InventorySnapshot!.CoveredEntryIds);
        Assert.DoesNotContain(result.Response.InventorySnapshot.Entries, e => e.InventoryEntryId == fixture.Material);
    }

    [Fact]
    public async Task Progress_UsesPlayerCurveUnlocksSlotsOnceAndPersistsDeathUntilRevive()
    {
        await using var fixture = await Fixture.CreateAsync();
        var pet = await fixture.AddPetAsync("WILD", "MALE", 1);
        Assert.True((await fixture.Repository.EquipAsync(fixture.Account, new PetEquipRequest
            { OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account, PetId = pet.InstanceId })).Succeeded);
        var required = Enumerable.Range(1, 19).Sum(l => PetGenetics.RequiredExperience(fixture.Master.Experience, l));
        var firstSkill = JsonSerializer.Deserialize<PetDetailsResponse>(pet.DetailsJson)!.Skills[0].Id;
        var request = new PetProgressRequest
        {
            OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account, ExpectedVersion = 1,
            Experience = required, HealthRatio = 0, IsDead = true, Cooldowns = new() { [firstSkill] = 12345678 },
        };
        var result = await fixture.Repository.ProgressAsync(fixture.Account, pet.InstanceId, request);
        Assert.True(result.Succeeded);
        Assert.Equal(20, result.Response!.Instance!.Details!.Level);
        Assert.Equal(2, result.Response.Instance.Details.Skills.Count);
        Assert.True(result.Response.Instance.Details.IsDead);
        Assert.Equal(12345678, result.Response.Instance.Details.Cooldowns[firstSkill]);
        Assert.True((await fixture.Repository.ProgressAsync(fixture.Account, pet.InstanceId, request)).Succeeded);
        var resurrection = await fixture.Repository.ProgressAsync(fixture.Account, pet.InstanceId, new PetProgressRequest
        {
            OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account, ExpectedVersion = 2, HealthRatio = 1,
        });
        Assert.Equal("pet_dead", resurrection.Failure);
        Assert.Equal(7, (await fixture.Player.InventoryEntries.AsNoTracking()
            .SingleAsync(e => e.InstanceId == pet.InstanceId)).SlotIndex);
        Assert.True((await fixture.Repository.GetInstanceAsync(fixture.Account, pet.InstanceId))!.Details!.IsDead);
        var revive = await fixture.Repository.ReviveAsync(fixture.Account, pet.InstanceId, new PetReviveRequest
            { OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account, FacilityId = "pet_center" });
        Assert.True(revive.Succeeded);
        Assert.False(revive.Response!.Instance!.Details!.IsDead);
        Assert.Equal(1, revive.Response.Instance.Details.HealthRatio);
        Assert.Equal(12345678, revive.Response.Instance.Details.Cooldowns[firstSkill]);
    }

    [Fact]
    public async Task Equip_MovesPetEntryToReservedSlotAndReplaysWithoutAnotherMove()
    {
        await using var fixture = await Fixture.CreateAsync();
        var pet = await fixture.AddPetAsync("WILD", "MALE", 1);
        var request = new PetEquipRequest { OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account, PetId = pet.InstanceId };
        var equipped = await fixture.Repository.EquipAsync(fixture.Account, request);
        Assert.True(equipped.Succeeded);
        var entry = await fixture.Player.InventoryEntries.AsNoTracking().SingleAsync(e => e.InstanceId == pet.InstanceId);
        var equip = await fixture.Player.Inventories.AsNoTracking().SingleAsync(i => i.InventoryType == "EQUIP_SLOT");
        Assert.Equal(equip.InventoryId, entry.InventoryId);
        Assert.Equal(7, entry.SlotIndex);
        Assert.Equal(7, equip.SlotCapacity);
        Assert.Contains(entry.InventoryEntryId, equipped.Response!.InventorySnapshot!.CoveredEntryIds);
        Assert.Contains(equipped.Response.InventorySnapshot.Entries, e => e.InventoryEntryId == entry.InventoryEntryId
            && e.InventoryId == equip.InventoryId && e.SlotIndex == 7);

        var replay = await fixture.Repository.EquipAsync(fixture.Account, request);
        Assert.True(replay.Succeeded);
        Assert.Single(replay.Response!.InventorySnapshot!.CoveredEntryIds);
        Assert.Single(await fixture.Player.PetOperations.AsNoTracking().Where(o => o.OperationId == request.OperationId).ToArrayAsync());
    }

    [Fact]
    public async Task Equip_ExchangesIntoTheIncomingBagSlotAndRejectsOccupiedReturnSlot()
    {
        await using var fixture = await Fixture.CreateAsync();
        var first = await fixture.AddPetAsync("WILD", "MALE", 1);
        var second = await fixture.AddPetAsync("WILD", "FEMALE", 1);
        var firstEntry = await fixture.Player.InventoryEntries.SingleAsync(e => e.InstanceId == first.InstanceId);
        var secondEntry = await fixture.Player.InventoryEntries.SingleAsync(e => e.InstanceId == second.InstanceId);
        firstEntry.SlotIndex = 1; secondEntry.SlotIndex = 2;
        await fixture.Player.SaveChangesAsync();
        Assert.True((await fixture.Repository.EquipAsync(fixture.Account, new PetEquipRequest
            { OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account, PetId = first.InstanceId })).Succeeded);
        var missingSlot = await fixture.Repository.EquipAsync(fixture.Account, new PetEquipRequest
            { OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account, PetId = second.InstanceId });
        Assert.Equal("bag_slot_required", missingSlot.Failure);
        var exchanged = await fixture.Repository.EquipAsync(fixture.Account, new PetEquipRequest
            { OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account, PetId = second.InstanceId, ReturnBagSlotIndex = 2 });
        Assert.True(exchanged.Succeeded);
        var result = await fixture.Player.InventoryEntries.AsNoTracking()
            .Where(e => e.InstanceId == first.InstanceId || e.InstanceId == second.InstanceId).ToArrayAsync();
        Assert.Equal(2, result.Single(e => e.InstanceId == first.InstanceId).SlotIndex);
        Assert.Equal(7, result.Single(e => e.InstanceId == second.InstanceId).SlotIndex);
        Assert.Equal(second.InstanceId, (await fixture.Repository.GetByAccountAsync(fixture.Account)).EquippedPetId);
        Assert.Equal(2, exchanged.Response!.InventorySnapshot!.CoveredEntryIds.Count);

        fixture.Player.InventoryEntries.Add(new InventoryEntryEntity { InventoryEntryId = Guid.NewGuid(),
            InventoryId = fixture.Bag, SlotIndex = 1, ItemId = "filler", ItemCategory = "material", Quantity = 1 });
        await fixture.Player.SaveChangesAsync();
        var blocked = await fixture.Repository.EquipAsync(fixture.Account, new PetEquipRequest
            { OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account, ReturnBagSlotIndex = 1 });
        Assert.Equal("bag_slot_occupied", blocked.Failure);
        Assert.Equal(second.InstanceId, (await fixture.Repository.GetByAccountAsync(fixture.Account)).EquippedPetId);
        Assert.Equal(7, (await fixture.Player.InventoryEntries.AsNoTracking()
            .SingleAsync(e => e.InstanceId == second.InstanceId)).SlotIndex);
    }

    [Fact]
    public async Task Equip_RepairsLegacySelectedBagPetWithTheSamePetId()
    {
        await using var fixture = await Fixture.CreateAsync();
        var pet = await fixture.AddPetAsync("WILD", "MALE", 1);
        fixture.Player.AccountPetStates.Add(new AccountPetStateEntity { AccountId = fixture.Account,
            EquippedPetId = pet.InstanceId, UpdatedAt = DateTime.UtcNow, UpdatedBy = fixture.Account });
        await fixture.Player.SaveChangesAsync();
        var result = await fixture.Repository.EquipAsync(fixture.Account, new PetEquipRequest
            { OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account, PetId = pet.InstanceId });
        Assert.True(result.Succeeded);
        Assert.Equal(7, (await fixture.Player.InventoryEntries.AsNoTracking()
            .SingleAsync(e => e.InstanceId == pet.InstanceId)).SlotIndex);
    }

    [Fact]
    public async Task Equip_RemovesSelectedPetToTheReservedBagSlot()
    {
        await using var fixture = await Fixture.CreateAsync();
        var pet = await fixture.AddPetAsync("WILD", "MALE", 1);
        Assert.True((await fixture.Repository.EquipAsync(fixture.Account, new PetEquipRequest
            { OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account, PetId = pet.InstanceId })).Succeeded);
        var request = new PetEquipRequest { OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account,
            ReturnBagSlotIndex = 1 };
        var removed = await fixture.Repository.EquipAsync(fixture.Account, request);
        Assert.True(removed.Succeeded);
        Assert.Null(removed.Response!.EquippedPetId);
        var entry = await fixture.Player.InventoryEntries.AsNoTracking().SingleAsync(e => e.InstanceId == pet.InstanceId);
        Assert.Equal(fixture.Bag, entry.InventoryId);
        Assert.Equal(1, entry.SlotIndex);
        Assert.Contains(entry.InventoryEntryId, removed.Response.InventorySnapshot!.CoveredEntryIds);
        Assert.Null((await fixture.Repository.GetByAccountAsync(fixture.Account)).EquippedPetId);
        Assert.True((await fixture.Repository.EquipAsync(fixture.Account, request)).Succeeded);
    }

    [Fact]
    public async Task PlayerStateSnapshot_CannotDeleteTheSelectedPetFromItsEquipmentSlot()
    {
        await using var fixture = await Fixture.CreateAsync();
        var pet = await fixture.AddPetAsync("WILD", "MALE", 1);
        Assert.True((await fixture.Repository.EquipAsync(fixture.Account, new PetEquipRequest
            { OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account, PetId = pet.InstanceId })).Succeeded);
        var entry = await fixture.Player.InventoryEntries.AsNoTracking().SingleAsync(e => e.InstanceId == pet.InstanceId);
        var save = await new PlayerStateSnapshotRepository(fixture.Player).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.Account, UpdatedBy = fixture.Account,
            Inventories = [new PlayerStateInventorySnapshot
            {
                InventoryId = entry.InventoryId, EntryMode = "DELTA",
                ExpectedEntries = [new PlayerStateExpectedInventoryEntry
                    { InventoryEntryId = entry.InventoryEntryId, UpdatedAt = entry.UpdatedAt }],
                DeletedEntryIds = [entry.InventoryEntryId],
            }],
        });
        Assert.Equal(PlayerStateSnapshotSaveFailure.Conflict, save.Failure);
        fixture.Player.ChangeTracker.Clear();
        Assert.True(await fixture.Player.InventoryEntries.AsNoTracking().AnyAsync(e => e.InventoryEntryId == entry.InventoryEntryId
            && !e.IsDeleted && e.SlotIndex == 7));
        var unchanged = await new PlayerStateSnapshotRepository(fixture.Player).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.Account, UpdatedBy = fixture.Account,
            Inventories = [new PlayerStateInventorySnapshot
            {
                InventoryId = entry.InventoryId, EntryMode = "DELTA",
                ExpectedEntries = [new PlayerStateExpectedInventoryEntry
                    { InventoryEntryId = entry.InventoryEntryId, UpdatedAt = entry.UpdatedAt }],
            }],
        });
        Assert.True(unchanged.Succeeded, unchanged.Detail);
    }

    [Fact]
    public async Task Breed_InheritsIndependentHigherBaseValuesCreatesHiddenBoundEggAndKeepsParents()
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Master.Rules.MutationChance = 0;
        await fixture.ReplaceMasterAsync();
        var male = await fixture.AddPetAsync("WILD", "MALE", 20);
        var female = await fixture.AddPetAsync("WILD", "FEMALE", 20);
        var maleDetails = JsonSerializer.Deserialize<PetDetailsResponse>(male.DetailsJson)!;
        var femaleDetails = JsonSerializer.Deserialize<PetDetailsResponse>(female.DetailsJson)!;
        maleDetails.Stats["POWER"] = new() { Tier = 10, BaseValue = 31, CurrentValue = 9000 };
        femaleDetails.Stats["DEFENSE"] = new() { Tier = 10, BaseValue = 32, CurrentValue = 10000 };
        male.DetailsJson = JsonSerializer.Serialize(maleDetails); female.DetailsJson = JsonSerializer.Serialize(femaleDetails);
        fixture.Player.PetInstances.UpdateRange(male, female);
        await fixture.Player.SaveChangesAsync();
        var request = new PetBreedRequest { OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account,
            FacilityId = "pet_center", MaleId = male.InstanceId, FemaleId = female.InstanceId };
        var bred = await fixture.Repository.BreedAsync(fixture.Account, request);
        Assert.True(bred.Succeeded);
        Assert.True(bred.Response!.Instance!.IsEgg);
        Assert.False(bred.Response.Instance.TradeAllowed);
        Assert.Null(bred.Response.Instance.Details);
        Assert.Equal(3, await fixture.Player.PetInstances.CountAsync());
        var child = await fixture.Repository.HatchAsync(fixture.Account, bred.Response.Instance.InstanceId,
            new PetFacilityRequest { OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account, FacilityId = "pet_center" });
        Assert.True(child.Succeeded);
        Assert.Equal(31, child.Response!.Instance!.Details!.Stats["POWER"].BaseValue);
        Assert.Equal(32, child.Response.Instance.Details.Stats["DEFENSE"].BaseValue);
        Assert.Equal(10, child.Response.Instance.Details.Stats["POWER"].Tier);
        Assert.Equal(1, child.Response.Instance.Details.Level);
        var cooldown = await fixture.Repository.BreedAsync(fixture.Account, new PetBreedRequest
            { OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account, FacilityId = "pet_center", MaleId = male.InstanceId, FemaleId = female.InstanceId });
        Assert.Equal("breed_cooldown", cooldown.Failure);
    }

    [Fact]
    public async Task Breed_RejectsSameSexAndUnownedParents()
    {
        await using var fixture = await Fixture.CreateAsync();
        var left = await fixture.AddPetAsync("WILD", "MALE", 20);
        var right = await fixture.AddPetAsync("WILD", "MALE", 20);
        var result = await fixture.Repository.BreedAsync(fixture.Account, new PetBreedRequest
            { OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account, FacilityId = "pet_center", MaleId = left.InstanceId, FemaleId = right.InstanceId });
        Assert.Equal("sex_mismatch", result.Failure);
        var unknown = await fixture.Repository.BreedAsync(fixture.Account, new PetBreedRequest
            { OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account, FacilityId = "pet_center", MaleId = left.InstanceId, FemaleId = Guid.NewGuid() });
        Assert.Equal("parents_invalid", unknown.Failure);
        Assert.Empty(await fixture.Player.PetOperations.ToArrayAsync());
    }

    [Fact]
    public async Task Inventory_RejectsPetOwnerTypeAndQuantitySpoofing()
    {
        await using var fixture = await Fixture.CreateAsync();
        var pet = await fixture.AddPetAsync("WILD", "MALE", 1);
        var repository = new InventoryRepository(fixture.Player);
        async Task<InventoryEntryResponse?> Insert(Guid id, string type, string category, long quantity)
            => await repository.CreateEntryAsync(fixture.Bag, new InventoryEntryCreateRequest
            { InstanceId = id, InstanceType = type, ItemCategory = category, Quantity = quantity, CreatedBy = fixture.Account });
        Assert.Null(await Insert(Guid.NewGuid(), "PET", "pet", 1));
        Assert.Null(await Insert(pet.InstanceId, "PET_EGG", "pet_egg", 1));
        Assert.Null(await Insert(pet.InstanceId, "PET", "pet", 2));
        Assert.Null(await repository.CreateEntryAsync(fixture.Bag,
            new InventoryEntryCreateRequest { ItemCategory = "pet", ItemId = "pet_wolf", CreatedBy = fixture.Account }));
    }

    [Fact]
    public async Task InventoryDirectWrites_PreserveSelectedPetEquipmentEntry()
    {
        await using var fixture = await Fixture.CreateAsync();
        var pet = await fixture.AddPetAsync("WILD", "MALE", 1);
        Assert.True((await fixture.Repository.EquipAsync(fixture.Account, new PetEquipRequest
            { OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account, PetId = pet.InstanceId })).Succeeded);
        var repository = new InventoryRepository(fixture.Player);
        var current = await fixture.Player.InventoryEntries.AsNoTracking().SingleAsync(e => e.InstanceId == pet.InstanceId);
        InventoryEntryUpdateRequest Update(int? slot, string category = "pet", string? type = "PET", Guid? id = null)
            => new() { SlotIndex = slot, ItemCategory = category, ItemId = category == "pet" ? pet.ItemId : "flower",
                InstanceType = type, InstanceId = id ?? pet.InstanceId, Quantity = 1, UpdatedBy = fixture.Account };

        Assert.NotNull(await repository.UpdateEntryAsync(current.InventoryEntryId, Update(7)));
        Assert.Null(await repository.UpdateEntryAsync(current.InventoryEntryId, Update(8)));
        Assert.Null(await repository.UpdateEntryAsync(current.InventoryEntryId, Update(7, type: "pet")));
        Assert.Null(await repository.UpdateEntryAsync(current.InventoryEntryId, Update(7, "material", null)));
        Assert.Null(await repository.DeleteEntryAsync(current.InventoryEntryId, fixture.Account));

        current = await fixture.Player.InventoryEntries.AsNoTracking().SingleAsync(e => e.InstanceId == pet.InstanceId);
        InventoryEntryReplaceRequest Replace(int? slot) => new() { UpdatedBy = fixture.Account,
            Entries = [new InventoryEntryReplaceItemRequest { InventoryEntryId = current.InventoryEntryId,
                ExpectedUpdatedAt = current.UpdatedAt, SlotIndex = slot, ItemCategory = "pet", ItemId = pet.ItemId,
                InstanceType = "PET", InstanceId = pet.InstanceId, Quantity = 1 }] };
        Assert.Null(await repository.ReplaceEntriesAsync(current.InventoryId, new InventoryEntryReplaceRequest
            { UpdatedBy = fixture.Account }));
        Assert.Null(await repository.ReplaceEntriesAsync(current.InventoryId, Replace(8)));
        Assert.Null(await repository.ReplaceEntriesAsync(fixture.Bag, Replace(7)));
        Assert.NotNull(await repository.ReplaceEntriesAsync(current.InventoryId, Replace(7)));
        fixture.Player.ChangeTracker.Clear();
        Assert.True(await fixture.Player.InventoryEntries.AsNoTracking().AnyAsync(e => e.InventoryEntryId == current.InventoryEntryId
            && !e.IsDeleted && e.InventoryId == current.InventoryId && e.SlotIndex == 7));
        Assert.Equal(pet.InstanceId, (await fixture.Repository.GetByAccountAsync(fixture.Account)).EquippedPetId);
    }

    [Fact]
    public async Task InventoryDirectWrite_AllowsLegacySelectedPetStillInBag()
    {
        await using var fixture = await Fixture.CreateAsync();
        var pet = await fixture.AddPetAsync("WILD", "MALE", 1);
        fixture.Player.AccountPetStates.Add(new AccountPetStateEntity { AccountId = fixture.Account,
            EquippedPetId = pet.InstanceId, UpdatedAt = DateTime.UtcNow, UpdatedBy = fixture.Account });
        await fixture.Player.SaveChangesAsync();
        var entry = await fixture.Player.InventoryEntries.AsNoTracking().SingleAsync(e => e.InstanceId == pet.InstanceId);
        Assert.NotNull(await new InventoryRepository(fixture.Player).UpdateEntryAsync(entry.InventoryEntryId,
            new InventoryEntryUpdateRequest { SlotIndex = 2, ItemCategory = "pet", ItemId = pet.ItemId,
                InstanceType = "PET", InstanceId = pet.InstanceId, Quantity = 1, UpdatedBy = fixture.Account }));
    }

    [Fact]
    public async Task OperationId_RejectsDifferentRequestAndResultLookupIsOwnerLimited()
    {
        await using var fixture = await Fixture.CreateAsync();
        var operation = Guid.NewGuid();
        var result = await fixture.EggAsync(operation);
        var conflict = await fixture.Repository.CreateEggAsync(fixture.Account,
            new PetEggCreateRequest { OperationId = operation, UpdatedBy = fixture.Account, SpeciesId = "cat" });
        Assert.True(result.Succeeded);
        Assert.Equal("operation_conflict", conflict.Failure);
        Assert.NotNull(await fixture.Repository.GetOperationAsync(fixture.Account, operation));
        Assert.Null(await fixture.Repository.GetOperationAsync(Guid.NewGuid(), operation));
    }

    [Fact]
    public void Genetics_WildNeverGetsTierThreeAndPotentialDoublesOnlyGrowth()
    {
        var master = MakeMaster();
        var species = master.Species[0];
        var details = PetGenetics.HatchWild(master, species, new Random(17));
        details.Stats["POWER"].BaseValue = 5; details.Stats["POWER"].Potential = true;
        details.Stats["DEFENSE"].BaseValue = 5; details.Stats["DEFENSE"].Potential = false;
        PetGenetics.Grow(master, species, details, long.MaxValue / 2, new Random(1));
        Assert.Equal(100, details.Level);
        Assert.Equal(3, details.Skills.Count);
        Assert.All(details.Skills, s => Assert.InRange(s.Tier, 0, s.SlotIndex));
        Assert.Equal(203, details.Stats["POWER"].CurrentValue);
        Assert.Equal(104, details.Stats["DEFENSE"].CurrentValue);
        Assert.Equal(0, details.Experience);
    }

    [Fact]
    public void Genetics_BredTierTwoFirstSkillCanUnlockTierThree()
    {
        var master = MakeMaster(); var species = master.Species[0];
        var parent = PetGenetics.HatchWild(master, species, new Random(1));
        parent.Skills = [new PetSkillResponse { Id = "s4", Tier = 2 }];
        master.Rules.MutationChance = 1;
        master.Rules.WildPotentialChance = 0;
        master.Rules.PotentialCountWeights = [0, 100, 0];
        parent.Stats["POWER"].Potential = true;
        foreach (var skill in species.Skills) skill.Weight = skill.Tier == 3 ? 1000000 : .0000001;
        var child = PetGenetics.Breed(master, species, parent, parent, new Random(31));
        Assert.Equal(2, child.Skills[0].Tier);
        Assert.Equal(1, child.Stats.Values.Count(s => s.Potential));
        foreach (var (id, stat) in child.Stats) Assert.Equal(parent.Stats[id].Tier + 1, stat.Tier);
        PetGenetics.Grow(master, species, child, 100000000, new Random(9));
        Assert.Equal(new[] { 2, 3, 3 }, child.Skills.Select(s => s.Tier));
        Assert.Equal(3, child.Skills.Select(s => s.Id).Distinct().Count());
    }

    [Fact]
    public async Task Progress_AppliesConfiguredActivityRateOnceAcrossOperationReplay()
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Master.Experience.ActivityRate = .5;
        await fixture.ReplaceMasterAsync();
        var pet = await fixture.AddPetAsync("WILD", "MALE", 1);
        Assert.True((await fixture.Repository.EquipAsync(fixture.Account, new PetEquipRequest
            { OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account, PetId = pet.InstanceId })).Succeeded);
        var request = new PetProgressRequest
        {
            OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account, ExpectedVersion = pet.Version,
            Experience = 103, HealthRatio = 1,
        };
        var result = await fixture.Repository.ProgressAsync(fixture.Account, pet.InstanceId, request);
        Assert.True(result.Succeeded);
        Assert.Equal(51, result.Response!.Instance!.Details!.Experience);
        var replay = await fixture.Repository.ProgressAsync(fixture.Account, pet.InstanceId, request);
        Assert.True(replay.Succeeded);
        Assert.Equal(51, replay.Response!.Instance!.Details!.Experience);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(.5)]
    [InlineData(1)]
    public void Master_AcceptsActivityRateWithinSchemaBounds(double rate)
    {
        var master = MakeMaster();
        master.Experience.ActivityRate = rate;
        PetMasterValidator.Validate(master);
    }

    [Theory]
    [InlineData(-.1)]
    [InlineData(1.1)]
    [InlineData(double.NaN)]
    [InlineData(double.PositiveInfinity)]
    public void Master_RejectsActivityRateOutsideSchemaBounds(double rate)
    {
        var master = MakeMaster();
        master.Experience.ActivityRate = rate;
        Assert.Throws<InvalidOperationException>(() => PetMasterValidator.Validate(master));
    }

    private static PetMasterResponse MakeMaster() => new()
    {
        Rules = new PetRules { BreedMaterials = [new() { ItemId = "flower", Quantity = 64 }], ReviveMaterials = [new() { ItemId = "flower", Quantity = 16 }] },
        Species = [new PetSpeciesMaster
        {
            Id = "wolf", EggItemId = "egg_wolf", PetItemId = "pet_wolf", Names = ["コハク"],
            HatchMaterials = [new() { ItemId = "flower", Quantity = 256 }],
            Stats = new[] { "VITALITY", "POWER", "DEFENSE", "EVASION", "SUPPORT" }.ToDictionary(id => id, id => new PetStatMaster
            {
                GrowthPerLevel = 1, Scale = 40, InheritanceMax = .5,
                Tiers = Enumerable.Range(0, 11).Select(t => new PetTierMaster { Tier = t, Min = t * 3 + 1, Max = t * 3 + 3, Weight = 1000.0 / (t + 1) }).ToList(),
            }),
            Skills = Enumerable.Range(0, 8).Select(s => new PetSkillMaster { Id = "s" + s, Tier = s / 2, CooldownSeconds = 10 }).ToList(),
        }],
    };

    private sealed class Fixture(SqliteConnection playerConnection, SqliteConnection masterConnection,
        AstralRecordDbContext player, MasterDataDbContext masterDb) : IAsyncDisposable
    {
        public AstralRecordDbContext Player { get; } = player;
        public MasterDataDbContext MasterDb { get; } = masterDb;
        public PetRepository Repository { get; } = new(player, masterDb);
        public Guid Account { get; } = Guid.NewGuid();
        public Guid Bag { get; } = Guid.NewGuid();
        public Guid Material { get; } = Guid.NewGuid();
        public Guid Owner { get; } = Guid.NewGuid();
        public PetMasterResponse Master { get; } = MakeMaster();
        public static async Task<Fixture> CreateAsync()
        {
            var p = new SqliteConnection("Data Source=:memory:"); var m = new SqliteConnection("Data Source=:memory:");
            await p.OpenAsync(); await m.OpenAsync();
            var db = new AstralRecordDbContext(new DbContextOptionsBuilder<AstralRecordDbContext>().UseSqlite(p).Options);
            var masters = new MasterDataDbContext(new DbContextOptionsBuilder<MasterDataDbContext>().UseSqlite(m).Options);
            await db.Database.EnsureCreatedAsync(); await MasterDataTestSeed.CreateSchemaAsync(masters);
            var fixture = new Fixture(p, m, db, masters);
            var now = DateTime.UtcNow;
            db.Users.Add(new UserEntity { Uuid = fixture.Owner, Mcid = "pet-fixture",
                JoinDate = now, LastJoinDate = now, CreatedAt = now, UpdatedAt = now,
                CreatedBy = fixture.Account, UpdatedBy = fixture.Account });
            db.Accounts.Add(new AccountEntity { Uuid = fixture.Account, UserId = fixture.Owner, AccountName = "pet-test" });
            db.Inventories.Add(new InventoryEntity { InventoryId = fixture.Bag, AccountId = fixture.Account, InventoryType = "BAG", InventoryProfile = "GAME", IsEnabled = true });
            db.InventoryEntries.Add(new InventoryEntryEntity { InventoryEntryId = fixture.Material, InventoryId = fixture.Bag, ItemId = "flower", ItemCategory = "material", Quantity = 400 });
            await db.SaveChangesAsync();
            var payload = JsonSerializer.SerializeToNode(fixture.Master, new JsonSerializerOptions(JsonSerializerDefaults.Web))!;
            payload["schemaVersion"] = 1;
            await MasterDataTestSeed.SeedInlinePayloadAsync(masters, payload.ToJsonString(), "pet", null);
            return fixture;
        }
        public Task<PetMutationResult> EggAsync(Guid? operation = null) => Repository.CreateEggAsync(Account,
            new PetEggCreateRequest { OperationId = operation ?? Guid.NewGuid(), UpdatedBy = Account, SpeciesId = "wolf" });
        public async Task<(string ServerId, Guid Boot, Guid Session, string Token)> StartEditWithRuntimeAsync(
            bool editAnotherOwnedAccount = false)
        {
            const string token = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
            var boot = Guid.NewGuid(); var session = Guid.NewGuid(); var editId = Guid.NewGuid();
            var before = DateTime.UtcNow.AddMinutes(-1);
            Player.PlayerAdminServerRuntimes.Add(new() { ServerId = "rpg-1", ServerSessionId = boot,
                Role = "RPG", RegisteredAtUtc = before, LastSeenUtc = before });
            Player.SkillTreeAccountSessions.Add(new() { AccountSessionId = session, AccountId = Account,
                ServerId = "rpg-1", ServerSessionId = boot, DefinitionGenerationId = "g",
                LeaseTokenHash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(token))).ToLowerInvariant(),
                CreatedAtUtc = before, ExpiresAtUtc = before.AddSeconds(1) });
            var now = DateTime.UtcNow;
            var editedAccount = editAnotherOwnedAccount ? Guid.NewGuid() : Account;
            if (editAnotherOwnedAccount)
                Player.Accounts.Add(new AccountEntity { Uuid = editedAccount, UserId = Owner,
                    AccountName = "other-offline-account" });
            Player.PlayerAdminEditSessions.Add(new() { EditSessionId = editId, AccountId = editedAccount,
                UserUuid = Owner, ActorUserUuid = Owner, Reason = "test", Status = "DRAINING",
                ExpectedServerCount = 1, CreatedAtUtc = now, UpdatedAtUtc = now, ExpiresAtUtc = now.AddMinutes(30) });
            Player.PlayerAdminEditDrains.Add(new() { EditSessionId = editId, ServerId = "rpg-1", ServerSessionId = boot });
            await Player.SaveChangesAsync();
            return ("rpg-1", boot, session, token);
        }
        public async Task ReplaceMasterAsync()
        {
            var entry = await MasterDb.Entries.SingleAsync();
            entry.PayloadJson = JsonSerializer.Serialize(Master, new JsonSerializerOptions(JsonSerializerDefaults.Web));
            await MasterDb.SaveChangesAsync();
        }
        public async Task<PetInstanceEntity> AddPetAsync(string origin, string sex, int level)
        {
            var details = PetGenetics.HatchWild(Master, Master.Species[0], new Random(10));
            details.Sex = sex; details.Level = level;
            var pet = new PetInstanceEntity { InstanceId = Guid.NewGuid(), AccountId = Account, SpeciesId = "wolf", ItemId = "pet_wolf",
                Origin = origin, DetailsJson = JsonSerializer.Serialize(details), CreatedAt = DateTime.UtcNow, UpdatedAt = DateTime.UtcNow };
            Player.PetInstances.Add(pet);
            Player.InventoryEntries.Add(new InventoryEntryEntity { InventoryEntryId = Guid.NewGuid(), InventoryId = Bag, ItemCategory = "pet",
                ItemId = pet.ItemId, InstanceType = "PET", InstanceId = pet.InstanceId, Quantity = 1 });
            await Player.SaveChangesAsync();
            return pet;
        }
        public async ValueTask DisposeAsync()
        { await Player.DisposeAsync(); await MasterDb.DisposeAsync(); await playerConnection.DisposeAsync(); await masterConnection.DisposeAsync(); }
    }
}
