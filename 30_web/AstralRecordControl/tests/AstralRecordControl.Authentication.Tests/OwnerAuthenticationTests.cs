using System.Net;
using System.Net.Http.Headers;
using System.Security.Claims;
using System.Text;
using System.Text.RegularExpressions;
using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Authentication;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Options;
using Xunit;

namespace AstralRecordControl.Authentication.Tests;

public sealed class OwnerAuthenticationTests
{
    private const string Password = "Valid-Owner-Password1!";

    [Fact]
    public async Task NewOwnerLogsInDirectlyAndReceivesSecureCookie()
    {
        await using var factory = new AuthFactory();
        using var client = Client(factory);
        await CreateOwner(factory);
        Assert.Equal(HttpStatusCode.Redirect, (await client.GetAsync("/Security")).StatusCode);
        using var login = await Post(client, "/Login", new() { ["UserName"] = "owner", ["Password"] = Password });
        Assert.Equal("/", login.Headers.Location?.OriginalString);
        var cookie = login.Headers.GetValues("Set-Cookie").Single(x => x.StartsWith(OwnerAuthentication.CookieName + "="));
        Assert.Contains("secure", cookie, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("httponly", cookie, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("samesite=lax", cookie, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("expires=", cookie, StringComparison.OrdinalIgnoreCase);
        Assert.Equal(HttpStatusCode.OK, (await client.GetAsync("/Security")).StatusCode);
    }

    [Fact]
    public async Task ExistingMfaOwnerLogsInWithPasswordAndLegacyMfaPagesRedirect()
    {
        await using var factory = new AuthFactory();
        using var client = Client(factory);
        await CreateOwner(factory);
        using (var scope = factory.Services.CreateScope())
        {
            var users = scope.ServiceProvider.GetRequiredService<UserManager<ControlUser>>();
            Assert.True((await users.SetTwoFactorEnabledAsync((await users.FindByNameAsync("owner"))!, true)).Succeeded);
        }
        Assert.Equal("/", (await Login(client)).Headers.Location?.OriginalString);
        Assert.Equal(HttpStatusCode.OK, (await client.GetAsync("/Security")).StatusCode);
        Assert.Equal("/", (await client.GetAsync("/Mfa/Enroll")).Headers.Location?.OriginalString);
        Assert.Equal("/", (await client.GetAsync("/Mfa/Verify")).Headers.Location?.OriginalString);
        using var anonymous = Client(factory);
        Assert.Equal("/Login", (await anonymous.GetAsync("/Mfa/Enroll")).Headers.Location?.OriginalString);
        Assert.Equal("/Login", (await anonymous.GetAsync("/Mfa/Verify")).Headers.Location?.OriginalString);
    }

    [Fact]
    public async Task OldPendingCookieCannotAccessOwnerAndLegacyMfaCookieIsRejected()
    {
        await using var factory = new AuthFactory();
        using var client = Client(factory);
        await CreateOwner(factory);
        client.DefaultRequestHeaders.Add("Cookie", "__Host-ControlPending=obsolete");
        Assert.Equal(HttpStatusCode.Redirect, (await client.GetAsync("/Security")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync("/api/control/v1/hosts")).StatusCode);
        using var login = await Login(client);
        Assert.Equal("/", login.Headers.Location?.OriginalString);
        Assert.Equal(HttpStatusCode.OK, (await client.GetAsync("/Security")).StatusCode);

        using var scope = factory.Services.CreateScope();
        var session = await scope.ServiceProvider.GetRequiredService<ControlDbContext>().OwnerSessions.SingleAsync();
        var claims = new ClaimsIdentity([
            new Claim(ClaimTypes.NameIdentifier, session.OwnerUserId.ToString()),
            new Claim("amr", "mfa"),
            new Claim(OwnerAuthentication.SessionClaim, session.SessionId.ToString()),
            new Claim(OwnerAuthentication.StampClaim, session.SecurityStamp)
        ], OwnerAuthentication.Scheme);
        var properties = new AuthenticationProperties { IssuedUtc = DateTimeOffset.UtcNow, ExpiresUtc = DateTimeOffset.UtcNow.AddHours(8) };
        var ticket = new AuthenticationTicket(new ClaimsPrincipal(claims), properties, OwnerAuthentication.Scheme);
        var format = scope.ServiceProvider.GetRequiredService<IOptionsMonitor<CookieAuthenticationOptions>>().Get(OwnerAuthentication.Scheme).TicketDataFormat;
        using var legacy = factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new Uri("https://localhost"), AllowAutoRedirect = false, HandleCookies = false });
        legacy.DefaultRequestHeaders.Add("Cookie", $"{OwnerAuthentication.CookieName}={format.Protect(ticket)}");
        Assert.Equal(HttpStatusCode.Unauthorized, (await legacy.GetAsync("/api/control/v1/hosts")).StatusCode);
    }

    [Fact]
    public async Task WrongPasswordAndNonOwnerCannotAccessOwnerEndpoints()
    {
        await using var factory = new AuthFactory();
        using var client = Client(factory);
        await CreateOwner(factory);
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync("/api/control/v1/hosts")).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await Post(client, "/Login", new() { ["UserName"] = "owner", ["Password"] = "wrong" })).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await Post(client, "/Login", new() { ["UserName"] = "other", ["Password"] = Password })).StatusCode);
        Assert.Equal(HttpStatusCode.Redirect, (await client.GetAsync("/Security")).StatusCode);
    }

    [Fact]
    public async Task CliUsesPasswordAndRemainsSeparateFromOwnerAndAgentCredentials()
    {
        await using var factory = new AuthFactory();
        await CreateOwner(factory);
        using var anonymous = factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new Uri("https://localhost"), AllowAutoRedirect = false, HandleCookies = false });
        async Task<HttpResponseMessage> CliLogin(string password) => await anonymous.PostAsync("/api/control/v1/auth/cli-token", new StringContent(ControlJson.Serialize(new CliLoginRequest("owner", password)), Encoding.UTF8, "application/json"));
        Assert.Equal(HttpStatusCode.Unauthorized, (await CliLogin("wrong")).StatusCode);
        using var issuedResponse = await CliLogin(Password);
        Assert.Equal(HttpStatusCode.OK, issuedResponse.StatusCode);
        var issued = ControlJson.Deserialize<CliTokenIssued>(await issuedResponse.Content.ReadAsStringAsync());
        Assert.Equal(DateTimeOffset.MaxValue, issued.ExpiresAtUtc);
        anonymous.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", issued.Token);
        Assert.Equal(HttpStatusCode.Unauthorized, (await anonymous.GetAsync("/api/control/v1/hosts")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await anonymous.GetAsync("/api/agent/v1/inventory")).StatusCode);
        using var scope = factory.Services.CreateScope();
        var service = scope.ServiceProvider.GetRequiredService<CliCredentialService>();
        Assert.NotNull(await service.AuthenticateAsync(issued.Token));
        var db = scope.ServiceProvider.GetRequiredService<ControlDbContext>();
        Assert.NotEqual(issued.Token, (await db.CliCredentials.SingleAsync()).TokenHashHex);
        await scope.ServiceProvider.GetRequiredService<OwnerAuthService>().RevokeAllAsync((await db.OwnerSettings.SingleAsync()).OwnerUserId);
        Assert.Null(await service.AuthenticateAsync(issued.Token));
    }

    [Fact]
    public async Task CliCanEnrollARegisteredHostWithoutAWebCookieAndRevokeTheIssuedAgentCredential()
    {
        await using var factory = new AuthFactory();
        await CreateOwner(factory);
        var hostId = Guid.NewGuid();
        using (var scope = factory.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<ControlDbContext>();
            db.Hosts.Add(new InventoryHost { HostId = hostId, HostKey = "cli-host", DisplayName = "CLI Host" });
            await db.SaveChangesAsync();
        }
        using var client = factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new Uri("https://localhost"), AllowAutoRedirect = false, HandleCookies = false });
        var path = $"/api/control/v1/hosts/{hostId:D}/enrollment";
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.PostAsync(path, null)).StatusCode);
        using var login = await client.PostAsync("/api/control/v1/auth/cli-token", new StringContent(ControlJson.Serialize(new CliLoginRequest("owner", Password)), Encoding.UTF8, "application/json"));
        var session = ControlJson.Deserialize<CliTokenIssued>(await login.Content.ReadAsStringAsync());
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", session.Token);
        using var issue = await client.PostAsync(path, null);
        Assert.Equal(HttpStatusCode.Created, issue.StatusCode);
        var issued = ControlJson.Deserialize<CredentialIssued>(await issue.Content.ReadAsStringAsync());
        Assert.False(string.IsNullOrWhiteSpace(issued.Token));
        using (var scope = factory.Services.CreateScope())
            Assert.Equal(hostId, (await scope.ServiceProvider.GetRequiredService<AgentCredentialService>().AuthenticateAsync(issued.Token))?.HostId);
        Assert.Equal(HttpStatusCode.NoContent, (await client.DeleteAsync($"{path}/{issued.CredentialId:D}")).StatusCode);
        using (var scope = factory.Services.CreateScope())
            Assert.Null(await scope.ServiceProvider.GetRequiredService<AgentCredentialService>().AuthenticateAsync(issued.Token));
    }

    [Fact]
    public async Task CliLogoutRevokesRememberedCredential()
    {
        await using var factory = new AuthFactory();
        await CreateOwner(factory);
        using var client = factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new Uri("https://localhost"), AllowAutoRedirect = false, HandleCookies = false });
        using var login = await client.PostAsync("/api/control/v1/auth/cli-token", new StringContent(ControlJson.Serialize(new CliLoginRequest("owner", Password)), Encoding.UTF8, "application/json"));
        var issued = ControlJson.Deserialize<CliTokenIssued>(await login.Content.ReadAsStringAsync());
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", issued.Token);
        Assert.Equal(HttpStatusCode.NoContent, (await client.GetAsync("/api/control/v1/auth/cli-token")).StatusCode);
        Assert.Equal(HttpStatusCode.NoContent, (await client.PostAsync("/api/control/v1/auth/cli-token/revoke", null)).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync("/api/control/v1/auth/cli-token")).StatusCode);
        using var scope = factory.Services.CreateScope();
        Assert.Null(await scope.ServiceProvider.GetRequiredService<CliCredentialService>().AuthenticateAsync(issued.Token));
    }

    [Fact]
    public async Task OwnerCanIssueAndRevokeHostTokenWithoutRecentReauthentication()
    {
        await using var factory = new AuthFactory();
        using var client = Client(factory);
        await CreateOwner(factory);
        await Login(client);
        var hostId = Guid.NewGuid();
        using (var scope = factory.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<ControlDbContext>();
            db.Hosts.Add(new InventoryHost { HostId = hostId, HostKey = "test-host", DisplayName = "Test Host" });
            await db.SaveChangesAsync();
        }
        var csrf = Token(await client.GetStringAsync("/Security"));
        using var issue = new HttpRequestMessage(HttpMethod.Post, $"/api/control/v1/hosts/{hostId}/credentials");
        issue.Headers.Add("Origin", "https://localhost");
        issue.Headers.Add("X-CSRF-TOKEN", csrf);
        using var issueResponse = await client.SendAsync(issue);
        Assert.Equal(HttpStatusCode.Created, issueResponse.StatusCode);
        var credential = ControlJson.Deserialize<CredentialIssued>(await issueResponse.Content.ReadAsStringAsync());
        using var revoke = new HttpRequestMessage(HttpMethod.Delete, $"/api/control/v1/hosts/{hostId}/credentials/{credential.CredentialId}");
        revoke.Headers.Add("Origin", "https://localhost");
        revoke.Headers.Add("X-CSRF-TOKEN", csrf);
        Assert.Equal(HttpStatusCode.NoContent, (await client.SendAsync(revoke)).StatusCode);
    }

    [Fact]
    public async Task CookieMutationsRequireCsrfAndFixedOriginAndLogoutRevokesSession()
    {
        await using var factory = new AuthFactory();
        using var client = Client(factory);
        await CreateOwner(factory);
        await Login(client);
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
        Assert.Equal(HttpStatusCode.Redirect, (await client.GetAsync("/Security")).StatusCode);
    }

    [Fact]
    public async Task LogoutAllInvalidatesEverySessionWithoutReauthentication()
    {
        await using var factory = new AuthFactory();
        using var first = Client(factory);
        using var second = Client(factory);
        await CreateOwner(factory);
        await Login(first);
        await Login(second);
        Assert.Equal(HttpStatusCode.OK, (await second.GetAsync("/Security")).StatusCode);
        Assert.Equal(HttpStatusCode.Redirect, (await Post(first, "/Security?handler=LogoutAll", new())).StatusCode);
        Assert.Equal(HttpStatusCode.Redirect, (await second.GetAsync("/Security")).StatusCode);
    }

    [Fact]
    public async Task ResetRevokesCookieAndAllowsDirectPasswordLogin()
    {
        await using var factory = new AuthFactory();
        using var client = Client(factory);
        await CreateOwner(factory);
        await Login(client);
        using (var scope = factory.Services.CreateScope())
            Assert.True((await scope.ServiceProvider.GetRequiredService<OwnerAuthService>().ResetAsync(Password)).Succeeded);
        Assert.Equal(HttpStatusCode.Redirect, (await client.GetAsync("/Security")).StatusCode);
        Assert.Equal("/", (await Login(client)).Headers.Location?.OriginalString);
    }

    [Fact]
    public async Task PasswordFailuresLockOwnerAfterTenAttempts()
    {
        await using var factory = new AuthFactory();
        await CreateOwner(factory);
        using var scope = factory.Services.CreateScope();
        var users = scope.ServiceProvider.GetRequiredService<UserManager<ControlUser>>();
        var auth = scope.ServiceProvider.GetRequiredService<OwnerAuthService>();
        for (var i = 0; i < 10; i++) Assert.Null(await auth.CheckPasswordAsync("owner", "invalid"));
        Assert.True(await users.IsLockedOutAsync((await users.FindByNameAsync("owner"))!));
        Assert.Null(await auth.CheckPasswordAsync("owner", Password));
    }

    [Fact]
    public async Task RememberedSessionRemainsValidUntilRevoked()
    {
        await using var factory = new AuthFactory();
        using var client = Client(factory);
        await CreateOwner(factory);
        await Login(client);
        using var scope = factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<ControlDbContext>();
        var session = await db.OwnerSessions.SingleAsync();
        Assert.Equal(DateTime.MaxValue, session.ExpiresAtUtc);
        await db.OwnerSessions.Where(x => x.SessionId == session.SessionId).ExecuteUpdateAsync(s => s.SetProperty(x => x.CreatedAtUtc, DateTime.UtcNow.AddDays(-2)));
        Assert.NotNull(await scope.ServiceProvider.GetRequiredService<OwnerAuthService>().ValidateSessionAsync(session.SessionId));
        await db.OwnerSessions.Where(x => x.SessionId == session.SessionId).ExecuteUpdateAsync(s => s.SetProperty(x => x.ExpiresAtUtc, DateTime.UtcNow.AddSeconds(-1)));
        Assert.Null(await scope.ServiceProvider.GetRequiredService<OwnerAuthService>().ValidateSessionAsync(session.SessionId));
        Assert.Equal(HttpStatusCode.Redirect, (await client.GetAsync("/Security")).StatusCode);
    }

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
    public async Task LoginRateLimitRejectsSixthAttempt()
    {
        await using var factory = new AuthFactory();
        using var client = Client(factory);
        for (var i = 0; i < 5; i++) Assert.Equal(HttpStatusCode.OK, (await Post(client, "/Login", new() { ["UserName"] = "missing", ["Password"] = "bad" })).StatusCode);
        Assert.Equal(HttpStatusCode.TooManyRequests, (await Post(client, "/Login", new() { ["UserName"] = "missing", ["Password"] = "bad" })).StatusCode);
    }

    [Theory]
    [InlineData("//server/share/control-data")]
    [InlineData("\\\\server\\share\\control-data")]
    public void StorageRejectsUncBeforeOpeningAnyNetworkPath(string path) => Assert.Throws<InvalidOperationException>(() => new ControlStorage(path));

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

    private static HttpClient Client(AuthFactory factory) => factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new Uri("https://localhost"), AllowAutoRedirect = false });
    private static async Task CreateOwner(AuthFactory factory)
    {
        using var scope = factory.Services.CreateScope();
        Assert.True((await scope.ServiceProvider.GetRequiredService<OwnerAuthService>().CreateAsync("owner", Password)).Succeeded);
    }
    private static Task<HttpResponseMessage> Login(HttpClient client) => Post(client, "/Login", new() { ["UserName"] = "owner", ["Password"] = Password });
    private static string Token(string html) => WebUtility.HtmlDecode(Regex.Match(html, "name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\"").Groups[1].Value);
    private static async Task<HttpResponseMessage> Post(HttpClient client, string path, Dictionary<string, string> values)
    {
        values["__RequestVerificationToken"] = Token(await client.GetStringAsync(path));
        using var request = new HttpRequestMessage(HttpMethod.Post, path) { Content = new FormUrlEncodedContent(values) };
        request.Headers.Add("Origin", "https://localhost");
        return await client.SendAsync(request);
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
