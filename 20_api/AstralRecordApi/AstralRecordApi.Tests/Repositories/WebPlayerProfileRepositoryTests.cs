using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Options;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed class WebPlayerProfileRepositoryTests
{
    [Fact]
    public async Task ConnectionInformationIsIncludedOnlyForItsOwner()
    {
        await using var fixture = await Fixture.CreateAsync();
        var owner = Guid.NewGuid(); var viewer = Guid.NewGuid();
        await fixture.AddProfileAsync(owner, "Owner", Guid.NewGuid(), level: 10, isPublic: true);
        await fixture.AddProfileAsync(viewer, "Viewer", Guid.NewGuid(), level: 10, isPublic: false, webAdmin: true);
        var runtime = new SkillTreeOperationRepository(fixture.Game, new AstralRecordApi.Services.NetworkRuntimeService(TimeProvider.System));
        var repository = new WebPlayerProfileRepository(fixture.Game, fixture.Management, fixture.Master,
            Microsoft.Extensions.Options.Options.Create(new WebPlayerProfileOptions { SkillTreeStructureId = "starter" }), runtime);
        Assert.NotNull((await repository.GetMyProfileAsync(owner))!.CurrentAccount!.Connection);
        Assert.Null((await repository.GetProfileAsync(owner, viewer, false))!.CurrentAccount!.Connection);
        Assert.Null((await repository.GetProfileAsync(owner, viewer, true))!.CurrentAccount!.Connection);
    }

    [Fact]
    public async Task GetProfileAsync_HidesDefaultPrivateProfileAndReturnsOnlySelectedAccountState()
    {
        await using var fixture = await Fixture.CreateAsync();
        var owner = Guid.NewGuid();
        var viewer = Guid.NewGuid();
        var account = Guid.NewGuid();
        await fixture.AddClassMasterAsync("adventurer", "冒険者");
        await fixture.AddClassMasterAsync("paladin", "聖騎士", "adventurer");
        await fixture.AddProfileAsync(owner, "Owner", account, level: 42, isPublic: false, classId: "paladin");
        await fixture.AddProfileAsync(viewer, "Viewer", Guid.NewGuid(), level: 3, isPublic: false);
        fixture.Game.AccountClassProgresses.Add(new AccountClassProgressEntity
        {
            AccountId = account, ClassId = "paladin", Level = 12, UpdatedAt = DateTime.UtcNow, UpdatedBy = owner,
        });
        var treeStateId = Guid.NewGuid();
        fixture.Game.AccountSkillTreeStates.Add(new AccountSkillTreeStateEntity
        {
            AccountSkillTreeStateId = treeStateId, AccountId = account,
            CreatedAt = DateTime.UtcNow, UpdatedAt = DateTime.UtcNow, CreatedBy = owner, UpdatedBy = owner,
        });
        fixture.Game.AccountSkillTreeUnlockedNodes.Add(new AccountSkillTreeUnlockedNodeEntity
        {
            AccountSkillTreeUnlockedNodeId = Guid.NewGuid(), AccountSkillTreeStateId = treeStateId, NodeId = "1000",
            CreatedAt = DateTime.UtcNow, UpdatedAt = DateTime.UtcNow, CreatedBy = owner, UpdatedBy = owner,
        });
        fixture.Game.Inventories.Add(new InventoryEntity
        {
            InventoryId = Guid.NewGuid(), AccountId = account, InventoryType = "CURRENCY", InventoryProfile = "GAME",
            IsEnabled = true, CreatedAt = DateTime.UtcNow, UpdatedAt = DateTime.UtcNow, CreatedBy = owner, UpdatedBy = owner,
        });
        var inventory = fixture.Game.Inventories.Local.Single();
        fixture.Game.InventoryEntries.Add(new InventoryEntryEntity
        {
            InventoryEntryId = Guid.NewGuid(), InventoryId = inventory.InventoryId, ItemCategory = "currency", ItemId = "99a00001",
            Quantity = 1234, CreatedAt = DateTime.UtcNow, UpdatedAt = DateTime.UtcNow, CreatedBy = owner, UpdatedBy = owner,
        });
        await fixture.Game.SaveChangesAsync();

        var hidden = await fixture.Repository.GetProfileAsync(owner, viewer, includePrivate: false);
        var mine = await fixture.Repository.GetMyProfileAsync(owner);
        var mismatchedUser = Guid.NewGuid();
        fixture.Game.Users.Add(new UserEntity
        {
            Uuid = mismatchedUser, Mcid = "WrongOwner", JoinDate = DateTime.UtcNow, LastJoinDate = DateTime.UtcNow,
            GlobalIp = "127.0.0.1", AccountId = account, Permission = 0,
            CreatedAt = DateTime.UtcNow, UpdatedAt = DateTime.UtcNow, CreatedBy = mismatchedUser, UpdatedBy = mismatchedUser,
        });
        await fixture.Game.SaveChangesAsync();
        var mismatchedProfile = await fixture.Repository.GetMyProfileAsync(mismatchedUser);

        Assert.Null(hidden);
        Assert.NotNull(mine);
        Assert.Equal(1234, mine.CurrentAccount!.Gold);
        Assert.Equal(account, mine.CurrentAccount.AccountId);
        Assert.Empty(mine.CurrentAccount.SkillTree.Nodes); // Local files never substitute for a verified loaded generation.
        Assert.Equal(12, mine.CurrentAccount.ClassLevel);
        Assert.NotNull(mismatchedProfile);
        Assert.Null(mismatchedProfile.CurrentAccount);
    }

    [Fact]
    public async Task UpdateVisibilityAndSearchAsync_UseManagementWebAdminForPrivateRowsAndLevelSort()
    {
        await using var fixture = await Fixture.CreateAsync();
        var admin = Guid.NewGuid();
        var privatePlayer = Guid.NewGuid();
        var publicPlayer = Guid.NewGuid();
        await fixture.AddProfileAsync(admin, "Admin", Guid.NewGuid(), level: 1, isPublic: false, webAdmin: true);
        await fixture.AddProfileAsync(privatePlayer, "Private", Guid.NewGuid(), level: 90, isPublic: false);
        await fixture.AddProfileAsync(publicPlayer, "Public", Guid.NewGuid(), level: 10, isPublic: true);
        var gameOnlyPlayer = Guid.NewGuid();
        await fixture.AddGameOnlyProfileAsync(gameOnlyPlayer, "GameOnly", Guid.NewGuid(), level: 50);
        fixture.Management.Players.Add(new ManagementPlayerEntity
        {
            PlayerUuid = Guid.NewGuid(), Mcid = "GameMissing", WebAdmin = false, IsProfilePublic = false,
            CreatedAt = DateTime.UtcNow, UpdatedAt = DateTime.UtcNow,
        });
        await fixture.Management.SaveChangesAsync();

        var publicOnly = await fixture.Repository.SearchAsync(admin, null, null, "level_desc", 1, 20, includePrivate: false);
        var nonAdminAll = await fixture.Repository.SearchAsync(publicPlayer, null, null, "level_desc", 1, 20, includePrivate: true);
        var all = await fixture.Repository.SearchAsync(admin, null, null, "level_desc", 1, 20, includePrivate: true);
        var ascending = await fixture.Repository.SearchAsync(admin, null, "adventurer", "level_asc", 1, 20, includePrivate: true);
        var updated = await fixture.Repository.UpdateVisibilityAsync(privatePlayer, true);
        var nowPublic = await fixture.Repository.GetProfileAsync(privatePlayer, publicPlayer, includePrivate: false);

        Assert.Single(publicOnly.Profiles);
        Assert.Equal(publicPlayer, publicOnly.Profiles[0].UserUuid);
        Assert.Single(nonAdminAll.Profiles);
        Assert.Equal(4, all.TotalCount);
        Assert.Equal(privatePlayer, all.Profiles[0].UserUuid);
        Assert.Contains(all.Profiles, profile => profile.UserUuid == gameOnlyPlayer);
        Assert.Equal(admin, ascending.Profiles[0].UserUuid);
        Assert.Equal(4, ascending.TotalCount);
        Assert.NotNull(updated);
        Assert.True(updated.IsPublic);
        Assert.NotNull(nowPublic);
        Assert.Null(nowPublic.Permission);
        await fixture.Repository.UpdateVisibilityAsync(privatePlayer, false);
        Assert.Null(await fixture.Repository.GetProfileAsync(privatePlayer, publicPlayer, includePrivate: false));
        Assert.Null(await fixture.Repository.GetProfileAsync(privatePlayer, publicPlayer, includePrivate: true));
        Assert.NotNull(await fixture.Repository.GetProfileAsync(privatePlayer, admin, includePrivate: true));
        fixture.Management.Players.Single(player => player.PlayerUuid == admin).WebAdmin = false;
        await fixture.Management.SaveChangesAsync();
        Assert.Null(await fixture.Repository.GetProfileAsync(privatePlayer, admin, includePrivate: true));
    }

    [Theory]
    [InlineData("99a00002", 10L)]
    [InlineData("99a00004", 1000L)]
    [InlineData("99a00007", 1000000L)]
    [InlineData("gold_coin", 10L)]
    [InlineData("gold_block", 1000L)]
    [InlineData("yggdrasil_star_core", 1000000L)]
    public async Task Gold_ConvertsCanonicalAndLegacyDenominationsWithoutChangingInventory(string itemId, long value)
    {
        await using var fixture = await Fixture.CreateAsync();
        var owner = Guid.NewGuid();
        var account = Guid.NewGuid();
        await fixture.AddProfileAsync(owner, "Owner", account, 10, false);
        var inventoryId = Guid.NewGuid();
        fixture.Game.Inventories.Add(new InventoryEntity
        {
            InventoryId = inventoryId, AccountId = account, InventoryType = "CURRENCY", InventoryProfile = "GAME",
            IsEnabled = true, CreatedAt = DateTime.UtcNow, UpdatedAt = DateTime.UtcNow, CreatedBy = owner, UpdatedBy = owner,
        });
        foreach (var deleted in new[] { false, true })
            fixture.Game.InventoryEntries.Add(new InventoryEntryEntity
            {
                InventoryEntryId = Guid.NewGuid(), InventoryId = inventoryId, ItemCategory = "currency", ItemId = itemId,
                Quantity = 3, IsDeleted = deleted, CreatedAt = DateTime.UtcNow, UpdatedAt = DateTime.UtcNow, CreatedBy = owner, UpdatedBy = owner,
            });
        await fixture.Game.SaveChangesAsync();
        var result = await fixture.Repository.GetMyProfileAsync(owner);
        Assert.Equal(value * 3, result!.CurrentAccount!.Gold);
        Assert.Equal(2, await fixture.Game.InventoryEntries.CountAsync());
        Assert.All(fixture.Game.InventoryEntries, entry => Assert.Equal(3, entry.Quantity));
    }

    [Fact]
    public async Task Search_UsesCurrentMcidAndLightweightSummaryAndStablePaging()
    {
        await using var fixture = await Fixture.CreateAsync();
        var admin = Guid.NewGuid();
        var owner = Guid.NewGuid();
        await fixture.AddProfileAsync(admin, "Admin", Guid.NewGuid(), 10, false, webAdmin: true);
        await fixture.AddProfileAsync(owner, "OldName", Guid.NewGuid(), 20, true);
        fixture.Game.Users.Single(user => user.Uuid == owner).Mcid = "NewName";
        await fixture.Game.SaveChangesAsync();
        var old = await fixture.Repository.SearchAsync(admin, "OldName", null, "level_desc", 1, 20, true);
        var current = await fixture.Repository.SearchAsync(admin, "newname", null, "level_desc", 1, 20, true);
        Assert.Empty(old.Profiles);
        Assert.Equal("NewName", Assert.Single(current.Profiles).Mcid);
        var first = await fixture.Repository.SearchAsync(admin, null, null, "level_asc", 1, 1, true);
        var second = await fixture.Repository.SearchAsync(admin, null, null, "level_asc", 2, 1, true);
        Assert.Equal(admin, Assert.Single(first.Profiles).UserUuid);
        Assert.Equal(owner, Assert.Single(second.Profiles).UserUuid);
        Assert.Equal(2, first.TotalCount);
        var json = System.Text.Json.JsonSerializer.Serialize(current);
        Assert.DoesNotContain("SkillTree", json);
        Assert.DoesNotContain("Gold", json);
    }

    [Fact]
    public async Task AccountRowsAndDetailSelection_UseOnlyTheTargetUsersNonDeletedAccounts()
    {
        await using var fixture = await Fixture.CreateAsync();
        var owner = Guid.NewGuid();
        var currentAccount = Guid.NewGuid();
        var secondAccount = Guid.NewGuid();
        var otherUser = Guid.NewGuid();
        var otherAccount = Guid.NewGuid();
        await fixture.AddClassMasterAsync("adventurer", "冒険者");
        await fixture.AddProfileAsync(owner, "Owner", currentAccount, 10, true);
        await fixture.AddProfileAsync(otherUser, "Other", otherAccount, 20, true);
        var now = DateTime.UtcNow;
        fixture.Game.Accounts.Add(new AccountEntity
        {
            Uuid = secondAccount, UserId = owner, AccountName = "second", SlotIndex = 1, IsActive = false, Mode = 0,
            Level = 30, ClassId = "adventurer", ClassLevel = 30, CreatedAt = now, UpdatedAt = now, CreatedBy = owner, UpdatedBy = owner,
        });
        await fixture.Game.SaveChangesAsync();

        var listing = await fixture.Repository.SearchAsync(owner, "Owner", "adventurer", "level_desc", 1, 20, includePrivate: false);
        var selected = await fixture.Repository.GetProfileAsync(owner, owner, includePrivate: false, accountId: secondAccount);
        var forged = await fixture.Repository.GetProfileAsync(owner, owner, includePrivate: false, accountId: otherAccount);

        Assert.Equal(2, listing.TotalCount);
        Assert.Equal(secondAccount, listing.Profiles[0].Account.AccountId);
        Assert.Equal(currentAccount, listing.Profiles[1].Account.AccountId);
        Assert.NotNull(selected);
        Assert.Equal(secondAccount, selected.CurrentAccount!.AccountId);
        Assert.Equal(2, selected.Accounts.Count);
        Assert.Equal(0, selected.Accounts[0].SlotIndex);
        Assert.Equal(1, selected.Accounts[1].SlotIndex);
        Assert.Null(forged);
        Assert.Equal(currentAccount, fixture.Game.Users.Single(user => user.Uuid == owner).AccountId);
    }

    private sealed class Fixture : IAsyncDisposable
    {
        private readonly SqliteConnection gameConnection;
        private readonly SqliteConnection managementConnection;
        private readonly SqliteConnection masterConnection;
        private readonly string filebaseRoot;

        private Fixture(
            SqliteConnection gameConnection,
            SqliteConnection managementConnection,
            SqliteConnection masterConnection,
            AstralRecordDbContext game,
            ManagementDbContext management,
            MasterDataDbContext master,
            string filebaseRoot)
        {
            this.gameConnection = gameConnection;
            this.managementConnection = managementConnection;
            this.masterConnection = masterConnection;
            this.filebaseRoot = filebaseRoot;
            Game = game;
            Management = management;
            Master = master;
            Repository = new WebPlayerProfileRepository(game, management, master,
                Microsoft.Extensions.Options.Options.Create(new WebPlayerProfileOptions { SkillTreeStructureId = "starter" }));
        }

        public AstralRecordDbContext Game { get; }
        public ManagementDbContext Management { get; }
        public MasterDataDbContext Master { get; }
        public WebPlayerProfileRepository Repository { get; }

        public static async Task<Fixture> CreateAsync()
        {
            var gameConnection = new SqliteConnection("Data Source=:memory:");
            var managementConnection = new SqliteConnection("Data Source=:memory:");
            var masterConnection = new SqliteConnection("Data Source=:memory:");
            await gameConnection.OpenAsync();
            await managementConnection.OpenAsync();
            await masterConnection.OpenAsync();
            var game = new AstralRecordDbContext(new DbContextOptionsBuilder<AstralRecordDbContext>().UseSqlite(gameConnection).Options);
            var management = new ManagementDbContext(new DbContextOptionsBuilder<ManagementDbContext>().UseSqlite(managementConnection).Options);
            var master = new MasterDataDbContext(new DbContextOptionsBuilder<MasterDataDbContext>().UseSqlite(masterConnection).Options);
            await game.Database.EnsureCreatedAsync();
            await management.Database.EnsureCreatedAsync();
            await master.Database.EnsureCreatedAsync();
            var root = Path.Combine(Path.GetTempPath(), "AstralRecord-WebProfile-" + Guid.NewGuid());
            var result = new Fixture(gameConnection, managementConnection, masterConnection, game, management, master, root);
            Directory.CreateDirectory(Path.Combine(result.filebaseRoot, "35.features.skilltree", "structures"));
            Directory.CreateDirectory(Path.Combine(result.filebaseRoot, "35.features.skilltree", "nodes"));
            await File.WriteAllTextAsync(Path.Combine(result.filebaseRoot, "35.features.skilltree", "structures", "starter.json"), """
                {"structureId":"starter","name":"Test Tree","rootNodeId":"1000","nodes":[{"nodeId":"1000","x":0,"y":0,"z":0},{"nodeId":"1001","x":1,"y":0,"z":0},{"nodeId":"1002","x":2,"y":0,"z":0}],"edges":[{"sourceNodeId":"1000","targetNodeId":"1001"}]}
                """);
            await File.WriteAllTextAsync(Path.Combine(result.filebaseRoot, "35.features.skilltree", "nodes", "1000.json"), """
                {"nodeId":"1000","name":"&dTest Node","icon":"STONE","pointType":"PP","pointCost":0,"unlockCondition":{"classId":"adventurer","playerLevel":40},"effects":[{"type":"status","status":"STRENGTH","modifierType":"FLAT","value":1},{"type":"status","status":"MAX_HEALTH","modifierType":"SCALAR","value":0.1}]}
                """);
            await File.WriteAllTextAsync(Path.Combine(result.filebaseRoot, "35.features.skilltree", "nodes", "1001.json"), """
                {"nodeId":"1001","name":"Hidden Node","icon":"STONE","pointType":"PP","pointCost":0,"unlockCondition":{"playerLevel":50},"effects":[]}
                """);
            await File.WriteAllTextAsync(Path.Combine(result.filebaseRoot, "35.features.skilltree", "nodes", "1002.json"), """
                {"nodeId":"1002","name":"Other Class CP","icon":"STONE","pointType":"CP","pointCost":1,"unlockCondition":{"classId":"mage"},"effects":[]}
                """);
            return result;
        }

        public async Task AddProfileAsync(Guid userId, string mcid, Guid accountId, int level, bool isPublic, bool webAdmin = false, string classId = "adventurer")
        {
            var now = DateTime.UtcNow;
            Management.Players.Add(new ManagementPlayerEntity { PlayerUuid = userId, Mcid = mcid, WebAdmin = webAdmin, IsProfilePublic = isPublic, CreatedAt = now, UpdatedAt = now });
            Game.Users.Add(new UserEntity { Uuid = userId, Mcid = mcid, JoinDate = now, LastJoinDate = now, GlobalIp = "127.0.0.1", AccountId = accountId, Permission = 0, CreatedAt = now, UpdatedAt = now, CreatedBy = userId, UpdatedBy = userId });
            Game.Accounts.Add(new AccountEntity { Uuid = accountId, UserId = userId, AccountName = "main", SlotIndex = 0, IsActive = true, Mode = 0, Level = level, ClassId = classId, ClassLevel = level, CreatedAt = now, UpdatedAt = now, CreatedBy = userId, UpdatedBy = userId });
            await Management.SaveChangesAsync();
            await Game.SaveChangesAsync();
        }

        public async Task AddGameOnlyProfileAsync(Guid userId, string mcid, Guid accountId, int level)
        {
            var now = DateTime.UtcNow;
            Game.Users.Add(new UserEntity { Uuid = userId, Mcid = mcid, JoinDate = now, LastJoinDate = now, GlobalIp = "127.0.0.1", AccountId = accountId, Permission = 0, CreatedAt = now, UpdatedAt = now, CreatedBy = userId, UpdatedBy = userId });
            Game.Accounts.Add(new AccountEntity { Uuid = accountId, UserId = userId, AccountName = "main", SlotIndex = 0, IsActive = true, Mode = 0, Level = level, ClassId = "adventurer", ClassLevel = level, CreatedAt = now, UpdatedAt = now, CreatedBy = userId, UpdatedBy = userId });
            await Game.SaveChangesAsync();
        }

        public async Task AddClassMasterAsync(string id, string name, string? parentId = null)
        {
            var now = DateTime.UtcNow;
            var parentJson = parentId is null ? "[]" : $"[{{\"class\":\"{parentId}\",\"level\":1}}]";
            Master.Entries.Add(new MasterDataEntryEntity
            {
                EntryId = Guid.NewGuid(), SourceId = Guid.NewGuid(), MasterType = "class", MasterId = id, SchemaVersion = 1,
                DisplayName = name, SourceFilePath = id + ".yml", SourceFileHash = new string('0', 64), PayloadVersion = 1,
                PayloadJson = $"{{\"schemaVersion\":1,\"id\":\"{id}\",\"type\":\"CLASS\",\"name\":\"{name}\",\"order\":1,\"shortName\":\"{id}\",\"role\":\"TEST\",\"unlockClassLevel\":{parentJson},\"baseStats\":[]}}",
                EffectiveFrom = now, CreatedAt = now, UpdatedAt = now,
            });
            await Master.SaveChangesAsync();
        }

        public async ValueTask DisposeAsync()
        {
            await Game.DisposeAsync();
            await Management.DisposeAsync();
            await Master.DisposeAsync();
            await gameConnection.DisposeAsync();
            await managementConnection.DisposeAsync();
            await masterConnection.DisposeAsync();
            if (Directory.Exists(filebaseRoot)) Directory.Delete(filebaseRoot, recursive: true);
        }
    }
}
