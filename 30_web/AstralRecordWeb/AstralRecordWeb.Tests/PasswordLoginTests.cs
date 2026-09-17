using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.RegularExpressions;
using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Xunit;

namespace AstralRecordWeb.Tests;

public sealed class PasswordLoginTests
{
    [Fact]
    public async Task PasswordLogin_CanOpenSettings_ButAdminRequiresCode_ThenExpires()
    {
        var api = new AuthHandler();
        var clock = new TestClock();
        await using var factory = new AuthFactory(api, clock);
        using var client = Client(factory);
        await Login(client, password: true);
        var body = WebUtility.HtmlDecode(await client.GetStringAsync("/LoginSettings"));
        Assert.Contains(api.LoginId, body);
        Assert.DoesNotContain("Minecraftで本人確認済み", body);
        using var denied = await client.GetAsync("/Admin/Items");
        Assert.Equal("/Reauthenticate", denied.Headers.Location?.OriginalString.Split('?')[0]);

        using var verified = await Post(client, "/Reauthenticate", new() { ["LoginCode"] = "SELF-CODE", ["ReturnUrl"] = "/Admin/Items" });
        Assert.Equal("/Admin/Items", verified.Headers.Location?.OriginalString);
        Assert.Equal(api.UserUuid, api.LastExpectedUuid);
        using var allowed = await client.GetAsync("/Admin/Items");
        Assert.Equal(HttpStatusCode.OK, allowed.StatusCode);
        Assert.True(allowed.Headers.CacheControl?.NoStore);
        clock.Now = clock.Now.AddMinutes(6);
        using var expired = await client.GetAsync("/Admin/Items");
        Assert.StartsWith("/Reauthenticate?", expired.Headers.Location?.OriginalString);
    }

    [Fact]
    public async Task Change_UsesCookieIdentityAndVersion_RotatesOtherSessions_AndNeverReflectsPasswords()
    {
        var api = new AuthHandler();
        await using var factory = new AuthFactory(api, new());
        using var client = Client(factory);
        using var other = Client(factory);
        await Login(client, password: true);
        await Login(other, password: true);
        var originalVersion = api.Version;
        using var updated = await Post(client, "/LoginSettings", new()
        {
            ["action"] = "change", ["CurrentPassword"] = "current-password-secret", ["NewPassword"] = "a-new-secret-password", ["ConfirmPassword"] = "a-new-secret-password",
            ["SessionVersion"] = Guid.NewGuid().ToString(), ["UserUuid"] = Guid.NewGuid().ToString(), ["CodeAuthenticatedAt"] = DateTimeOffset.UtcNow.ToString("O"),
        });
        Assert.Equal(HttpStatusCode.Found, updated.StatusCode);
        Assert.Equal(originalVersion, api.LastUpdate!.SessionVersion);
        Assert.Null(api.LastUpdate.CodeAuthenticationProof);
        Assert.Equal("current-password-secret", api.LastUpdate.CurrentPassword);
        Assert.Equal(api.UserUuid, api.LastUpdateUuid);
        using var mine = await client.GetAsync("/LoginSettings");
        Assert.Equal(HttpStatusCode.OK, mine.StatusCode);
        using var revoked = await other.GetAsync("/LoginSettings");
        Assert.Equal("/Login", revoked.Headers.Location?.AbsolutePath);

        api.RejectUpdate = true;
        using var rejected = await Post(client, "/LoginSettings", new()
        {
            ["action"] = "change", ["CurrentPassword"] = "do-not-render-current", ["NewPassword"] = "do-not-render-new", ["ConfirmPassword"] = "do-not-render-new",
        });
        var body = await rejected.Content.ReadAsStringAsync();
        Assert.DoesNotContain("do-not-render-current", body);
        Assert.DoesNotContain("do-not-render-new", body);
    }

    [Fact]
    public async Task Enable_RequiresRecentCode_AndDisableDoesNotRequireNewPassword()
    {
        var api = new AuthHandler { Enabled = false };
        var clock = new TestClock();
        await using var factory = new AuthFactory(api, clock);
        using var client = Client(factory);
        await Login(client, password: false);
        var enabledForm = new Dictionary<string, string> { ["action"] = "enable", ["NewPassword"] = "a-long-password-value", ["ConfirmPassword"] = "a-long-password-value" };
        clock.Now = clock.Now.AddMinutes(6);
        using var denied = await Post(client, "/LoginSettings", enabledForm);
        Assert.Equal(HttpStatusCode.OK, denied.StatusCode);
        Assert.Equal(0, api.Updates);
        using var reauth = await Post(client, "/Reauthenticate", new() { ["LoginCode"] = "SELF-CODE" });
        Assert.Equal(HttpStatusCode.Found, reauth.StatusCode);
        using var enabled = await Post(client, "/LoginSettings", enabledForm);
        Assert.Equal(HttpStatusCode.Found, enabled.StatusCode);
        Assert.Equal("fixture-proof", api.LastUpdate!.CodeAuthenticationProof);
        using var disabled = await Post(client, "/LoginSettings", new() { ["action"] = "disable" });
        Assert.Equal(HttpStatusCode.Found, disabled.StatusCode);
        Assert.Null(api.LastUpdate!.NewPassword);
        Assert.False(api.Enabled);
    }

    [Fact]
    public async Task Reauthentication_RejectsAnotherUser_AndExternalReturnUrl()
    {
        var api = new AuthHandler();
        await using var factory = new AuthFactory(api, new());
        using var client = Client(factory);
        await Login(client, password: true);
        api.ReturnWrongUser = true;
        using var rejected = await Post(client, "/Reauthenticate", new() { ["LoginCode"] = "OTHER-CODE" });
        Assert.Equal(HttpStatusCode.OK, rejected.StatusCode);
        using var admin = await client.GetAsync("/Admin/Items");
        Assert.StartsWith("/Reauthenticate?", admin.Headers.Location?.OriginalString);
        api.ReturnWrongUser = false;
        using var safe = await Post(client, "/Reauthenticate", new() { ["LoginCode"] = "SELF-CODE", ["ReturnUrl"] = "https://example.com/steal" });
        Assert.Equal("/LoginSettings", safe.Headers.Location?.OriginalString);
    }

    [Fact]
    public async Task SessionValidationFailure_RejectsExistingCookie_AndMutationsRequireCsrf()
    {
        var api = new AuthHandler();
        await using var factory = new AuthFactory(api, new());
        using var client = Client(factory);
        await Login(client, password: false);
        using var missingCsrf = await client.PostAsync("/LoginSettings", new FormUrlEncodedContent(new Dictionary<string, string> { ["action"] = "disable" }));
        Assert.Equal(HttpStatusCode.BadRequest, missingCsrf.StatusCode);
        Assert.Equal(0, api.Updates);
        api.FailState = true;
        using var unavailable = await client.GetAsync("/LoginSettings");
        Assert.Equal("/Login", unavailable.Headers.Location?.AbsolutePath);
    }

    [Fact]
    public async Task UpdatingSettings_DoesNotExtendOriginalCodeAuthenticationWindow()
    {
        var api = new AuthHandler();
        var clock = new TestClock();
        await using var factory = new AuthFactory(api, clock);
        using var client = Client(factory);
        await Login(client, password: false);
        clock.Now = clock.Now.AddMinutes(4);
        using var updated = await Post(client, "/LoginSettings", new()
        {
            ["action"] = "change", ["NewPassword"] = "a-new-secret-password", ["ConfirmPassword"] = "a-new-secret-password",
        });
        Assert.Equal(HttpStatusCode.Found, updated.StatusCode);
        using var stillRecent = await client.GetAsync("/Admin/Items");
        Assert.Equal(HttpStatusCode.OK, stillRecent.StatusCode);
        clock.Now = clock.Now.AddMinutes(2);
        using var expired = await client.GetAsync("/Admin/Items");
        Assert.StartsWith("/Reauthenticate?", expired.Headers.Location?.OriginalString);
    }

    [Fact]
    public async Task AuthenticationAttempts_AreRateLimitedBeforeCallingApi()
    {
        var api = new AuthHandler { RejectLogin = true };
        await using var factory = new AuthFactory(api, new());
        using var client = Client(factory);
        var token = Token(await client.GetStringAsync("/Login"));
        for (var i = 0; i < 20; i++)
        {
            using var attempt = await client.PostAsync("/Login?handler=Password", new FormUrlEncodedContent(new Dictionary<string, string>
            {
                ["__RequestVerificationToken"] = token, ["LoginId"] = "ar-test", ["Password"] = "some-password-value",
            }));
            Assert.Equal(HttpStatusCode.OK, attempt.StatusCode);
        }
        using var blocked = await client.PostAsync("/Login", new FormUrlEncodedContent(new Dictionary<string, string> { ["__RequestVerificationToken"] = token, ["LoginCode"] = "CODE-CODE" }));
        Assert.Equal(HttpStatusCode.TooManyRequests, blocked.StatusCode);
        Assert.Equal(20, api.Logins);
    }

    private static HttpClient Client(AuthFactory factory) => factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new Uri("https://localhost"), AllowAutoRedirect = false });
    private static string Token(string body) => WebUtility.HtmlDecode(Regex.Match(body, "name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\"").Groups[1].Value);
    private static async Task<HttpResponseMessage> Post(HttpClient client, string path, Dictionary<string, string> fields)
    {
        fields["__RequestVerificationToken"] = Token(await client.GetStringAsync(path.Split('?')[0]));
        return await client.PostAsync(path, new FormUrlEncodedContent(fields));
    }
    private static async Task Login(HttpClient client, bool password)
    {
        using var response = await Post(client, password ? "/Login?handler=Password" : "/Login", password
            ? new() { ["LoginId"] = "ar-test", ["Password"] = "current-password-secret" }
            : new() { ["LoginCode"] = "SELF-CODE" });
        Assert.Equal(HttpStatusCode.Found, response.StatusCode);
    }

    private sealed class TestClock : TimeProvider
    {
        public DateTimeOffset Now { get; set; } = DateTimeOffset.UtcNow;
        public override DateTimeOffset GetUtcNow() => Now;
    }
    private sealed class AuthFactory(AuthHandler handler, TestClock clock) : WebApplicationFactory<Program>
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder) => builder.ConfigureTestServices(services =>
        {
            handler.Clock = clock;
            services.AddSingleton<TimeProvider>(clock);
            services.AddHttpClient<WebAuthApiClient>().ConfigurePrimaryHttpMessageHandler(() => handler);
            services.AddHttpClient<ItemMasterApiClient>().ConfigurePrimaryHttpMessageHandler(() => handler);
        });
    }
    private sealed class AuthHandler : HttpMessageHandler
    {
        public TestClock Clock { get; set; } = new();
        private DateTimeOffset? codeAuthenticatedAt;
        public Guid UserUuid { get; } = Guid.NewGuid();
        public Guid Version { get; private set; } = Guid.NewGuid();
        public string LoginId => "AR-TEST23456789";
        public bool Enabled { get; set; } = true;
        public bool ReturnWrongUser { get; set; }
        public bool RejectUpdate { get; set; }
        public bool FailState { get; set; }
        public bool RejectLogin { get; set; }
        public int Logins { get; private set; }
        public int Updates { get; private set; }
        public Guid? LastExpectedUuid { get; private set; }
        public Guid LastUpdateUuid { get; private set; }
        public WebCredentialUpdateRequest? LastUpdate { get; private set; }
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            var path = request.RequestUri!.AbsolutePath;
            if (path.EndsWith("/challenges/consume") || path.EndsWith("/password/login"))
            {
                Logins++;
                if (RejectLogin) return new(HttpStatusCode.BadRequest);
                if (path.EndsWith("/challenges/consume"))
                {
                    codeAuthenticatedAt = Clock.Now;
                    LastExpectedUuid = (await request.Content!.ReadFromJsonAsync<WebLoginChallengeConsumeRequest>(ct))!.ExpectedUserUuid;
                }
                return Json(new WebLoginChallengeConsumeResponse { UserUuid = ReturnWrongUser ? Guid.NewGuid() : UserUuid, SessionVersion = Version, Mcid = "Tester", CodeAuthenticatedAt = path.EndsWith("/challenges/consume") ? Clock.Now : null, CodeAuthenticationProof = path.EndsWith("/challenges/consume") ? "fixture-proof" : null });
            }
            if (path.EndsWith("/credentials"))
            {
                if (FailState) return new(HttpStatusCode.ServiceUnavailable);
                if (request.Method == HttpMethod.Post)
                {
                    Updates++;
                    LastUpdateUuid = Guid.Parse(path.Split('/')[4]);
                    LastUpdate = await request.Content!.ReadFromJsonAsync<WebCredentialUpdateRequest>(ct);
                    if (RejectUpdate) return new(HttpStatusCode.BadRequest);
                    if (LastUpdate!.SessionVersion != Version) return new(HttpStatusCode.Unauthorized);
                    Version = Guid.NewGuid();
                    Enabled = LastUpdate.Action != "disable";
                }
                return Json(new WebCredentialState { SessionVersion = Version, LoginId = LoginId, Enabled = Enabled, CodeAuthenticationProof = request.Method == HttpMethod.Post ? LastUpdate?.CodeAuthenticationProof : null, CodeAuthenticatedAt = request.Method == HttpMethod.Post && LastUpdate?.CodeAuthenticationProof is not null ? codeAuthenticatedAt : null });
            }
            if (path.EndsWith("/authorization")) return Json(new { webAdmin = true });
            if (path == "/api/item") return Json(Array.Empty<object>());
            return new(HttpStatusCode.NotFound);
        }
        private static HttpResponseMessage Json(object value) => new(HttpStatusCode.OK) { Content = JsonContent.Create(value, options: new JsonSerializerOptions(JsonSerializerDefaults.Web)) };
    }
}
