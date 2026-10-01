using System.Text.Json;
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
        await fixture.Repository.EquipAsync(fixture.Account, new PetEquipRequest { OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account, PetId = null });
        await fixture.Repository.EquipAsync(fixture.Account, new PetEquipRequest { OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account, PetId = pet.InstanceId });
        Assert.True((await fixture.Repository.GetInstanceAsync(fixture.Account, pet.InstanceId))!.Details!.IsDead);
        var revive = await fixture.Repository.ReviveAsync(fixture.Account, pet.InstanceId, new PetReviveRequest
            { OperationId = Guid.NewGuid(), UpdatedBy = fixture.Account, FacilityId = "pet_center" });
        Assert.True(revive.Succeeded);
        Assert.False(revive.Response!.Instance!.Details!.IsDead);
        Assert.Equal(1, revive.Response.Instance.Details.HealthRatio);
        Assert.Equal(12345678, revive.Response.Instance.Details.Cooldowns[firstSkill]);
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
        public PetMasterResponse Master { get; } = MakeMaster();
        public static async Task<Fixture> CreateAsync()
        {
            var p = new SqliteConnection("Data Source=:memory:"); var m = new SqliteConnection("Data Source=:memory:");
            await p.OpenAsync(); await m.OpenAsync();
            var db = new AstralRecordDbContext(new DbContextOptionsBuilder<AstralRecordDbContext>().UseSqlite(p).Options);
            var masters = new MasterDataDbContext(new DbContextOptionsBuilder<MasterDataDbContext>().UseSqlite(m).Options);
            await db.Database.EnsureCreatedAsync(); await MasterDataTestSeed.CreateSchemaAsync(masters);
            var fixture = new Fixture(p, m, db, masters);
            db.Accounts.Add(new AccountEntity { Uuid = fixture.Account, UserId = Guid.NewGuid(), AccountName = "pet-test" });
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
