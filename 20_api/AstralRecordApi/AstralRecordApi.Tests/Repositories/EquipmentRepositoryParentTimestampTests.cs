using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed class EquipmentRepositoryParentTimestampTests
{
    [Fact]
    public async Task DeleteEnchantBySlotIndexAsync_AdvancesEquipmentParentTimestamp()
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.DbContext.EquipmentInstanceEnchants.Add(new EquipmentInstanceEnchantEntity
        {
            EnchantId = Guid.NewGuid(), EquipmentInstanceId = fixture.InstanceId, SlotIndex = 0,
            EnchantMasterId = "test", EffectId = "test.effect", Status = "FIXED", Type = "FLAT", Value = 1,
            CreatedAt = fixture.InitialUpdatedAt, UpdatedAt = fixture.InitialUpdatedAt,
            CreatedBy = fixture.AccountId, UpdatedBy = fixture.AccountId,
        });
        await fixture.DbContext.SaveChangesAsync();

        var deleted = await new EquipmentRepository(fixture.DbContext)
            .DeleteEnchantBySlotIndexAsync(fixture.InstanceId, 0, fixture.AccountId);

        Assert.True(deleted);
        var parent = await fixture.DbContext.EquipmentInstances.AsNoTracking().SingleAsync();
        Assert.True(parent.UpdatedAt > fixture.InitialUpdatedAt);
        Assert.Equal(fixture.AccountId, parent.UpdatedBy);
    }

    [Fact]
    public async Task DeleteRuneBySlotIndexAsync_AdvancesEquipmentParentTimestamp()
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.DbContext.EquipmentInstanceRunes.Add(new EquipmentInstanceRuneEntity
        {
            RuneId = Guid.NewGuid(), EquipmentInstanceId = fixture.InstanceId, SlotIndex = 0, ItemId = "test_rune",
            CreatedAt = fixture.InitialUpdatedAt, UpdatedAt = fixture.InitialUpdatedAt,
            CreatedBy = fixture.AccountId, UpdatedBy = fixture.AccountId,
        });
        await fixture.DbContext.SaveChangesAsync();

        var deleted = await new EquipmentRepository(fixture.DbContext)
            .DeleteRuneBySlotIndexAsync(fixture.InstanceId, 0, fixture.AccountId);

        Assert.True(deleted);
        var parent = await fixture.DbContext.EquipmentInstances.AsNoTracking().SingleAsync();
        Assert.True(parent.UpdatedAt > fixture.InitialUpdatedAt);
        Assert.Equal(fixture.AccountId, parent.UpdatedBy);
    }

    private sealed class Fixture : IAsyncDisposable
    {
        private readonly SqliteConnection connection;
        public AstralRecordDbContext DbContext { get; }
        public Guid AccountId { get; }
        public Guid InstanceId { get; }
        public DateTime InitialUpdatedAt { get; }

        private Fixture(SqliteConnection connection, AstralRecordDbContext dbContext, Guid accountId, Guid instanceId, DateTime initialUpdatedAt)
        {
            this.connection = connection;
            DbContext = dbContext;
            AccountId = accountId;
            InstanceId = instanceId;
            InitialUpdatedAt = initialUpdatedAt;
        }

        public static async Task<Fixture> CreateAsync()
        {
            var connection = new SqliteConnection("Data Source=:memory:");
            await connection.OpenAsync();
            var dbContext = new AstralRecordDbContext(new DbContextOptionsBuilder<AstralRecordDbContext>()
                .UseSqlite(connection).Options);
            await dbContext.Database.EnsureCreatedAsync();
            var accountId = Guid.NewGuid();
            var instanceId = Guid.NewGuid();
            var initialUpdatedAt = DateTime.UtcNow.AddMinutes(-1);
            dbContext.EquipmentInstances.Add(new EquipmentInstanceEntity
            {
                EquipmentInstanceId = instanceId, AccountId = accountId, ItemId = "test_equipment",
                EnhanceLevel = 0, RuneMaxSlots = 1, TranscendenceRank = 0,
                CreatedAt = initialUpdatedAt, UpdatedAt = initialUpdatedAt,
                CreatedBy = accountId, UpdatedBy = accountId,
            });
            await dbContext.SaveChangesAsync();
            return new Fixture(connection, dbContext, accountId, instanceId, initialUpdatedAt);
        }

        public async ValueTask DisposeAsync()
        {
            await DbContext.DisposeAsync();
            await connection.DisposeAsync();
        }
    }
}
