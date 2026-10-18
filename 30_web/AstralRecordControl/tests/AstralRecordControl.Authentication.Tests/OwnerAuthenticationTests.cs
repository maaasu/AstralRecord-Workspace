using System.Net;
using System.Security.Cryptography;
using System.Text.RegularExpressions;
using AstralRecordControl.Web.Authentication;
using AstralRecordControl.Web.Data;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Xunit;

namespace AstralRecordControl.Authentication.Tests;

public sealed class OwnerAuthenticationTests
{
    [Theory]
    [InlineData("//server/share/control-data")]
    [InlineData("\\\\server\\share\\control-data")]
    public void StorageRejectsUncBeforeOpeningAnyNetworkPath(string path) => Assert.Throws<InvalidOperationException>(() => new ControlStorage(path));

    private const string Password = "Valid-Owner-Password1!";

    [Fact]
    public async Task OwnerSingletonAndDatabaseConstraintRejectSecondOwner()
    {
        await using var factory = new AuthFactory();
        using var scope = factory.Services.CreateScope();
        var auth = scope.ServiceProvider.GetRequiredService<OwnerAuthService>();
        Assert.True((await auth.CreateAsync("owner", Password)).Succeeded);
        Assert.False((await auth.CreateAsync("other", Password)).Succeeded);
        var users = scope.ServiceProvider.GetRequiredService<UserManager<ControlUser>>();
        await Assert.ThrowsAsync<DbUpdateException>(() => users.CreateAsync(new ControlUser { Id = Guid.NewGuid(), UserName = "bypass" }, Password));
    }

    [Fact]
    public async Task PasswordOnlyCannotAccessOwnerAndMfaEnrollmentProducesSecureCookie()
    {
        await using var factory = new AuthFactory();
        using var client = factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new Uri("https://localhost"), AllowAutoRedirect = false });
        await CreateOwner(factory);
        Assert.Equal(HttpStatusCode.Redirect, (await client.GetAsync("/Security")).StatusCode);
        var response = await Post(client, "/Login", new() { ["UserName"] = "owner", ["Password"] = Password });
        Assert.Equal("/Mfa/Enroll", response.Headers.Location?.OriginalString);
        Assert.Equal(HttpStatusCode.Redirect, (await client.GetAsync("/Security")).StatusCode);
        var enrollment = await client.GetStringAsync("/Mfa/Enroll");
        Assert.Contains("otpauth://", enrollment);
        var key = await GetKey(factory);
        response = await Post(client, "/Mfa/Enroll", new() { ["Code"] = Totp(key) });
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Contains("回復コード", await response.Content.ReadAsStringAsync());
        var cookie = response.Headers.GetValues("Set-Cookie").Single(x => x.StartsWith(OwnerAuthentication.CookieName + "="));
        Assert.Contains("secure", cookie, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("httponly", cookie, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("samesite=lax", cookie, StringComparison.OrdinalIgnoreCase);
        Assert.Equal(HttpStatusCode.OK, (await client.GetAsync("/Security")).StatusCode);
    }

    [Fact]
    public async Task ResetRevokesExistingCookieAndRecoveryCodeAndRequiresEnrollmentAgain()
    {
        await using var factory = new AuthFactory();
        using var client = factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new Uri("https://localhost"), AllowAutoRedirect = false });
        await CreateOwner(factory);
        await LoginEnrolled(factory, client);
        using (var scope = factory.Services.CreateScope())
        {
            var users = scope.ServiceProvider.GetRequiredService<UserManager<ControlUser>>();
            var user = (await users.FindByNameAsync("owner"))!;
            var code = (await users.GenerateNewTwoFactorRecoveryCodesAsync(user, 1))!.Single();
            Assert.True((await scope.ServiceProvider.GetRequiredService<OwnerAuthService>().ResetAsync(Password)).Succeeded);
            Assert.False((await users.RedeemTwoFactorRecoveryCodeAsync(user, code)).Succeeded);
        }
        Assert.Equal(HttpStatusCode.Redirect, (await client.GetAsync("/Security")).StatusCode);
        var response = await Post(client, "/Login", new() { ["UserName"] = "owner", ["Password"] = Password });
        Assert.Equal("/Mfa/Enroll", response.Headers.Location?.OriginalString);
    }

    [Fact]
    public async Task RecoveryCodeIsSingleUseAndAccountLocksAfterTenFailures()
    {
        await using var factory = new AuthFactory();
        await CreateOwner(factory);
        using var scope = factory.Services.CreateScope();
        var users = scope.ServiceProvider.GetRequiredService<UserManager<ControlUser>>();
        var user = (await users.FindByNameAsync("owner"))!;
        await users.SetTwoFactorEnabledAsync(user, true);
        var code = (await users.GenerateNewTwoFactorRecoveryCodesAsync(user, 1))!.Single();
        var auth = scope.ServiceProvider.GetRequiredService<OwnerAuthService>();
        Assert.True(await auth.VerifyMfaAsync(user, code, true));
        Assert.False(await auth.VerifyMfaAsync(user, code, true));
        for (var i = 0; i < 10; i++) Assert.Null(await auth.CheckPasswordAsync("owner", "invalid"));
        Assert.True(await users.IsLockedOutAsync(user));
        Assert.Null(await auth.CheckPasswordAsync("owner", Password));
    }

    [Fact]
    public async Task CookieMutationsRequireCsrfAndFixedOriginAndLogoutRevokesSession()
    {
        await using var factory = new AuthFactory();
        using var client = factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new Uri("https://localhost"), AllowAutoRedirect = false });
        await CreateOwner(factory);
        await LoginEnrolled(factory, client);
        using var invalid = new HttpRequestMessage(HttpMethod.Post, "/Logout") { Content = new FormUrlEncodedContent(new Dictionary<string, string>()) };
        invalid.Headers.Add("Origin", "https://localhost");
        Assert.Equal(HttpStatusCode.BadRequest, (await client.SendAsync(invalid)).StatusCode);
        var html = await client.GetStringAsync("/Logout");
        using var crossOrigin = new HttpRequestMessage(HttpMethod.Post, "/Logout") { Content = new FormUrlEncodedContent(new Dictionary<string, string> { ["__RequestVerificationToken"] = Token(html) }) };
        crossOrigin.Headers.Add("Origin", "https://evil.example");
        Assert.Equal(HttpStatusCode.Forbidden, (await client.SendAsync(crossOrigin)).StatusCode);
        Assert.Equal(HttpStatusCode.Redirect, (await Post(client, "/Logout", new())).StatusCode);
        using var scope = factory.Services.CreateScope();
        Assert.All(await scope.ServiceProvider.GetRequiredService<ControlDbContext>().OwnerSessions.ToListAsync(), x => Assert.NotNull(x.RevokedAtUtc));
    }

    [Fact]
    public async Task AbsoluteEightHourSessionExpiresAndClosesHubConnection()
    {
        await using var factory = new AuthFactory();
        using var client = factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new Uri("https://localhost"), AllowAutoRedirect = false });
        await CreateOwner(factory);
        await LoginEnrolled(factory, client);
        using var scope = factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<ControlDbContext>();
        var session = await db.OwnerSessions.SingleAsync();
        Assert.Equal(TimeSpan.FromHours(8), session.ExpiresAtUtc - session.CreatedAtUtc);
        var aborted = false;
        var connections = factory.Services.GetRequiredService<OwnerSessionConnections>();
        connections.Register(session.SessionId, "connection", () => aborted = true);
        await db.OwnerSessions.Where(x => x.SessionId == session.SessionId).ExecuteUpdateAsync(s => s.SetProperty(x => x.ExpiresAtUtc, DateTime.UtcNow.AddSeconds(-1)));
        Assert.Null(await scope.ServiceProvider.GetRequiredService<OwnerAuthService>().ValidateSessionAsync(session.SessionId));
        await scope.ServiceProvider.GetRequiredService<OwnerAuthService>().RevokeAsync(session.SessionId);
        Assert.True(aborted);
        Assert.Equal(HttpStatusCode.Redirect, (await client.GetAsync("/Security")).StatusCode);
    }

    [Fact]
    public async Task EnrolledOwnerLogsInWithTotpAndLogoutAllInvalidatesEverySession()
    {
        await using var factory = new AuthFactory();
        using var first = factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new Uri("https://localhost"), AllowAutoRedirect = false });
        using var second = factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new Uri("https://localhost"), AllowAutoRedirect = false });
        await CreateOwner(factory);
        await LoginEnrolled(factory, first);
        Assert.Equal("/Mfa/Verify", (await Post(second, "/Login", new() { ["UserName"] = "owner", ["Password"] = Password })).Headers.Location?.OriginalString);
        Assert.Equal(HttpStatusCode.Redirect, (await second.GetAsync("/Security")).StatusCode);
        Assert.Equal(HttpStatusCode.Redirect, (await Post(second, "/Mfa/Verify", new() { ["Code"] = Totp(await GetKey(factory)) })).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await second.GetAsync("/Security")).StatusCode);
        var html = await first.GetStringAsync("/Security");
        using var request = new HttpRequestMessage(HttpMethod.Post, "/Security?handler=LogoutAll") { Content = new FormUrlEncodedContent(new Dictionary<string, string> { ["__RequestVerificationToken"] = Token(html) }) };
        request.Headers.Add("Origin", "https://localhost");
        Assert.Equal(HttpStatusCode.Redirect, (await first.SendAsync(request)).StatusCode);
        Assert.Equal(HttpStatusCode.Redirect, (await second.GetAsync("/Security")).StatusCode);
    }

    [Fact]
    public async Task LoginRateLimitRejectsSixthAttempt()
    {
        await using var factory = new AuthFactory();
        using var client = factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new Uri("https://localhost"), AllowAutoRedirect = false });
        for (var i = 0; i < 5; i++) Assert.Equal(HttpStatusCode.OK, (await Post(client, "/Login", new() { ["UserName"] = "missing", ["Password"] = "bad" })).StatusCode);
        Assert.Equal(HttpStatusCode.TooManyRequests, (await Post(client, "/Login", new() { ["UserName"] = "missing", ["Password"] = "bad" })).StatusCode);
    }

    [Fact]
    public async Task UnknownSchemaAndConcurrentWebLeaseAreRejected()
    {
        var path = Path.Combine(Path.GetTempPath(), "control-schema-" + Guid.NewGuid());
        var storage = new ControlStorage(path);
        try
        {
            using (storage.AcquireExclusiveLease()) Assert.Throws<IOException>(() => storage.AcquireExclusiveLease());
            await using var db = new ControlDbContext(new DbContextOptionsBuilder<ControlDbContext>().UseSqlite(storage.ConnectionString).Options);
            await db.Database.ExecuteSqlRawAsync("PRAGMA user_version=99;");
            await Assert.ThrowsAsync<InvalidOperationException>(() => ControlDatabase.InitializeAsync(db));
        }
        finally { SqliteConnection.ClearAllPools(); Directory.Delete(path, true); }
    }

    private static async Task CreateOwner(AuthFactory factory)
    {
        using var scope = factory.Services.CreateScope();
        Assert.True((await scope.ServiceProvider.GetRequiredService<OwnerAuthService>().CreateAsync("owner", Password)).Succeeded);
    }
    private static async Task<string> GetKey(AuthFactory factory)
    {
        using var scope = factory.Services.CreateScope();
        var users = scope.ServiceProvider.GetRequiredService<UserManager<ControlUser>>();
        return (await users.GetAuthenticatorKeyAsync((await users.FindByNameAsync("owner"))!))!;
    }
    private static async Task LoginEnrolled(AuthFactory factory, HttpClient client)
    {
        await Post(client, "/Login", new() { ["UserName"] = "owner", ["Password"] = Password });
        await client.GetStringAsync("/Mfa/Enroll");
        Assert.Equal(HttpStatusCode.OK, (await Post(client, "/Mfa/Enroll", new() { ["Code"] = Totp(await GetKey(factory)) })).StatusCode);
    }
    private static string Token(string html) => WebUtility.HtmlDecode(Regex.Match(html, "name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\"").Groups[1].Value);
    private static async Task<HttpResponseMessage> Post(HttpClient client, string path, Dictionary<string, string> values)
    {
        values["__RequestVerificationToken"] = Token(await client.GetStringAsync(path));
        using var request = new HttpRequestMessage(HttpMethod.Post, path) { Content = new FormUrlEncodedContent(values) };
        request.Headers.Add("Origin", "https://localhost");
        return await client.SendAsync(request);
    }
    private static string Totp(string key)
    {
        const string alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        var bytes = new List<byte>(); var buffer = 0; var bits = 0;
        foreach (var c in key) { buffer = (buffer << 5) | alphabet.IndexOf(c); bits += 5; if (bits >= 8) { bits -= 8; bytes.Add((byte)(buffer >> bits)); } }
        var step = BitConverter.GetBytes(DateTimeOffset.UtcNow.ToUnixTimeSeconds() / 30);
        if (BitConverter.IsLittleEndian) Array.Reverse(step);
        var hash = HMACSHA1.HashData(bytes.ToArray(), step);
        var offset = hash[^1] & 15;
        var value = ((hash[offset] & 127) << 24) | (hash[offset + 1] << 16) | (hash[offset + 2] << 8) | hash[offset + 3];
        return (value % 1000000).ToString("D6");
    }

    private sealed class AuthFactory : WebApplicationFactory<Program>
    {
        private readonly string root = Path.Combine(Path.GetTempPath(), "control-auth-" + Guid.NewGuid());
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            builder.UseEnvironment("Testing");
            builder.UseSetting("Control:DataRoot", root);
            builder.UseSetting("Control:PublicOrigin", "https://localhost");
            builder.ConfigureAppConfiguration((_, config) => config.AddInMemoryCollection(new Dictionary<string, string?> { ["Control:DataRoot"] = root, ["Control:PublicOrigin"] = "https://localhost" }));
        }
        public override async ValueTask DisposeAsync()
        {
            await base.DisposeAsync();
            SqliteConnection.ClearAllPools();
            if (Directory.Exists(root)) Directory.Delete(root, true);
        }
    }
}
