using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using Microsoft.EntityFrameworkCore.Storage;
using System.Data.Common;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed class NetworkManagementRepositoryTests
{
    [Fact]
    public async Task VipAdmissionUsesOnlySelectedOwnedActiveAccountAndExpiresImmediately()
    {
        await using var f = await Fixture.Create();
        await f.Repository.BootstrapAsync(new ManagedNetworkSettings
        {
            Channels = [new() { ServerId = "lobby", DisplayName = "Lobby" }, new() { ServerId = "vip", DisplayName = "VIP", IsGame = true, DonorOnly = true, DonorExtraPlayers = 3 }],
        });
        var vipId = Guid.NewGuid(); var normalId = Guid.NewGuid(); var now = f.Clock.GetUtcNow().UtcDateTime;
        f.Game.Accounts.AddRange(new AccountEntity { Uuid = vipId, UserId = f.UserId, AccountName = "Vip", SlotIndex = 1 },
            new AccountEntity { Uuid = normalId, UserId = f.UserId, AccountName = "Normal", SlotIndex = 2 });
        f.Game.AccountBenefits.Add(new() { AccountId = vipId, DonerExpiresAt = now.AddDays(15) });
        var user = await f.Game.Users.SingleAsync(); user.AccountId = normalId; user.Permission = 5;
        await f.Game.SaveChangesAsync();
        Assert.False((await f.Repository.GetChannelAccessAsync(f.UserId, "vip")).Allowed);
        user.AccountId = vipId; await f.Game.SaveChangesAsync();
        var access = await f.Repository.GetChannelAccessAsync(f.UserId, "vip");
        Assert.True(access.Allowed); Assert.True(access.IsVip); Assert.Equal("DONER", access.VipTier);
        Assert.Equal(0, access.Permission);
        Assert.True((await f.Repository.GetSettingsAsync())!.Channels.Single(c => c.ServerId == "vip").DonorOnly);
        (await f.Game.AccountBenefits.SingleAsync()).DonerExpiresAt = now;
        await f.Game.SaveChangesAsync();
        Assert.False((await f.Repository.GetChannelAccessAsync(f.UserId, "vip")).Allowed);
    }

    [Fact]
    public async Task Bootstrap_DoesNotOverwriteWebEdits_AndStaleRevisionDoesNotAudit()
    {
        await using var fixture = await Fixture.Create();
        var repo = fixture.Repository;
        var initial = await repo.BootstrapAsync(Settings());
        Assert.True(initial.Created);
        Assert.Equal(1, initial.Settings.Revision);
        var edited = await repo.UpdateSettingsAsync(Settings(1, "新しいロビー"), fixture.UserId);
        Assert.Equal(2, edited.Revision);
        var repeated = await repo.BootstrapAsync(Settings());
        Assert.False(repeated.Created);
        Assert.Equal("新しいロビー", repeated.Settings.Channels[0].DisplayName);
        await Assert.ThrowsAsync<NetworkManagementConflictException>(() => repo.UpdateSettingsAsync(Settings(1), fixture.UserId));
        Assert.Equal(2, await fixture.Management.NetworkAudits.CountAsync());
    }

    [Fact]
    public async Task Membership_UsesRegisteredGameUuid_AndResponseNamesAreNeverTrusted()
    {
        await using var fixture = await Fixture.Create();
        var saved = await fixture.Repository.UpdateSettingsAsync(new ManagedNetworkSettings
        {
            Channels = Settings().Channels,
            AuthorityUsers = [fixture.UserId, fixture.UserId],
            Players = [new(fixture.UserId, "ForgedName")],
        }, fixture.UserId);
        Assert.Equal([fixture.UserId], saved.AuthorityUsers);
        Assert.Equal("Tester", Assert.Single(saved.Players).Mcid);
        Assert.Empty((await fixture.Repository.GetSettingsAsync())!.Players);
        Assert.DoesNotContain("ForgedName", (await fixture.Management.NetworkSettings.SingleAsync()).SettingsJson);
        Assert.Equal(fixture.UserId, Assert.Single(await fixture.Repository.SearchPlayersAsync("Tes")).UserUuid);
        await Assert.ThrowsAsync<ArgumentException>(() => fixture.Repository.UpdateSettingsAsync(new ManagedNetworkSettings
        {
            Revision = 1, Channels = Settings().Channels, AuthorityUsers = [Guid.NewGuid()],
        }, fixture.UserId));
        Assert.Single(await fixture.Management.NetworkAudits.ToListAsync());
    }

    [Fact]
    public async Task ChannelRoles_AreIsolated_AndDebugDoesNotElevatePermission()
    {
        await using var fixture = await Fixture.Create();
        await fixture.Repository.BootstrapAsync(new ManagedNetworkSettings
        {
            Channels = [Settings().Channels[0], new()
            {
                ServerId = "dev", DisplayName = "開発", IsGame = true, WhitelistEnabled = true,
                DebugUsers = [fixture.UserId],
            }, new() { ServerId = "private", DisplayName = "限定", IsGame = true, WhitelistEnabled = true }],
        });
        var debug = await fixture.Repository.GetChannelAccessAsync(fixture.UserId, "DEV");
        Assert.True(debug.Allowed);
        Assert.True(debug.DebugUser);
        Assert.False(debug.IsAuthority);
        Assert.Equal(0, debug.Permission);
        Assert.False((await fixture.Repository.GetChannelAccessAsync(fixture.UserId, "private")).Allowed);
        Assert.False((await fixture.Repository.GetChannelAccessAsync(fixture.UserId, "unknown")).ChannelKnown);
        Assert.False(await fixture.Repository.CanManageBanFromGameAsync(fixture.UserId));
        var current = (await fixture.Repository.GetSettingsAsync())!;
        await fixture.Repository.UpdateSettingsAsync(new ManagedNetworkSettings
        {
            Revision = current.Revision, Channels = current.Channels, AuthorityUsers = [fixture.UserId],
        }, fixture.UserId);
        var authority = await fixture.Repository.GetChannelAccessAsync(fixture.UserId, "private");
        Assert.True(authority.Allowed);
        Assert.Equal(99, authority.Permission);
        Assert.True(await fixture.Repository.CanManageBanFromGameAsync(fixture.UserId));
        Assert.True(await fixture.Repository.CanManageBanFromGameAsync(Guid.Empty));
    }

    [Fact]
    public async Task ManagedClear_OverridesLegacyBan_AndPersistsWithoutGameUser()
    {
        await using var fixture = await Fixture.Create();
        var user = await fixture.Game.Users.SingleAsync();
        user.BanIndefinite = true;
        await fixture.Game.SaveChangesAsync();
        Assert.True((await fixture.Repository.GetBanAsync(fixture.UserId))!.IsActive);
        var cleared = await fixture.Repository.UpdateBanAsync(fixture.UserId, new() { IsBanned = false }, fixture.UserId);
        Assert.False(cleared!.IsActive);
        Assert.True((await fixture.Game.Users.SingleAsync()).BanIndefinite);
        Assert.False((await fixture.Repository.GetBanAsync(fixture.UserId))!.IsActive);
        var identity = await fixture.Management.Players.SingleAsync();
        Assert.False(identity.WebAdmin);
        Assert.False(identity.IsProfilePublic);
        Assert.Null(identity.FirstWebLoginAt);
        await fixture.Game.Users.ExecuteDeleteAsync();
        var permanent = await fixture.Repository.UpdateBanAsync(fixture.UserId, new()
        {
            ExpectedRevision = 1, IsBanned = true, Reason = "  確認済みの理由  ",
        }, fixture.UserId);
        Assert.True(permanent!.IsIndefinite);
        Assert.Equal("Tester", permanent.Mcid);
        Assert.Equal("確認済みの理由", permanent.Reason);
        Assert.Single(await fixture.Repository.GetActiveBansAsync());
        Assert.Equal(2, await fixture.Management.NetworkAudits.CountAsync());
    }

    [Fact]
    public async Task TimedBan_ExpiresAtExactUtcBoundary_AndRejectsLostUpdate()
    {
        await using var fixture = await Fixture.Create();
        var expiry = fixture.Clock.GetUtcNow().AddDays(2);
        var saved = await fixture.Repository.UpdateBanAsync(fixture.UserId, new()
        {
            IsBanned = true, ExpiresAtUtc = expiry.ToOffset(TimeSpan.FromHours(9)), Reason = "期限付き",
        }, fixture.UserId);
        Assert.True(saved!.IsActive);
        Assert.False(saved.IsIndefinite);
        Assert.Equal(expiry, saved.ExpiresAtUtc);
        await Assert.ThrowsAsync<NetworkManagementConflictException>(() => fixture.Repository.UpdateBanAsync(fixture.UserId, new() { IsBanned = false }, fixture.UserId));
        Assert.True((await fixture.Repository.GetBanAsync(fixture.UserId))!.IsActive);
        fixture.Clock.UtcNow = expiry;
        Assert.False((await fixture.Repository.GetBanAsync(fixture.UserId))!.IsActive);
        Assert.Empty(await fixture.Repository.GetActiveBansAsync());
        await Assert.ThrowsAsync<ArgumentException>(() => fixture.Repository.UpdateBanAsync(fixture.UserId, new()
        {
            ExpectedRevision = 1, IsBanned = true, ExpiresAtUtc = expiry,
        }, fixture.UserId));
        Assert.Single(await fixture.Management.NetworkAudits.ToListAsync());
    }

    [Fact]
    public async Task BootstrapPreservesLegacyLargeIntervalsAndCapacities()
    {
        await using var fixture = await Fixture.Create();
        var imported = await fixture.Repository.BootstrapAsync(new()
        {
            LobbyServerId = "LOBBY", TransferCooldownSeconds = 7200, TabRefreshSeconds = 90, PresenceHeartbeatSeconds = 60,
            Channels = [new() { ServerId = "lobby", DisplayName = "ロビー", MaxPlayers = int.MaxValue, DonorExtraPlayers = int.MaxValue }],
        });
        Assert.Equal(60, imported.Settings.PresenceHeartbeatSeconds);
        Assert.Equal(7200, imported.Settings.TransferCooldownSeconds);
        Assert.Equal(int.MaxValue, imported.Settings.Channels[0].MaxPlayers);
        Assert.True((await fixture.Repository.GetChannelAccessAsync(fixture.UserId, "LoBbY")).Allowed);
    }

    [Fact]
    public async Task AdminEndpointsRecheckManagementFlag_AndGamePermissionDoesNotGrantWebAccess()
    {
        await using var fixture = await Fixture.Create();
        var authorization = new WebAuthRepository(fixture.Game, fixture.Management,
            Microsoft.Extensions.Options.Options.Create(new AstralRecordApi.Options.WebAuthOptions()),
            new AstralRecordApi.Authentication.WebCodeProofProtector(new Microsoft.AspNetCore.DataProtection.EphemeralDataProtectionProvider()));
        var controller = new AstralRecordApi.Controllers.NetworkManagementController(fixture.Repository, authorization);
        Assert.Equal(403, Assert.IsType<Microsoft.AspNetCore.Mvc.StatusCodeResult>(await controller.GetSettings(fixture.UserId)).StatusCode);
        fixture.Management.Players.Add(new ManagementPlayerEntity
        {
            PlayerUuid = fixture.UserId, Mcid = "Tester", WebAdmin = true,
            CreatedAt = fixture.Clock.GetUtcNow().UtcDateTime, UpdatedAt = fixture.Clock.GetUtcNow().UtcDateTime,
        });
        await fixture.Management.SaveChangesAsync();
        Assert.IsType<Microsoft.AspNetCore.Mvc.OkObjectResult>(await controller.UpdateSettings(fixture.UserId, Settings()));
        Assert.IsType<Microsoft.AspNetCore.Mvc.OkObjectResult>(await controller.UpdateBan(fixture.UserId, fixture.UserId, new() { IsBanned = true }));
        (await fixture.Management.Players.SingleAsync()).WebAdmin = false;
        (await fixture.Game.Users.SingleAsync()).Permission = 99;
        await fixture.Management.SaveChangesAsync();
        await fixture.Game.SaveChangesAsync();
        Assert.Equal(403, Assert.IsType<Microsoft.AspNetCore.Mvc.StatusCodeResult>(await controller.UpdateBan(fixture.UserId, fixture.UserId, new() { ExpectedRevision = 1 })).StatusCode);
        Assert.True((await fixture.Repository.GetBanAsync(fixture.UserId))!.IsActive);
    }

    [Theory]
    [InlineData("bootstrap", false)]
    [InlineData("settings", false)]
    [InlineData("ban", false)]
    [InlineData("bootstrap", true)]
    [InlineData("settings", true)]
    [InlineData("ban", true)]
    public async Task RetryingTransaction_DoesNotDuplicateRevisionOrAudit(string operation, bool loseCommitAcknowledgement)
    {
        var fault = new TransientFault();
        await using var fixture = await Fixture.Create(loseCommitAcknowledgement
            ? new LoseCommitAcknowledgement(fault) : new FailAfterSave(fault));
        Assert.True(fixture.Management.Database.CreateExecutionStrategy().RetriesOnFailure);
        if (operation == "settings") await fixture.Repository.BootstrapAsync(Settings());
        fault.Armed = true;
        switch (operation)
        {
            case "bootstrap":
                var created = await fixture.Repository.BootstrapAsync(Settings());
                Assert.True(created.Created);
                Assert.Equal(1, created.Settings.Revision);
                break;
            case "settings":
                var updated = await fixture.Repository.UpdateSettingsAsync(Settings(1, "更新済み"), fixture.UserId);
                Assert.Equal(2, updated.Revision);
                Assert.Equal("更新済み", updated.Channels[0].DisplayName);
                break;
            default:
                var banned = await fixture.Repository.UpdateBanAsync(fixture.UserId, new() { IsBanned = true }, fixture.UserId);
                Assert.Equal(1, banned!.Revision);
                Assert.True(banned.IsActive);
                Assert.Single(await fixture.Management.Players.ToListAsync());
                break;
        }
        Assert.Equal(1, fault.Failures);
        Assert.Equal(operation == "settings" ? 2 : 1, await fixture.Management.NetworkAudits.CountAsync());
    }

    private sealed class TransientFaultException : Exception;
    private sealed class TransientFault
    {
        public bool Armed { get; set; }
        public int Failures { get; private set; }
        public void ThrowOnce()
        {
            if (!Armed) return;
            Armed = false;
            Failures++;
            throw new TransientFaultException();
        }
    }
    private sealed class FailAfterSave(TransientFault fault) : SaveChangesInterceptor
    {
        public override ValueTask<int> SavedChangesAsync(SaveChangesCompletedEventData eventData, int result, CancellationToken cancellationToken = default)
        {
            fault.ThrowOnce();
            return ValueTask.FromResult(result);
        }
    }
    private sealed class LoseCommitAcknowledgement(TransientFault fault) : DbTransactionInterceptor
    {
        public override Task TransactionCommittedAsync(DbTransaction transaction, TransactionEndEventData eventData, CancellationToken cancellationToken = default)
        {
            fault.ThrowOnce();
            return Task.CompletedTask;
        }
    }
    private sealed class RetryingStrategyFactory(ExecutionStrategyDependencies dependencies) : IExecutionStrategyFactory
    {
        public IExecutionStrategy Create() => new RetryingStrategy(dependencies);
    }
    private sealed class RetryingStrategy(ExecutionStrategyDependencies dependencies) : ExecutionStrategy(dependencies, 3, TimeSpan.Zero)
    {
        protected override bool ShouldRetryOn(Exception exception) => exception is TransientFaultException;
    }

    private static ManagedNetworkSettings Settings(int revision = 0, string name = "ロビー") => new()
    {
        Revision = revision, Channels = [new() { ServerId = "lobby", DisplayName = name }],
    };

    private sealed class Clock : TimeProvider
    {
        public DateTimeOffset UtcNow { get; set; } = new(2026, 9, 16, 0, 0, 0, TimeSpan.Zero);
        public override DateTimeOffset GetUtcNow() => UtcNow;
        public override TimeZoneInfo LocalTimeZone => TimeZoneInfo.Utc;
    }

    private sealed class Fixture : IAsyncDisposable
    {
        private readonly SqliteConnection gameConnection = new("Data Source=:memory:");
        private readonly SqliteConnection managementConnection = new("Data Source=:memory:");
        public Guid UserId { get; } = Guid.NewGuid();
        public Clock Clock { get; } = new();
        public AstralRecordDbContext Game { get; private set; } = null!;
        public ManagementDbContext Management { get; private set; } = null!;
        public NetworkManagementRepository Repository => new(Management, Game, Clock);

        public static async Task<Fixture> Create(params IInterceptor[] interceptors)
        {
            var fixture = new Fixture();
            await fixture.gameConnection.OpenAsync();
            await fixture.managementConnection.OpenAsync();
            fixture.Game = new(new DbContextOptionsBuilder<AstralRecordDbContext>().UseSqlite(fixture.gameConnection).Options);
            fixture.Management = new(new DbContextOptionsBuilder<ManagementDbContext>().UseSqlite(fixture.managementConnection)
                .ReplaceService<IExecutionStrategyFactory, RetryingStrategyFactory>().AddInterceptors(interceptors).Options);
            await fixture.Management.Database.EnsureCreatedAsync();
            await fixture.Game.Database.EnsureCreatedAsync();
            fixture.Game.Users.Add(new UserEntity
            {
                Uuid = fixture.UserId, Mcid = "Tester", GlobalIp = "127.0.0.1",
                JoinDate = fixture.Clock.GetUtcNow().UtcDateTime, LastJoinDate = fixture.Clock.GetUtcNow().UtcDateTime,
                CreatedAt = fixture.Clock.GetUtcNow().UtcDateTime, UpdatedAt = fixture.Clock.GetUtcNow().UtcDateTime,
                CreatedBy = fixture.UserId, UpdatedBy = fixture.UserId,
            });
            await fixture.Game.SaveChangesAsync();
            return fixture;
        }

        public async ValueTask DisposeAsync()
        {
            await Game.DisposeAsync();
            await Management.DisposeAsync();
            await gameConnection.DisposeAsync();
            await managementConnection.DisposeAsync();
        }
    }
}
