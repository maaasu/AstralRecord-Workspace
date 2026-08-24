using System.Data.Common;
using System.Text.Json;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class EquipmentOrbOperationRepositoryTests
{
    [Fact]
    public async Task Enhance_AppliesEquipmentRuleAndConsumesExactlyOneOrbAtomically()
    {
        await using var harness = await OrbOperationHarness.CreateAsync();
        var orb = await harness.AddOrbAsync("enhance_orb", new ItemOrbEffectResponse
        {
            Type = "ENHANCE",
            TargetSlots = ["WEAPON"],
            Rank = 0,
        });

        var result = await harness.ExecuteAsync("enhance_orb", orb);

        Assert.Equal("APPLIED", result.Result);
        Assert.Equal("ENHANCE", result.OperationType);
        Assert.True(result.PaymentConsumed);
        Assert.True(result.EnhancementSucceeded);
        Assert.Equal(1, result.Equipment!.EnhanceLevel);
        Assert.Equal(1, await harness.GetEntryQuantityAsync(orb));
        await harness.AssertSingleTerminalLedgerAsync(result.OperationId, paymentConsumed: true);
    }

    [Fact]
    public async Task Enhance_GuaranteedFailureStillConsumesExactlyOneOrbAndAppliesFailureRule()
    {
        await using var harness = await OrbOperationHarness.CreateAsync(
            equipment: CreateEquipment(
                enhanceLevels:
                [
                    CreateEnhanceLevel(1, 1.0F, "NONE"),
                    CreateEnhanceLevel(2, 0.0F, "DECREASE_ONE"),
                ],
                maxEnhanceLevel: 2));
        await harness.SetEquipmentStateAsync(instance => instance.EnhanceLevel = 1);
        var orb = await harness.AddOrbAsync("failure_enhance_orb", new ItemOrbEffectResponse
        {
            Type = "ENHANCE",
            TargetSlots = ["WEAPON"],
            Rank = 0,
        });

        var result = await harness.ExecuteAsync("failure_enhance_orb", orb);

        Assert.Equal("APPLIED", result.Result);
        Assert.True(result.PaymentConsumed);
        Assert.False(result.EnhancementSucceeded);
        Assert.Equal("DECREASE_ONE", result.FailAction);
        Assert.Equal(0, result.Equipment!.EnhanceLevel);
        Assert.Equal(1, await harness.GetEntryQuantityAsync(orb));
    }

    [Fact]
    public async Task Repair_UpdatesDurabilityAndConsumesExactlyOneOrbAtomically()
    {
        await using var harness = await OrbOperationHarness.CreateAsync();
        await harness.SetEquipmentStateAsync(instance => instance.DurabilityValue = 40);
        var orb = await harness.AddOrbAsync("sindri_orb", new ItemOrbEffectResponse
        {
            Type = "REPAIR",
            RepairAmount = 25,
        });

        var result = await harness.ExecuteAsync("sindri_orb", orb);

        Assert.Equal("APPLIED", result.Result);
        Assert.True(result.PaymentConsumed);
        Assert.Equal(25, result.RepairedAmount);
        Assert.Equal(65, result.Equipment!.DurabilityValue);
        Assert.Equal(1, await harness.GetEntryQuantityAsync(orb));
        await harness.AssertSingleTerminalLedgerAsync(result.OperationId, paymentConsumed: true);
    }

    [Fact]
    public async Task EnchantFillAll_AppliesEveryEmptySlotAndConsumesOneOrbForTheBatch()
    {
        await using var harness = await OrbOperationHarness.CreateAsync();
        harness.SetEnchantMaster(CreateEnchantMaster(
            CreateEnchantEntry("weapon_attack_scalar_130", "ATTACK", "SCALAR", "1.30", 30),
            CreateEnchantEntry("weapon_critical_rate_19", "CRITICAL_RATE", "FLAT", "19", 1)));
        var orb = await harness.AddOrbAsync("enchant_fill_all_orb", new ItemOrbEffectResponse
        {
            Type = "ENCHANT",
            EnchantMasterId = "enchant001",
            EnchantOperation = "FILL_ALL_EMPTY",
        });

        var result = await harness.ExecuteAsync("enchant_fill_all_orb", orb);

        Assert.Equal("APPLIED", result.Result);
        Assert.True(result.PaymentConsumed);
        Assert.Equal(2, result.Equipment!.Enchants.Count);
        Assert.Equal([0, 1], result.Equipment.Enchants.Select(enchant => enchant.SlotIndex).ToArray());
        Assert.Equal(2, result.Equipment.Enchants
            .Select(enchant => enchant.EffectId)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .Count());
        Assert.Equal(1, await harness.GetEntryQuantityAsync(orb));
        await harness.AssertSingleTerminalLedgerAsync(result.OperationId, paymentConsumed: true);
    }

    [Fact]
    public async Task EnchantFillAll_SupportsIntMaxWeightsWithoutOverflow()
    {
        await using var harness = await OrbOperationHarness.CreateAsync();
        harness.SetEnchantMaster(CreateEnchantMaster(
            CreateEnchantEntry("max_weight_attack", "ATTACK", "FLAT", "5", int.MaxValue),
            CreateEnchantEntry("max_weight_critical", "CRITICAL_RATE", "FLAT", "19", int.MaxValue)));
        var orb = await harness.AddOrbAsync("max_weight_enchant_orb", new ItemOrbEffectResponse
        {
            Type = "ENCHANT",
            EnchantMasterId = "enchant001",
            EnchantOperation = "FILL_ALL_EMPTY",
        });

        var result = await harness.ExecuteAsync("max_weight_enchant_orb", orb);

        Assert.Equal("APPLIED", result.Result);
        Assert.True(result.PaymentConsumed);
        Assert.Equal(2, result.Equipment!.Enchants.Count);
        Assert.Equal(1, await harness.GetEntryQuantityAsync(orb));
    }

    [Fact]
    public async Task ActiveMarketListing_BlocksOrbMutationWithoutConsumingPayment()
    {
        await using var harness = await OrbOperationHarness.CreateAsync();
        await harness.SetEquipmentStateAsync(instance => instance.DurabilityValue = 40);
        await harness.AddActiveMarketListingAsync();
        var orb = await harness.AddOrbAsync("listed_repair_orb", new ItemOrbEffectResponse
        {
            Type = "REPAIR",
            RepairAmount = 25,
        });

        var result = await harness.ExecuteAsync("listed_repair_orb", orb);

        Assert.Equal("NOT_ELIGIBLE", result.Result);
        Assert.False(result.PaymentConsumed);
        Assert.Equal(2, await harness.GetEntryQuantityAsync(orb));
        Assert.Equal(40, (await harness.GetEquipmentAsync()).DurabilityValue);
        await harness.AssertSingleTerminalLedgerAsync(result.OperationId, paymentConsumed: false);
    }

    [Fact]
    public async Task Transcendence_ConsumesOneOrbMaterialsAndGoldInTheSameTransaction()
    {
        var equipment = CreateEquipment(transcendence:
        [
            new ItemEquipmentTranscendenceResponse
            {
                Name = "星鋼化",
                Rank = 1,
                RequiredEnhanceLevel = 3,
                RequiredMaterials =
                [
                    new ItemEquipmentEnhanceMaterialResponse { ItemId = "star_ore", Amount = 2 },
                ],
                RequiredCurrency = 150,
            },
        ]);
        await using var harness = await OrbOperationHarness.CreateAsync(equipment: equipment);
        await harness.SetEquipmentStateAsync(instance => instance.EnhanceLevel = 3);
        var orb = await harness.AddOrbAsync("state_orb", new ItemOrbEffectResponse
        {
            Type = "TRANSCENDENCE",
            Rank = 1,
        });
        var material = await harness.AddNormalEntryAsync("star_ore", "material", 5);
        await harness.AddCurrencyEntryAsync("gold_ingot", 2);

        var result = await harness.ExecuteAsync("state_orb", orb);

        Assert.Equal("APPLIED", result.Result);
        Assert.True(result.PaymentConsumed);
        Assert.Equal("星鋼化", result.TransitionName);
        Assert.Equal(1, result.Equipment!.TranscendenceRank);
        Assert.Equal(1, await harness.GetEntryQuantityAsync(orb));
        Assert.Equal(3, await harness.GetEntryQuantityAsync(material));
        Assert.Equal(50, await harness.GetGoldValueAsync());
        await harness.AssertSingleTerminalLedgerAsync(result.OperationId, paymentConsumed: true);
    }

    /**
     * 設計入力: 00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.02-登録系.md
     * 章・見出し: オーブ装備操作 > 支払い entry の消費順
     * 検証契約: itemId で素材を分割消費するときは、同一 inventory 内の slotIndex が大きい entry から消費する。
     */
    [Fact]
    public async Task Transcendence_ConsumesMaterialsFromHighestSlotFirst()
    {
        var equipment = CreateEquipment(transcendence:
        [
            new ItemEquipmentTranscendenceResponse
            {
                Name = "星鋼化",
                Rank = 1,
                RequiredEnhanceLevel = 3,
                RequiredMaterials =
                [
                    new ItemEquipmentEnhanceMaterialResponse { ItemId = "star_ore", Amount = 2 },
                ],
            },
        ]);
        await using var harness = await OrbOperationHarness.CreateAsync(equipment: equipment);
        await harness.SetEquipmentStateAsync(instance => instance.EnhanceLevel = 3);
        var orb = await harness.AddOrbAsync("highest_slot_orb", new ItemOrbEffectResponse
        {
            Type = "TRANSCENDENCE",
            Rank = 1,
        });
        var lowSlot = await harness.AddNormalEntryAsync("star_ore", "material", 3, slotIndex: 1);
        var highSlot = await harness.AddNormalEntryAsync("star_ore", "material", 3, slotIndex: 2);

        var result = await harness.ExecuteAsync("highest_slot_orb", orb);

        Assert.Equal("APPLIED", result.Result);
        Assert.True(result.PaymentConsumed);
        Assert.Equal(3, await harness.GetEntryQuantityAsync(lowSlot));
        Assert.Equal(1, await harness.GetEntryQuantityAsync(highSlot));
    }

    [Fact]
    public async Task Transcendence_DoesNotUseItemIdPopulatedEquipmentAsMaterial()
    {
        var equipment = CreateEquipment(transcendence:
        [
            new ItemEquipmentTranscendenceResponse
            {
                Name = "装備ID素材化",
                Rank = 1,
                RequiredEnhanceLevel = 3,
                RequiredMaterials =
                [
                    new ItemEquipmentEnhanceMaterialResponse { ItemId = "test_equipment", Amount = 1 },
                ],
            },
        ]);
        await using var harness = await OrbOperationHarness.CreateAsync(equipment: equipment);
        await harness.SetEquipmentStateAsync(instance => instance.EnhanceLevel = 3);
        await harness.SetEquipmentEntryItemIdAsync("test_equipment");
        var orb = await harness.AddOrbAsync("equipment_as_material_orb", new ItemOrbEffectResponse
        {
            Type = "TRANSCENDENCE",
            Rank = 1,
        });

        var result = await harness.ExecuteAsync("equipment_as_material_orb", orb);

        Assert.Equal("PAYMENT_UNAVAILABLE", result.Result);
        Assert.False(result.PaymentConsumed);
        Assert.Equal(0, (await harness.GetEquipmentAsync()).TranscendenceRank);
        Assert.Equal(2, await harness.GetEntryQuantityAsync(orb));
    }

    [Fact]
    public async Task NoCandidate_IsPersistedWithoutPaymentOrEquipmentMutation()
    {
        await using var harness = await OrbOperationHarness.CreateAsync();
        await harness.AddEnchantAsync(
            slotIndex: 0,
            effectId: "legacy_" + Guid.NewGuid().ToString("N"),
            status: "ATTACK",
            type: "SCALAR",
            value: 1.30M);
        harness.SetEnchantMaster(CreateEnchantMaster(
            CreateEnchantEntry("weapon_attack_scalar_130", "ATTACK", "SCALAR", "1.30", 30)));
        var orb = await harness.AddOrbAsync("no_candidate_orb", new ItemOrbEffectResponse
        {
            Type = "ENCHANT",
            EnchantMasterId = "enchant001",
            EnchantOperation = "FILL_ONE_EMPTY",
        });

        var result = await harness.ExecuteAsync("no_candidate_orb", orb);

        Assert.Equal("NO_CANDIDATE", result.Result);
        Assert.False(result.PaymentConsumed);
        Assert.True(result.TargetAvailable);
        Assert.NotNull(result.Equipment);
        Assert.Equal(2, await harness.GetEntryQuantityAsync(orb));
        Assert.Single(await harness.GetEnchantsAsync());
        await harness.AssertSingleTerminalLedgerAsync(result.OperationId, paymentConsumed: false);
    }

    [Fact]
    public async Task LegacySemanticDuplicate_IsExcludedFromNewEnchantCandidates()
    {
        await using var harness = await OrbOperationHarness.CreateAsync();
        await harness.AddEnchantAsync(
            slotIndex: 0,
            effectId: "legacy_" + Guid.NewGuid().ToString("N"),
            status: "ATTACK",
            type: "SCALAR",
            value: 1.30M);
        harness.SetEnchantMaster(CreateEnchantMaster(
            CreateEnchantEntry("weapon_attack_scalar_130", "ATTACK", "SCALAR", "1.30", 100),
            CreateEnchantEntry("weapon_critical_rate_19", "CRITICAL_RATE", "FLAT", "19", 1)));
        var orb = await harness.AddOrbAsync("semantic_duplicate_orb", new ItemOrbEffectResponse
        {
            Type = "ENCHANT",
            EnchantMasterId = "enchant001",
            EnchantOperation = "FILL_ONE_EMPTY",
        });

        var result = await harness.ExecuteAsync("semantic_duplicate_orb", orb);

        Assert.Equal("APPLIED", result.Result);
        var enchants = await harness.GetEnchantsAsync();
        Assert.Equal(2, enchants.Count);
        Assert.DoesNotContain(enchants, enchant =>
            enchant.EffectId.Equals("weapon_attack_scalar_130", StringComparison.OrdinalIgnoreCase));
        Assert.Contains(enchants, enchant =>
            enchant.EffectId.Equals("weapon_critical_rate_19", StringComparison.OrdinalIgnoreCase));
    }

    [Theory]
    [InlineData(2, "PAYMENT_UNAVAILABLE", 0, false)]
    [InlineData(3, "APPLIED", 1, true)]
    public async Task OrbUsedAsTransitionMaterial_RequiresMaterialAmountPlusOneOrb(
        long quantity,
        string expectedResult,
        int expectedRank,
        bool expectedConsumed)
    {
        const string orbItemId = "self_material_state_orb";
        var equipment = CreateEquipment(transcendence:
        [
            new ItemEquipmentTranscendenceResponse
            {
                Name = "自己触媒化",
                Rank = 1,
                RequiredEnhanceLevel = 3,
                RequiredMaterials =
                [
                    new ItemEquipmentEnhanceMaterialResponse { ItemId = orbItemId, Amount = 2 },
                ],
            },
        ]);
        await using var harness = await OrbOperationHarness.CreateAsync(equipment: equipment);
        await harness.SetEquipmentStateAsync(instance => instance.EnhanceLevel = 3);
        var orb = await harness.AddOrbAsync(orbItemId, new ItemOrbEffectResponse
        {
            Type = "TRANSCENDENCE",
            Rank = 1,
        }, quantity);

        var result = await harness.ExecuteAsync(orbItemId, orb);

        Assert.Equal(expectedResult, result.Result);
        Assert.Equal(expectedConsumed, result.PaymentConsumed);
        var instance = await harness.GetEquipmentAsync();
        Assert.Equal(expectedRank, instance.TranscendenceRank);
        if (expectedConsumed)
            Assert.True((await harness.GetEntryAsync(orb)).IsDeleted);
        else
            Assert.Equal(quantity, await harness.GetEntryQuantityAsync(orb));
        await harness.AssertSingleTerminalLedgerAsync(result.OperationId, expectedConsumed);
    }

    [Fact]
    public async Task SameOperationIdReplay_ReturnsSameResultWithoutDoubleConsumption()
    {
        await using var harness = await OrbOperationHarness.CreateAsync();
        await harness.SetEquipmentStateAsync(instance => instance.DurabilityValue = 10);
        var orb = await harness.AddOrbAsync("replay_repair_orb", new ItemOrbEffectResponse
        {
            Type = "REPAIR",
            RepairFull = true,
        });
        var operationId = Guid.NewGuid();
        var request = harness.CreateRequest(operationId, "replay_repair_orb", orb);

        var first = await harness.ExecuteAsync(request);
        var second = await harness.ExecuteAsync(request);

        Assert.Equal(JsonSerializer.Serialize(first), JsonSerializer.Serialize(second));
        Assert.Equal(1, await harness.GetEntryQuantityAsync(orb));
        Assert.Equal(100, (await harness.GetEquipmentAsync()).DurabilityValue);
        await harness.AssertSingleTerminalLedgerAsync(operationId, paymentConsumed: true);
        var stored = await harness.FindAsync(operationId, harness.AccountId);
        Assert.Equal(JsonSerializer.Serialize(first), JsonSerializer.Serialize(stored));
        Assert.Null(await harness.FindAsync(operationId, Guid.NewGuid()));
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task ReplayAfterTargetIsDeletedOrTransferred_ReturnsTerminalTombstone(bool delete)
    {
        await using var harness = await OrbOperationHarness.CreateAsync();
        await harness.SetEquipmentStateAsync(instance => instance.DurabilityValue = 20);
        var orb = await harness.AddOrbAsync("tombstone_repair_orb", new ItemOrbEffectResponse
        {
            Type = "REPAIR",
            RepairFull = true,
        });
        var operationId = Guid.NewGuid();
        var request = harness.CreateRequest(operationId, "tombstone_repair_orb", orb);
        var first = await harness.ExecuteAsync(request);
        Assert.Equal("APPLIED", first.Result);

        await harness.SetEquipmentStateAsync(instance =>
        {
            if (delete)
                instance.IsDeleted = true;
            else
                instance.AccountId = Guid.NewGuid();
        });

        var replay = await harness.ExecuteAsync(request);
        var lookup = await harness.FindAsync(operationId, harness.AccountId);

        Assert.Equal("APPLIED", replay.Result);
        Assert.True(replay.PaymentConsumed);
        Assert.False(replay.TargetAvailable);
        Assert.Null(replay.Equipment);
        Assert.NotNull(lookup);
        Assert.Equal("APPLIED", lookup!.Result);
        Assert.False(lookup.TargetAvailable);
        Assert.Null(lookup.Equipment);
        Assert.Equal(1, await harness.GetEntryQuantityAsync(orb));
        await harness.AssertSingleTerminalLedgerAsync(operationId, paymentConsumed: true);
    }

    [Fact]
    public async Task ReplayAndFind_ReadLedgerAndCurrentEquipmentInsideOneTransactionSnapshot()
    {
        var interceptor = new TransactionReadInterceptor();
        await using var harness = await OrbOperationHarness.CreateAsync(interceptor: interceptor);
        var orb = await harness.AddOrbAsync("transaction_snapshot_repair_orb", new ItemOrbEffectResponse
        {
            Type = "REPAIR",
            RepairFull = true,
        });
        await harness.SetEquipmentStateAsync(instance => instance.DurabilityValue = 40);
        var request = harness.CreateRequest(
            Guid.NewGuid(),
            "transaction_snapshot_repair_orb",
            orb);
        await harness.ExecuteAsync(request);

        interceptor.Reset();
        var replay = await harness.ExecuteAsync(request);
        Assert.Equal("APPLIED", replay.Result);
        interceptor.AssertAllReadsAreTransactional();

        interceptor.Reset();
        var lookup = await harness.FindAsync(request.OperationId, harness.AccountId);
        Assert.NotNull(lookup);
        interceptor.AssertAllReadsAreTransactional();
    }

    [Fact]
    public async Task TargetNoLongerInInventoryOrActiveLoadout_IsRejectedWithoutPayment()
    {
        await using var harness = await OrbOperationHarness.CreateAsync();
        await harness.SetEquipmentStateAsync(instance => instance.DurabilityValue = 20);
        await harness.RemoveEquipmentMembershipAsync();
        var orb = await harness.AddOrbAsync("missing_target_repair_orb", new ItemOrbEffectResponse
        {
            Type = "REPAIR",
            RepairFull = true,
        });

        var result = await harness.ExecuteAsync("missing_target_repair_orb", orb);

        Assert.Equal("NOT_ELIGIBLE", result.Result);
        Assert.False(result.PaymentConsumed);
        Assert.False(result.TargetAvailable);
        Assert.Null(result.Equipment);
        Assert.Equal(2, await harness.GetEntryQuantityAsync(orb));
        Assert.Equal(20, (await harness.GetEquipmentAsync()).DurabilityValue);
        await harness.AssertSingleTerminalLedgerAsync(result.OperationId, paymentConsumed: false);
    }

    [Fact]
    public async Task SameOperationIdWithDifferentRequest_ReturnsConflictWithoutSecondPayment()
    {
        await using var harness = await OrbOperationHarness.CreateAsync();
        await harness.SetEquipmentStateAsync(instance => instance.DurabilityValue = 1);
        var firstOrb = await harness.AddOrbAsync("conflict_repair_orb", new ItemOrbEffectResponse
        {
            Type = "REPAIR",
            RepairFull = true,
        }, quantity: 1);
        var secondOrb = await harness.AddNormalEntryAsync("conflict_repair_orb", "orb", 1);
        var operationId = Guid.NewGuid();

        var first = await harness.ExecuteAsync(
            harness.CreateRequest(operationId, "conflict_repair_orb", firstOrb));
        var conflict = await harness.ExecuteAsync(
            harness.CreateRequest(operationId, "conflict_repair_orb", secondOrb));

        Assert.Equal("APPLIED", first.Result);
        Assert.Equal("OPERATION_CONFLICT", conflict.Result);
        Assert.False(conflict.PaymentConsumed);
        Assert.True((await harness.GetEntryAsync(firstOrb)).IsDeleted);
        Assert.Equal(1, await harness.GetEntryQuantityAsync(secondOrb));
        await harness.AssertSingleTerminalLedgerAsync(operationId, paymentConsumed: true);
    }

    [Fact]
    public async Task WrongEquipmentOwner_IsTerminalNotEligibleWithoutPaymentOrMutation()
    {
        await using var harness = await OrbOperationHarness.CreateAsync();
        var orb = await harness.AddOrbAsync("owner_repair_orb", new ItemOrbEffectResponse
        {
            Type = "REPAIR",
            RepairFull = true,
        });
        await harness.SetEquipmentStateAsync(instance => instance.DurabilityValue = 20);
        var request = harness.CreateRequest(Guid.NewGuid(), "owner_repair_orb", orb);
        request.AccountId = Guid.NewGuid();

        var result = await harness.ExecuteAsync(request);

        Assert.Equal("NOT_ELIGIBLE", result.Result);
        Assert.False(result.PaymentConsumed);
        Assert.Equal(2, await harness.GetEntryQuantityAsync(orb));
        Assert.Equal(20, (await harness.GetEquipmentAsync()).DurabilityValue);
        await harness.AssertSingleTerminalLedgerAsync(result.OperationId, paymentConsumed: false);
    }

    [Fact]
    public async Task LedgerInsertFailure_RollsBackEquipmentAndOrbPayment()
    {
        await using var harness = await OrbOperationHarness.CreateAsync();
        var orb = await harness.AddOrbAsync("rollback_enhance_orb", new ItemOrbEffectResponse
        {
            Type = "ENHANCE",
            TargetSlots = ["WEAPON"],
            Rank = 0,
        });
        await harness.CreateFailingLedgerInsertTriggerAsync();

        await Assert.ThrowsAsync<DbUpdateException>(() =>
            harness.ExecuteAsync("rollback_enhance_orb", orb));

        Assert.Equal(0, (await harness.GetEquipmentAsync()).EnhanceLevel);
        Assert.Equal(2, await harness.GetEntryQuantityAsync(orb));
        Assert.Empty(await harness.GetLedgersAsync());
    }

    [Theory]
    [InlineData("FILL_ONE_EMPTY", 1, 0, 1)]
    [InlineData("FILL_ALL_EMPTY", 2, 0, 1)]
    [InlineData("OVERWRITE_RANDOM", 1, 1, 2)]
    public async Task ConcurrentEnchantOperationsOnSameEquipment_AreSerializedWithoutOverconsumption(
        string operation,
        int maxSlots,
        int initialEnchantCount,
        int expectedAppliedCount)
    {
        await using var harness = await ConcurrentOrbOperationHarness.CreateAsync(
            operation,
            maxSlots,
            initialEnchantCount);
        using var start = new Barrier(2);

        var tasks = harness.Requests.Select(request => Task.Run(async () =>
        {
            start.SignalAndWait();
            return await harness.ExecuteAsync(request);
        })).ToArray();
        var results = await Task.WhenAll(tasks);

        Assert.Equal(expectedAppliedCount, results.Count(result => result.Result == "APPLIED"));
        if (operation != "OVERWRITE_RANDOM")
            Assert.Single(results, result => result.Result == "NO_SLOT");
        Assert.Equal(expectedAppliedCount, await harness.GetConsumedOrbCountAsync());
        Assert.Equal(2, await harness.GetLedgerCountAsync());
        Assert.Equal(maxSlots, await harness.GetEnchantCountAsync());
    }

    private static ItemEquipmentResponse CreateEquipment(
        IReadOnlyList<ItemEquipmentEnhanceLevelResponse>? enhanceLevels = null,
        int maxEnhanceLevel = 3,
        IReadOnlyList<ItemEquipmentTranscendenceResponse>? transcendence = null) => new()
    {
        Slot = "WEAPON",
        Durability = new ItemEquipmentDurabilityResponse { Max = 100 },
        Enchant = new ItemEquipmentEnchantResponse { MaxSlots = 2 },
        Enhance = new ItemEquipmentEnhanceResponse
        {
            MaxLevel = maxEnhanceLevel,
            Levels = enhanceLevels ??
            [
                CreateEnhanceLevel(1, 1.0F, "NONE"),
                CreateEnhanceLevel(2, 1.0F, "NONE"),
                CreateEnhanceLevel(3, 1.0F, "NONE"),
            ],
        },
        Transcendence = transcendence ?? [],
    };

    private static ItemEquipmentEnhanceLevelResponse CreateEnhanceLevel(
        int level,
        float successRate,
        string failAction) => new()
    {
        Level = level,
        SuccessRate = successRate,
        FailAction = failAction,
    };

    private static EnchantEntryResponse CreateEnchantEntry(
        string effectId,
        string status,
        string type,
        string value,
        int weight) => new()
    {
        EffectId = effectId,
        Status = status,
        Type = type,
        Value = value,
        Weight = weight,
    };

    private static EnchantMasterResponse CreateEnchantMaster(params EnchantEntryResponse[] entries) => new()
    {
        Id = "enchant001",
        Targets =
        [
            new EnchantTargetResponse
            {
                EquipmentType = "WEAPON",
                Entries = entries,
            },
        ],
    };

    private sealed class OrbOperationHarness : IAsyncDisposable
    {
        private static readonly IReadOnlyDictionary<string, long> GoldValues =
            new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase)
            {
                ["gold"] = 1,
                ["ast_gold"] = 1,
                ["gold_coin"] = 10,
                ["gold_ingot"] = 100,
                ["gold_block"] = 1_000,
                ["gold_diamond"] = 10_000,
                ["gold_diamond_block"] = 100_000,
                ["yggdrasil_star_core"] = 1_000_000,
            };

        private readonly SqliteConnection connection;
        private readonly AstralRecordDbContext dbContext;
        private readonly MutableItemRepository items;
        private readonly MutableEnchantRepository enchants;

        private OrbOperationHarness(
            SqliteConnection connection,
            AstralRecordDbContext dbContext,
            MutableItemRepository items,
            MutableEnchantRepository enchants,
            Guid accountId,
            Guid equipmentInstanceId,
            Guid bagInventoryId,
            Guid currencyInventoryId)
        {
            this.connection = connection;
            this.dbContext = dbContext;
            this.items = items;
            this.enchants = enchants;
            AccountId = accountId;
            EquipmentInstanceId = equipmentInstanceId;
            BagInventoryId = bagInventoryId;
            CurrencyInventoryId = currencyInventoryId;
        }

        public Guid AccountId { get; }

        public Guid EquipmentInstanceId { get; }

        public Guid BagInventoryId { get; }

        public Guid CurrencyInventoryId { get; }

        public static async Task<OrbOperationHarness> CreateAsync(
            ItemEquipmentResponse? equipment = null,
            DbCommandInterceptor? interceptor = null)
        {
            var connection = new SqliteConnection("Data Source=:memory:");
            await connection.OpenAsync();
            var optionsBuilder = new DbContextOptionsBuilder<AstralRecordDbContext>()
                .UseSqlite(connection);
            if (interceptor is not null)
                optionsBuilder.AddInterceptors(interceptor);
            var options = optionsBuilder.Options;
            var dbContext = new AstralRecordDbContext(options);
            await dbContext.Database.EnsureCreatedAsync();

            var accountId = Guid.NewGuid();
            var equipmentInstanceId = Guid.NewGuid();
            var bagInventoryId = Guid.NewGuid();
            var currencyInventoryId = Guid.NewGuid();
            var now = DateTime.UtcNow;
            var equipmentItem = new ItemResponse
            {
                SchemaVersion = 1,
                Id = "test_equipment",
                Category = "equipment",
                Name = "test equipment",
                Icon = "IRON_SWORD",
                Rarity = "COMMON",
                Equipment = equipment ?? CreateEquipment(),
            };
            var items = new MutableItemRepository(equipmentItem);
            var enchants = new MutableEnchantRepository();

            dbContext.Inventories.AddRange(
                CreateInventory(bagInventoryId, accountId, "BAG", now),
                CreateInventory(currencyInventoryId, accountId, "CURRENCY", now));
            dbContext.EquipmentInstances.Add(new EquipmentInstanceEntity
            {
                EquipmentInstanceId = equipmentInstanceId,
                AccountId = accountId,
                ItemId = equipmentItem.Id,
                EnhanceLevel = 0,
                RuneMaxSlots = 0,
                TranscendenceRank = 0,
                DurabilityMax = 100,
                DurabilityValue = 100,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = accountId,
                UpdatedBy = accountId,
            });
            var equipmentEntry = CreateEntry(
                Guid.NewGuid(),
                bagInventoryId,
                accountId,
                equipmentItem.Id,
                "equipment",
                1,
                now);
            equipmentEntry.ItemId = null;
            equipmentEntry.InstanceType = "EQUIPMENT";
            equipmentEntry.InstanceId = equipmentInstanceId;
            dbContext.InventoryEntries.Add(equipmentEntry);
            await dbContext.SaveChangesAsync();

            return new OrbOperationHarness(
                connection,
                dbContext,
                items,
                enchants,
                accountId,
                equipmentInstanceId,
                bagInventoryId,
                currencyInventoryId);
        }

        public void SetEnchantMaster(EnchantMasterResponse master) => enchants.Master = master;

        public async Task<Guid> AddOrbAsync(
            string itemId,
            ItemOrbEffectResponse effect,
            long quantity = 2)
        {
            items.Add(new ItemResponse
            {
                SchemaVersion = 1,
                Id = itemId,
                Category = "orb",
                Name = itemId,
                Icon = "AMETHYST_SHARD",
                Rarity = "COMMON",
                Orb = new ItemOrbResponse { Effect = effect },
            });
            return await AddNormalEntryAsync(itemId, "orb", quantity);
        }

        public async Task<Guid> AddNormalEntryAsync(
            string itemId,
            string category,
            long quantity,
            int? slotIndex = null)
        {
            var id = Guid.NewGuid();
            dbContext.InventoryEntries.Add(CreateEntry(
                id,
                BagInventoryId,
                AccountId,
                itemId,
                category,
                quantity,
                DateTime.UtcNow,
                slotIndex));
            await dbContext.SaveChangesAsync();
            return id;
        }

        public async Task<Guid> AddCurrencyEntryAsync(string itemId, long quantity)
        {
            var id = Guid.NewGuid();
            dbContext.InventoryEntries.Add(CreateEntry(
                id,
                CurrencyInventoryId,
                AccountId,
                itemId,
                "currency",
                quantity,
                DateTime.UtcNow));
            await dbContext.SaveChangesAsync();
            return id;
        }

        public async Task AddEnchantAsync(
            int slotIndex,
            string effectId,
            string status,
            string type,
            decimal value)
        {
            var now = DateTime.UtcNow;
            dbContext.EquipmentInstanceEnchants.Add(new EquipmentInstanceEnchantEntity
            {
                EnchantId = Guid.NewGuid(),
                EquipmentInstanceId = EquipmentInstanceId,
                SlotIndex = slotIndex,
                EnchantMasterId = "legacy",
                EffectId = effectId,
                Status = status,
                Type = type,
                Value = value,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = AccountId,
                UpdatedBy = AccountId,
            });
            await dbContext.SaveChangesAsync();
        }

        public async Task AddActiveMarketListingAsync()
        {
            var now = DateTime.UtcNow;
            dbContext.MarketListings.Add(new MarketListingEntity
            {
                ListingId = Guid.NewGuid(),
                SellerAccountId = AccountId,
                ItemCategory = "equipment",
                ItemId = "test_equipment",
                InstanceType = "EQUIPMENT",
                InstanceId = EquipmentInstanceId,
                Quantity = 1,
                CurrencyId = "gold",
                UnitPrice = 100,
                TotalPrice = 100,
                PriceFloor = 1,
                PriceConfidence = "HIGH",
                Status = "ACTIVE",
                ListedAt = now,
                ExpiresAt = now.AddDays(1),
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = AccountId,
                UpdatedBy = AccountId,
            });
            await dbContext.SaveChangesAsync();
        }

        public async Task SetEquipmentStateAsync(Action<EquipmentInstanceEntity> mutate)
        {
            dbContext.ChangeTracker.Clear();
            var instance = await dbContext.EquipmentInstances.SingleAsync(candidate =>
                candidate.EquipmentInstanceId == EquipmentInstanceId);
            mutate(instance);
            await dbContext.SaveChangesAsync();
        }

        public async Task SetEquipmentEntryItemIdAsync(string itemId)
        {
            dbContext.ChangeTracker.Clear();
            var entry = await dbContext.InventoryEntries.SingleAsync(candidate =>
                candidate.InstanceId == EquipmentInstanceId && !candidate.IsDeleted);
            entry.ItemId = itemId;
            await dbContext.SaveChangesAsync();
        }

        public async Task RemoveEquipmentMembershipAsync()
        {
            dbContext.ChangeTracker.Clear();
            var entry = await dbContext.InventoryEntries.SingleAsync(candidate =>
                candidate.InstanceId == EquipmentInstanceId && !candidate.IsDeleted);
            entry.IsDeleted = true;
            entry.Quantity = 0;
            entry.UpdatedAt = DateTime.UtcNow;
            await dbContext.SaveChangesAsync();
        }

        public EquipmentOrbOperationRequest CreateRequest(
            Guid operationId,
            string orbItemId,
            Guid orbEntryId) => new()
        {
            OperationId = operationId,
            AccountId = AccountId,
            EquipmentInstanceId = EquipmentInstanceId,
            OrbInventoryEntryId = orbEntryId,
            OrbItemId = orbItemId,
        };

        public Task<EquipmentOrbOperationResponse> ExecuteAsync(
            string orbItemId,
            Guid orbEntryId) => ExecuteAsync(CreateRequest(Guid.NewGuid(), orbItemId, orbEntryId));

        public Task<EquipmentOrbOperationResponse> ExecuteAsync(EquipmentOrbOperationRequest request)
        {
            dbContext.ChangeTracker.Clear();
            return new EquipmentOrbOperationRepository(dbContext, items, enchants).ExecuteAsync(request);
        }

        public Task<EquipmentOrbOperationResponse?> FindAsync(Guid operationId, Guid accountId)
        {
            dbContext.ChangeTracker.Clear();
            return new EquipmentOrbOperationRepository(dbContext, items, enchants)
                .FindAsync(operationId, accountId);
        }

        public async Task<InventoryEntryEntity> GetEntryAsync(Guid entryId)
        {
            dbContext.ChangeTracker.Clear();
            return await dbContext.InventoryEntries.AsNoTracking().SingleAsync(entry =>
                entry.InventoryEntryId == entryId);
        }

        public async Task<long> GetEntryQuantityAsync(Guid entryId) =>
            (await GetEntryAsync(entryId)).Quantity;

        public async Task<EquipmentInstanceEntity> GetEquipmentAsync()
        {
            dbContext.ChangeTracker.Clear();
            return await dbContext.EquipmentInstances.AsNoTracking().SingleAsync(instance =>
                instance.EquipmentInstanceId == EquipmentInstanceId);
        }

        public async Task<IReadOnlyList<EquipmentInstanceEnchantEntity>> GetEnchantsAsync()
        {
            dbContext.ChangeTracker.Clear();
            return await dbContext.EquipmentInstanceEnchants.AsNoTracking()
                .Where(enchant => enchant.EquipmentInstanceId == EquipmentInstanceId)
                .OrderBy(enchant => enchant.SlotIndex)
                .ToListAsync();
        }

        public async Task<IReadOnlyList<EquipmentOrbOperationEntity>> GetLedgersAsync()
        {
            dbContext.ChangeTracker.Clear();
            return await dbContext.EquipmentOrbOperations.AsNoTracking().ToListAsync();
        }

        public async Task<long> GetGoldValueAsync()
        {
            dbContext.ChangeTracker.Clear();
            var entries = await dbContext.InventoryEntries.AsNoTracking()
                .Where(entry => entry.InventoryId == CurrencyInventoryId
                    && !entry.IsDeleted
                    && entry.Quantity > 0)
                .ToListAsync();
            return entries.Sum(entry => entry.ItemId is not null
                    && GoldValues.TryGetValue(entry.ItemId, out var value)
                ? entry.Quantity * value
                : 0L);
        }

        public async Task AssertSingleTerminalLedgerAsync(Guid operationId, bool paymentConsumed)
        {
            var ledger = Assert.Single(await GetLedgersAsync());
            Assert.Equal(operationId, ledger.OperationId);
            Assert.Equal(paymentConsumed, ledger.PaymentConsumed);
            Assert.NotEqual(default, ledger.CompletedAt);
            Assert.False(string.IsNullOrWhiteSpace(ledger.ResultPayloadJson));
        }

        public Task CreateFailingLedgerInsertTriggerAsync() => dbContext.Database.ExecuteSqlRawAsync("""
            CREATE TRIGGER fail_equipment_orb_operation_insert
            BEFORE INSERT ON equipment_orb_operation
            BEGIN
                SELECT RAISE(ABORT, 'forced ledger failure');
            END;
            """);

        public async ValueTask DisposeAsync()
        {
            await dbContext.DisposeAsync();
            await connection.DisposeAsync();
        }

        private static InventoryEntity CreateInventory(
            Guid inventoryId,
            Guid accountId,
            string type,
            DateTime now) => new()
        {
            InventoryId = inventoryId,
            AccountId = accountId,
            InventoryType = type,
            InventoryProfile = "GAME",
            IsEnabled = true,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = accountId,
            UpdatedBy = accountId,
        };

        private static InventoryEntryEntity CreateEntry(
            Guid entryId,
            Guid inventoryId,
            Guid accountId,
            string itemId,
            string category,
            long quantity,
            DateTime now,
            int? slotIndex = null) => new()
        {
            InventoryEntryId = entryId,
            InventoryId = inventoryId,
            SlotIndex = slotIndex,
            ItemCategory = category,
            ItemId = itemId,
            Quantity = quantity,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = accountId,
            UpdatedBy = accountId,
        };
    }

    private sealed class ConcurrentOrbOperationHarness : IAsyncDisposable
    {
        private readonly string databasePath;
        private readonly string connectionString;
        private readonly MutableItemRepository items;
        private readonly MutableEnchantRepository enchants;
        private readonly Guid equipmentInstanceId;
        private readonly IReadOnlyList<Guid> orbEntryIds;

        private ConcurrentOrbOperationHarness(
            string databasePath,
            string connectionString,
            MutableItemRepository items,
            MutableEnchantRepository enchants,
            Guid equipmentInstanceId,
            IReadOnlyList<Guid> orbEntryIds,
            IReadOnlyList<EquipmentOrbOperationRequest> requests)
        {
            this.databasePath = databasePath;
            this.connectionString = connectionString;
            this.items = items;
            this.enchants = enchants;
            this.equipmentInstanceId = equipmentInstanceId;
            this.orbEntryIds = orbEntryIds;
            Requests = requests;
        }

        public IReadOnlyList<EquipmentOrbOperationRequest> Requests { get; }

        public static async Task<ConcurrentOrbOperationHarness> CreateAsync(
            string operation,
            int maxSlots,
            int initialEnchantCount)
        {
            var databasePath = Path.Combine(
                Path.GetTempPath(),
                "astralrecord-orb-" + Guid.NewGuid().ToString("N") + ".db");
            var connectionString = new SqliteConnectionStringBuilder
            {
                DataSource = databasePath,
                Mode = SqliteOpenMode.ReadWriteCreate,
                Cache = SqliteCacheMode.Shared,
                Pooling = false,
                DefaultTimeout = 30,
            }.ToString();
            var equipmentItem = new ItemResponse
            {
                SchemaVersion = 1,
                Id = "concurrent_equipment",
                Category = "equipment",
                Name = "concurrent equipment",
                Icon = "IRON_SWORD",
                Rarity = "COMMON",
                Equipment = new ItemEquipmentResponse
                {
                    Slot = "WEAPON",
                    Enchant = new ItemEquipmentEnchantResponse { MaxSlots = maxSlots },
                },
            };
            const string orbItemId = "concurrent_enchant_orb";
            var orbItem = new ItemResponse
            {
                SchemaVersion = 1,
                Id = orbItemId,
                Category = "orb",
                Name = "concurrent enchant orb",
                Icon = "AMETHYST_SHARD",
                Rarity = "COMMON",
                Orb = new ItemOrbResponse
                {
                    Effect = new ItemOrbEffectResponse
                    {
                        Type = "ENCHANT",
                        EnchantMasterId = "enchant001",
                        EnchantOperation = operation,
                    },
                },
            };
            var items = new MutableItemRepository(equipmentItem, orbItem);
            var enchants = new MutableEnchantRepository
            {
                Master = CreateEnchantMaster(
                    CreateEnchantEntry("weapon_attack_scalar_130", "ATTACK", "SCALAR", "1.30", 30),
                    CreateEnchantEntry("weapon_critical_rate_19", "CRITICAL_RATE", "FLAT", "19", 10),
                    CreateEnchantEntry("weapon_attack_flat_5", "ATTACK", "FLAT", "5", 1)),
            };

            var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
                .UseSqlite(connectionString)
                .Options;
            await using var setupContext = new AstralRecordDbContext(options);
            await setupContext.Database.EnsureCreatedAsync();
            await setupContext.Database.ExecuteSqlRawAsync("PRAGMA journal_mode=WAL;");
            await setupContext.Database.ExecuteSqlRawAsync("PRAGMA busy_timeout=30000;");

            var now = DateTime.UtcNow;
            var accountId = Guid.NewGuid();
            var equipmentInstanceId = Guid.NewGuid();
            var inventoryId = Guid.NewGuid();
            setupContext.Inventories.Add(new InventoryEntity
            {
                InventoryId = inventoryId,
                AccountId = accountId,
                InventoryType = "BAG",
                InventoryProfile = "GAME",
                IsEnabled = true,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = accountId,
                UpdatedBy = accountId,
            });
            setupContext.EquipmentInstances.Add(new EquipmentInstanceEntity
            {
                EquipmentInstanceId = equipmentInstanceId,
                AccountId = accountId,
                ItemId = equipmentItem.Id,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = accountId,
                UpdatedBy = accountId,
            });

            var orbEntryIds = new[] { Guid.NewGuid(), Guid.NewGuid() };
            var equipmentEntry = new InventoryEntryEntity
            {
                InventoryEntryId = Guid.NewGuid(),
                InventoryId = inventoryId,
                SlotIndex = 0,
                ItemCategory = "equipment",
                ItemId = null,
                InstanceType = "EQUIPMENT",
                InstanceId = equipmentInstanceId,
                Quantity = 1,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = accountId,
                UpdatedBy = accountId,
            };
            setupContext.InventoryEntries.Add(equipmentEntry);
            setupContext.InventoryEntries.AddRange(orbEntryIds.Select((entryId, index) =>
                new InventoryEntryEntity
                {
                    InventoryEntryId = entryId,
                    InventoryId = inventoryId,
                    SlotIndex = index + 1,
                    ItemCategory = "orb",
                    ItemId = orbItemId,
                    Quantity = 1,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = accountId,
                    UpdatedBy = accountId,
                }));
            for (var slotIndex = 0; slotIndex < initialEnchantCount; slotIndex++)
            {
                setupContext.EquipmentInstanceEnchants.Add(new EquipmentInstanceEnchantEntity
                {
                    EnchantId = Guid.NewGuid(),
                    EquipmentInstanceId = equipmentInstanceId,
                    SlotIndex = slotIndex,
                    EnchantMasterId = "enchant001",
                    EffectId = "existing_effect_" + slotIndex,
                    Status = "ATTACK",
                    Type = "FLAT",
                    Value = 1,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = accountId,
                    UpdatedBy = accountId,
                });
            }
            await setupContext.SaveChangesAsync();

            var requests = orbEntryIds.Select(entryId => new EquipmentOrbOperationRequest
            {
                OperationId = Guid.NewGuid(),
                AccountId = accountId,
                EquipmentInstanceId = equipmentInstanceId,
                OrbInventoryEntryId = entryId,
                OrbItemId = orbItemId,
            }).ToList();
            return new ConcurrentOrbOperationHarness(
                databasePath,
                connectionString,
                items,
                enchants,
                equipmentInstanceId,
                orbEntryIds,
                requests);
        }

        public async Task<EquipmentOrbOperationResponse> ExecuteAsync(EquipmentOrbOperationRequest request)
        {
            await using var dbContext = CreateContext();
            await dbContext.Database.ExecuteSqlRawAsync("PRAGMA busy_timeout=30000;");
            return await new EquipmentOrbOperationRepository(dbContext, items, enchants)
                .ExecuteAsync(request);
        }

        public async Task<int> GetConsumedOrbCountAsync()
        {
            await using var dbContext = CreateContext();
            return await dbContext.InventoryEntries.AsNoTracking()
                .CountAsync(entry => orbEntryIds.Contains(entry.InventoryEntryId) && entry.IsDeleted);
        }

        public async Task<int> GetLedgerCountAsync()
        {
            await using var dbContext = CreateContext();
            return await dbContext.EquipmentOrbOperations.AsNoTracking().CountAsync();
        }

        public async Task<int> GetEnchantCountAsync()
        {
            await using var dbContext = CreateContext();
            return await dbContext.EquipmentInstanceEnchants.AsNoTracking()
                .CountAsync(enchant => enchant.EquipmentInstanceId == equipmentInstanceId);
        }

        public ValueTask DisposeAsync()
        {
            if (File.Exists(databasePath))
                File.Delete(databasePath);
            var writeAheadLog = databasePath + "-wal";
            if (File.Exists(writeAheadLog))
                File.Delete(writeAheadLog);
            var sharedMemory = databasePath + "-shm";
            if (File.Exists(sharedMemory))
                File.Delete(sharedMemory);
            return ValueTask.CompletedTask;
        }

        private AstralRecordDbContext CreateContext() => new(
            new DbContextOptionsBuilder<AstralRecordDbContext>()
                .UseSqlite(connectionString)
                .Options);
    }

    private sealed class MutableItemRepository(params ItemResponse[] initialItems) : IItemRepository
    {
        private readonly Dictionary<string, ItemResponse> items = initialItems.ToDictionary(
            item => item.Id,
            StringComparer.OrdinalIgnoreCase);

        public void Add(ItemResponse item) => items[item.Id] = item;

        public IReadOnlyList<ItemSummaryResponse> GetAllSummaries() => items.Values
            .Select(item => new ItemSummaryResponse { Id = item.Id, Category = item.Category })
            .ToList();

        public ItemResponse? GetById(string itemId) => items.GetValueOrDefault(itemId.Trim());
    }

    private sealed class MutableEnchantRepository : IEnchantRepository
    {
        public EnchantMasterResponse? Master { get; set; }

        public EnchantMasterResponse? GetById(string enchantMasterId) =>
            Master is not null
            && string.Equals(Master.Id, enchantMasterId.Trim(), StringComparison.OrdinalIgnoreCase)
                ? Master
                : null;
    }

    private sealed class TransactionReadInterceptor : DbCommandInterceptor
    {
        private readonly List<bool> transactionalReads = [];

        public override InterceptionResult<DbDataReader> ReaderExecuting(
            DbCommand command,
            CommandEventData eventData,
            InterceptionResult<DbDataReader> result)
        {
            Record(command);
            return result;
        }

        public override ValueTask<InterceptionResult<DbDataReader>> ReaderExecutingAsync(
            DbCommand command,
            CommandEventData eventData,
            InterceptionResult<DbDataReader> result,
            CancellationToken cancellationToken = default)
        {
            Record(command);
            return ValueTask.FromResult(result);
        }

        public void Reset() => transactionalReads.Clear();

        public void AssertAllReadsAreTransactional()
        {
            Assert.True(transactionalReads.Count >= 5);
            Assert.All(transactionalReads, Assert.True);
        }

        private void Record(DbCommand command)
        {
            if (command.CommandText.TrimStart().StartsWith("SELECT", StringComparison.OrdinalIgnoreCase))
                transactionalReads.Add(command.Transaction is not null);
        }
    }
}
