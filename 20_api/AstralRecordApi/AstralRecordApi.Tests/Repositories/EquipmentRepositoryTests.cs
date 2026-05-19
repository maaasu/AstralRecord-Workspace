using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class EquipmentRepositoryTests
{
    [Fact]
    public async Task AddAsync_PersistsEquipmentInstanceStatRolls_WithCurrentSchema()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();

        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;

        await using (var setupContext = new AstralRecordDbContext(options))
        {
            await setupContext.Database.ExecuteSqlRawAsync(@"
                CREATE TABLE equipment_instance (
                    equipment_instance_id TEXT NOT NULL PRIMARY KEY,
                    account_id TEXT NOT NULL,
                    item_id TEXT NOT NULL,
                    enhance_level INTEGER NOT NULL,
                    rune_max_slots INTEGER NOT NULL,
                    transcendence_rank INTEGER NOT NULL,
                    durability_max INTEGER NULL,
                    durability_value INTEGER NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    created_by TEXT NOT NULL,
                    updated_by TEXT NOT NULL,
                    is_deleted INTEGER NOT NULL
                );");

            await setupContext.Database.ExecuteSqlRawAsync(@"
                CREATE TABLE equipment_instance_stat_roll (
                    stat_roll_id TEXT NOT NULL PRIMARY KEY,
                    equipment_instance_id TEXT NOT NULL,
                    status TEXT NOT NULL,
                    random_min TEXT NOT NULL,
                    random_max TEXT NOT NULL,
                    sort_order INTEGER NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    created_by TEXT NOT NULL,
                    updated_by TEXT NOT NULL
                );");
        }

        var now = DateTime.UtcNow;
        var instanceId = Guid.NewGuid();
        var userId = Guid.NewGuid();

        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new EquipmentRepository(dbContext);

        var instance = new EquipmentInstanceEntity
        {
            EquipmentInstanceId = instanceId,
            AccountId = Guid.NewGuid(),
            ItemId = "sample_sword",
            EnhanceLevel = 0,
            RuneMaxSlots = 1,
            TranscendenceRank = 0,
            DurabilityMax = 100,
            DurabilityValue = 100,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = userId,
            UpdatedBy = userId,
            IsDeleted = false,
        };

        var statRolls = new[]
        {
            new EquipmentInstanceStatRollEntity
            {
                StatRollId = Guid.NewGuid(),
                EquipmentInstanceId = instanceId,
                Status = "ATTACK",
                RandomMin = "24",
                RandomMax = "32",
                SortOrder = 0,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = userId,
                UpdatedBy = userId,
            }
        };

        await repository.AddAsync(instance, statRolls, []);

        var savedStatRolls = await dbContext.EquipmentInstanceStatRolls.AsNoTracking().ToListAsync();
        Assert.Single(savedStatRolls);
    }
}
