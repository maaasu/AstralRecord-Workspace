using System.Security.Cryptography;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed class PlayerAdminEditRepositoryTests
{
    [Fact]
    public async Task StartPersistsUserWideLockAndOnlyReplaysSameSessionIdentity()
    {
        await using var fixture = await Fixture.CreateAsync();
        var id = Guid.NewGuid();
        var started = await fixture.Repository.StartAsync(fixture.AccountId, fixture.Actor,
            new(id, " maintenance "));

        Assert.Equal(201, started.StatusCode);
        Assert.Equal("DRAINING", started.Value!.Status);
        Assert.Equal(3, started.Value.ExpectedServerCount);
        Assert.True(await fixture.Repository.IsUserLockedAsync(fixture.UserId));
        Assert.Equal(200, (await fixture.Repository.StartAsync(fixture.AccountId, fixture.Actor,
            new(id, "maintenance"))).StatusCode);
        Assert.Equal(409, (await fixture.Repository.StartAsync(fixture.OtherAccountId, fixture.Actor,
            new(id, "maintenance"))).StatusCode);
        Assert.Equal(409, (await fixture.Repository.StartAsync(fixture.AccountId, fixture.Actor,
            new(id, "different reason"))).StatusCode);
        Assert.Equal(409, (await fixture.Repository.StartAsync(fixture.AccountId, Guid.NewGuid(),
            new(id, "maintenance"))).StatusCode);
        Assert.Equal(409, (await fixture.Repository.StartAsync(fixture.OtherActiveAccountId, fixture.Actor,
            new(Guid.NewGuid(), "maintenance"))).StatusCode);
        Assert.Equal(403, (await fixture.Repository.GetByIdAsync(id, Guid.NewGuid())).StatusCode);
        Assert.Equal(1, await fixture.Db.PlayerAdminEditSessions.CountAsync());
        Assert.Equal(3, await fixture.Db.PlayerAdminEditDrains.CountAsync());
    }

    [Fact]
    public async Task DrainRequiresSavedOfflineAckAndClosedSessionsForEveryUserAccount()
    {
        await using var fixture = await Fixture.CreateAsync();
        var sessionId = await fixture.StartAsync();
        fixture.Db.SkillTreeAccountSessions.Add(new SkillTreeAccountSessionEntity
        {
            AccountSessionId = Guid.NewGuid(), AccountId = fixture.OtherAccountId,
            ServerId = "rpg", ServerSessionId = fixture.Boots["rpg"],
            DefinitionGenerationId = "generation", LeaseTokenHash = "hash",
            CreatedAtUtc = DateTime.UtcNow, ExpiresAtUtc = DateTime.UtcNow.AddHours(1),
            Closed = false,
        });
        await fixture.Db.SaveChangesAsync();

        var early = await fixture.Repository.AcknowledgeDrainAsync(sessionId, fixture.Ack("lobby"));
        Assert.Equal(409, early.StatusCode);
        Assert.Equal(400, (await fixture.Repository.AcknowledgeDrainAsync(sessionId,
            fixture.Ack("rpg") with { Saved = false })).StatusCode);
        Assert.Equal(409, (await fixture.Repository.AcknowledgeDrainAsync(sessionId,
            fixture.Ack("rpg"))).StatusCode);
        Assert.Equal("DRAINING", (await fixture.Repository.RefreshAsync(sessionId, fixture.Actor)).Value!.Status);

        var gameplaySession = await fixture.Db.SkillTreeAccountSessions.SingleAsync();
        gameplaySession.Closed = true;
        await fixture.Db.SaveChangesAsync();
        var rpgAck = fixture.Ack("rpg");
        Assert.Equal("DRAINING", (await fixture.Repository.AcknowledgeDrainAsync(sessionId, rpgAck)).Value!.Status);
        Assert.Equal(200, (await fixture.Repository.AcknowledgeDrainAsync(sessionId, rpgAck)).StatusCode);
        Assert.Equal(409, (await fixture.Repository.AcknowledgeDrainAsync(sessionId,
            rpgAck with { AckId = Guid.NewGuid() })).StatusCode);
        Assert.Equal("DRAINING", (await fixture.Repository.AcknowledgeDrainAsync(sessionId,
            fixture.Ack("lobby"))).Value!.Status);
        Assert.Equal("READY", (await fixture.Repository.AcknowledgeDrainAsync(sessionId,
            fixture.Ack("proxy"))).Value!.Status);
        Assert.Equal(3, await fixture.Db.PlayerAdminEditDrains.CountAsync(x => x.AcknowledgedAtUtc != null));
    }

    [Fact]
    public async Task ChangedServerBootAndExpiredUnreadySessionFailClosed()
    {
        await using var fixture = await Fixture.CreateAsync();
        var sessionId = await fixture.StartAsync();
        Assert.Equal(409, (await fixture.Repository.RegisterServerAsync("rpg",
            new(Guid.NewGuid(), "RPG", fixture.ItemHash, fixture.ClassHash))).StatusCode);
        Assert.Equal(409, (await fixture.Repository.AcknowledgeDrainAsync(sessionId,
            fixture.Ack("rpg") with { ServerSessionId = Guid.NewGuid() })).StatusCode);
        Assert.Equal(409, (await fixture.Repository.GetDrainsAsync("rpg", Guid.NewGuid())).StatusCode);
        var session = await fixture.Db.PlayerAdminEditSessions.SingleAsync();
        session.ExpiresAtUtc = DateTime.UtcNow.AddSeconds(-1);
        await fixture.Db.SaveChangesAsync();
        Assert.Equal("RECOVERY_REQUIRED", (await fixture.Repository.RefreshAsync(sessionId, fixture.Actor)).Value!.Status);
        Assert.True(await fixture.Repository.IsUserLockedAsync(fixture.UserId));
        Assert.Equal(409, (await fixture.Repository.GetEditorAsync(sessionId, fixture.Actor)).StatusCode);
    }

    [Fact]
    public async Task ExistingRpgServerCannotBeRecastAsLobbyDuringItsBoot()
    {
        await using var fixture = await Fixture.CreateAsync();
        Assert.Equal(409, (await fixture.Repository.RegisterServerAsync("rpg",
            new(fixture.Boots["rpg"], "LOBBY", null, null))).StatusCode);
        Assert.Equal(409, (await fixture.Repository.RegisterServerAsync("rpg",
            new(Guid.NewGuid(), "LOBBY", null, null))).StatusCode);
        var persisted = await fixture.Db.PlayerAdminServerRuntimes.AsNoTracking()
            .SingleAsync(x => x.ServerId == "rpg");
        Assert.Equal("RPG", persisted.Role);
        Assert.Equal(fixture.Boots["rpg"], persisted.ServerSessionId);
    }

    [Fact]
    public async Task CatalogDriftAndUnreadyStateDoNotCreateEditableSessionOrReceipt()
    {
        await using var fixture = await Fixture.CreateAsync();
        var rpg = await fixture.Db.PlayerAdminServerRuntimes.SingleAsync(x => x.ServerId == "rpg");
        rpg.ItemCatalogHash = new string('0', 64);
        await fixture.Db.SaveChangesAsync();
        Assert.Equal(409, (await fixture.Repository.StartAsync(fixture.AccountId, fixture.Actor,
            new(Guid.NewGuid(), "maintenance"))).StatusCode);
        Assert.Empty(await fixture.Db.PlayerAdminEditSessions.ToListAsync());
        rpg = await fixture.Db.PlayerAdminServerRuntimes.SingleAsync(x => x.ServerId == "rpg");
        rpg.ItemCatalogHash = fixture.ItemHash;
        await fixture.Db.SaveChangesAsync();

        var sessionId = await fixture.StartAsync();
        Assert.Equal(409, (await fixture.Repository.GetEditorAsync(sessionId, fixture.Actor)).StatusCode);
        var apply = new PlayerAdminEditOperationRequest(Guid.NewGuid(), 1, new string('a', 64),
            fixture.ItemHash, 50, null, []);
        Assert.Equal(409, (await fixture.Repository.ApplyAsync(sessionId, fixture.Actor, apply)).StatusCode);
        Assert.Empty(await fixture.Db.PlayerAdminEditOperations.ToListAsync());
        Assert.Equal(1, (await fixture.Db.Accounts.AsNoTracking()
            .SingleAsync(x => x.Uuid == fixture.AccountId)).Level);
    }

    [Fact]
    public async Task ApplyFailureRollsBackLevelClassAndPartiallyFilledInventory()
    {
        await using var fixture = await Fixture.CreateAsync();
        var sessionId = await fixture.StartAndDrainAsync();
        var editor = (await fixture.Repository.GetEditorAsync(sessionId, fixture.Actor)).Value!;
        var request = fixture.Apply(editor, 50, "mage",
        [new("GRANT", "iron", null, 2, null), new("GRANT", "sword", null, 1, null)]);

        var result = await fixture.Repository.ApplyAsync(sessionId, fixture.Actor, request);

        Assert.Equal(409, result.StatusCode);
        fixture.Db.ChangeTracker.Clear();
        var account = await fixture.Db.Accounts.SingleAsync(x => x.Uuid == fixture.AccountId);
        Assert.Equal(1, account.Level);
        Assert.Equal(0, account.TotalExperience);
        Assert.Equal("adventurer", account.ClassId);
        Assert.Equal(1, account.ProgressVersion);
        Assert.Equal(63, (await fixture.Db.InventoryEntries.SingleAsync()).Quantity);
        Assert.Empty(await fixture.Db.EquipmentInstances.ToListAsync());
        Assert.Empty(await fixture.Db.PlayerAdminEditOperations.ToListAsync());
        Assert.Equal("READY", (await fixture.Repository.GetByIdAsync(sessionId, fixture.Actor)).Value!.Status);
    }

    [Fact]
    public async Task ApplyReceiptReplayKeepsGrantedEquipmentIdsAndAtomicLevelClassProgress()
    {
        await using var fixture = await Fixture.CreateAsync(bagCapacity: 3);
        var sessionId = await fixture.StartAndDrainAsync();
        var editor = (await fixture.Repository.GetEditorAsync(sessionId, fixture.Actor)).Value!;
        var request = fixture.Apply(editor, 50, "mage", [new("GRANT", "sword", null, 1, null)]);

        var first = await fixture.Repository.ApplyAsync(sessionId, fixture.Actor, request);
        Assert.Equal(200, first.StatusCode);
        Assert.Equal("COMPLETED", first.Value!.Status);
        var entry = await fixture.Db.InventoryEntries.AsNoTracking().SingleAsync(x => x.InstanceType == "EQUIPMENT");
        var instance = await fixture.Db.EquipmentInstances.AsNoTracking().SingleAsync();
        Assert.Equal(instance.EquipmentInstanceId, entry.InstanceId);
        Assert.Equal(fixture.AccountId, instance.AccountId);
        Assert.Equal(1, entry.Quantity);
        var account = await fixture.Db.Accounts.AsNoTracking().SingleAsync(x => x.Uuid == fixture.AccountId);
        Assert.Equal(50, account.Level);
        Assert.Equal(4_993_486, account.TotalExperience);
        Assert.Equal(50, account.HighestLevel);
        Assert.Equal("mage", account.ClassId);
        Assert.Equal(7, account.ClassLevel);
        Assert.Equal(500, account.ClassExperience);
        Assert.Equal(3, account.ProgressVersion);
        Assert.Equal(1, await fixture.Db.PlayerAdminEditOperations.CountAsync());

        var replay = await fixture.Repository.ApplyAsync(sessionId, fixture.Actor, request);
        Assert.Equal(200, replay.StatusCode);
        Assert.Equal(first.Value, replay.Value);
        Assert.Equal(409, (await fixture.Repository.ApplyAsync(sessionId, fixture.Actor,
            request with { Level = 49 })).StatusCode);
        Assert.Equal(1, await fixture.Db.EquipmentInstances.CountAsync());
        Assert.Equal(1, await fixture.Db.InventoryEntries.CountAsync(x => x.InstanceType == "EQUIPMENT"));
        Assert.Equal(entry.InventoryEntryId, (await fixture.Db.InventoryEntries.AsNoTracking()
            .SingleAsync(x => x.InstanceType == "EQUIPMENT")).InventoryEntryId);
    }

    private sealed class Fixture : IAsyncDisposable
    {
        private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
        {
            Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        };
        private readonly SqliteConnection _connection;
        public AstralRecordDbContext Db { get; }
        public PlayerAdminEditRepository Repository { get; }
        public Guid UserId { get; } = Guid.NewGuid();
        public Guid AccountId { get; } = Guid.Parse("00000000-0000-0000-0000-000000000001");
        public Guid OtherAccountId { get; } = Guid.NewGuid();
        public Guid OtherActiveAccountId { get; } = Guid.NewGuid();
        public Guid Actor { get; } = Guid.NewGuid();
        public Guid InventoryId { get; } = Guid.NewGuid();
        public Dictionary<string, Guid> Boots { get; } = new()
        {
            ["rpg"] = Guid.NewGuid(), ["lobby"] = Guid.NewGuid(), ["proxy"] = Guid.NewGuid(),
        };
        public string ItemHash { get; }
        public string ClassHash { get; }

        private Fixture(SqliteConnection connection, AstralRecordDbContext db, int bagCapacity)
        {
            _connection = connection;
            Db = db;
            var items = new ItemCatalog();
            var classes = new ClassCatalog();
            ItemHash = CatalogHash(items.GetAllSummaries().Select(x => (x.Id, (object)items.GetById(x.Id)!)));
            ClassHash = CatalogHash(classes.GetAllSummaries().Select(x => (x.Id, (object)classes.GetById(x.Id)!)));
            var config = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
            {
                ["PlayerAdmin:RequiredServerIds:0"] = "rpg",
                ["PlayerAdmin:RequiredServerIds:1"] = "lobby",
                ["PlayerAdmin:RequiredServerIds:2"] = "proxy",
            }).Build();
            Repository = new PlayerAdminEditRepository(db, items, classes, config);
            BagCapacity = bagCapacity;
        }

        private int BagCapacity { get; }

        public static async Task<Fixture> CreateAsync(int bagCapacity = 2)
        {
            var connection = new SqliteConnection("Data Source=:memory:");
            await connection.OpenAsync();
            var options = new DbContextOptionsBuilder<AstralRecordDbContext>().UseSqlite(connection).Options;
            var db = new AstralRecordDbContext(options);
            await db.Database.EnsureCreatedAsync();
            var fixture = new Fixture(connection, db, bagCapacity);
            await fixture.SeedAsync();
            return fixture;
        }

        private async Task SeedAsync()
        {
            var now = DateTime.UtcNow;
            Db.Users.Add(new UserEntity { Uuid = UserId, Mcid = "TestPlayer",
                JoinDate = now, LastJoinDate = now, CreatedAt = now, UpdatedAt = now,
                CreatedBy = Actor, UpdatedBy = Actor });
            Db.Accounts.AddRange(
                new AccountEntity { Uuid = AccountId, UserId = UserId, AccountName = "Main",
                    SlotIndex = 1, Level = 1, ClassId = "adventurer", CreatedAt = now,
                    UpdatedAt = now, CreatedBy = Actor, UpdatedBy = Actor },
                new AccountEntity { Uuid = OtherAccountId, UserId = UserId, AccountName = "Old",
                    SlotIndex = 2, IsDeleted = true, CreatedAt = now, UpdatedAt = now,
                    CreatedBy = Actor, UpdatedBy = Actor },
                new AccountEntity { Uuid = OtherActiveAccountId, UserId = UserId, AccountName = "Alt",
                    SlotIndex = 3, CreatedAt = now, UpdatedAt = now,
                    CreatedBy = Actor, UpdatedBy = Actor });
            Db.AccountClassProgresses.Add(new AccountClassProgressEntity
            {
                AccountId = AccountId, ClassId = "mage", Level = 7,
                Experience = 500, UpdatedAt = now, UpdatedBy = Actor,
            });
            Db.Inventories.Add(new InventoryEntity
            {
                InventoryId = InventoryId, AccountId = AccountId, InventoryProfile = "GAME",
                InventoryType = "BAG", SlotCapacity = BagCapacity, IsEnabled = true,
                CreatedAt = now, UpdatedAt = now, CreatedBy = Actor, UpdatedBy = Actor,
            });
            Db.InventoryEntries.Add(new InventoryEntryEntity
            {
                InventoryEntryId = Guid.NewGuid(), InventoryId = InventoryId, SlotIndex = 1,
                ItemCategory = "material", ItemId = "iron", Quantity = 63,
                CreatedAt = now, UpdatedAt = now, CreatedBy = Actor, UpdatedBy = Actor,
            });
            foreach (var (serverId, boot) in Boots)
                Db.PlayerAdminServerRuntimes.Add(new PlayerAdminServerRuntimeEntity
                {
                    ServerId = serverId, ServerSessionId = boot,
                    Role = serverId == "rpg" ? "RPG" : serverId == "lobby" ? "LOBBY" : "PROXY",
                    ItemCatalogHash = serverId == "rpg" ? ItemHash : null,
                    ClassCatalogHash = serverId == "rpg" ? ClassHash : null,
                    Enabled = true, RegisteredAtUtc = now, LastSeenUtc = now,
                });
            await Db.SaveChangesAsync();
        }

        public async Task<Guid> StartAsync()
        {
            var id = Guid.NewGuid();
            Assert.Equal(201, (await Repository.StartAsync(AccountId, Actor,
                new(id, "offline maintenance"))).StatusCode);
            return id;
        }

        public async Task<Guid> StartAndDrainAsync()
        {
            var id = await StartAsync();
            Assert.Equal(200, (await Repository.AcknowledgeDrainAsync(id, Ack("rpg"))).StatusCode);
            Assert.Equal(200, (await Repository.AcknowledgeDrainAsync(id, Ack("lobby"))).StatusCode);
            Assert.Equal("READY", (await Repository.AcknowledgeDrainAsync(id, Ack("proxy"))).Value!.Status);
            return id;
        }

        public PlayerAdminDrainAckRequest Ack(string serverId) =>
            new(serverId, Boots[serverId], AccountId, UserId, true, true, Guid.NewGuid());

        public PlayerAdminEditOperationRequest Apply(PlayerAdminEditorResponse editor, int? level,
            string? classId, IReadOnlyList<PlayerAdminInventoryChange> changes) =>
            new(Guid.NewGuid(), editor.Session.Revision, editor.ExpectedStateHash,
                editor.CatalogVersion, level, classId, changes);

        private static string CatalogHash(IEnumerable<(string Id, object Value)> values)
        {
            var text = new StringBuilder();
            foreach (var (id, value) in values.OrderBy(x => x.Id, StringComparer.Ordinal))
                text.Append(id).Append('\t').Append(Hash(JsonSerializer.Serialize(value, value.GetType(), JsonOptions))).Append('\n');
            return Hash(text.ToString());
        }

        private static string Hash(string text) => Convert.ToHexString(
            SHA256.HashData(Encoding.UTF8.GetBytes(text))).ToLowerInvariant();

        public async ValueTask DisposeAsync()
        {
            await Db.DisposeAsync();
            await _connection.DisposeAsync();
        }
    }

    private sealed class ItemCatalog : IItemRepository
    {
        private static readonly ItemResponse[] Items =
        [
            new() { SchemaVersion = 1, Id = "iron", Category = "material", Name = "Iron",
                Icon = "IRON_INGOT", Rarity = "common", MaxStack = 64 },
            new() { SchemaVersion = 1, Id = "sword", Category = "equipment", Name = "Sword",
                Icon = "IRON_SWORD", Rarity = "common", MaxStack = 1,
                Equipment = new ItemEquipmentResponse { Slot = "WEAPON",
                    Durability = new ItemEquipmentDurabilityResponse { Max = 20 },
                    Stats = [new ItemEquipmentStatResponse { Status = "ATTACK",
                        Value = new ItemEquipmentStatValueResponse { Min = "2", Max = "4" } }] } },
        ];
        public IReadOnlyList<ItemSummaryResponse> GetAllSummaries() => Items.Select(x =>
            new ItemSummaryResponse { Id = x.Id, Category = x.Category, Name = x.Name }).ToArray();
        public ItemResponse? GetById(string itemId) => Items.SingleOrDefault(x => x.Id == itemId);
    }

    private sealed class ClassCatalog : IClassRepository
    {
        private static readonly ClassResponse[] Classes = [MakeClass("adventurer"), MakeClass("mage")];
        public IReadOnlyList<ClassSummaryResponse> GetAllSummaries() => Classes.Select(x =>
            new ClassSummaryResponse { Id = x.Id, Name = x.Name, Order = x.Order,
                ShortName = x.ShortName, Role = x.Role }).ToArray();
        public ClassResponse? GetById(string classId) => Classes.SingleOrDefault(x => x.Id == classId);
        private static ClassResponse MakeClass(string id) => new()
        {
            SchemaVersion = 1, Id = id, Type = "class", Name = id,
            Order = id == "adventurer" ? 1 : 2, ShortName = id,
            Role = "DAMAGE", BaseStats = [],
        };
    }
}
