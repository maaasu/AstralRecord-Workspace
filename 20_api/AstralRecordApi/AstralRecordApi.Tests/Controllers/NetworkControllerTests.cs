using AstralRecordApi.Controllers;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using AstralRecordApi.Services;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Configuration;
using Xunit;

namespace AstralRecordApi.Tests.Controllers;

public sealed class NetworkControllerTests
{
    [Fact]
    public async Task AdmissionAllowsUnregisteredUuidWithNormalPermission()
    {
        var time = new FixedTimeProvider(new DateTimeOffset(2026, 9, 4, 12, 0, 0, TimeSpan.Zero));
        var controller = new NetworkController(
            new FakeUserRepository(null), new NetworkRuntimeService(time), time, Configuration(), new NoManagedConfiguration());

        var result = await controller.GetAdmission(Guid.NewGuid());

        var response = Assert.IsType<NetworkAdmissionResponse>(Assert.IsType<OkObjectResult>(result).Value);
        Assert.True(response.Admitted);
        Assert.False(response.Registered);
        Assert.Equal(0, response.Permission);
    }

    [Fact]
    public async Task AdmissionRejectsActiveTimedBan()
    {
        var time = new FixedTimeProvider(new DateTimeOffset(2026, 9, 4, 12, 0, 0, TimeSpan.Zero));
        var user = User(permission: 99, banDate: new DateTime(2026, 9, 4, 12, 5, 0));
        var controller = new NetworkController(
            new FakeUserRepository(user), new NetworkRuntimeService(time), time, Configuration(), new NoManagedConfiguration());

        var result = await controller.GetAdmission(user.Uuid);

        var response = Assert.IsType<NetworkAdmissionResponse>(Assert.IsType<OkObjectResult>(result).Value);
        Assert.False(response.Admitted);
        Assert.Equal("banned", response.DenyReason);
        Assert.Equal(99, response.Permission);
    }

    [Fact]
    public async Task AdmissionAllowsExpiredTimedBan()
    {
        var time = new FixedTimeProvider(new DateTimeOffset(2026, 9, 4, 12, 0, 0, TimeSpan.Zero));
        var user = User(permission: 5, banDate: new DateTime(2026, 9, 4, 11, 59, 59));
        var controller = new NetworkController(
            new FakeUserRepository(user), new NetworkRuntimeService(time), time, Configuration(), new NoManagedConfiguration());

        var result = await controller.GetAdmission(user.Uuid);

        var response = Assert.IsType<NetworkAdmissionResponse>(Assert.IsType<OkObjectResult>(result).Value);
        Assert.True(response.Admitted);
        Assert.True(response.Registered);
        Assert.Equal(0, response.Permission);
    }

    [Fact]
    public async Task AdmissionUsesProxyAuthorityPermissionWithoutBypassingBan()
    {
        var time = new FixedTimeProvider(new DateTimeOffset(2026, 9, 4, 12, 0, 0, TimeSpan.Zero));
        var user = User(permission: 0, banDate: new DateTime(2026, 9, 4, 12, 5, 0));
        var runtime = new NetworkRuntimeService(time);
        runtime.ReplaceAuthorities(new NetworkAuthorityUpdateRequest([user.Uuid]));
        var controller = new NetworkController(new FakeUserRepository(user), runtime, time, Configuration(), new NoManagedConfiguration());

        var result = await controller.GetAdmission(user.Uuid);

        var response = Assert.IsType<NetworkAdmissionResponse>(Assert.IsType<OkObjectResult>(result).Value);
        Assert.False(response.Admitted);
        Assert.Equal(99, response.Permission);
    }

    [Fact]
    public async Task AuthorityReplacementRejectsSharedApiCredentialWithoutProxySyncKey()
    {
        var runtime = new NetworkRuntimeService(TimeProvider.System);
        var controller = Controller(runtime, providedSyncKey: null);

        var result = await controller.ReplaceAuthorities(
            new NetworkAuthorityUpdateRequest([Guid.NewGuid()]));

        Assert.IsType<UnauthorizedResult>(result);
        Assert.Empty(runtime.GetAuthorities());
    }

    [Fact]
    public void ChatPublishAcceptsCompleteMinecraftPlayerIdentity()
    {
        var runtime = new NetworkRuntimeService(TimeProvider.System);
        var controller = Controller(runtime, providedSyncKey: null);
        var playerId = Guid.NewGuid();

        var result = controller.PublishChat(new NetworkChatPublishRequest(
            Guid.NewGuid(), "minecraft", "ch1", "AstralRecord#1", "こんにちは", "chat",
            playerId, "AstralRecord"));

        var response = Assert.IsType<NetworkChatMessageResponse>(Assert.IsType<OkObjectResult>(result).Value);
        Assert.Equal(playerId, response.AuthorPlayerId);
        Assert.Equal("AstralRecord", response.AuthorMinecraftName);
    }

    [Fact]
    public void ChatPublishRejectsPartialMinecraftPlayerIdentity()
    {
        var controller = Controller(new NetworkRuntimeService(TimeProvider.System), providedSyncKey: null);

        var result = controller.PublishChat(new NetworkChatPublishRequest(
            Guid.NewGuid(), "minecraft", "ch1", "AstralRecord#1", "こんにちは", "chat",
            Guid.NewGuid(), null));

        Assert.IsType<BadRequestResult>(result);
    }

    [Fact]
    public void ChatPublishAcceptsLifecycleActionWithPlayerIdentity()
    {
        var runtime = new NetworkRuntimeService(TimeProvider.System);
        var controller = Controller(runtime, providedSyncKey: null);
        var playerId = Guid.NewGuid();

        var result = controller.PublishChat(new NetworkChatPublishRequest(
            Guid.NewGuid(), "minecraft", "proxy", "AstralRecord", "参加しました", "lifecycle",
            playerId, "AstralRecord", "join"));

        var response = Assert.IsType<NetworkChatMessageResponse>(Assert.IsType<OkObjectResult>(result).Value);
        Assert.Equal("join", response.Action);
        Assert.Equal(playerId, response.AuthorPlayerId);
    }

    [Fact]
    public void ChatPublishRejectsLifecycleActionWithoutPlayerIdentity()
    {
        var controller = Controller(new NetworkRuntimeService(TimeProvider.System), providedSyncKey: null);

        var result = controller.PublishChat(new NetworkChatPublishRequest(
            Guid.NewGuid(), "minecraft", "proxy", "AstralRecord", "参加しました", "lifecycle",
            null, null, "join"));

        Assert.IsType<BadRequestResult>(result);
    }

    [Fact]
    public void ChatPublishRejectsLifecycleActionOnChat()
    {
        var controller = Controller(new NetworkRuntimeService(TimeProvider.System), providedSyncKey: null);

        var result = controller.PublishChat(new NetworkChatPublishRequest(
            Guid.NewGuid(), "minecraft", "ch1", "AstralRecord", "こんにちは", "chat",
            Guid.NewGuid(), "AstralRecord", "join"));

        Assert.IsType<BadRequestResult>(result);
    }

    [Fact]
    public void ChatPublishRejectsLifecycleActionFromDiscord()
    {
        var controller = Controller(new NetworkRuntimeService(TimeProvider.System), providedSyncKey: null);

        var result = controller.PublishChat(new NetworkChatPublishRequest(
            Guid.NewGuid(), "discord", "lobby", "DiscordUser", "参加しました", "lifecycle",
            Guid.NewGuid(), "AstralRecord", "join"));

        Assert.IsType<BadRequestResult>(result);
    }

    [Fact]
    public async Task AuthorityReplacementAcceptsProxySyncKey()
    {
        var runtime = new NetworkRuntimeService(TimeProvider.System);
        var authority = Guid.NewGuid();
        var controller = Controller(runtime, "proxy-secret");

        var result = await controller.ReplaceAuthorities(new NetworkAuthorityUpdateRequest([authority]));

        Assert.IsType<OkObjectResult>(result);
        Assert.True(runtime.IsAuthority(authority));
    }

    [Fact]
    public async Task AuthorityReplacementRejectsSyncKeyEqualToSharedApiKey()
    {
        var runtime = new NetworkRuntimeService(TimeProvider.System);
        var configuration = new ConfigurationBuilder()
            .AddInMemoryCollection(new Dictionary<string, string?>
            {
                ["ApiKey:Key"] = "same-secret",
                ["Network:AuthoritySyncKey"] = "same-secret"
            })
            .Build();
        var controller = new NetworkController(
            new FakeUserRepository(null), runtime, TimeProvider.System, configuration, new NoManagedConfiguration())
        {
            ControllerContext = new ControllerContext { HttpContext = new DefaultHttpContext() }
        };
        controller.Request.Headers["X-Authority-Sync-Key"] = "same-secret";

        var result = await controller.ReplaceAuthorities(
            new NetworkAuthorityUpdateRequest([Guid.NewGuid()]));

        Assert.IsType<UnauthorizedResult>(result);
        Assert.Empty(runtime.GetAuthorities());
    }

    [Theory]
    [InlineData(null, "dedicated")]
    [InlineData("wrong", "dedicated")]
    [InlineData("shared-secret", "shared-secret")]
    [InlineData("anything", "")]
    public async Task RuntimeBanRejectsMissingWrongOrSharedModerationCredential(string? provided, string expected)
    {
        var configuration = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
        {
            ["ApiKey:Key"] = "shared-secret", ["Network:ModerationKey"] = expected,
        }).Build();
        var controller = new NetworkController(new FakeUserRepository(null), new NetworkRuntimeService(TimeProvider.System),
            TimeProvider.System, configuration, new NoManagedConfiguration())
        {
            ControllerContext = new() { HttpContext = new DefaultHttpContext() },
        };
        if (provided is not null) controller.Request.Headers["X-Network-Moderation-Key"] = provided;
        // The repository throws if called: no actor (including Console) may bypass the dedicated credential.
        Assert.IsType<UnauthorizedResult>(await controller.UpdateRuntimeBan(Guid.NewGuid(), Guid.Empty, new()));
    }

    [Fact]
    public async Task ManagedAdmissionEnforcesBanBeforeAuthority_AndUsesManagedClearOverLegacy()
    {
        var user = User(0, DateTime.UtcNow.AddYears(1));
        var management = new NoManagedConfiguration
        {
            Settings = new() { AuthorityUsers = [user.Uuid], Channels = [new() { ServerId = "lobby", DisplayName = "Lobby", WhitelistEnabled = true }] },
            Ban = new() { UserUuid = user.Uuid, IsActive = true, IsBanned = true, IsIndefinite = true },
        };
        var controller = new NetworkController(new FakeUserRepository(user), new NetworkRuntimeService(TimeProvider.System), TimeProvider.System, Configuration(), management);
        var denied = Assert.IsType<NetworkAdmissionResponse>(Assert.IsType<OkObjectResult>(await controller.GetAdmission(user.Uuid)).Value);
        Assert.Equal("banned", denied.DenyReason);
        Assert.Equal(99, denied.Permission);
        management.Ban = new() { UserUuid = user.Uuid, Revision = 1, IsActive = false };
        var cleared = Assert.IsType<NetworkAdmissionResponse>(Assert.IsType<OkObjectResult>(await controller.GetAdmission(user.Uuid)).Value);
        Assert.True(cleared.Admitted);
        Assert.Null(cleared.BanDate);
        var unknown = Assert.IsType<NetworkAdmissionResponse>(Assert.IsType<OkObjectResult>(await controller.GetAdmission(user.Uuid, "missing")).Value);
        Assert.Equal("unknown_channel", unknown.DenyReason);
        Assert.False(unknown.Admitted);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task RuntimeBanRequiresActorPermissionAfterDedicatedCredential(bool allowed)
    {
        var management = new NoManagedConfiguration { RuntimeAllowed = allowed, Ban = new() { Revision = 1 } };
        var configuration = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
        {
            ["ApiKey:Key"] = "shared", ["Network:ModerationKey"] = "rpg-only",
        }).Build();
        var controller = new NetworkController(new FakeUserRepository(null), new NetworkRuntimeService(TimeProvider.System), TimeProvider.System, configuration, management)
        {
            ControllerContext = new() { HttpContext = new DefaultHttpContext() },
        };
        controller.Request.Headers["X-Network-Moderation-Key"] = "rpg-only";
        var result = await controller.UpdateRuntimeBan(Guid.NewGuid(), Guid.NewGuid(), new() { IsBanned = true });
        if (allowed) Assert.IsType<OkObjectResult>(result);
        else Assert.Equal(403, Assert.IsType<StatusCodeResult>(result).StatusCode);
        Assert.Equal(allowed ? 1 : 0, management.BanWrites);
    }

    private static NetworkController Controller(NetworkRuntimeService runtime, string? providedSyncKey)
    {
        var controller = new NetworkController(
            new FakeUserRepository(null), runtime, TimeProvider.System, Configuration(), new NoManagedConfiguration());
        controller.ControllerContext = new ControllerContext { HttpContext = new DefaultHttpContext() };
        if (providedSyncKey is not null)
            controller.Request.Headers["X-Authority-Sync-Key"] = providedSyncKey;
        return controller;
    }

    private static IConfiguration Configuration() => new ConfigurationBuilder()
        .AddInMemoryCollection(new Dictionary<string, string?>
        {
            ["ApiKey:Key"] = "shared-secret",
            ["Network:AuthoritySyncKey"] = "proxy-secret"
        })
        .Build();

    private static UserResponse User(int permission, DateTime? banDate = null) => new()
    {
        Uuid = Guid.NewGuid(),
        Mcid = "AstralRecord",
        Permission = permission,
        BanDate = banDate,
        BanIndefinite = false
    };

    private sealed class FixedTimeProvider(DateTimeOffset now) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => now;
        public override TimeZoneInfo LocalTimeZone => TimeZoneInfo.Utc;
    }

    private sealed class NoManagedConfiguration : INetworkManagementRepository
    {
        public ManagedNetworkSettings? Settings { get; set; }
        public NetworkBanStateResponse? Ban { get; set; }
        public bool? RuntimeAllowed { get; set; }
        public int BanWrites { get; private set; }
        public Task<ManagedNetworkSettings?> GetSettingsAsync(bool includePlayers = false) => Task.FromResult(Settings);
        public Task<NetworkBanStateResponse?> GetBanAsync(Guid uuid) => Task.FromResult(Ban);
        public Task<(ManagedNetworkSettings Settings, bool Created)> BootstrapAsync(ManagedNetworkSettings request) => throw new NotSupportedException();
        public Task<ManagedNetworkSettings> UpdateSettingsAsync(ManagedNetworkSettings request, Guid actorUuid) => throw new NotSupportedException();
        public Task<IReadOnlyList<NetworkManagedPlayer>> SearchPlayersAsync(string? query) => throw new NotSupportedException();
        public Task<NetworkChannelAccessResponse> GetChannelAccessAsync(Guid userUuid, string serverId) => Task.FromResult(NetworkAccessPolicy.Evaluate(userUuid, serverId, Settings, 0));
        public Task<IReadOnlyList<NetworkBanStateResponse>> GetActiveBansAsync() => throw new NotSupportedException();
        public Task<NetworkBanStateResponse?> UpdateBanAsync(Guid userUuid, NetworkBanUpdateRequest request, Guid actorUuid)
        {
            BanWrites++;
            return Task.FromResult(Ban);
        }
        public Task<bool> CanManageBanFromGameAsync(Guid actorUuid) => Task.FromResult(RuntimeAllowed ?? throw new NotSupportedException());
    }

    private sealed class FakeUserRepository(UserResponse? user) : IUserRepository
    {
        public Task<UserResponse?> GetByUuidAsync(Guid uuid) => Task.FromResult(user);
        public Task<UserResponse?> GetByMcidAsync(string mcid) => throw new NotSupportedException();
        public Task<IReadOnlyList<string>> GetMcidsAsync(string? prefix) => throw new NotSupportedException();
        public Task<bool> HasOtherByGlobalIpAsync(string globalIp, Guid excludingUuid) => throw new NotSupportedException();
        public Task<UserResponse> CreateAsync(UserCreateRequest request) => throw new NotSupportedException();
        public Task<UserResponse?> UpdateAsync(Guid uuid, UserUpdateRequest request) => throw new NotSupportedException();
        public Task<UserHistoryResponse> CreateHistoryAsync(UserHistoryCreateRequest request) => throw new NotSupportedException();
    }
}
