using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using System.Text.Json;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed class PlayerStateSnapshotRepositoryTests
{
    [Fact]
    public async Task SaveAsync_MovesEntryAcrossSnapshotParents_AndReplaysFixedAck()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var snapshotId = Guid.NewGuid();
        var request = fixture.CreateMoveRequest(snapshotId);

        var first = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(request);

        Assert.True(first.Succeeded);
        Assert.Equal(fixture.SecondInventoryId, (await fixture.DbContext.InventoryEntries.SingleAsync()).InventoryId);
        var firstAck = first.Ack!;
        Assert.Single(firstAck.Entries);
        Assert.False(firstAck.Entries.Single().IsDeleted);

        var entry = await fixture.DbContext.InventoryEntries.SingleAsync();
        entry.UpdatedAt = entry.UpdatedAt.AddSeconds(1);
        await fixture.DbContext.SaveChangesAsync();

        var replay = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(request);

        Assert.True(replay.Succeeded);
        Assert.Equal(firstAck.Entries.Single().UpdatedAt, replay.Ack!.Entries.Single().UpdatedAt);
        var differentPayload = fixture.CreateMoveRequest(snapshotId, quantity: 2);
        var collision = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(differentPayload);
        Assert.Equal(PlayerStateSnapshotSaveFailure.Conflict, collision.Failure);
    }

    [Fact]
    public async Task SaveAsync_RejectsInventoryWhenExpectedEntrySetOmitsConcurrentAddition()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        fixture.DbContext.InventoryEntries.Add(new InventoryEntryEntity
        {
            InventoryEntryId = Guid.NewGuid(),
            InventoryId = fixture.FirstInventoryId,
            ItemCategory = "CURRENCY",
            ItemId = "gold",
            Quantity = 1,
            CreatedAt = fixture.BaseTime,
            UpdatedAt = fixture.BaseTime,
            CreatedBy = fixture.AccountId,
            UpdatedBy = fixture.AccountId,
        });
        await fixture.DbContext.SaveChangesAsync();

        var result = await new PlayerStateSnapshotRepository(fixture.DbContext)
            .SaveAsync(fixture.CreateMoveRequest(Guid.NewGuid()));

        Assert.Equal(PlayerStateSnapshotSaveFailure.Conflict, result.Failure);
        Assert.Contains("baseline", result.Detail!, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task SaveAsync_RejectsDeletionWhenBaselineEntryWasChangedExternally()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var entry = await fixture.DbContext.InventoryEntries.SingleAsync();
        entry.Quantity = 11;
        entry.UpdatedAt = entry.UpdatedAt.AddSeconds(1);
        await fixture.DbContext.SaveChangesAsync();

        var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            Inventories =
            [
                new PlayerStateInventorySnapshot
                {
                    InventoryId = fixture.FirstInventoryId,
                    ExpectedEntries = [new PlayerStateExpectedInventoryEntry { InventoryEntryId = fixture.EntryId, UpdatedAt = fixture.BaseTime }],
                    Entries = [],
                },
            ],
        });

        Assert.Equal(PlayerStateSnapshotSaveFailure.Conflict, result.Failure);
        var unchanged = await fixture.DbContext.InventoryEntries.SingleAsync();
        Assert.False(unchanged.IsDeleted);
        Assert.Equal(11, unchanged.Quantity);
    }

    [Fact]
    public async Task SaveAsync_UsesProgressVersionAndDoesNotOverwriteModeWhenModeIsNotDirty()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var account = await fixture.DbContext.Accounts.SingleAsync();
        account.Mode = 2;
        account.MenuShortcutsJson = "[\"STATUS\"]";
        account.UpdatedAt = account.UpdatedAt.AddSeconds(5); // position/menu系の別更新を模擬する。
        await fixture.DbContext.SaveChangesAsync();
        var section = new PlayerStateAccountProgressSection
        {
            AccountId = fixture.AccountId,
            ClientRevision = 9,
            ExpectedProgressVersion = 1,
            Level = 2,
            TotalExperience = 100,
            ClassId = "adventurer",
            ClassLevel = 2,
            ClassExperience = 100,
            ClassProgresses = [new AccountClassProgressUpdateRequest { ClassId = "adventurer", Level = 2, Experience = 100 }],
            Mode = null,
        };

        var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            AccountProgress = JsonSerializer.SerializeToElement(section, new JsonSerializerOptions(JsonSerializerDefaults.Web)),
        });

        Assert.True(result.Succeeded);
        var saved = await fixture.DbContext.Accounts.SingleAsync();
        Assert.Equal(2, saved.Mode);
        Assert.Equal(2, saved.ProgressVersion);
        Assert.Equal(100, saved.TotalExperience);
        Assert.Equal(2, result.Ack!.AccountProgress!.Value.GetProperty("progressVersion").GetInt32());
    }

    private sealed class SnapshotFixture : IAsyncDisposable
    {
        private readonly SqliteConnection connection;
        public AstralRecordDbContext DbContext { get; }
        public Guid AccountId { get; }
        public Guid FirstInventoryId { get; }
        public Guid SecondInventoryId { get; }
        public Guid EntryId { get; }
        public DateTime BaseTime { get; }

        private SnapshotFixture(SqliteConnection connection, AstralRecordDbContext dbContext,
            Guid accountId, Guid firstInventoryId, Guid secondInventoryId, Guid entryId, DateTime baseTime)
        {
            this.connection = connection;
            DbContext = dbContext;
            AccountId = accountId;
            FirstInventoryId = firstInventoryId;
            SecondInventoryId = secondInventoryId;
            EntryId = entryId;
            BaseTime = baseTime;
        }

        public static async Task<SnapshotFixture> CreateAsync()
        {
            var connection = new SqliteConnection("Data Source=:memory:");
            await connection.OpenAsync();
            var options = new DbContextOptionsBuilder<AstralRecordDbContext>().UseSqlite(connection).Options;
            var dbContext = new AstralRecordDbContext(options);
            await dbContext.Database.EnsureCreatedAsync();
            var accountId = Guid.NewGuid();
            var firstInventoryId = Guid.NewGuid();
            var secondInventoryId = Guid.NewGuid();
            var entryId = Guid.NewGuid();
            var time = DateTime.SpecifyKind(DateTime.UtcNow.AddMinutes(-1), DateTimeKind.Utc);
            dbContext.Accounts.Add(new AccountEntity
            {
                Uuid = accountId, UserId = Guid.NewGuid(), AccountName = "snapshot-test",
                Level = 1, ClassId = "adventurer", ClassLevel = 1, ProgressVersion = 1,
                CreatedAt = time, UpdatedAt = time, CreatedBy = accountId, UpdatedBy = accountId,
            });
            dbContext.Inventories.AddRange(
                new InventoryEntity { InventoryId = firstInventoryId, AccountId = accountId, InventoryType = "CURRENCY", InventoryProfile = "GAME", IsEnabled = true, CreatedAt = time, UpdatedAt = time, CreatedBy = accountId, UpdatedBy = accountId },
                new InventoryEntity { InventoryId = secondInventoryId, AccountId = accountId, InventoryType = "CURRENCY", InventoryProfile = "GAME", IsEnabled = true, CreatedAt = time, UpdatedAt = time, CreatedBy = accountId, UpdatedBy = accountId });
            dbContext.InventoryEntries.Add(new InventoryEntryEntity
            {
                InventoryEntryId = entryId, InventoryId = firstInventoryId, ItemCategory = "CURRENCY", ItemId = "gold", Quantity = 10,
                CreatedAt = time, UpdatedAt = time, CreatedBy = accountId, UpdatedBy = accountId,
            });
            await dbContext.SaveChangesAsync();
            return new SnapshotFixture(connection, dbContext, accountId, firstInventoryId, secondInventoryId, entryId, time);
        }

        public PlayerStateSnapshotSaveRequest CreateMoveRequest(Guid snapshotId, long quantity = 10) => new()
        {
            SnapshotId = snapshotId,
            AccountId = AccountId,
            UpdatedBy = AccountId,
            Inventories =
            [
                new PlayerStateInventorySnapshot
                {
                    InventoryId = FirstInventoryId,
                    ExpectedEntries = [new PlayerStateExpectedInventoryEntry { InventoryEntryId = EntryId, UpdatedAt = BaseTime }],
                    Entries = [],
                },
                new PlayerStateInventorySnapshot
                {
                    InventoryId = SecondInventoryId, ExpectedEntries = [],
                    Entries = [new PlayerStateInventoryEntrySnapshot { InventoryEntryId = EntryId, ExpectedUpdatedAt = BaseTime, ItemCategory = "CURRENCY", ItemId = "gold", Quantity = quantity }],
                },
            ],
        };

        public async ValueTask DisposeAsync()
        {
            await DbContext.DisposeAsync();
            await connection.DisposeAsync();
        }
    }
}
