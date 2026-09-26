using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using AstralRecordApi.Services;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed class AccountBenefitsRepositoryTests
{
    [Fact]
    public void AstralderDefersDonerAndRepeatedItemsExtendBothPeriods()
    {
        var now = new DateTime(2026, 9, 26, 1, 0, 0, DateTimeKind.Utc);
        var state = new AccountBenefitsEntity();
        AccountBenefitsPolicy.AddVip(state, "DONER", 15, now);
        AccountBenefitsPolicy.AddVip(state, "DONER", 15, now);
        AccountBenefitsPolicy.AddVip(state, "ASTRALDER", 20, now);
        AccountBenefitsPolicy.AddVip(state, "ASTRALDER", 20, now);
        Assert.Equal(now.AddDays(40), state.AstralderExpiresAt);
        Assert.Equal(now.AddDays(70), state.DonerExpiresAt);
        Assert.Equal("ASTRALDER", AccountBenefitsPolicy.Describe(state, now).VipTier);
        Assert.Equal("DONER", AccountBenefitsPolicy.Describe(state, now.AddDays(40)).VipTier);
        Assert.Equal("NONE", AccountBenefitsPolicy.Describe(state, now.AddDays(70)).VipTier);
    }

    [Fact]
    public void DonerPurchasedDuringAstralderStartsAfterAstralder()
    {
        var now = DateTime.UtcNow;
        var state = new AccountBenefitsEntity();
        AccountBenefitsPolicy.AddVip(state, "ASTRALDER", 20, now);
        AccountBenefitsPolicy.AddVip(state, "DONER", 15, now.AddDays(2));
        Assert.Equal(now.AddDays(35), state.DonerExpiresAt);
    }

    [Fact]
    public void DailyAwardUsesJstBoundaryAndDoesNotExceedPurchasedDays()
    {
        var now = new DateTime(2026, 9, 26, 14, 59, 0, DateTimeKind.Utc);
        var state = new AccountBenefitsEntity();
        AccountBenefitsPolicy.AddVip(state, "ASTRALDER", 20, now);
        Assert.Equal(1, AccountBenefitsPolicy.ClaimDaily(state, now));
        Assert.Equal(0, AccountBenefitsPolicy.ClaimDaily(state, now.AddSeconds(30)));
        for (var day = 0; day < 19; day++) Assert.Equal(1, AccountBenefitsPolicy.ClaimDaily(state, now.AddMinutes(1).AddDays(day)));
        Assert.Equal(0, AccountBenefitsPolicy.ClaimDaily(state, now.AddMinutes(1).AddDays(19)));
        Assert.Equal(20, state.InstancePriorityUses);
        AccountBenefitsPolicy.AddVip(state, "ASTRALDER", 20, now.AddDays(21));
        Assert.Equal(20, state.AstralderDailyCreditsRemaining);
    }

    [Fact]
    public async Task ItemConsumeReplayUsesCurrentSnapshotWithoutDoubleAward()
    {
        await using var f = await Fixture.Create();
        var request = new AccountBenefitItemRequest { OperationId = Guid.NewGuid(), InventoryEntryId = f.Entry, ExpectedUpdatedAt = f.Time.Now.UtcDateTime };
        var first = await f.Repository.ExecuteAsync(f.Account, "ITEM", request);
        Assert.Equal("COMPLETED", first!.Status);
        Assert.Equal(5, first.Benefits.InstancePriorityUses);
        Assert.Equal(1, Assert.Single(first.InventorySnapshot!.Entries).Quantity);
        await f.Repository.ExecuteAsync(f.Account, "ITEM", new AccountBenefitItemRequest { OperationId = Guid.NewGuid(), InventoryEntryId = f.Entry, ExpectedUpdatedAt = f.Time.Now.UtcDateTime });
        var replay = await f.Repository.ExecuteAsync(f.Account, "ITEM", request);
        Assert.Equal(10, replay!.Benefits.InstancePriorityUses);
        Assert.Empty(replay.InventorySnapshot!.Entries);
        Assert.Equal(2, await f.Db.AccountBenefitOperations.CountAsync());
    }

    [Fact]
    public async Task FailureAfterSaveRollsBackInventoryBenefitsAndLedgerTogether()
    {
        await using var f = await Fixture.Create();
        var request = new AccountBenefitItemRequest { OperationId = Guid.NewGuid(), InventoryEntryId = f.Entry, ExpectedUpdatedAt = f.Time.Now.UtcDateTime };
        f.Fault.Enabled = true;
        await Assert.ThrowsAsync<InvalidOperationException>(() => f.Repository.ExecuteAsync(f.Account, "ITEM", request));
        f.Db.ChangeTracker.Clear();
        Assert.Equal(2, (await f.Db.InventoryEntries.SingleAsync()).Quantity);
        Assert.Empty(await f.Db.AccountBenefits.ToListAsync());
        Assert.Empty(await f.Db.AccountBenefitOperations.ToListAsync());
        f.Fault.Enabled = false;
        Assert.Equal(5, (await f.Repository.ExecuteAsync(f.Account, "ITEM", request))!.Benefits.InstancePriorityUses);
    }

    [Fact]
    public async Task StaleEntryRejectsWithoutGrantOrConsumption()
    {
        await using var f = await Fixture.Create();
        var response = await f.Repository.ExecuteAsync(f.Account, "ITEM", new AccountBenefitItemRequest
        { OperationId = Guid.NewGuid(), InventoryEntryId = f.Entry, ExpectedUpdatedAt = f.Time.Now.AddSeconds(-1).UtcDateTime });
        Assert.Equal("inventory_conflict", response!.Reason);
        Assert.Equal(0, response.Benefits.InstancePriorityUses);
        Assert.Equal(2, Assert.Single(response.InventorySnapshot!.Entries).Quantity);
    }

    [Fact]
    public async Task PriorityRefundIsIdempotentAndPrecedingRefundPreventsLateConsume()
    {
        await using var f = await Fixture.Create();
        f.Db.AccountBenefits.Add(new() { AccountId = f.Account, InstancePriorityUses = 1 });
        await f.Db.SaveChangesAsync();
        var operation = new AccountBenefitOperationRequest { OperationId = Guid.NewGuid() };
        Assert.Equal(0, (await f.Repository.ExecuteAsync(f.Account, "PRIORITY", operation))!.Benefits.InstancePriorityUses);
        Assert.Equal(0, (await f.Repository.ExecuteAsync(f.Account, "PRIORITY", operation))!.Benefits.InstancePriorityUses);
        Assert.Equal(1, (await f.Repository.ExecuteAsync(f.Account, "REFUND", operation))!.Benefits.InstancePriorityUses);
        Assert.Equal(1, (await f.Repository.ExecuteAsync(f.Account, "REFUND", operation))!.Benefits.InstancePriorityUses);
        Assert.Equal("REJECTED", (await f.Repository.ExecuteAsync(f.Account, "PRIORITY", operation))!.Status);
        var cancelled = new AccountBenefitOperationRequest { OperationId = Guid.NewGuid() };
        await f.Repository.ExecuteAsync(f.Account, "REFUND", cancelled);
        var late = await f.Repository.ExecuteAsync(f.Account, "PRIORITY", cancelled);
        Assert.Equal("REJECTED", late!.Status);
        Assert.Equal(1, late.Benefits.InstancePriorityUses);
    }

    [Fact]
    public async Task LoginDifferentOperationIdsCannotAwardTwiceOnSameDay()
    {
        await using var f = await Fixture.Create();
        f.Db.AccountBenefits.Add(new() { AccountId = f.Account, AstralderExpiresAt = f.Time.Now.AddDays(20).UtcDateTime, AstralderDailyCreditsRemaining = 20 });
        await f.Db.SaveChangesAsync();
        Assert.Equal(1, (await f.Repository.ExecuteAsync(f.Account, "LOGIN", new() { OperationId = Guid.NewGuid() }))!.AwardedPriorityUses);
        Assert.Equal(0, (await f.Repository.ExecuteAsync(f.Account, "LOGIN", new() { OperationId = Guid.NewGuid() }))!.AwardedPriorityUses);
        f.Time.Now = f.Time.Now.AddDays(4);
        var response = await f.Repository.ExecuteAsync(f.Account, "LOGIN", new() { OperationId = Guid.NewGuid() });
        Assert.Equal(2, response!.Benefits.InstancePriorityUses);
    }

    [Fact]
    public async Task OperationIdCannotBeReusedForDifferentAction()
    {
        await using var f = await Fixture.Create();
        var request = new AccountBenefitOperationRequest { OperationId = Guid.NewGuid() };
        await f.Repository.ExecuteAsync(f.Account, "LOGIN", request);
        Assert.Equal("operation_conflict", (await f.Repository.ExecuteAsync(f.Account, "PRIORITY", request))!.Reason);
    }

    private sealed class SaveFault : SaveChangesInterceptor
    {
        public bool Enabled;
        public override ValueTask<int> SavedChangesAsync(SaveChangesCompletedEventData eventData, int result, CancellationToken cancellationToken = default)
        {
            if (Enabled) throw new InvalidOperationException("Injected failure after SQL writes before commit.");
            return ValueTask.FromResult(result);
        }
    }
    private sealed class Clock : TimeProvider
    {
        public DateTimeOffset Now = new(2026, 9, 26, 1, 0, 0, TimeSpan.Zero);
        public override DateTimeOffset GetUtcNow() => Now;
    }
    private sealed class Items : IItemRepository
    {
        public IReadOnlyList<ItemSummaryResponse> GetAllSummaries() => [];
        public ItemResponse? GetById(string id) => new() { Id = id, SchemaVersion = 1, Name = "優先券", Icon = "PAPER", Category = "consumable", Rarity = "COMMON", UnTradeable = true, UnSellable = true,
            Consumable = new() { Effects = [new() { Type = "INSTANCE_PRIORITY", Value = 5 }] } };
    }
    private sealed class Fixture(SqliteConnection connection, AstralRecordDbContext db, SaveFault fault) : IAsyncDisposable
    {
        public AstralRecordDbContext Db => db;
        public SaveFault Fault => fault;
        public Guid Account = Guid.NewGuid();
        public Guid Entry = Guid.NewGuid();
        public Clock Time = new();
        public AccountBenefitsRepository Repository => new(db, new Items(), Time);
        public static async Task<Fixture> Create()
        {
            var connection = new SqliteConnection("Data Source=:memory:");
            await connection.OpenAsync();
            var fault = new SaveFault();
            var db = new AstralRecordDbContext(new DbContextOptionsBuilder<AstralRecordDbContext>().UseSqlite(connection).AddInterceptors(fault).Options);
            await db.Database.EnsureCreatedAsync();
            var f = new Fixture(connection, db, fault);
            var userId = Guid.NewGuid(); var inventoryId = Guid.NewGuid(); var now = f.Time.Now.UtcDateTime;
            db.Users.Add(new() { Uuid = userId, Mcid = "VipTester", CreatedAt = now, UpdatedAt = now });
            db.Accounts.Add(new() { Uuid = f.Account, UserId = userId, AccountName = "VipTester", CreatedAt = now, UpdatedAt = now });
            db.Inventories.Add(new() { InventoryId = inventoryId, AccountId = f.Account, InventoryProfile = "GAME", InventoryType = "HOTBAR", IsEnabled = true, CreatedAt = now, UpdatedAt = now });
            db.InventoryEntries.Add(new() { InventoryEntryId = f.Entry, InventoryId = inventoryId, ItemCategory = "consumable", ItemId = "30a00007", Quantity = 2, CreatedAt = now, UpdatedAt = now });
            await db.SaveChangesAsync();
            return f;
        }
        public async ValueTask DisposeAsync() { await db.DisposeAsync(); await connection.DisposeAsync(); }
    }
}
