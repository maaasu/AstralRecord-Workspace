using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using AstralRecordApi.Services;
using Xunit;

namespace AstralRecordApi.Tests.Services;

public class EquipmentServiceTests
{
    [Fact]
    public async Task ApplyOrb_DelegatesUnifiedRequestAndReturnsTerminalResult()
    {
        var orbOperations = new TestOrbOperationRepository
        {
            ExecuteResult = new EquipmentOrbOperationResponse
            {
                OperationId = Guid.NewGuid(),
                Result = "APPLIED",
                OperationType = "REPAIR",
                PaymentConsumed = true,
            },
        };
        var service = CreateService(orbOperations);
        var request = new EquipmentOrbOperationRequest
        {
            OperationId = orbOperations.ExecuteResult.OperationId,
            AccountId = Guid.NewGuid(),
            EquipmentInstanceId = Guid.NewGuid(),
            OrbInventoryEntryId = Guid.NewGuid(),
            OrbItemId = "sindri_orb",
        };

        var result = await service.ApplyOrbAsync(request);

        Assert.Same(request, orbOperations.LastExecuteRequest);
        Assert.Same(orbOperations.ExecuteResult, result);
    }

    [Fact]
    public async Task FindOrbOperation_DelegatesOwnerScopedLookup()
    {
        var operationId = Guid.NewGuid();
        var accountId = Guid.NewGuid();
        var orbOperations = new TestOrbOperationRepository
        {
            FindResult = new EquipmentOrbOperationResponse
            {
                OperationId = operationId,
                Result = "PAYMENT_UNAVAILABLE",
                OperationType = "TRANSCENDENCE",
            },
        };
        var service = CreateService(orbOperations);

        var result = await service.FindOrbOperationAsync(operationId, accountId);

        Assert.Same(orbOperations.FindResult, result);
        Assert.Equal((operationId, accountId), orbOperations.LastFindRequest);
    }

    [Fact]
    public async Task DeleteEnchant_RejectsEquipmentOwnedByAnotherAccount()
    {
        var ownerId = Guid.NewGuid();
        var equipment = new TestEquipmentRepository
        {
            Instance = CreateInstance(ownerId),
        };
        var service = CreateService(new TestOrbOperationRepository(), equipment);

        var result = await service.DeleteEnchantAsync(new EquipmentEnchantDeleteRequest
        {
            EquipmentInstanceId = equipment.Instance.EquipmentInstanceId,
            SlotIndex = 0,
            UpdatedBy = Guid.NewGuid(),
        });

        Assert.Null(result);
        Assert.False(equipment.DeleteEnchantCalled);
    }

    [Fact]
    public async Task Create_PersistsEquipmentAndResolvedStatRolls()
    {
        var accountId = Guid.NewGuid();
        var actorId = Guid.NewGuid();
        var item = CreateEquipmentItem();
        var equipment = new TestEquipmentRepository();
        var service = new EquipmentService(
            new TestItemRepository(item),
            equipment,
            new TestOrbOperationRepository(),
            new TestAccountRepository(accountId));

        var result = await service.CreateAsync(new EquipmentCreateRequest
        {
            EquipmentId = item.Id,
            AccountId = accountId,
            Source = "test",
            CreatedBy = actorId,
        });

        Assert.NotNull(result);
        Assert.Equal(accountId, result!.AccountId);
        Assert.Equal(100, result.DurabilityMax);
        Assert.Equal(100, result.DurabilityValue);
        Assert.Equal(2, result.StatRolls.Count);
        Assert.Equal(["ATTACK", "CRITICAL_RATE"], result.StatRolls.Select(roll => roll.Status).ToArray());
        Assert.NotNull(equipment.Instance);
        Assert.Equal(2, equipment.AddedStatRolls.Count);
    }

    [Fact]
    public async Task Create_ReturnsNullWithoutPersistingWhenAccountDoesNotExist()
    {
        var equipment = new TestEquipmentRepository();
        var service = new EquipmentService(
            new TestItemRepository(CreateEquipmentItem()),
            equipment,
            new TestOrbOperationRepository(),
            new TestAccountRepository(Guid.NewGuid()));

        var result = await service.CreateAsync(new EquipmentCreateRequest
        {
            EquipmentId = "test_equipment",
            AccountId = Guid.NewGuid(),
            Source = "test",
            CreatedBy = Guid.NewGuid(),
        });

        Assert.Null(result);
        Assert.Null(equipment.Instance);
    }

    [Fact]
    public async Task AttachRune_AcceptsRuneWhenEquipmentSlotAndTagMatch()
    {
        var accountId = Guid.NewGuid();
        var equipment = new TestEquipmentRepository
        {
            Instance = CreateInstance(accountId),
        };
        equipment.Instance!.RuneMaxSlots = 2;
        var service = new EquipmentService(
            new TestItemRepository(CreateRuneEquipmentItem(), CreateRuneItem("SWORD")),
            equipment,
            new TestOrbOperationRepository(),
            new TestAccountRepository(accountId));

        var result = await service.AttachRuneAsync(new EquipmentRuneAttachRequest
        {
            EquipmentInstanceId = equipment.Instance.EquipmentInstanceId,
            RuneItemId = "sword_rune",
            UpdatedBy = accountId,
        });

        Assert.NotNull(result);
        Assert.Equal("sword_rune", equipment.UpsertedRune?.ItemId);
    }

    [Fact]
    public async Task AttachRune_RejectsRuneWhenEquipmentTagDoesNotMatch()
    {
        var accountId = Guid.NewGuid();
        var equipment = new TestEquipmentRepository
        {
            Instance = CreateInstance(accountId),
        };
        equipment.Instance!.RuneMaxSlots = 2;
        var service = new EquipmentService(
            new TestItemRepository(CreateRuneEquipmentItem(), CreateRuneItem("BOW")),
            equipment,
            new TestOrbOperationRepository(),
            new TestAccountRepository(accountId));

        var result = await service.AttachRuneAsync(new EquipmentRuneAttachRequest
        {
            EquipmentInstanceId = equipment.Instance.EquipmentInstanceId,
            RuneItemId = "sword_rune",
            UpdatedBy = accountId,
        });

        Assert.Null(result);
        Assert.Null(equipment.UpsertedRune);
    }

    [Fact]
    public async Task UpdateDurability_ClampsValueAndEnforcesOwnership()
    {
        var accountId = Guid.NewGuid();
        var equipment = new TestEquipmentRepository
        {
            Instance = CreateInstance(accountId),
        };
        equipment.Instance.DurabilityMax = 100;
        equipment.Instance.DurabilityValue = 20;
        var service = CreateService(new TestOrbOperationRepository(), equipment);

        var updated = await service.UpdateDurabilityAsync(new EquipmentDurabilityUpdateRequest
        {
            EquipmentInstanceId = equipment.Instance.EquipmentInstanceId,
            DurabilityValue = 999,
            UpdatedBy = accountId,
        });
        var rejected = await service.UpdateDurabilityAsync(new EquipmentDurabilityUpdateRequest
        {
            EquipmentInstanceId = equipment.Instance.EquipmentInstanceId,
            DurabilityValue = 1,
            UpdatedBy = Guid.NewGuid(),
        });

        Assert.Equal(100, updated!.DurabilityValue);
        Assert.Null(rejected);
        Assert.Equal(1, equipment.UpdateInstanceCount);
    }

    private static EquipmentService CreateService(
        IEquipmentOrbOperationRepository orbOperations,
        TestEquipmentRepository? equipment = null)
    {
        var accountId = Guid.NewGuid();
        return new EquipmentService(
            new TestItemRepository(),
            equipment ?? new TestEquipmentRepository(),
            orbOperations,
            new TestAccountRepository(accountId));
    }

    private static EquipmentInstanceEntity CreateInstance(Guid accountId) => new()
    {
        EquipmentInstanceId = Guid.NewGuid(),
        AccountId = accountId,
        ItemId = "test_equipment",
        CreatedAt = DateTime.UtcNow,
        UpdatedAt = DateTime.UtcNow,
        CreatedBy = accountId,
        UpdatedBy = accountId,
    };

    private static ItemResponse CreateEquipmentItem() => new()
    {
        SchemaVersion = 1,
        Id = "test_equipment",
        Category = "equipment",
        Name = "test equipment",
        Icon = "IRON_SWORD",
        Rarity = "COMMON",
        Equipment = new ItemEquipmentResponse
        {
            Slot = "WEAPON",
            Durability = new ItemEquipmentDurabilityResponse { Max = 100 },
            Stats =
            [
                new ItemEquipmentStatResponse
                {
                    Status = "ATTACK",
                    Type = "FLAT",
                    Value = new ItemEquipmentStatValueResponse { Min = "10", Max = "10" },
                },
                new ItemEquipmentStatResponse
                {
                    Status = "CRITICAL_RATE",
                    Type = "FLAT",
                    Value = new ItemEquipmentStatValueResponse { Min = "1", Max = "5" },
                },
            ],
        },
    };

    private static ItemResponse CreateRuneEquipmentItem() => new()
    {
        SchemaVersion = 1,
        Id = "test_equipment",
        Category = "equipment",
        Name = "test equipment",
        Icon = "IRON_SWORD",
        Rarity = "COMMON",
        Equipment = new ItemEquipmentResponse
        {
            Slot = "WEAPON",
            Tag = "SWORD",
            Rune = new ItemEquipmentRuneResponse { MaxSlots = "2" },
        },
    };

    private static ItemResponse CreateRuneItem(string targetTag) => new()
    {
        SchemaVersion = 1,
        Id = "sword_rune",
        Category = "rune",
        Name = "sword rune",
        Icon = "REDSTONE",
        Rarity = "COMMON",
        Rune = new ItemRuneResponse
        {
            TargetSlots = ["WEAPON"],
            TargetTags = [targetTag],
        },
    };

    private sealed class TestOrbOperationRepository : IEquipmentOrbOperationRepository
    {
        public EquipmentOrbOperationResponse ExecuteResult { get; init; } = new()
        {
            OperationId = Guid.Empty,
            Result = "NOT_ELIGIBLE",
            OperationType = string.Empty,
        };

        public EquipmentOrbOperationResponse? FindResult { get; init; }

        public EquipmentOrbOperationRequest? LastExecuteRequest { get; private set; }

        public (Guid OperationId, Guid AccountId)? LastFindRequest { get; private set; }

        public Task<EquipmentOrbOperationResponse> ExecuteAsync(EquipmentOrbOperationRequest request)
        {
            LastExecuteRequest = request;
            return Task.FromResult(ExecuteResult);
        }

        public Task<EquipmentOrbOperationResponse?> FindAsync(Guid operationId, Guid accountId)
        {
            LastFindRequest = (operationId, accountId);
            return Task.FromResult(FindResult);
        }
    }

    private sealed class TestItemRepository : IItemRepository
    {
        private readonly IReadOnlyList<ItemResponse> items;

        public TestItemRepository(params ItemResponse?[] items)
        {
            this.items = items.Where(item => item is not null).Select(item => item!).ToArray();
        }

        public IReadOnlyList<ItemSummaryResponse> GetAllSummaries() =>
            items.Select(item => new ItemSummaryResponse { Id = item.Id, Category = item.Category }).ToArray();

        public ItemResponse? GetById(string itemId) => items.FirstOrDefault(item =>
            string.Equals(item.Id, itemId, StringComparison.OrdinalIgnoreCase));
    }

    private sealed class TestEquipmentRepository : IEquipmentRepository
    {
        public EquipmentInstanceEntity? Instance { get; set; }

        public IReadOnlyList<EquipmentInstanceStatRollEntity> AddedStatRolls { get; private set; } = [];

        public bool DeleteEnchantCalled { get; private set; }

        public EquipmentInstanceRuneEntity? UpsertedRune { get; private set; }

        public int UpdateInstanceCount { get; private set; }

        public Task AddAsync(
            EquipmentInstanceEntity instance,
            IReadOnlyList<EquipmentInstanceStatRollEntity> statRolls)
        {
            Instance = instance;
            AddedStatRolls = statRolls;
            return Task.CompletedTask;
        }

        public Task<EquipmentInstanceEntity?> FindInstanceAsync(Guid instanceId) =>
            Task.FromResult(Instance?.EquipmentInstanceId == instanceId ? Instance : null);

        public Task<IReadOnlyList<EquipmentInstanceStatRollEntity>> FindStatRollsAsync(Guid instanceId) =>
            Task.FromResult<IReadOnlyList<EquipmentInstanceStatRollEntity>>([]);

        public Task<IReadOnlyList<EquipmentInstanceEnchantEntity>> FindEnchantsAsync(Guid instanceId) =>
            Task.FromResult<IReadOnlyList<EquipmentInstanceEnchantEntity>>([]);

        public Task<IReadOnlyList<EquipmentInstanceRuneEntity>> FindRunesAsync(Guid instanceId) =>
            Task.FromResult<IReadOnlyList<EquipmentInstanceRuneEntity>>([]);

        public Task<bool> DeleteEnchantBySlotIndexAsync(Guid instanceId, int slotIndex, Guid accountId)
        {
            DeleteEnchantCalled = true;
            return Task.FromResult(true);
        }

        public Task<bool> UpsertRuneAsync(Guid instanceId, Guid accountId, EquipmentInstanceRuneEntity rune)
        {
            UpsertedRune = rune;
            return Task.FromResult(true);
        }

        public Task<bool> DeleteRuneBySlotIndexAsync(Guid instanceId, int slotIndex) =>
            Task.FromResult(false);

        public Task<EquipmentInstanceEntity?> UpdateDurabilityAsync(
            Guid instanceId,
            int durabilityValue,
            Guid updatedBy)
        {
            if (Instance?.EquipmentInstanceId != instanceId
                || Instance.AccountId != updatedBy
                || !Instance.DurabilityMax.HasValue
                || !Instance.DurabilityValue.HasValue)
                return Task.FromResult<EquipmentInstanceEntity?>(null);
            Instance.DurabilityValue = Math.Clamp(durabilityValue, 0, Instance.DurabilityMax.Value);
            Instance.UpdatedBy = updatedBy;
            UpdateInstanceCount++;
            return Task.FromResult<EquipmentInstanceEntity?>(Instance);
        }

        public Task<bool> SoftDeleteInstanceAsync(Guid instanceId) => Task.FromResult(false);
    }

    private sealed class TestAccountRepository(Guid accountId) : IAccountRepository
    {
        public Task<IReadOnlyList<AccountResponse>> GetByUserIdAsync(Guid userId) =>
            Task.FromResult<IReadOnlyList<AccountResponse>>([]);

        public Task<AccountResponse?> GetByUuidAsync(Guid uuid) =>
            Task.FromResult<AccountResponse?>(uuid == accountId ? new AccountResponse { Uuid = uuid } : null);

        public Task<AccountResponse> CreateAsync(AccountCreateRequest request) =>
            throw new NotSupportedException();

        public Task<AccountResponse?> UpdateAsync(Guid uuid, AccountUpdateRequest request) =>
            throw new NotSupportedException();

        public Task<AccountDeleteResponse?> DeleteAsync(Guid uuid, AccountDeleteRequest request) =>
            throw new NotSupportedException();
    }
}
