using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using System.Security.Cryptography;
using System.Text;
using Microsoft.Data.SqlClient;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Caching.Memory;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed class PaidServicesRepositoryTests
{
    [Fact]
    public async Task PendingShopSettlement_RequiresCapturedRuntimeProofWhileEditing_AndReplaysTerminalReceipt()
    {
        await using var f = await Fixture.CreateAsync();
        f.SeedOffer("30a00007", 500, "INSTANCE_PRIORITY", 5);
        await f.Master.SaveChangesAsync();
        var proof = await f.AddRuntimeSessionAsync();
        var repo = new AstraldShopRepository(f.Db, f.Master, f.Network, f.Items, TimeProvider.System);
        var operationId = Guid.NewGuid();
        Assert.Equal("PENDING", (await repo.CreateAsync(f.UserId,
            new(operationId, f.AccountId, "30a00007", 500, null))).Status);
        await f.StartEditAsync();
        Assert.Null(await repo.ProcessAsync(operationId, new(f.AccountId, true)));
        Assert.Null(await repo.ProcessAsync(operationId, proof with { AccountLeaseToken = new string('0', 64) }));
        Assert.Equal("PENDING", (await f.Db.AstraldShopPurchases.AsNoTracking().SingleAsync()).Status);
        Assert.Equal("COMPLETED", (await repo.ProcessAsync(operationId, proof))!.Status);
        await f.Db.PlayerAdminEditSessions.ExecuteUpdateAsync(x => x.SetProperty(e => e.Status, "READY"));
        Assert.Equal("COMPLETED", (await repo.ProcessAsync(operationId, new(f.AccountId, true)))!.Status);
        Assert.Equal(500, (await f.Db.InventoryEntries.SingleAsync(x => x.ItemId == "99a00021")).Quantity);
    }

    [Fact]
    public async Task PendingMailSettlement_RequiresCapturedRuntimeProofAndRejectsReady()
    {
        await using var f = await Fixture.CreateAsync();
        f.SeedMail("proof-mail", mixed: false);
        await f.Master.SaveChangesAsync();
        var shopProof = await f.AddRuntimeSessionAsync();
        var proof = new WebMailClaimProcessRequest(shopProof.AccountId, shopProof.PreparedOnline,
            shopProof.ServerId, shopProof.ServerSessionId, shopProof.AccountSessionId, shopProof.AccountLeaseToken);
        using var cache = new MemoryCache(new MemoryCacheOptions());
        var repo = new WebMailRepository(f.Db, new MailRepository(f.Db, f.Master, cache), f.Items, TimeProvider.System);
        var operationId = Guid.NewGuid();
        Assert.Equal("PENDING", (await repo.CreateAsync(f.UserId, "proof-mail",
            new(operationId, f.AccountId))).Status);
        await f.StartEditAsync();
        Assert.Null(await repo.ProcessAsync(operationId, new(f.AccountId, true)));
        await f.Db.PlayerAdminEditSessions.ExecuteUpdateAsync(x => x.SetProperty(e => e.Status, "READY"));
        Assert.Null(await repo.ProcessAsync(operationId, proof));
        Assert.Equal("PENDING", (await f.Db.WebMailCurrencyClaims.AsNoTracking().SingleAsync()).Status);
        await f.Db.PlayerAdminEditSessions.ExecuteUpdateAsync(x => x.SetProperty(e => e.Status, "RECOVERY_REQUIRED"));
        Assert.Equal("COMPLETED", (await repo.ProcessAsync(operationId, proof))!.Status);
        Assert.Equal("COMPLETED", (await repo.ProcessAsync(operationId, new(f.AccountId, true)))!.Status);
    }

    [Fact]
    public async Task WebBoostNeedsOptInButGameTicketAndLocalEffectRemainAvailable()
    {
        await using var f = await Fixture.CreateAsync();
        f.SeedOffer("30a00014", 500, "CHANNEL_EXP_BOOST", 1.1);
        await f.Master.SaveChangesAsync();
        var shop = new AstraldShopRepository(f.Db, f.Master, f.Network, f.Items, TimeProvider.System);
        Assert.Empty((await shop.GetCatalogAsync()).Channels);
        var rejected = await shop.CreateAsync(f.UserId, new(Guid.NewGuid(), f.AccountId, "30a00014", 500, "ch1"));
        Assert.Equal("network_boost_disabled", rejected.Reason);
        Assert.Equal(1000, (await f.Db.InventoryEntries.SingleAsync(x => x.ItemId == "99a00021")).Quantity);

        var now = DateTime.UtcNow;
        var ticket = f.AddTicket("30a00014", "CHANNEL_EXP_BOOST", 1.1, now);
        await f.Db.SaveChangesAsync();
        var boosts = new ChannelBoostRepository(f.Db, f.Network, f.Items, TimeProvider.System);
        Assert.Equal("COMPLETED", (await boosts.ActivateAsync("ch1", new(Guid.NewGuid(), f.AccountId, ticket, now))).Status);
        var local = Assert.Single((await boosts.GetSnapshotAsync()).Channels);
        Assert.False(local.NetworkBoostEnabled);
        Assert.NotNull(local.Exp);
        Assert.Equal("CH1", local.DisplayName);
        f.Network.BoostEnabled = true;
        Assert.Single((await shop.GetCatalogAsync()).Channels);
        Assert.True(Assert.Single((await boosts.GetSnapshotAsync()).Channels).NetworkBoostEnabled);
    }

    [Fact]
    public async Task PendingWebBoostRechecksOptInWithoutChargingOrActivating()
    {
        await using var f = await Fixture.CreateAsync();
        f.Network.BoostEnabled = true;
        f.SeedOffer("30a00014", 500, "CHANNEL_EXP_BOOST", 1.1);
        await f.Master.SaveChangesAsync();
        f.Db.SkillTreeAccountSessions.Add(new() { AccountSessionId = Guid.NewGuid(), AccountId = f.AccountId,
            ServerId = "ch1", DefinitionGenerationId = "g", LeaseTokenHash = "h", Closed = false,
            CreatedAtUtc = DateTime.UtcNow, ExpiresAtUtc = DateTime.UtcNow.AddMinutes(1) });
        await f.Db.SaveChangesAsync();
        var shop = new AstraldShopRepository(f.Db, f.Master, f.Network, f.Items, TimeProvider.System);
        var id = Guid.NewGuid();
        Assert.Equal("PENDING", (await shop.CreateAsync(f.UserId, new(id, f.AccountId, "30a00014", 500, "ch1"))).Status);
        f.Network.BoostEnabled = false;
        var result = await shop.ProcessAsync(id, new(f.AccountId, true));
        Assert.Equal("REJECTED", result!.Status);
        Assert.Equal("network_boost_disabled", result.Reason);
        Assert.Equal(1000, (await f.Db.InventoryEntries.SingleAsync(x => x.ItemId == "99a00021")).Quantity);
        Assert.Empty(await f.Db.ChannelBoosts.ToArrayAsync());
        Assert.Empty(await f.Db.ChannelBoostEvents.ToArrayAsync());
    }

    [Fact]
    public async Task CompletedWebBoostReplaySurvivesLaterOptOutWithoutAnotherCharge()
    {
        await using var f = await Fixture.CreateAsync();
        f.Network.BoostEnabled = true;
        f.SeedOffer("30a00014", 500, "CHANNEL_EXP_BOOST", 1.1);
        await f.Master.SaveChangesAsync();
        var shop = new AstraldShopRepository(f.Db, f.Master, f.Network, f.Items, TimeProvider.System);
        var request = new AstraldShopPurchaseRequest(Guid.NewGuid(), f.AccountId, "30a00014", 500, "ch1");
        Assert.Equal("COMPLETED", (await shop.CreateAsync(f.UserId, request)).Status);
        f.Network.BoostEnabled = false;
        Assert.Equal("COMPLETED", (await shop.CreateAsync(f.UserId, request)).Status);
        Assert.Equal("COMPLETED", (await shop.ProcessAsync(request.OperationId, new(f.AccountId, true)))!.Status);
        Assert.Equal(500, (await f.Db.InventoryEntries.SingleAsync(x => x.ItemId == "99a00021")).Quantity);
        Assert.Single(await f.Db.ChannelBoostEvents.ToArrayAsync());
    }

    [Fact]
    public async Task TicketActivationRejectsSameKindAndSpecialWithoutConsuming()
    {
        await using var f = await Fixture.CreateAsync();
        var repo = new ChannelBoostRepository(f.Db, f.Network, f.Items, TimeProvider.System);
        var now = DateTime.UtcNow;
        var exp = f.AddTicket("30a00014", "CHANNEL_EXP_BOOST", 1.1, now);
        var special = f.AddTicket("30a00024", "CHANNEL_SPECIAL_BOOST", 2, now);
        await f.Db.SaveChangesAsync();
        var first = await repo.ActivateAsync("CH1", new(Guid.NewGuid(), f.AccountId, exp, now));
        Assert.Equal("COMPLETED", first.Status);
        var rejected = await repo.ActivateAsync("ch1", new(Guid.NewGuid(), f.AccountId, special, now));
        Assert.Equal("boost_already_active", rejected.Reason);
        Assert.Equal(1, (await f.Db.InventoryEntries.SingleAsync(x => x.InventoryEntryId == special)).Quantity);
        Assert.Single(await repo.GetEventsAsync(0) is { Events: var events } ? events : []);
    }

    [Fact]
    public async Task PaidPurchaseChecksCurrentAccountPriceAndReplay()
    {
        await using var f = await Fixture.CreateAsync();
        f.SeedOffer("30a00007", 500, "INSTANCE_PRIORITY", 5);
        await f.Master.SaveChangesAsync();
        var repo = new AstraldShopRepository(f.Db, f.Master, f.Network, f.Items, TimeProvider.System);
        var operation = Guid.NewGuid();
        var wrong = await repo.CreateAsync(f.UserId, new(operation, f.AccountId, "30a00007", 600, null));
        Assert.Equal("price_changed", wrong.Reason);
        Assert.Equal(1000, (await f.Db.InventoryEntries.SingleAsync(x => x.ItemId == "99a00021")).Quantity);
        var request = new AstraldShopPurchaseRequest(operation, f.AccountId, "30a00007", 500, null);
        var first = await repo.CreateAsync(f.UserId, request);
        Assert.Equal("COMPLETED", first.Status);
        Assert.Equal(500, first.PaidAstraldBalance);
        Assert.Equal(5, first.Benefits!.InstancePriorityUses);
        Assert.Equal("COMPLETED", (await repo.CreateAsync(f.UserId, request)).Status);
        Assert.Equal("operation_conflict", (await repo.CreateAsync(f.UserId,
            request with { ExpectedPricePaidAstrald = 600 })).Reason);
        var user = await f.Db.Users.SingleAsync();
        user.AccountId = Guid.NewGuid();
        await f.Db.SaveChangesAsync();
        Assert.Equal("COMPLETED", (await repo.CreateAsync(f.UserId, request)).Status);
        Assert.Equal("account_not_current", (await repo.CreateAsync(f.UserId,
            request with { OperationId = Guid.NewGuid() })).Reason);
        Assert.Equal(500, (await f.Db.InventoryEntries.SingleAsync(x => x.ItemId == "99a00021")).Quantity);
    }

    [Fact]
    public async Task OnlinePurchaseStaysPendingUntilProcessAndReusesFrozenEffect()
    {
        await using var f = await Fixture.CreateAsync();
        f.SeedOffer("30a00007", 500, "INSTANCE_PRIORITY", 5);
        await f.Master.SaveChangesAsync();
        f.Db.SkillTreeAccountSessions.Add(new() { AccountSessionId = Guid.NewGuid(), AccountId = f.AccountId,
            ServerId = "ch1", DefinitionGenerationId = "g", LeaseTokenHash = "h", Closed = false,
            CreatedAtUtc = DateTime.UtcNow, ExpiresAtUtc = DateTime.UtcNow.AddMinutes(-1) });
        await f.Db.SaveChangesAsync();
        var repo = new AstraldShopRepository(f.Db, f.Master, f.Network, f.Items, TimeProvider.System);
        var operationId = Guid.NewGuid();
        var accepted = await repo.CreateAsync(f.UserId, new(operationId, f.AccountId, "30a00007", 500, null));
        Assert.Equal("PENDING", accepted.Status);
        Assert.Equal(1000, (await f.Db.InventoryEntries.SingleAsync(x => x.ItemId == "99a00021")).Quantity);
        f.Master.Entries.Single().PayloadJson = f.ShopPayload("30a00007", 900);
        await f.Master.SaveChangesAsync();
        var done = await repo.ProcessAsync(operationId, new(f.AccountId, true));
        Assert.Equal("COMPLETED", done!.Status);
        Assert.Equal(500, done.PaidAstraldBalance);
        Assert.NotNull(done.InventorySnapshot);
        Assert.Equal(5, done.Benefits!.InstancePriorityUses);
        var paid = await f.Db.InventoryEntries.SingleAsync(x => x.ItemId == "99a00021");
        paid.Quantity = 400;
        await f.Db.SaveChangesAsync();
        var replay = await repo.ProcessAsync(operationId, new(f.AccountId, true));
        Assert.Equal(400, replay!.PaidAstraldBalance);
        Assert.Equal(400, Assert.Single(replay.InventorySnapshot!.CurrencyEntries).Quantity);
        Assert.Equal(5, (await f.Db.AccountBenefits.SingleAsync()).InstancePriorityUses);
    }

    [Fact]
    public async Task MixedMailWebClaimGrantsOnlyCurrencyAndLeavesItemForGame()
    {
        await using var f = await Fixture.CreateAsync();
        f.SeedMail("gift", mixed: true);
        await f.Master.SaveChangesAsync();
        using var cache = new MemoryCache(new MemoryCacheOptions());
        var mail = new MailRepository(f.Db, f.Master, cache);
        var repo = new WebMailRepository(f.Db, mail, f.Items, TimeProvider.System);
        var request = new WebMailCurrencyClaimRequest(Guid.NewGuid(), f.AccountId);
        var first = await repo.CreateAsync(f.UserId, "gift", request);
        Assert.Equal("COMPLETED", first.Status);
        Assert.Equal(1500, (await f.Db.InventoryEntries.SingleAsync(x => x.ItemId == "99a00021")).Quantity);
        var remaining = Assert.Single(await mail.GetAvailableByAccountIdAsync(f.AccountId, "unread"));
        Assert.True(remaining.CurrencyClaimed);
        Assert.False(remaining.CanClaimCurrency);
        Assert.False(remaining.IsRead);
        Assert.Equal("30a00007", Assert.Single(remaining.Rewards).ItemId);
        Assert.Equal("COMPLETED", (await repo.CreateAsync(f.UserId, "gift", request)).Status);
        Assert.Equal("already_claimed", (await repo.CreateAsync(f.UserId, "gift",
            new(Guid.NewGuid(), f.AccountId))).Reason);
        Assert.Equal(1500, (await f.Db.InventoryEntries.SingleAsync(x => x.ItemId == "99a00021")).Quantity);
    }

    [Fact]
    public async Task CurrencyOnlyMailClaimMarksReadAndExpiredOpenSessionStillRequiresProcess()
    {
        await using var f = await Fixture.CreateAsync();
        f.SeedMail("paid", mixed: false);
        await f.Master.SaveChangesAsync();
        using var cache = new MemoryCache(new MemoryCacheOptions());
        var mail = new MailRepository(f.Db, f.Master, cache);
        var repo = new WebMailRepository(f.Db, mail, f.Items, TimeProvider.System);
        f.Db.SkillTreeAccountSessions.Add(new() { AccountSessionId = Guid.NewGuid(), AccountId = f.AccountId,
            ServerId = "ch1", DefinitionGenerationId = "g", LeaseTokenHash = "h", Closed = false,
            CreatedAtUtc = DateTime.UtcNow, ExpiresAtUtc = DateTime.UtcNow.AddMinutes(-1) });
        await f.Db.SaveChangesAsync();
        var opId = Guid.NewGuid();
        Assert.Equal("PENDING", (await repo.CreateAsync(f.UserId, "paid", new(opId, f.AccountId))).Status);
        Assert.Equal(1000, (await f.Db.InventoryEntries.SingleAsync(x => x.ItemId == "99a00021")).Quantity);
        var done = await repo.ProcessAsync(opId, new(f.AccountId, true));
        Assert.Equal("COMPLETED", done!.Status);
        Assert.NotNull(done.InventorySnapshot);
        Assert.Equal(1500, (await f.Db.InventoryEntries.SingleAsync(x => x.ItemId == "99a00021")).Quantity);
        Assert.True(Assert.Single(await mail.GetAvailableByAccountIdAsync(f.AccountId, "all")).IsRead);
    }

    [Fact]
    [Trait("Category", "SqlServerIntegration")]
    public async Task SqlServer_ConcurrentSameChannelActivationConsumesExactlyOneTicket()
    {
        if (Environment.GetEnvironmentVariable("ASTRALRECORD_RUN_SQLSERVER_INTEGRATION") != "1") return;
        const string prefix = "AstralRecordPaidServicesIntegration_";
        var databaseName = prefix + Guid.NewGuid().ToString("N");
        var master = new SqlConnectionStringBuilder
        {
            DataSource = @"localhost\SQLEXPRESS", InitialCatalog = "master",
            IntegratedSecurity = true, TrustServerCertificate = true,
        }.ConnectionString;
        var game = new SqlConnectionStringBuilder(master) { InitialCatalog = databaseName }.ConnectionString;
        static string Quote(string name) => "[" + name.Replace("]", "]]", StringComparison.Ordinal) + "]";
        async Task AdminSql(string sql)
        {
            await using var connection = new SqlConnection(master);
            await connection.OpenAsync();
            await using var command = new SqlCommand(sql, connection);
            await command.ExecuteNonQueryAsync();
        }
        try
        {
            await AdminSql($"CREATE DATABASE {Quote(databaseName)}");
            var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
                .UseSqlServer(game, x => x.EnableRetryOnFailure()).Options;
            var now = DateTime.UtcNow;
            now = new DateTime(now.Ticks - now.Ticks % TimeSpan.TicksPerMillisecond, DateTimeKind.Utc);
            var accounts = new[] { Guid.NewGuid(), Guid.NewGuid() };
            var entries = new[] { Guid.NewGuid(), Guid.NewGuid() };
            await using (var setup = new AstralRecordDbContext(options))
            {
                await setup.Database.EnsureCreatedAsync();
                setup.ChannelBoostCursors.Add(new() { Id = 1 });
                for (var index = 0; index < 2; index++)
                {
                    var user = Guid.NewGuid(); var inventory = Guid.NewGuid();
                    setup.Users.Add(new() { Uuid = user, Mcid = "Buyer" + index,
                        AccountId = accounts[index], CreatedAt = now, UpdatedAt = now });
                    setup.Accounts.Add(new() { Uuid = accounts[index], UserId = user,
                        AccountName = "Buyer" + index, CreatedAt = now, UpdatedAt = now });
                    setup.Inventories.Add(new() { InventoryId = inventory, AccountId = accounts[index],
                        InventoryProfile = "GAME", InventoryType = "BAG", IsEnabled = true,
                        CreatedAt = now, UpdatedAt = now });
                    setup.InventoryEntries.Add(new() { InventoryEntryId = entries[index], InventoryId = inventory,
                        ItemCategory = "consumable", ItemId = "30a00014", Quantity = 1,
                        CreatedAt = now, UpdatedAt = now });
                }
                await setup.SaveChangesAsync();
            }
            var network = new FakeNetwork(); var items = new FakeItems();
            async Task<ChannelBoostActivateResponse> Activate(int index)
            {
                await using var context = new AstralRecordDbContext(options);
                return await new ChannelBoostRepository(context, network, items, TimeProvider.System)
                    .ActivateAsync("ch1", new(Guid.NewGuid(), accounts[index], entries[index], now));
            }
            var responses = await Task.WhenAll(Activate(0), Activate(1));
            Assert.Single(responses, x => x.Status == "COMPLETED");
            Assert.Single(responses, x => x.Reason == "boost_already_active");
            await using var verify = new AstralRecordDbContext(options);
            Assert.Equal(1, await verify.ChannelBoostEvents.CountAsync());
            Assert.Equal(1, await verify.ChannelBoostCursors.Select(x => x.LastEventCursor).SingleAsync());
            Assert.Equal(1, await verify.InventoryEntries.CountAsync(x => x.Quantity == 0));
            Assert.Equal(1, await verify.InventoryEntries.CountAsync(x => x.Quantity == 1));
        }
        finally
        {
            if (!databaseName.StartsWith(prefix, StringComparison.Ordinal) || databaseName.Length != prefix.Length + 32)
                throw new InvalidOperationException("Only the random integration database may be removed.");
            await AdminSql($"ALTER DATABASE {Quote(databaseName)} SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE {Quote(databaseName)}");
        }
    }

    private sealed class FakeNetwork : INetworkManagementRepository
    {
        public bool BoostEnabled { get; set; }
        public Task<ManagedNetworkSettings?> GetSettingsAsync(bool includePlayers = false) =>
            Task.FromResult<ManagedNetworkSettings?>(new() { Channels = [new() { ServerId = "ch1", IsGame = true,
                DisplayName = "CH1", NetworkBoostEnabled = BoostEnabled }] });
        public Task<(ManagedNetworkSettings Settings, bool Created)> BootstrapAsync(ManagedNetworkSettings request) => throw new NotSupportedException();
        public Task<ManagedNetworkSettings> UpdateSettingsAsync(ManagedNetworkSettings request, Guid actorUuid) => throw new NotSupportedException();
        public Task<IReadOnlyList<NetworkManagedPlayer>> SearchPlayersAsync(string? query) => throw new NotSupportedException();
        public Task<NetworkChannelAccessResponse> GetChannelAccessAsync(Guid userUuid, string serverId) => throw new NotSupportedException();
        public Task<NetworkBanStateResponse?> GetBanAsync(Guid userUuid) => throw new NotSupportedException();
        public Task<IReadOnlyList<NetworkBanStateResponse>> GetActiveBansAsync() => throw new NotSupportedException();
        public Task<NetworkBanStateResponse?> UpdateBanAsync(Guid userUuid, NetworkBanUpdateRequest request, Guid actorUuid) => throw new NotSupportedException();
        public Task<bool> CanManageBanFromGameAsync(Guid actorUuid) => throw new NotSupportedException();
    }
    private sealed class FakeItems : IItemRepository
    {
        public IReadOnlyList<ItemSummaryResponse> GetAllSummaries() => [];
        public ItemResponse? GetById(string id)
        {
            if (id == "99a00021") return new() { SchemaVersion = 1, Id = id, Name = "有償", Icon = "PAPER", Category = "currency", Rarity = "COMMON" };
            var effect = id switch
            {
                "30a00007" => ("INSTANCE_PRIORITY", 5.0),
                "30a00014" => ("CHANNEL_EXP_BOOST", 1.1),
                "30a00024" => ("CHANNEL_SPECIAL_BOOST", 2.0),
                _ => ("", 0.0),
            };
            return effect.Item1 == "" ? null : new()
            {
                SchemaVersion = 1, Id = id, Name = "&eTicket", Icon = "PAPER", Category = "consumable", Rarity = "RARE",
                UnTradeable = true, UnSellable = true,
                Consumable = new() { OnUse = new() { Amount = 1 }, Effects = [new()
                    { Type = effect.Item1, Value = effect.Item2,
                      DurationSeconds = effect.Item1.StartsWith("CHANNEL") ? 3600 : null }] },
            };
        }
    }
    private sealed class Fixture(SqliteConnection gameConnection, SqliteConnection masterConnection,
        AstralRecordDbContext db, MasterDataDbContext master) : IAsyncDisposable
    {
        public AstralRecordDbContext Db = db;
        public MasterDataDbContext Master = master;
        public FakeNetwork Network = new();
        public FakeItems Items = new();
        public Guid UserId = Guid.NewGuid();
        public Guid AccountId = Guid.NewGuid();
        public Guid BagId = Guid.NewGuid();
        private Guid bootId;
        private Guid accountSessionId;
        private const string LeaseToken = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        public async Task<AstraldShopProcessRequest> AddRuntimeSessionAsync()
        {
            bootId = Guid.NewGuid(); accountSessionId = Guid.NewGuid();
            var now = DateTime.UtcNow.AddMinutes(-1);
            Db.PlayerAdminServerRuntimes.Add(new() { ServerId = "rpg-1", ServerSessionId = bootId,
                Role = "RPG", Enabled = true, RegisteredAtUtc = now, LastSeenUtc = now });
            Db.SkillTreeAccountSessions.Add(new() { AccountSessionId = accountSessionId, AccountId = AccountId,
                ServerId = "rpg-1", ServerSessionId = bootId, DefinitionGenerationId = "g",
                LeaseTokenHash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(LeaseToken))).ToLowerInvariant(),
                CreatedAtUtc = now, ExpiresAtUtc = now.AddSeconds(1) });
            await Db.SaveChangesAsync();
            return new(AccountId, true, "rpg-1", bootId, accountSessionId, LeaseToken);
        }
        public async Task<PlayerAdminEditSessionEntity> StartEditAsync()
        {
            var now = DateTime.UtcNow;
            var edit = new PlayerAdminEditSessionEntity { EditSessionId = Guid.NewGuid(), AccountId = AccountId,
                UserUuid = UserId, ActorUserUuid = UserId, Reason = "test", Status = "DRAINING",
                ExpectedServerCount = 1, CreatedAtUtc = now, UpdatedAtUtc = now, ExpiresAtUtc = now.AddMinutes(30) };
            Db.PlayerAdminEditSessions.Add(edit);
            Db.PlayerAdminEditDrains.Add(new() { EditSessionId = edit.EditSessionId,
                ServerId = "rpg-1", ServerSessionId = bootId });
            await Db.SaveChangesAsync();
            return edit;
        }
        public static async Task<Fixture> CreateAsync()
        {
            var gameConnection = new SqliteConnection("Data Source=:memory:");
            var masterConnection = new SqliteConnection("Data Source=:memory:");
            await gameConnection.OpenAsync(); await masterConnection.OpenAsync();
            var db = new AstralRecordDbContext(new DbContextOptionsBuilder<AstralRecordDbContext>().UseSqlite(gameConnection).Options);
            var master = new MasterDataDbContext(new DbContextOptionsBuilder<MasterDataDbContext>().UseSqlite(masterConnection).Options);
            await db.Database.EnsureCreatedAsync(); await master.Database.EnsureCreatedAsync();
            var f = new Fixture(gameConnection, masterConnection, db, master);
            var now = DateTime.UtcNow;
            db.Users.Add(new() { Uuid = f.UserId, AccountId = f.AccountId, Mcid = "Buyer", CreatedAt = now, UpdatedAt = now });
            db.Accounts.Add(new() { Uuid = f.AccountId, UserId = f.UserId, AccountName = "Buyer", CreatedAt = now, UpdatedAt = now });
            db.Inventories.Add(new() { InventoryId = f.BagId, AccountId = f.AccountId,
                InventoryType = "BAG", InventoryProfile = "GAME", IsEnabled = true, CreatedAt = now, UpdatedAt = now });
            var currencyId = Guid.NewGuid();
            db.Inventories.Add(new() { InventoryId = currencyId, AccountId = f.AccountId,
                InventoryType = "CURRENCY", InventoryProfile = "GAME", IsEnabled = true, CreatedAt = now, UpdatedAt = now });
            db.InventoryEntries.Add(new() { InventoryEntryId = Guid.NewGuid(), InventoryId = currencyId,
                ItemCategory = "currency", ItemId = "99a00021", Quantity = 1000, CreatedAt = now, UpdatedAt = now });
            db.ChannelBoostCursors.Add(new() { Id = 1, LastEventCursor = 0 });
            await db.SaveChangesAsync();
            return f;
        }
        public Guid AddTicket(string id, string kind, double multiplier, DateTime now)
        {
            var entryId = Guid.NewGuid();
            Db.InventoryEntries.Add(new() { InventoryEntryId = entryId, InventoryId = BagId,
                ItemCategory = "consumable", ItemId = id, Quantity = 1, CreatedAt = now, UpdatedAt = now });
            return entryId;
        }
        public void SeedOffer(string id, int price, string kind, double value)
        {
            Master.Entries.Add(new() { EntryId = Guid.NewGuid(), MasterType = "shop", MasterId = "astrald_shop",
                PayloadJson = ShopPayload(id, price), SourceFileHash = new string('0', 64) });
        }
        public void SeedMail(string id, bool mixed)
        {
            var rewards = mixed
                ? """[{"itemId":"99a00021","category":"CURRENCY","amount":500},{"itemId":"30a00007","category":"consumable","amount":1}]"""
                : """[{"itemId":"99a00021","category":"CURRENCY","amount":500}]""";
            Master.Entries.Add(new() { EntryId = Guid.NewGuid(), MasterType = "mail", MasterId = id,
                SourceFileHash = new string('0', 64), PayloadJson = $$"""
                    {"schemaVersion":1,"id":"{{id}}","icon":"CHEST","title":"Gift","body":"Gift",
                    "publishFrom":"{{DateTime.UtcNow.AddMinutes(-1):O}}","rewards":{{rewards}}}
                    """ });
        }
        public string ShopPayload(string id, int price) => $$"""
            {"items":[{"itemId":{"ref":"item:{{id}}"},"amount":1,"priceGold":0,
            "requiredItems":[{"itemId":{"ref":"item:99a00021"},"amount":{{price}}}]}]}
            """;
        public async ValueTask DisposeAsync()
        { await Db.DisposeAsync(); await Master.DisposeAsync(); await gameConnection.DisposeAsync(); await masterConnection.DisposeAsync(); }
    }
}
