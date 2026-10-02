using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class EquipmentLoadoutRepositoryTests
{
    [Fact]
    public async Task ActivateAsync_DeactivatesOtherLoadouts_InSameAccountProfile()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();

        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;

        await CreateLoadoutTablesAsync(options);

        var accountId = Guid.NewGuid();
        var userId = Guid.NewGuid();
        var activeLoadoutId = Guid.NewGuid();
        var targetLoadoutId = Guid.NewGuid();
        var now = DateTime.UtcNow;

        await using (var setupContext = new AstralRecordDbContext(options))
        {
            setupContext.Users.Add(new UserEntity { Uuid = userId, Mcid = "loadout-owner",
                JoinDate = now, LastJoinDate = now, CreatedAt = now, UpdatedAt = now,
                CreatedBy = userId, UpdatedBy = userId });
            setupContext.Accounts.Add(new AccountEntity { Uuid = accountId, UserId = userId,
                AccountName = "loadout", CreatedAt = now, UpdatedAt = now,
                CreatedBy = userId, UpdatedBy = userId });
            await setupContext.EquipmentLoadouts.AddRangeAsync(
                new EquipmentLoadoutEntity
                {
                    EquipmentLoadoutId = activeLoadoutId,
                    AccountId = accountId,
                    LoadoutProfile = "GAME",
                    LoadoutName = "Normal",
                    SortOrder = 0,
                    IsActive = true,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = userId,
                    UpdatedBy = userId,
                    IsDeleted = false,
                },
                new EquipmentLoadoutEntity
                {
                    EquipmentLoadoutId = targetLoadoutId,
                    AccountId = accountId,
                    LoadoutProfile = "GAME",
                    LoadoutName = "Boss",
                    SortOrder = 1,
                    IsActive = false,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = userId,
                    UpdatedBy = userId,
                    IsDeleted = false,
                });

            await setupContext.SaveChangesAsync();
        }

        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new EquipmentLoadoutRepository(dbContext);

        var activated = await repository.ActivateAsync(targetLoadoutId, userId);

        Assert.NotNull(activated);
        Assert.True(activated.IsActive);

        var loadouts = await dbContext.EquipmentLoadouts.AsNoTracking().ToListAsync();
        Assert.False(loadouts.Single(x => x.EquipmentLoadoutId == activeLoadoutId).IsActive);
        Assert.True(loadouts.Single(x => x.EquipmentLoadoutId == targetLoadoutId).IsActive);
    }

    [Fact]
    public async Task UpsertSlotAsync_RejectsEquipmentOwnedByAnotherAccount()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();

        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;

        await CreateLoadoutTablesAsync(options);
        await CreateEquipmentInstanceTableAsync(options);

        var accountId = Guid.NewGuid();
        var otherAccountId = Guid.NewGuid();
        var userId = Guid.NewGuid();
        var loadoutId = Guid.NewGuid();
        var equipmentInstanceId = Guid.NewGuid();
        var now = DateTime.UtcNow;

        await using (var setupContext = new AstralRecordDbContext(options))
        {
            setupContext.Users.Add(new UserEntity { Uuid = userId, Mcid = "loadout-owner",
                JoinDate = now, LastJoinDate = now, CreatedAt = now, UpdatedAt = now,
                CreatedBy = userId, UpdatedBy = userId });
            setupContext.Accounts.Add(new AccountEntity { Uuid = accountId, UserId = userId,
                AccountName = "loadout", CreatedAt = now, UpdatedAt = now,
                CreatedBy = userId, UpdatedBy = userId });
            await setupContext.EquipmentLoadouts.AddAsync(new EquipmentLoadoutEntity
            {
                EquipmentLoadoutId = loadoutId,
                AccountId = accountId,
                LoadoutProfile = "GAME",
                LoadoutName = "Normal",
                SortOrder = 0,
                IsActive = true,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = userId,
                UpdatedBy = userId,
                IsDeleted = false,
            });

            await setupContext.EquipmentInstances.AddAsync(new EquipmentInstanceEntity
            {
                EquipmentInstanceId = equipmentInstanceId,
                AccountId = otherAccountId,
                ItemId = "bronze_sword",
                EnhanceLevel = 0,
                RuneMaxSlots = 0,
                TranscendenceRank = 0,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = userId,
                UpdatedBy = userId,
                IsDeleted = false,
            });

            await setupContext.SaveChangesAsync();
        }

        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new EquipmentLoadoutRepository(dbContext);

        var slot = await repository.UpsertSlotAsync(loadoutId, new EquipmentLoadoutSlotUpsertRequest
        {
            SlotType = "WEAPON",
            SlotIndex = 0,
            EquipmentInstanceId = equipmentInstanceId,
            UpdatedBy = userId,
        });

        Assert.Null(slot);
        Assert.Empty(await dbContext.EquipmentLoadoutSlots.AsNoTracking().ToListAsync());
    }

    [Fact]
    public async Task UpsertSlotAsync_AdvancesParentTimestampBeyondFutureBaseline()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();

        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;

        await CreateLoadoutTablesAsync(options);
        await CreateEquipmentInstanceTableAsync(options);

        var accountId = Guid.NewGuid();
        var loadoutId = Guid.NewGuid();
        var equipmentInstanceId = Guid.NewGuid();
        var futureBaseline = DateTime.UtcNow.AddMinutes(1);

        await using (var setupContext = new AstralRecordDbContext(options))
        {
            setupContext.Users.Add(new UserEntity { Uuid = accountId, Mcid = "loadout-owner",
                JoinDate = futureBaseline, LastJoinDate = futureBaseline,
                CreatedAt = futureBaseline, UpdatedAt = futureBaseline,
                CreatedBy = accountId, UpdatedBy = accountId });
            setupContext.Accounts.Add(new AccountEntity { Uuid = accountId, UserId = accountId,
                AccountName = "loadout", CreatedAt = futureBaseline, UpdatedAt = futureBaseline,
                CreatedBy = accountId, UpdatedBy = accountId });
            await setupContext.EquipmentLoadouts.AddAsync(new EquipmentLoadoutEntity
            {
                EquipmentLoadoutId = loadoutId,
                AccountId = accountId,
                LoadoutProfile = "GAME",
                LoadoutName = "Normal",
                SortOrder = 0,
                IsActive = true,
                CreatedAt = futureBaseline,
                UpdatedAt = futureBaseline,
                CreatedBy = accountId,
                UpdatedBy = accountId,
                IsDeleted = false,
            });
            await setupContext.EquipmentInstances.AddAsync(new EquipmentInstanceEntity
            {
                EquipmentInstanceId = equipmentInstanceId,
                AccountId = accountId,
                ItemId = "bronze_sword",
                CreatedAt = futureBaseline,
                UpdatedAt = futureBaseline,
                CreatedBy = accountId,
                UpdatedBy = accountId,
                IsDeleted = false,
            });
            await setupContext.SaveChangesAsync();
        }

        await using var dbContext = new AstralRecordDbContext(options);
        var result = await new EquipmentLoadoutRepository(dbContext).UpsertSlotAsync(loadoutId, new EquipmentLoadoutSlotUpsertRequest
        {
            SlotType = "WEAPON",
            SlotIndex = 0,
            EquipmentInstanceId = equipmentInstanceId,
            UpdatedBy = accountId,
        });

        Assert.NotNull(result);
        var persistedLoadout = await dbContext.EquipmentLoadouts.AsNoTracking().SingleAsync(loadout => loadout.EquipmentLoadoutId == loadoutId);
        Assert.True(persistedLoadout.UpdatedAt > futureBaseline);
    }

    private static async Task CreateLoadoutTablesAsync(DbContextOptions<AstralRecordDbContext> options)
    {
        await using var setupContext = new AstralRecordDbContext(options);
        await setupContext.Database.EnsureCreatedAsync();
    }

    private static Task CreateEquipmentInstanceTableAsync(DbContextOptions<AstralRecordDbContext> _) =>
        Task.CompletedTask;
}
