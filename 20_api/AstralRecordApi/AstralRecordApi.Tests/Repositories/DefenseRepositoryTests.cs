using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed class DefenseRepositoryTests
{
    private const string ServerId = "game-1";
    [Fact]
    public async Task RewardsUseMultiplesOfIntervalAndHighestApplicableTier()
    {
        await using var f = await Fixture.Create();
        var (runId, owner) = await f.StartRunningAsync();
        await f.HeartbeatAsync(runId, owner, 10, 120);

        var first = Assert.Single(await f.Repository.RewardsAsync(ServerId, runId,
            f.Reward(owner, 5, 120)));
        Assert.Equal("orb-low", first.OrbItemId);
        Assert.Equal(2, first.OrbQuantity);
        var second = Assert.Single(await f.Repository.RewardsAsync(ServerId, runId,
            f.Reward(owner, 10, 120)));
        Assert.Equal("orb-high", second.OrbItemId);
        Assert.Equal(7, second.OrbQuantity);
        await Assert.ThrowsAsync<ArgumentException>(() => f.Repository.RewardsAsync(ServerId, runId,
            f.Reward(owner, 6, 120)));
        Assert.Equal(2, await f.Management.DefenseRewards.CountAsync());
        Assert.Equal(2, await f.Game.PlayerMailDeliveries.CountAsync());
    }

    [Fact]
    public async Task ClaimedPendingRunAcceptsDelayedOwnerReportsAfterStaleExpiryWithoutResurrection()
    {
        await using var f = await Fixture.Create();
        var owner = Guid.NewGuid();
        var replacement = Guid.NewGuid();
        var runId = await f.CreatePendingAsync(owner);
        Assert.Equal(owner, (await f.Management.DefenseRuns.SingleAsync()).InstanceId);

        f.Clock.UtcNow = f.Clock.UtcNow.AddSeconds(130);
        Assert.Null((await f.Repository.RuntimeAsync(ServerId, replacement)).PendingRun);
        Assert.Equal("ABORTED", (await f.Management.DefenseRuns.SingleAsync()).Status);
        await Assert.ThrowsAsync<DefenseConflictException>(() => f.Repository.ReportAsync(ServerId, runId,
            f.Report(replacement, "STARTED", 1)));

        Assert.Equal("ABORTED", (await f.Repository.ReportAsync(ServerId, runId,
            f.Report(owner, "STARTED", 1, f.Participant()))).Status);
        Assert.Equal("ABORTED", (await f.Repository.ReportAsync(ServerId, runId,
            f.Report(owner, "HEARTBEAT", 5, f.Participant(120)))).Status);
        var reward = f.Reward(owner, 5, 120);
        Assert.Single(await f.Repository.RewardsAsync(ServerId, runId, reward));
        Assert.Equal("ABORTED", (await f.Repository.ReportAsync(ServerId, runId,
            f.Report(owner, "ENDED", 5))).Status);
        Assert.Single(await f.Repository.RewardsAsync(ServerId, runId, reward));
        Assert.Equal("ABORTED", (await f.Management.DefenseRuns.SingleAsync()).Status);
        Assert.Single(await f.Management.DefenseRewards.ToListAsync());
        Assert.Single(await f.Game.PlayerMailDeliveries.ToListAsync());
        await Assert.ThrowsAsync<DefenseConflictException>(() => f.Repository.RewardsAsync(ServerId, runId,
            f.Reward(replacement, 5, 120)));
    }

    [Fact]
    public async Task EliminatedParticipantCannotEarnNewStageButCanReplayEarnedStage()
    {
        await using var f = await Fixture.Create();
        var (runId, owner) = await f.StartRunningAsync();
        await f.HeartbeatAsync(runId, owner, 5, 120);
        var earned = f.Reward(owner, 5, 120);
        Assert.Single(await f.Repository.RewardsAsync(ServerId, runId, earned));
        await f.HeartbeatAsync(runId, owner, 10, 150, eliminated: true);

        await Assert.ThrowsAsync<ArgumentException>(() => f.Repository.RewardsAsync(ServerId, runId,
            f.Reward(owner, 10, 150)));
        Assert.Single(await f.Repository.RewardsAsync(ServerId, runId, earned));
        Assert.Single(await f.Management.DefenseRewards.ToListAsync());
        Assert.Single(await f.Game.PlayerMailDeliveries.ToListAsync());
    }

    [Fact]
    public async Task DelayedOpenReportDoesNotMakeManualStartAvailableWithoutFreshPoll()
    {
        await using var f = await Fixture.Create();
        var owner = Guid.NewGuid();
        await f.Repository.UpdateChannelAsync(ServerId, new()
        {
            ExpectedRevision = 0, Configuration = Configuration(),
        }, f.UserId);
        await f.Repository.RuntimeAsync(ServerId, owner);
        f.Clock.UtcNow = f.Clock.UtcNow.AddSeconds(31);
        var channel = await f.Repository.RegistrationAsync(ServerId,
            new DefenseRegistrationReport(owner, Guid.NewGuid(), "OPEN", 1));
        Assert.Equal("UNKNOWN", channel.RegistrationStatus);
        Assert.Null(channel.RegisteredCount);
        await Assert.ThrowsAsync<DefenseConflictException>(() => f.Repository.StartAsync(ServerId,
            new DefenseStartRequest(Guid.NewGuid(), 1)));
        Assert.Empty(await f.Management.DefenseRuns.ToListAsync());

        await f.Repository.RuntimeAsync(ServerId, owner);
        Assert.Equal("PENDING", (await f.Repository.StartAsync(ServerId,
            new DefenseStartRequest(Guid.NewGuid(), 1))).Status);
    }

    [Fact]
    public async Task MidnightJstDailyRunIsAvailableDuringPreviousDayRegistrationWindow()
    {
        await using var f = await Fixture.Create();
        f.Clock.UtcNow = new DateTimeOffset(2026, 10, 19, 14, 59, 0, TimeSpan.Zero); // 23:59 JST
        await f.Repository.UpdateChannelAsync(ServerId, new()
        {
            ExpectedRevision = 0, DailyEnabled = true, DailyStartTimeJst = "00:00",
            Configuration = Configuration(),
        }, f.UserId);
        var owner = Guid.NewGuid();
        var pending = Assert.IsType<DefenseRunResponse>((await f.Repository.RuntimeAsync(ServerId, owner)).PendingRun);
        Assert.Equal("DAILY", pending.Source);
        Assert.Equal(new DateTime(2026, 10, 19, 15, 0, 0, DateTimeKind.Utc), pending.ScheduledAtUtc);
        Assert.Equal(new DateTime(2026, 10, 20),
            (await f.Management.DefenseRuns.SingleAsync(x => x.RunId == pending.RunId)).ScheduledDateJst);
        await Assert.ThrowsAsync<DefenseConflictException>(() => f.Repository.ReportAsync(ServerId, pending.RunId,
            f.Report(owner, "STARTED", 1, f.Participant())));
    }

    [Fact]
    public async Task UndeliverableFirstPageDoesNotBlockLaterValidRewards()
    {
        await using var f = await Fixture.Create();
        var (runId, owner) = await f.StartRunningAsync();
        await f.HeartbeatAsync(runId, owner, 5, 120);
        var missingAccount = Guid.NewGuid();
        f.Management.DefenseParticipants.Add(new DefenseParticipantEntity
        {
            RunId = runId, AccountId = missingAccount, UserUuid = Guid.NewGuid(),
        });
        for (var index = 1; index <= 50; index++)
            f.Management.DefenseRewards.Add(new DefenseRewardEntity
            {
                RunId = runId, AccountId = missingAccount, UserUuid = Guid.NewGuid(),
                DefenseLevel = index, OrbItemId = "orb-low", OrbQuantity = 1,
                MailId = "unavailable-" + index, CreatedAtUtc = f.Clock.UtcNow.UtcDateTime.AddMinutes(-1),
            });
        await f.Management.SaveChangesAsync();

        Assert.Single(await f.Repository.RewardsAsync(ServerId, runId, f.Reward(owner, 5, 120)));
        Assert.Single(await f.Game.PlayerMailDeliveries.ToListAsync());
        Assert.Equal(50, await f.Management.DefenseRewards.CountAsync(x => x.DeliveredAtUtc == null));
        Assert.Equal(0, await f.Repository.DeliverPendingAsync(CancellationToken.None));
        Assert.Single(await f.Game.PlayerMailDeliveries.ToListAsync());
    }

    private static DefenseConfiguration Configuration() => new()
    {
        WorldName = "world", CoreX = 0, CoreY = 64, CoreZ = 0,
        EntryX = 1, EntryY = 64, EntryZ = 1, SpawnPoints = [new DefensePoint(10, 64, 10)],
        CoreMaxHealth = 100, CoreDefense = 0, BaseSpawnCount = 1, SpawnIntervalSeconds = 5,
        MaxSpawnsPerWave = 10, MaxSpawnsPerTick = 5, MaxAliveEnemies = 20,
        RetaliationSeconds = 5, RetaliationRange = 5,
        PlayerSpawnCoefficient = 1, PlayerHealthCoefficient = 1, PlayerDamageCoefficient = 1,
        PostMaxLevelHealthCoefficient = 1, PostMaxLevelDamageCoefficient = 1,
        RegistrationLeadSeconds = 120, ReconnectGraceSeconds = 60,
        ReviveProtectionSeconds = 0, ReviveShieldMode = "EMPTY",
        RewardEveryLevels = 5, MinimumActiveSeconds = 30,
        RewardTiers = [new DefenseRewardTier(1, "orb-low", 2), new DefenseRewardTier(10, "orb-high", 7)],
    };

    private sealed class Clock : TimeProvider
    {
        public DateTimeOffset UtcNow { get; set; } = new(2026, 10, 19, 0, 0, 0, TimeSpan.Zero);
        public override DateTimeOffset GetUtcNow() => UtcNow;
        public override TimeZoneInfo LocalTimeZone => TimeZoneInfo.Utc;
    }

    private sealed class Fixture : IAsyncDisposable
    {
        private readonly SqliteConnection gameConnection = new("Data Source=:memory:");
        private readonly SqliteConnection managementConnection = new("Data Source=:memory:");
        public Guid UserId { get; } = Guid.NewGuid();
        public Guid AccountId { get; } = Guid.NewGuid();
        public Clock Clock { get; } = new();
        public AstralRecordDbContext Game { get; private set; } = null!;
        public ManagementDbContext Management { get; private set; } = null!;
        public DefenseRepository Repository { get; private set; } = null!;

        public static async Task<Fixture> Create()
        {
            var f = new Fixture();
            await f.gameConnection.OpenAsync();
            await f.managementConnection.OpenAsync();
            f.Game = new(new DbContextOptionsBuilder<AstralRecordDbContext>().UseSqlite(f.gameConnection).Options);
            f.Management = new(new DbContextOptionsBuilder<ManagementDbContext>().UseSqlite(f.managementConnection).Options);
            await f.Game.Database.EnsureCreatedAsync();
            await f.Management.Database.EnsureCreatedAsync();
            f.Game.Accounts.Add(new AccountEntity
            {
                Uuid = f.AccountId, UserId = f.UserId, AccountName = "Tester", SlotIndex = 1,
                CreatedAt = f.Clock.UtcNow.UtcDateTime, UpdatedAt = f.Clock.UtcNow.UtcDateTime,
                CreatedBy = f.UserId, UpdatedBy = f.UserId,
            });
            await f.Game.SaveChangesAsync();
            f.Repository = new(f.Management, f.Game, new Network(), new Mobs(), new Items(), f.Clock);
            return f;
        }

        public DefenseParticipant Participant(int activeSeconds = 0, bool eliminated = false)
            => new(UserId, AccountId, activeSeconds, false, eliminated);

        public DefenseRunReportRequest Report(Guid owner, string kind, int level, params DefenseParticipant[] participants)
            => new() { InstanceId = owner, OperationId = Guid.NewGuid(), Event = kind,
                DefenseLevel = level, Participants = participants };

        public DefenseRewardReportRequest Reward(Guid owner, int level, int activeSeconds)
            => new(owner, Guid.NewGuid(), [new DefenseRewardEntry(UserId, AccountId, level, activeSeconds, false)]);

        public async Task<Guid> CreatePendingAsync(Guid owner)
        {
            await Repository.UpdateChannelAsync(ServerId, new()
            {
                ExpectedRevision = 0, Configuration = Configuration(),
            }, UserId);
            await Repository.RuntimeAsync(ServerId, owner);
            await Repository.RegistrationAsync(ServerId,
                new DefenseRegistrationReport(owner, Guid.NewGuid(), "OPEN", 1));
            var pending = await Repository.StartAsync(ServerId, new DefenseStartRequest(Guid.NewGuid(), 1));
            Assert.Equal("PENDING", pending.Status);
            Assert.Equal(pending.RunId, (await Repository.RuntimeAsync(ServerId, owner)).PendingRun?.RunId);
            return pending.RunId;
        }

        public async Task<(Guid RunId, Guid Owner)> StartRunningAsync()
        {
            var owner = Guid.NewGuid();
            var runId = await CreatePendingAsync(owner);
            Assert.Equal("RUNNING", (await Repository.ReportAsync(ServerId, runId,
                Report(owner, "STARTED", 1, Participant()))).Status);
            return (runId, owner);
        }

        public async Task HeartbeatAsync(Guid runId, Guid owner, int level, int activeSeconds, bool eliminated = false)
            => Assert.Equal("RUNNING", (await Repository.ReportAsync(ServerId, runId,
                Report(owner, "HEARTBEAT", level, Participant(activeSeconds, eliminated)))).Status);

        public async ValueTask DisposeAsync()
        {
            await Game.DisposeAsync();
            await Management.DisposeAsync();
            await gameConnection.DisposeAsync();
            await managementConnection.DisposeAsync();
        }
    }

    private sealed class Network : INetworkManagementRepository
    {
        public Task<ManagedNetworkSettings?> GetSettingsAsync(bool includePlayers = false)
            => Task.FromResult<ManagedNetworkSettings?>(new()
            {
                Channels = [new ManagedNetworkChannel { ServerId = ServerId, DisplayName = "Game", IsGame = true }],
            });
        public Task<(ManagedNetworkSettings Settings, bool Created)> BootstrapAsync(ManagedNetworkSettings request) => throw new NotSupportedException();
        public Task<ManagedNetworkSettings> UpdateSettingsAsync(ManagedNetworkSettings request, Guid actorUuid) => throw new NotSupportedException();
        public Task<IReadOnlyList<NetworkManagedPlayer>> SearchPlayersAsync(string? query) => throw new NotSupportedException();
        public Task<NetworkChannelAccessResponse> GetChannelAccessAsync(Guid userUuid, string serverId) => throw new NotSupportedException();
        public Task<NetworkBanStateResponse?> GetBanAsync(Guid userUuid) => throw new NotSupportedException();
        public Task<IReadOnlyList<NetworkBanStateResponse>> GetActiveBansAsync() => throw new NotSupportedException();
        public Task<NetworkBanStateResponse?> UpdateBanAsync(Guid userUuid, NetworkBanUpdateRequest request, Guid actorUuid) => throw new NotSupportedException();
        public Task<bool> CanManageBanFromGameAsync(Guid actorUuid) => throw new NotSupportedException();
    }

    private sealed class Mobs : IMobRepository
    {
        public IReadOnlyList<MobSummaryResponse> GetAllSummaries(string? category = null)
            => [new MobSummaryResponse { Id = "enemy", Category = "ENEMY", Name = "Enemy", EntityType = "ZOMBIE", DefenseEligible = true }];
        public MobResponse? GetById(string mobId) => throw new NotSupportedException();
    }

    private sealed class Items : IItemRepository
    {
        public IReadOnlyList<ItemSummaryResponse> GetAllSummaries()
            => [new ItemSummaryResponse { Id = "orb-low", Category = "orb" },
                new ItemSummaryResponse { Id = "orb-high", Category = "orb" }];
        public ItemResponse? GetById(string itemId) => throw new NotSupportedException();
    }
}
