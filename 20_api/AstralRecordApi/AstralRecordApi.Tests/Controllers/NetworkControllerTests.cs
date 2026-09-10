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
            new FakeUserRepository(null), new NetworkRuntimeService(time), time, Configuration());

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
            new FakeUserRepository(user), new NetworkRuntimeService(time), time, Configuration());

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
            new FakeUserRepository(user), new NetworkRuntimeService(time), time, Configuration());

        var result = await controller.GetAdmission(user.Uuid);

        var response = Assert.IsType<NetworkAdmissionResponse>(Assert.IsType<OkObjectResult>(result).Value);
        Assert.True(response.Admitted);
        Assert.True(response.Registered);
        Assert.Equal(5, response.Permission);
    }

    [Fact]
    public async Task AdmissionUsesProxyAuthorityPermissionWithoutBypassingBan()
    {
        var time = new FixedTimeProvider(new DateTimeOffset(2026, 9, 4, 12, 0, 0, TimeSpan.Zero));
        var user = User(permission: 0, banDate: new DateTime(2026, 9, 4, 12, 5, 0));
        var runtime = new NetworkRuntimeService(time);
        runtime.ReplaceAuthorities(new NetworkAuthorityUpdateRequest([user.Uuid]));
        var controller = new NetworkController(new FakeUserRepository(user), runtime, time, Configuration());

        var result = await controller.GetAdmission(user.Uuid);

        var response = Assert.IsType<NetworkAdmissionResponse>(Assert.IsType<OkObjectResult>(result).Value);
        Assert.False(response.Admitted);
        Assert.Equal(99, response.Permission);
    }

    [Fact]
    public void AuthorityReplacementRejectsSharedApiCredentialWithoutProxySyncKey()
    {
        var runtime = new NetworkRuntimeService(TimeProvider.System);
        var controller = Controller(runtime, providedSyncKey: null);

        var result = controller.ReplaceAuthorities(
            new NetworkAuthorityUpdateRequest([Guid.NewGuid()]));

        Assert.IsType<UnauthorizedResult>(result);
        Assert.Empty(runtime.GetAuthorities());
    }

    [Fact]
    public void AuthorityReplacementAcceptsProxySyncKey()
    {
        var runtime = new NetworkRuntimeService(TimeProvider.System);
        var authority = Guid.NewGuid();
        var controller = Controller(runtime, "proxy-secret");

        var result = controller.ReplaceAuthorities(new NetworkAuthorityUpdateRequest([authority]));

        Assert.IsType<OkObjectResult>(result);
        Assert.True(runtime.IsAuthority(authority));
    }

    [Fact]
    public void AuthorityReplacementRejectsSyncKeyEqualToSharedApiKey()
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
            new FakeUserRepository(null), runtime, TimeProvider.System, configuration)
        {
            ControllerContext = new ControllerContext { HttpContext = new DefaultHttpContext() }
        };
        controller.Request.Headers["X-Authority-Sync-Key"] = "same-secret";

        var result = controller.ReplaceAuthorities(
            new NetworkAuthorityUpdateRequest([Guid.NewGuid()]));

        Assert.IsType<UnauthorizedResult>(result);
        Assert.Empty(runtime.GetAuthorities());
    }

    private static NetworkController Controller(NetworkRuntimeService runtime, string? providedSyncKey)
    {
        var controller = new NetworkController(
            new FakeUserRepository(null), runtime, TimeProvider.System, Configuration());
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
