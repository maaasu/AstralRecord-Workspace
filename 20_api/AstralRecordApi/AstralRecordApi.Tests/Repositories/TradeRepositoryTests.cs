using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed class TradeRepositoryTests
{
    [Fact]
    public async Task CommitAsync_TransfersEquipmentOwnershipMembershipAndGoldAtomically()
    {
        await using var harness = await TradeHarness.CreateAsync();

        Assert.Equal(100, await harness.TotalGoldAsync(harness.PlayerAAccountId));

        var result = await harness.Repository.CommitAsync(harness.Request());

        Assert.True(result.Succeeded);
        Assert.NotNull(result.Value);
        Assert.Equal(70, await harness.TotalGoldAsync(harness.PlayerAAccountId));
        Assert.Equal(30, await harness.TotalGoldAsync(harness.PlayerBAccountId));
        var entry = await harness.DbContext.InventoryEntries.AsNoTracking()
            .SingleAsync(candidate => candidate.InventoryEntryId == harness.EquipmentEntryId);
        Assert.Equal(harness.PlayerBBagId, entry.InventoryId);
        Assert.Null(entry.SlotIndex);
        var equipment = await harness.DbContext.EquipmentInstances.AsNoTracking()
            .SingleAsync(candidate => candidate.EquipmentInstanceId == harness.EquipmentInstanceId);
        Assert.Equal(harness.PlayerBAccountId, equipment.AccountId);
        Assert.Contains(harness.EquipmentEntryId, result.Value.PlayerAAffectedInventoryEntryIds);
        Assert.Contains(harness.EquipmentEntryId, result.Value.PlayerBAffectedInventoryEntryIds);
    }

    [Fact]
    public async Task CommitAsync_ReplaysSameOperationWithoutSecondTransfer()
    {
        await using var harness = await TradeHarness.CreateAsync();
        var request = harness.Request();

        Assert.Equal(100, await harness.TotalGoldAsync(harness.PlayerAAccountId));

        var first = await harness.Repository.CommitAsync(request);
        var replay = await harness.Repository.CommitAsync(request);

        Assert.True(first.Succeeded);
        Assert.True(replay.Succeeded);
        Assert.Equal(first.Value!.OperationId, replay.Value!.OperationId);
        Assert.Equal(70, await harness.TotalGoldAsync(harness.PlayerAAccountId));
        Assert.Equal(30, await harness.TotalGoldAsync(harness.PlayerBAccountId));
        Assert.Equal(1, await harness.DbContext.TradeCommits.CountAsync());
    }

    [Fact]
    public async Task CommitAsync_TransfersStackableRuneByItemIdAtomically()
    {
        await using var harness = await TradeHarness.CreateAsync();

        var result = await harness.Repository.CommitAsync(harness.Request(includeRune: true));

        Assert.True(result.Succeeded);
        var source = await harness.DbContext.InventoryEntries.AsNoTracking()
            .SingleAsync(candidate => candidate.InventoryEntryId == harness.RuneEntryId);
        var destination = await harness.DbContext.InventoryEntries.AsNoTracking()
            .SingleAsync(candidate => candidate.InventoryId == harness.PlayerBBagId
                && candidate.ItemCategory == "rune"
                && candidate.ItemId == "trade_rune"
                && !candidate.IsDeleted);
        Assert.Equal(1, source.Quantity);
        Assert.Equal(1, destination.Quantity);
        Assert.Null(source.InstanceType);
        Assert.Null(source.InstanceId);
        Assert.Contains(harness.RuneEntryId, result.Value!.PlayerAAffectedInventoryEntryIds);
        Assert.Contains(destination.InventoryEntryId, result.Value.PlayerBAffectedInventoryEntryIds);
        Assert.Null(destination.SlotIndex);
    }

    [Fact]
    public async Task CommitAsync_LeavesNewMaterialSlotForPluginReconciliation()
    {
        await using var harness = await TradeHarness.CreateAsync();

        var result = await harness.Repository.CommitAsync(harness.Request(includeMaterial: true));

        Assert.True(result.Succeeded);
        var material = await harness.DbContext.InventoryEntries.AsNoTracking()
            .SingleAsync(candidate => candidate.InventoryId == harness.PlayerBBagId
                && candidate.ItemId == "trade_material"
                && !candidate.IsDeleted);
        Assert.Equal(harness.PlayerBBagId, material.InventoryId);
        Assert.Null(material.SlotIndex);
        Assert.Contains(material.InventoryEntryId, result.Value!.PlayerBAffectedInventoryEntryIds);
    }

    [Fact]
    public async Task CommitAsync_RejectsReusedOperationIdWithDifferentRequest()
    {
        await using var harness = await TradeHarness.CreateAsync();
        var request = harness.Request();

        Assert.True((await harness.Repository.CommitAsync(request)).Succeeded);
        request.PlayerAGold = 20;

        var conflict = await harness.Repository.CommitAsync(request);

        Assert.False(conflict.Succeeded);
        Assert.Equal(409, conflict.StatusCode);
        Assert.Equal("trade.idempotency_conflict", conflict.ErrorCode);
        Assert.Equal(70, await harness.TotalGoldAsync(harness.PlayerAAccountId));
        Assert.Equal(30, await harness.TotalGoldAsync(harness.PlayerBAccountId));
    }

    private sealed class TradeHarness : IAsyncDisposable
    {
        private readonly SqliteConnection connection;

        private TradeHarness(SqliteConnection connection, AstralRecordDbContext dbContext)
        {
            this.connection = connection;
            DbContext = dbContext;
            Repository = new TradeRepository(dbContext);
        }

        public AstralRecordDbContext DbContext { get; }
        public TradeRepository Repository { get; }
        public Guid PlayerAAccountId { get; private init; }
        public Guid PlayerBAccountId { get; private init; }
        public Guid PlayerABagId { get; private init; }
        public Guid PlayerBBagId { get; private init; }
        public Guid EquipmentEntryId { get; private init; }
        public Guid EquipmentInstanceId { get; private init; }
        public Guid RuneEntryId { get; private init; }
        public Guid MaterialEntryId { get; private init; }

        public static async Task<TradeHarness> CreateAsync()
        {
            var connection = new SqliteConnection("Data Source=:memory:");
            await connection.OpenAsync();
            var options = new DbContextOptionsBuilder<AstralRecordDbContext>().UseSqlite(connection).Options;
            var dbContext = new AstralRecordDbContext(options);
            await dbContext.Database.EnsureCreatedAsync();
            var harness = new TradeHarness(connection, dbContext)
            {
                PlayerAAccountId = Guid.NewGuid(),
                PlayerBAccountId = Guid.NewGuid(),
                PlayerABagId = Guid.NewGuid(),
                PlayerBBagId = Guid.NewGuid(),
                EquipmentEntryId = Guid.NewGuid(),
                EquipmentInstanceId = Guid.NewGuid(),
                RuneEntryId = Guid.NewGuid(),
                MaterialEntryId = Guid.NewGuid(),
            };
            await harness.SeedAsync();
            return harness;
        }

        public TradeCommitRequest Request(bool includeRune = false, bool includeMaterial = false)
        {
            var playerAItems = new List<TradeCommitItemRequest>
            {
                new() { SourceInventoryEntryId = EquipmentEntryId, Quantity = 1 },
            };
            if (includeRune)
                playerAItems.Add(new TradeCommitItemRequest { SourceInventoryEntryId = RuneEntryId, Quantity = 1 });
            if (includeMaterial)
                playerAItems.Add(new TradeCommitItemRequest { SourceInventoryEntryId = MaterialEntryId, Quantity = 2 });

            return new TradeCommitRequest
            {
                OperationId = Guid.NewGuid(),
                PlayerAAccountId = PlayerAAccountId,
                PlayerBAccountId = PlayerBAccountId,
                PlayerAItems = playerAItems,
                PlayerBItems = [],
                PlayerAGold = 30,
                PlayerBGold = 0,
                UpdatedBy = PlayerAAccountId,
            };
        }

        public async Task<long> TotalGoldAsync(Guid accountId)
        {
            var entries = await (from entry in DbContext.InventoryEntries.AsNoTracking()
                                 join inventory in DbContext.Inventories.AsNoTracking() on entry.InventoryId equals inventory.InventoryId
                                 where inventory.AccountId == accountId && inventory.InventoryType == "CURRENCY" && !entry.IsDeleted
                                 select entry).ToListAsync();
            return entries.Sum(entry => entry.ItemId switch
            {
                "gold" or "ast_gold" => entry.Quantity,
                "gold_coin" => entry.Quantity * 10L,
                "gold_ingot" => entry.Quantity * 100L,
                "gold_block" => entry.Quantity * 1_000L,
                "gold_diamond" => entry.Quantity * 10_000L,
                "gold_diamond_block" => entry.Quantity * 100_000L,
                "yggdrasil_star_core" => entry.Quantity * 1_000_000L,
                _ => 0L,
            });
        }

        private async Task SeedAsync()
        {
            var now = DateTime.UtcNow;
            foreach (var accountId in new[] { PlayerAAccountId, PlayerBAccountId })
            {
                DbContext.Accounts.Add(new AccountEntity
                {
                    Uuid = accountId,
                    UserId = Guid.NewGuid(),
                    AccountName = accountId == PlayerAAccountId ? "trade-a" : "trade-b",
                    IsActive = true,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = accountId,
                    UpdatedBy = accountId,
                });
            }
            AddInventory(PlayerAAccountId, PlayerABagId, "BAG", now);
            AddInventory(PlayerBAccountId, PlayerBBagId, "BAG", now);
            var aCurrency = Guid.NewGuid();
            var bCurrency = Guid.NewGuid();
            AddInventory(PlayerAAccountId, aCurrency, "CURRENCY", now);
            AddInventory(PlayerBAccountId, bCurrency, "CURRENCY", now);
            DbContext.InventoryEntries.AddRange(
                CurrencyEntry(aCurrency, PlayerAAccountId, 100, now),
                CurrencyEntry(bCurrency, PlayerBAccountId, 0, now),
                new InventoryEntryEntity
                {
                    InventoryEntryId = EquipmentEntryId,
                    InventoryId = PlayerABagId,
                    SlotIndex = 1,
                    ItemCategory = "equipment",
                    InstanceType = "EQUIPMENT",
                    InstanceId = EquipmentInstanceId,
                    Quantity = 1,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = PlayerAAccountId,
                    UpdatedBy = PlayerAAccountId,
                },
                new InventoryEntryEntity
                {
                    InventoryEntryId = RuneEntryId,
                    InventoryId = PlayerABagId,
                    SlotIndex = 2,
                    ItemCategory = "rune",
                    ItemId = "trade_rune",
                    Quantity = 2,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = PlayerAAccountId,
                    UpdatedBy = PlayerAAccountId,
                },
                new InventoryEntryEntity
                {
                    InventoryEntryId = MaterialEntryId,
                    InventoryId = PlayerABagId,
                    SlotIndex = 3,
                    ItemCategory = "material",
                    ItemId = "trade_material",
                    Quantity = 2,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = PlayerAAccountId,
                    UpdatedBy = PlayerAAccountId,
                });
            DbContext.EquipmentInstances.Add(new EquipmentInstanceEntity
            {
                EquipmentInstanceId = EquipmentInstanceId,
                AccountId = PlayerAAccountId,
                ItemId = "trade_equipment",
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = PlayerAAccountId,
                UpdatedBy = PlayerAAccountId,
            });
            await DbContext.SaveChangesAsync();
        }

        private void AddInventory(Guid accountId, Guid inventoryId, string type, DateTime now)
        {
            DbContext.Inventories.Add(new InventoryEntity
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
            });
        }

        private static InventoryEntryEntity CurrencyEntry(Guid inventoryId, Guid accountId, long quantity, DateTime now) => new()
        {
            InventoryEntryId = Guid.NewGuid(),
            InventoryId = inventoryId,
            ItemCategory = "currency",
            ItemId = "gold",
            Quantity = quantity,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = accountId,
            UpdatedBy = accountId,
        };

        public async ValueTask DisposeAsync()
        {
            await DbContext.DisposeAsync();
            await connection.DisposeAsync();
        }
    }
}
