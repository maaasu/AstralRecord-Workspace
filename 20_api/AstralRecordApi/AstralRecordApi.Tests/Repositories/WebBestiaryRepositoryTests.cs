using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed class WebBestiaryRepositoryTests
{
    [Theory]
    [InlineData("\"visible_item\"")]
    [InlineData("\"item:visible_item\"")]
    [InlineData("{\"ref\":\"item:visible_item\"}")]
    [InlineData("{\"ref\":\" ITEM:visible_item \"}")]
    public async Task Detail_UsesOwnedAccountAndStandardLevel_WithoutHiddenDrops(string itemReferenceJson)
    {
        await using var fixture = await Fixture.CreateAsync();
        var userId = Guid.NewGuid();
        var accountId = Guid.NewGuid();
        var otherAccountId = Guid.NewGuid();
        await fixture.AddAccountAsync(userId, accountId, "main", active: true);
        await fixture.AddAccountAsync(userId, otherAccountId, "sub", active: false);
        await fixture.AddRecordAsync(accountId, "test_mob", 3);
        await fixture.AddMobAsync(itemReferenceJson);
        await fixture.AddItemAsync("visible_item", "&a表示アイテム", "EMERALD");

        var list = await fixture.Repository.GetListAsync(userId, null);
        var detail = await fixture.Repository.GetDetailAsync(userId, null, "test_mob");
        var forged = await fixture.Repository.GetDetailAsync(userId, Guid.NewGuid(), "test_mob");
        var unrecorded = await fixture.Repository.GetDetailAsync(userId, otherAccountId, "test_mob");

        Assert.NotNull(list);
        Assert.Equal(3, list.TotalDefeats);
        Assert.Equal(2, list.Accounts.Count);
        Assert.Equal(5, Assert.Single(list.Mobs).Level);
        Assert.NotNull(detail);
        Assert.Equal(5, detail.Mob.Level);
        Assert.Equal("ZOMBIE", detail.Mob.EntityType);
        Assert.Equal("最大HP", Assert.Single(detail.Mob.BaseStats).DisplayName);
        Assert.Equal("1200", detail.Mob.BaseStats[0].DisplayValue);
        Assert.Equal("visible_item", Assert.Single(detail.Mob.Drops.Items).ItemId);
        Assert.Equal("表示アイテム", detail.Mob.Drops.Items[0].Name);
        Assert.Equal("EMERALD", detail.Mob.Drops.Items[0].Icon);
        Assert.Equal(20, detail.Mob.Drops.Exp);
        Assert.NotNull(detail.Mob.Drops.Money);
        Assert.Equal(2, detail.Mob.Drops.Money.Min);
        Assert.Equal(4, detail.Mob.Drops.Money.Max);
        Assert.True(detail.Mob.Drops.HasAdditionalDrops);
        Assert.Null(forged);
        Assert.Null(unrecorded);
    }

    [Theory]
    [InlineData("\"missing_item\"", "missing_item")]
    [InlineData("{\"ref\":\"item:missing_item\"}", "missing_item")]
    [InlineData("{\"ref\":\"mob.enemy:visible_item\"}", "mob.enemy:visible_item")]
    public async Task Detail_UnresolvedOrWrongTypeReference_RemainsUnregistered(string reference, string expectedId)
    {
        await using var fixture = await Fixture.CreateAsync();
        var user = Guid.NewGuid();
        var account = Guid.NewGuid();
        await fixture.AddAccountAsync(user, account, "main", active: true);
        await fixture.AddRecordAsync(account, "test_mob", 1);
        await fixture.AddMobAsync(reference);
        await fixture.AddItemAsync("visible_item", "登録済み素材", "EMERALD");

        var detail = await fixture.Repository.GetDetailAsync(user, null, "test_mob");

        var drop = Assert.Single(detail!.Mob.Drops.Items);
        Assert.Equal(expectedId, drop.ItemId);
        Assert.Equal("未登録のアイテム", drop.Name);
        Assert.Equal("BARRIER", drop.Icon);
    }

    [Fact]
    public async Task Detail_DeletedItem_IsNotExposedAsRegisteredDrop()
    {
        await using var fixture = await Fixture.CreateAsync();
        var user = Guid.NewGuid();
        var account = Guid.NewGuid();
        await fixture.AddAccountAsync(user, account, "main", active: true);
        await fixture.AddRecordAsync(account, "test_mob", 1);
        await fixture.AddMobAsync("{\"ref\":\"item:visible_item\"}");
        await fixture.AddItemAsync("visible_item", "削除済み素材", "EMERALD");
        (await fixture.Master.Entries.SingleAsync(entry => entry.MasterType == "item")).IsDeleted = true;
        await fixture.Master.SaveChangesAsync();

        var detail = await fixture.Repository.GetDetailAsync(user, null, "test_mob");

        Assert.Equal("未登録のアイテム", Assert.Single(detail!.Mob.Drops.Items).Name);
    }

    private sealed class Fixture : IAsyncDisposable
    {
        private readonly SqliteConnection gameConnection;
        private readonly SqliteConnection masterConnection;
        private Fixture(SqliteConnection gameConnection, SqliteConnection masterConnection, AstralRecordDbContext game, MasterDataDbContext master)
        {
            this.gameConnection = gameConnection; this.masterConnection = masterConnection; Game = game; Master = master;
            Repository = new WebBestiaryRepository(game, master);
        }
        public AstralRecordDbContext Game { get; }
        public MasterDataDbContext Master { get; }
        public WebBestiaryRepository Repository { get; }
        public static async Task<Fixture> CreateAsync()
        {
            var gameConnection = new SqliteConnection("Data Source=:memory:");
            var masterConnection = new SqliteConnection("Data Source=:memory:");
            await gameConnection.OpenAsync(); await masterConnection.OpenAsync();
            var game = new AstralRecordDbContext(new DbContextOptionsBuilder<AstralRecordDbContext>().UseSqlite(gameConnection).Options);
            var master = new MasterDataDbContext(new DbContextOptionsBuilder<MasterDataDbContext>().UseSqlite(masterConnection).Options);
            await game.Database.EnsureCreatedAsync(); await master.Database.EnsureCreatedAsync();
            return new Fixture(gameConnection, masterConnection, game, master);
        }
        public async Task AddAccountAsync(Guid userId, Guid accountId, string name, bool active)
        {
            var now = DateTime.UtcNow;
            if (!await Game.Users.AnyAsync(user => user.Uuid == userId))
                Game.Users.Add(new UserEntity { Uuid = userId, Mcid = "Tester", GlobalIp = "127.0.0.1", AccountId = accountId, JoinDate = now, LastJoinDate = now, CreatedAt = now, UpdatedAt = now, CreatedBy = userId, UpdatedBy = userId });
            Game.Accounts.Add(new AccountEntity { Uuid = accountId, UserId = userId, AccountName = name, SlotIndex = active ? 0 : 1, IsActive = active, CreatedAt = now, UpdatedAt = now, CreatedBy = userId, UpdatedBy = userId });
            await Game.SaveChangesAsync();
        }
        public async Task AddRecordAsync(Guid accountId, string mobId, long count)
        {
            var now = DateTime.UtcNow;
            Game.AccountMobRecords.Add(new AccountMobRecordEntity { AccountMobRecordId = Guid.NewGuid(), AccountId = accountId, MobId = mobId, MobCategory = "ENEMY", DefeatCount = count, FirstDefeatedAt = now, LastDefeatedAt = now, CreatedAt = now, UpdatedAt = now, CreatedBy = accountId, UpdatedBy = accountId });
            await Game.SaveChangesAsync();
        }
        public async Task AddMobAsync(string itemReferenceJson)
        {
            var now = DateTime.UtcNow;
            Master.Entries.Add(new MasterDataEntryEntity { EntryId = Guid.NewGuid(), SourceId = Guid.NewGuid(), MasterType = "mob.enemy", MasterId = "test_mob", SchemaVersion = 1, SourceFilePath = "test.yml", SourceFileHash = new string('0', 64), PayloadVersion = 1, EffectiveFrom = now, CreatedAt = now, UpdatedAt = now, PayloadJson = """{"schemaVersion":1,"id":"test_mob","type":"MOB","category":"ENEMY","name":"&aテストモブ","level":1,"entityType":"ZOMBIE","baseStats":[{"status":"MAX_HEALTH","value":100}],"drops":{"exp":10,"money":{"min":2,"max":4},"items":[{"itemId":"visible_item","rate":50,"amount":"1","hidden":false},{"itemId":"secret_item","rate":1,"amount":"1","hidden":true}],"lootTable":"secret_table"},"levels":[{"level":5,"entityType":"CREEPER","baseStats":[{"status":"MAX_HEALTH","value":1200}],"drops":{"exp":20}}]}""" });
            var mob = Master.ChangeTracker.Entries<MasterDataEntryEntity>().Single(entry => entry.Entity.MasterType == "mob.enemy").Entity;
            mob.PayloadJson = mob.PayloadJson.Replace("\"visible_item\"", itemReferenceJson, StringComparison.Ordinal);
            await Master.SaveChangesAsync();
        }
        public async Task AddItemAsync(string id, string name, string icon)
        {
            var now = DateTime.UtcNow;
            Master.Entries.Add(new MasterDataEntryEntity { EntryId = Guid.NewGuid(), SourceId = Guid.NewGuid(), MasterType = "item", MasterId = id, SchemaVersion = 1, SourceFilePath = id + ".yml", SourceFileHash = new string('0', 64), PayloadVersion = 1, EffectiveFrom = now, CreatedAt = now, UpdatedAt = now, PayloadJson = $"{{\"schemaVersion\":1,\"id\":\"{id}\",\"category\":\"material\",\"name\":\"{name}\",\"icon\":\"{icon}\",\"rarity\":\"COMMON\"}}" });
            await Master.SaveChangesAsync();
        }
        public async ValueTask DisposeAsync() { await Game.DisposeAsync(); await Master.DisposeAsync(); await gameConnection.DisposeAsync(); await masterConnection.DisposeAsync(); }
    }
}
