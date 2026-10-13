using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Repositories;
using AstralRecordApi.Services;
using Microsoft.Data.Sqlite;
using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed class ServerRecordsRepositoryTests
{
    [Fact]
    [Trait("Category", "SqlServerIntegration")]
    public async Task Rankings_SqlServerExecutesAllQueriesAndSaturatesGoldAndMigrationIsIdempotent()
    {
        if (Environment.GetEnvironmentVariable("ASTRALRECORD_RUN_SQLSERVER_INTEGRATION") != "1") return;
        var database = "AR_ServerRecords_Test_" + Guid.NewGuid().ToString("N");
        var connectionString = new SqlConnectionStringBuilder(@"Server=localhost\SQLEXPRESS;Integrated Security=True;TrustServerCertificate=True") { InitialCatalog = database }.ConnectionString;
        await using var db = new AstralRecordDbContext(new DbContextOptionsBuilder<AstralRecordDbContext>().UseSqlServer(connectionString).Options);
        await using var managementConnection = new SqliteConnection("Data Source=:memory:");
        await managementConnection.OpenAsync();
        await using var management = new ManagementDbContext(new DbContextOptionsBuilder<ManagementDbContext>().UseSqlite(managementConnection).Options);
        try
        {
            await db.Database.EnsureCreatedAsync(); await management.Database.EnsureCreatedAsync();
            var legacyAccount = AddAccount(db, "BeforeMigration", 1, 12);
            await db.SaveChangesAsync();
            await db.Database.ExecuteSqlRawAsync("""
                DECLARE @name sysname = (SELECT dc.name FROM sys.default_constraints dc
                    JOIN sys.columns c ON dc.parent_object_id=c.object_id AND dc.parent_column_id=c.column_id
                    WHERE dc.parent_object_id=OBJECT_ID(N'dbo.account') AND c.name=N'play_time_seconds');
                IF @name IS NOT NULL BEGIN
                    DECLARE @sql nvarchar(max)=N'ALTER TABLE dbo.account DROP CONSTRAINT '+QUOTENAME(@name);
                    EXEC sys.sp_executesql @sql;
                END;
                ALTER TABLE dbo.account DROP COLUMN play_time_seconds;
                """);
            var migrationPath = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "../../../../../../00_docs/40_Database設計書/table-definitions/AstralRecord/migrations/20261012_account_play_time.sql"));
            var migration = await File.ReadAllTextAsync(migrationPath);
            await db.Database.ExecuteSqlRawAsync(migration); await db.Database.ExecuteSqlRawAsync(migration);
            db.ChangeTracker.Clear();
            Assert.Equal(0, db.Accounts.Single(row => row.Uuid == legacyAccount.Uuid).PlayTimeSeconds);
            await Assert.ThrowsAsync<SqlException>(() => db.Database.ExecuteSqlInterpolatedAsync(
                $"UPDATE dbo.account SET play_time_seconds = -1 WHERE uuid = {legacyAccount.Uuid}"));
            var account = AddAccount(db, "SqlServer", 15, 25);
            var overflowOwner = AddAccount(db, "Overflow", 10, long.MaxValue);
            AddAccount(db, "Overflow", 9, long.MaxValue, overflowOwner.UserId, slot: 1);
            AddCurrency(db, account, "GAME", "99a00007", long.MaxValue);
            db.AccountDungeonRecords.Add(new AccountDungeonRecordEntity { AccountDungeonRecordId = Guid.NewGuid(), AccountId = account.Uuid, DungeonId = "dungeon", ClearCount = 2 });
            db.AccountMobRecords.Add(new AccountMobRecordEntity { AccountMobRecordId = Guid.NewGuid(), AccountId = account.Uuid, MobId = "boss", MobCategory = "BOSS", DefeatCount = 3 });
            var state = new AccountQuestStateEntity { AccountQuestStateId = Guid.NewGuid(), AccountId = account.Uuid };
            state.Completions.Add(new AccountQuestCompletionEntity { AccountQuestCompletionId = Guid.NewGuid(), QuestId = "quest" });
            db.AccountQuestStates.Add(state);
            await db.SaveChangesAsync();
            var repository = new ServerRecordsRepository(db, new NetworkManagementRepository(management, db, TimeProvider.System), TimeProvider.System, new NetworkRuntimeService(TimeProvider.System));
            var result = await repository.GetAsync(10);
            Assert.Equal(8, result.Rankings.Count);
            Assert.All(result.Rankings, ranking => Assert.NotEmpty(ranking.Entries));
            Assert.Equal(long.MaxValue, result.Rankings.Single(ranking => ranking.Type == "play_time").Entries[0].Value);
            Assert.Equal(long.MaxValue, result.Rankings.Single(ranking => ranking.Type == "gold").Entries[0].Value);
            var request = new AstralRecordApi.Models.PlayerStateSnapshotSaveRequest
            {
                SnapshotId = Guid.NewGuid(), AccountId = account.Uuid, UpdatedBy = account.UserId,
                PlayTime = System.Text.Json.JsonSerializer.SerializeToElement(new { accountId = account.Uuid, deltaSeconds = 4L }),
            };
            var snapshot = new PlayerStateSnapshotRepository(db);
            Assert.True((await snapshot.SaveAsync(request)).Succeeded);
            Assert.True((await snapshot.SaveAsync(request)).Succeeded);
            Assert.Equal(29, db.Accounts.Single(row => row.Uuid == account.Uuid).PlayTimeSeconds);
        }
        finally
        {
            // 上で生成した固定prefix+UUIDの一時DBだけを削除する。
            Microsoft.Data.SqlClient.SqlConnection.ClearAllPools();
            await db.Database.EnsureDeletedAsync();
        }
    }

    [Fact]
    public async Task Rankings_GroupUsersConvertGoldExcludeOperatorsAndLimitInDatabase()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await using var managementConnection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync(); await managementConnection.OpenAsync();
        var commands = new List<string>();
        await using var db = new AstralRecordDbContext(new DbContextOptionsBuilder<AstralRecordDbContext>().UseSqlite(connection)
            .LogTo(commands.Add, [RelationalEventId.CommandExecuted]).Options);
        await using var management = new ManagementDbContext(new DbContextOptionsBuilder<ManagementDbContext>().UseSqlite(managementConnection).Options);
        await db.Database.EnsureCreatedAsync(); await management.Database.EnsureCreatedAsync();
        var normal = AddAccount(db, "Alice", 10, 12);
        var secondary = AddAccount(db, "Alice", 20, 8, normal.UserId, slot: 1);
        var other = AddAccount(db, "Bob", 15, 4);
        var authority = AddAccount(db, "OfflineAuthority", 999, 999);
        AddAccount(db, "StoredOperator", 999, 999, permission: 99);
        AddAccount(db, "LegacyOperator", 999, 999, permission: 10);
        AddAccount(db, "DeletedOwner", 999, 999, deletedUser: true);
        AddAccount(db, "DeletedAccount", 999, 999, deletedAccount: true);
        AddAccount(db, "Alice", 999, 999, normal.UserId, mode: 2, slot: 2);
        management.NetworkSettings.Add(new ManagedNetworkSettingsEntity { Id = 1, Revision = 1, SettingsJson = "{\"authorityUsers\":[\"" + authority.UserId + "\"]}" });
        AddCurrency(db, normal, "GAME", "99a00002", 3);
        AddCurrency(db, secondary, "GAME", "GOLD_BLOCK", 2);
        AddCurrency(db, normal, "ADMIN", "99a00007", 999);
        AddCurrency(db, other, "GAME", "99a00001", 12);
        AddCurrency(db, normal, "GAME", "99a00007", 999, deleted: true);
        foreach (var account in new[] { normal, secondary })
        {
            db.AccountDungeonRecords.Add(new AccountDungeonRecordEntity { AccountDungeonRecordId = Guid.NewGuid(), AccountId = account.Uuid, DungeonId = "same", ClearCount = 3 });
            db.AccountMobRecords.Add(new AccountMobRecordEntity { AccountMobRecordId = Guid.NewGuid(), AccountId = account.Uuid, MobId = "boss", MobCategory = "BOSS", DefeatCount = 2 });
            db.AccountMobRecords.Add(new AccountMobRecordEntity { AccountMobRecordId = Guid.NewGuid(), AccountId = account.Uuid, MobId = "enemy", MobCategory = "ENEMY", DefeatCount = 5 });
            db.AccountMobRecords.Add(new AccountMobRecordEntity { AccountMobRecordId = Guid.NewGuid(), AccountId = account.Uuid, MobId = "npc", MobCategory = "NPC", DefeatCount = 999 });
            var questState = new AccountQuestStateEntity { AccountQuestStateId = Guid.NewGuid(), AccountId = account.Uuid };
            questState.Completions.Add(new AccountQuestCompletionEntity { AccountQuestCompletionId = Guid.NewGuid(), AccountQuestStateId = questState.AccountQuestStateId, QuestId = "sameQuest" });
            db.AccountQuestStates.Add(questState);
        }
        await db.SaveChangesAsync(); await management.SaveChangesAsync();
        commands.Clear();
        var repository = new ServerRecordsRepository(db, new NetworkManagementRepository(management, db, TimeProvider.System), TimeProvider.System, new NetworkRuntimeService(TimeProvider.System));
        var result = await repository.GetAsync(1);
        Assert.Equal(8, commands.Count);
        Assert.All(commands, command => { Assert.Contains("LIMIT", command); Assert.Contains("ORDER BY", command); Assert.Contains("GROUP BY", command); });
        Assert.Equal(8, result.Rankings.Count);
        var values = result.Rankings.ToDictionary(ranking => ranking.Type, ranking => Assert.Single(ranking.Entries).Value);
        Assert.Equal(20, values["level"]); Assert.Equal(2030, values["gold"]); Assert.Equal(20, values["play_time"]);
        Assert.Equal(6, values["dungeon_clears"]); Assert.Equal(4, values["boss_defeats"]); Assert.Equal(14, values["mob_defeats"]);
        Assert.Equal(1, values["dungeon_types"]); Assert.Equal(1, values["quest_types"]);
        Assert.All(result.Rankings, ranking => { Assert.True(ranking.Available); Assert.Equal(normal.UserId, ranking.Entries[0].UserUuid); Assert.Equal(1, ranking.Entries[0].Rank); });
        var larger = await repository.GetAsync(10);
        Assert.Equal(new[] { "Alice", "Bob" }, larger.Rankings.Single(ranking => ranking.Type == "level").Entries.Select(entry => entry.PlayerName));
    }

    [Fact]
    public async Task Rankings_EmptyDatabaseReturnsAllTypesAndInvalidLimitIsRejected()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await using var managementConnection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync(); await managementConnection.OpenAsync();
        await using var db = new AstralRecordDbContext(new DbContextOptionsBuilder<AstralRecordDbContext>().UseSqlite(connection).Options);
        await using var management = new ManagementDbContext(new DbContextOptionsBuilder<ManagementDbContext>().UseSqlite(managementConnection).Options);
        await db.Database.EnsureCreatedAsync(); await management.Database.EnsureCreatedAsync();
        var runtime = new NetworkRuntimeService(TimeProvider.System);
        var repository = new ServerRecordsRepository(db, new NetworkManagementRepository(management, db, TimeProvider.System), TimeProvider.System, runtime);
        var result = await repository.GetAsync(10);
        Assert.Equal(8, result.Rankings.Count); Assert.All(result.Rankings, ranking => Assert.Empty(ranking.Entries));
        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(() => repository.GetAsync(0));
        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(() => repository.GetAsync(101));
        var legacyAuthority = AddAccount(db, "LegacyProxyAuthority", 20, 0);
        await db.SaveChangesAsync();
        runtime.ReplaceAuthorities(new AstralRecordApi.Models.NetworkAuthorityUpdateRequest([legacyAuthority.UserId]));
        var beforeBootstrap = await repository.GetAsync(10);
        Assert.All(beforeBootstrap.Rankings, ranking => Assert.Empty(ranking.Entries));
        management.NetworkSettings.Add(new ManagedNetworkSettingsEntity { Id = 1, Revision = 1, SettingsJson = "{\"authorityUsers\":[]}" });
        await management.SaveChangesAsync();
        var afterBootstrap = await repository.GetAsync(10);
        Assert.Equal(legacyAuthority.UserId, Assert.Single(afterBootstrap.Rankings.Single(ranking => ranking.Type == "level").Entries).UserUuid);
        Assert.Empty(afterBootstrap.Rankings.Single(ranking => ranking.Type == "play_time").Entries);
    }

    [Fact]
    public async Task Rankings_SharedCacheAvoidsAllEightQueriesAndRefreshesAuthorityExclusion()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await using var managementConnection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync(); await managementConnection.OpenAsync();
        var commands = new List<string>();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>().UseSqlite(connection)
            .LogTo(commands.Add, [RelationalEventId.CommandExecuted]).Options;
        await using var db = new AstralRecordDbContext(options);
        await using var otherScope = new AstralRecordDbContext(options);
        await using var management = new ManagementDbContext(new DbContextOptionsBuilder<ManagementDbContext>().UseSqlite(managementConnection).Options);
        await db.Database.EnsureCreatedAsync(); await management.Database.EnsureCreatedAsync();
        var first = AddAccount(db, "Alice", 20, 0);
        AddAccount(db, "Bob", 10, 0);
        await db.SaveChangesAsync();
        var runtime = new NetworkRuntimeService(TimeProvider.System);
        var cache = new ServerRecordsCache(TimeProvider.System);
        var repository = new ServerRecordsRepository(db, new NetworkManagementRepository(management, db, TimeProvider.System), TimeProvider.System, runtime, cache);
        var otherRepository = new ServerRecordsRepository(otherScope, new NetworkManagementRepository(management, otherScope, TimeProvider.System), TimeProvider.System, runtime, cache);
        commands.Clear();
        var result = await repository.GetAsync(1);
        Assert.Equal(8, commands.Count);
        var second = await otherRepository.GetAsync(10);
        Assert.Equal(8, commands.Count);
        Assert.Equal(result.GeneratedAt, second.GeneratedAt);
        Assert.Equal(2, second.Rankings.Single(ranking => ranking.Type == "level").Entries.Count);
        runtime.ReplaceAuthorities(new AstralRecordApi.Models.NetworkAuthorityUpdateRequest([first.UserId]));
        var changed = await otherRepository.GetAsync(10);
        Assert.Equal(16, commands.Count);
        Assert.Equal("Bob", Assert.Single(changed.Rankings.Single(ranking => ranking.Type == "level").Entries).PlayerName);
    }

    private static AccountEntity AddAccount(AstralRecordDbContext db, string name, int level, long seconds, Guid? ownerId = null,
        int permission = 0, byte mode = 0, int slot = 0, bool deletedUser = false, bool deletedAccount = false)
    {
        var owner = ownerId ?? Guid.NewGuid();
        if (ownerId is null) db.Users.Add(new UserEntity { Uuid = owner, Mcid = name, Permission = permission, IsDeleted = deletedUser });
        var account = new AccountEntity { Uuid = Guid.NewGuid(), UserId = owner, AccountName = "slot" + slot, SlotIndex = slot,
            Level = level, HighestLevel = level, Mode = mode, PlayTimeSeconds = seconds, IsDeleted = deletedAccount };
        db.Accounts.Add(account); return account;
    }

    private static void AddCurrency(AstralRecordDbContext db, AccountEntity account, string profile, string item, long quantity, bool deleted = false)
    {
        var inventory = new InventoryEntity { InventoryId = Guid.NewGuid(), AccountId = account.Uuid, InventoryType = "CURRENCY", InventoryProfile = profile, IsEnabled = true };
        db.Inventories.Add(inventory);
        db.InventoryEntries.Add(new InventoryEntryEntity { InventoryEntryId = Guid.NewGuid(), InventoryId = inventory.InventoryId,
            ItemCategory = "currency", ItemId = item, Quantity = quantity, IsDeleted = deleted });
    }
}
