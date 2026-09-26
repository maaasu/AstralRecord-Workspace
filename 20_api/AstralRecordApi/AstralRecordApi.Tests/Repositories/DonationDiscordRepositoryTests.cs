using System.Net;
using System.Text;
using System.Text.Json;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Options;
using AstralRecordApi.Repositories;
using AstralRecordApi.Services;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed class DonationDiscordRepositoryTests
{
    [Fact]
    public async Task Link_VerifiesDiscordIdentityAndMembership_EncryptsTokens_AndRejectsDuplicateIdentity()
    {
        await using var fixture = await Fixture.Create(request => request.RequestUri!.AbsolutePath switch
        {
            "/api/v10/users/@me" => Json(HttpStatusCode.OK, """{"id":"123456789012345678","username":"discord-user","global_name":"Discord Name"}"""),
            "/api/v10/users/@me/guilds/987654321012345678/member" => Json(HttpStatusCode.OK, "{}"),
            _ => throw new InvalidOperationException("Unexpected Discord endpoint"),
        });
        var first = Guid.NewGuid();
        var result = await fixture.Repository.LinkAsync(first, new("access-secret", "refresh-secret"));
        Assert.Equal("123456789012345678", result.DiscordUserId);
        Assert.Equal("Discord Name", result.DiscordName);
        Assert.True(result.IsGuildMember);
        Assert.Equal(result, await fixture.Repository.GetAsync(first));
        var row = await fixture.Db.Set<DonationDiscordLinkEntity>().SingleAsync();
        Assert.NotEqual("access-secret", row.ProtectedAccessToken);
        Assert.NotEqual("refresh-secret", row.ProtectedRefreshToken);
        Assert.DoesNotContain("access-secret", JsonSerializer.Serialize(result));
        Assert.DoesNotContain("refresh-secret", JsonSerializer.Serialize(result));
        await Assert.ThrowsAsync<ArgumentException>(() => fixture.Repository.LinkAsync(Guid.NewGuid(), new("access-secret", "refresh-secret")));
        Assert.Single(await fixture.Db.Set<DonationDiscordLinkEntity>().ToListAsync());
    }

    [Fact]
    public async Task Link_RejectsNonMemberWithoutSaving()
    {
        await using var fixture = await Fixture.Create(request => request.RequestUri!.AbsolutePath switch
        {
            "/api/v10/users/@me" => Json(HttpStatusCode.OK, """{"id":"123456789012345678","username":"user"}"""),
            "/api/v10/users/@me/guilds/987654321012345678/member" => Json(HttpStatusCode.NotFound, "{}"),
            _ => throw new InvalidOperationException("Unexpected Discord endpoint"),
        });
        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => fixture.Repository.LinkAsync(Guid.NewGuid(), new("access", "refresh")));
        Assert.Empty(await fixture.Db.Set<DonationDiscordLinkEntity>().ToListAsync());
    }

    [Fact]
    public async Task Verify_RefreshesRejectedAccessToken_AndPersistsRotatedTokens()
    {
        var refreshCount = 0;
        var accessExpired = false;
        await using var fixture = await Fixture.Create(request =>
        {
            var path = request.RequestUri!.AbsolutePath;
            if (path == "/api/v10/oauth2/token")
            {
                refreshCount++;
                return Json(HttpStatusCode.OK, """{"access_token":"new-access","refresh_token":"new-refresh","token_type":"Bearer"}""");
            }
            var token = request.Headers.Authorization?.Parameter;
            if (path == "/api/v10/users/@me")
                return Json(HttpStatusCode.OK, """{"id":"123456789012345678","username":"user"}""");
            if (path == "/api/v10/users/@me/guilds/987654321012345678/member")
                return Json(accessExpired && token == "old-access" ? HttpStatusCode.Unauthorized : HttpStatusCode.OK, "{}");
            throw new InvalidOperationException("Unexpected Discord endpoint");
        });
        var user = Guid.NewGuid();
        await fixture.Repository.LinkAsync(user, new("old-access", "old-refresh"));
        accessExpired = true;
        var verified = await fixture.Repository.VerifyMembershipAsync(user);
        Assert.True(verified.IsGuildMember);
        Assert.Equal(1, refreshCount);
        var row = await fixture.Db.Set<DonationDiscordLinkEntity>().SingleAsync();
        var protector = fixture.Protection.CreateProtector("AstralRecordApi.Donations.DiscordTokens.v1");
        Assert.Equal("new-access", protector.Unprotect(row.ProtectedAccessToken));
        Assert.Equal("new-refresh", protector.Unprotect(row.ProtectedRefreshToken));
        Assert.True((await fixture.Repository.VerifyMembershipAsync(user)).IsGuildMember);
        Assert.Equal(1, refreshCount);
    }

    [Fact]
    public async Task Verify_RecordsLostMembership_AndRejectsApplication()
    {
        var member = true;
        await using var fixture = await Fixture.Create(request => request.RequestUri!.AbsolutePath switch
        {
            "/api/v10/users/@me" => Json(HttpStatusCode.OK, """{"id":"123456789012345678","username":"user"}"""),
            "/api/v10/users/@me/guilds/987654321012345678/member" => Json(member ? HttpStatusCode.OK : HttpStatusCode.NotFound, "{}"),
            _ => throw new InvalidOperationException("Unexpected Discord endpoint"),
        });
        var user = Guid.NewGuid();
        await fixture.Repository.LinkAsync(user, new("access", "refresh"));
        member = false;
        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => fixture.Repository.VerifyMembershipAsync(user));
        Assert.False((await fixture.Repository.GetAsync(user))!.IsGuildMember);
    }

    [Fact]
    public async Task Verify_InvalidGrantDoesNotReplaceStoredTokenOrExposeSecrets()
    {
        var accessExpired = false;
        await using var fixture = await Fixture.Create(request => request.RequestUri!.AbsolutePath switch
        {
            "/api/v10/users/@me" => Json(HttpStatusCode.OK, """{"id":"123456789012345678","username":"user"}"""),
            "/api/v10/users/@me/guilds/987654321012345678/member" =>
                Json(accessExpired ? HttpStatusCode.Unauthorized : HttpStatusCode.OK, "{}"),
            "/api/v10/oauth2/token" => Json(HttpStatusCode.BadRequest, """{"error":"invalid_grant"}"""),
            _ => throw new InvalidOperationException("Unexpected Discord endpoint"),
        });
        var user = Guid.NewGuid();
        await fixture.Repository.LinkAsync(user, new("access-secret", "refresh-secret"));
        accessExpired = true;
        var error = await Assert.ThrowsAsync<UnauthorizedAccessException>(() => fixture.Repository.VerifyMembershipAsync(user));
        Assert.DoesNotContain("access-secret", error.ToString());
        Assert.DoesNotContain("refresh-secret", error.ToString());
        var row = await fixture.Db.Set<DonationDiscordLinkEntity>().SingleAsync();
        var protector = fixture.Protection.CreateProtector("AstralRecordApi.Donations.DiscordTokens.v1");
        Assert.Equal("access-secret", protector.Unprotect(row.ProtectedAccessToken));
        Assert.Equal("refresh-secret", protector.Unprotect(row.ProtectedRefreshToken));
    }

    private static HttpResponseMessage Json(HttpStatusCode status, string body) => new(status)
    {
        Content = new StringContent(body, Encoding.UTF8, "application/json"),
    };

    private sealed class FakeHttpHandler(Func<HttpRequestMessage, HttpResponseMessage> handle) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
            => Task.FromResult(handle(request));
    }

    private sealed class TestManagementDbContext(DbContextOptions<ManagementDbContext> options) : ManagementDbContext(options)
    {
        protected override void OnModelCreating(Microsoft.EntityFrameworkCore.ModelBuilder modelBuilder)
        {
            base.OnModelCreating(modelBuilder);
            DonationDiscordMapping.Configure(modelBuilder);
        }
    }

    private sealed class Fixture : IAsyncDisposable
    {
        private readonly SqliteConnection connection = new("Data Source=:memory:");
        private HttpClient http = null!;
        public TestManagementDbContext Db { get; private set; } = null!;
        public IDataProtectionProvider Protection { get; } = new EphemeralDataProtectionProvider();
        public DonationDiscordRepository Repository { get; private set; } = null!;

        public static async Task<Fixture> Create(Func<HttpRequestMessage, HttpResponseMessage> handle)
        {
            var fixture = new Fixture();
            await fixture.connection.OpenAsync();
            fixture.Db = new(new DbContextOptionsBuilder<ManagementDbContext>().UseSqlite(fixture.connection).Options);
            await fixture.Db.Database.EnsureCreatedAsync();
            fixture.http = new HttpClient(new FakeHttpHandler(handle));
            var client = new DiscordDonationClient(fixture.http, Microsoft.Extensions.Options.Options.Create(new DonationDiscordOptions
            {
                DiscordClientId = "client-id", DiscordClientSecret = "client-secret", DiscordGuildId = "987654321012345678",
            }));
            fixture.Repository = new(fixture.Db, client, fixture.Protection, TimeProvider.System);
            return fixture;
        }

        public async ValueTask DisposeAsync()
        {
            http.Dispose();
            await Db.DisposeAsync();
            await connection.DisposeAsync();
        }
    }
}
