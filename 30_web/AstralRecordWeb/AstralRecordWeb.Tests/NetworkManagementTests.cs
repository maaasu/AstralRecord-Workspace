using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.RegularExpressions;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Xunit;

namespace AstralRecordWeb.Tests;

public sealed class NetworkManagementTests
{
    [Fact]
    public async Task NetworkSettings_RequireRecheckedWebAdmin_AndUseCookieActorWithApiKey()
    {
        var api = new ManagementHandler();
        await using var factory = new ManagementFactory(api);
        using var client = Client(factory);
        await Login(client);

        using var denied = await client.GetAsync("/Admin/Network");
        Assert.Equal(HttpStatusCode.Found, denied.StatusCode);
        Assert.Equal(0, api.SettingsGets);

        api.Admin = true;
        var body = WebUtility.HtmlDecode(await client.GetStringAsync("/Admin/Network"));
        Assert.Contains("星界ネットワーク設定", body);
        Assert.Contains("ロビーの接続先識別子", body);
        Assert.Contains("管理者専用", body);
        Assert.Equal(ManagementHandler.ActorId, api.LastActor);
        Assert.Equal("fixture-api-key", api.LastApiKey);
    }

    [Fact]
    public async Task NetworkSettings_SaveNeedsCsrf_RechecksAdmin_AndStoresOnlyUserUuids()
    {
        var api = new ManagementHandler { Admin = true };
        await using var factory = new ManagementFactory(api);
        using var client = Client(factory);
        await Login(client);
        var body = WebUtility.HtmlDecode(await client.GetStringAsync("/Admin/Network"));

        using var noToken = await client.PostAsync("/Admin/Network?handler=Save", new FormUrlEncodedContent(SettingsForm()));
        Assert.Equal(HttpStatusCode.BadRequest, noToken.StatusCode);
        Assert.Equal(0, api.SettingsPuts);

        api.Admin = false;
        using var revoked = await client.PostAsync("/Admin/Network?handler=Save", new FormUrlEncodedContent(SettingsForm(Token(body))));
        Assert.Equal(HttpStatusCode.Found, revoked.StatusCode);
        Assert.Equal(0, api.SettingsPuts);

        api.Admin = true;
        var refreshed = WebUtility.HtmlDecode(await client.GetStringAsync("/Admin/Network"));
        using var saved = await client.PostAsync("/Admin/Network?handler=Save", new FormUrlEncodedContent(SettingsForm(Token(refreshed))));
        Assert.Equal(HttpStatusCode.Found, saved.StatusCode);
        Assert.Equal(1, api.SettingsPuts);
        Assert.Equal(ManagementHandler.ActorId, api.LastActor);
        Assert.Contains(ManagementHandler.TargetId.ToString(), api.LastSettingsBody);
        Assert.DoesNotContain("mcid", api.LastSettingsBody, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("authorityUsers", api.LastSettingsBody, StringComparison.Ordinal);
    }

    [Fact]
    public async Task BanConfirmation_UsesRouteUserUuid_NotFormTarget_AndRequiresCsrf()
    {
        var api = new ManagementHandler { Admin = true };
        await using var factory = new ManagementFactory(api);
        using var client = Client(factory);
        await Login(client);
        var path = $"/players/{ManagementHandler.TargetId:D}";
        var body = WebUtility.HtmlDecode(await client.GetStringAsync(path));
        Assert.Contains("管理者操作: 利用停止", body);
        Assert.Contains("利用停止の内容を確認", body);
        Assert.Contains(ManagementHandler.TargetId.ToString(), body);
        Assert.DoesNotContain("accountId", api.LastBanPath ?? string.Empty, StringComparison.OrdinalIgnoreCase);

        using var noToken = await client.PostAsync(path + "?handler=Ban", new FormUrlEncodedContent(BanForm()));
        Assert.Equal(HttpStatusCode.BadRequest, noToken.StatusCode);
        Assert.Equal(0, api.BanPuts);

        var form = BanForm(Token(body));
        form.Add(new("TargetUserUuid", Guid.NewGuid().ToString()));
        using var saved = await client.PostAsync(path + "?handler=Ban", new FormUrlEncodedContent(form));
        Assert.Equal(HttpStatusCode.Found, saved.StatusCode);
        Assert.Equal(1, api.BanPuts);
        Assert.Equal($"/api/network-management/bans/{ManagementHandler.TargetId:D}", api.LastBanPath);
        Assert.Equal(ManagementHandler.ActorId, api.LastActor);
        Assert.Contains("2026-12-31T05:00", api.LastBanBody);
    }

    [Fact]
    public async Task BanPost_RechecksWebAdminBeforeCallingApi()
    {
        var api = new ManagementHandler { Admin = true };
        await using var factory = new ManagementFactory(api);
        using var client = Client(factory);
        await Login(client);
        var path = $"/players/{ManagementHandler.TargetId:D}";
        var body = WebUtility.HtmlDecode(await client.GetStringAsync(path));
        api.Admin = false;

        using var revoked = await client.PostAsync(path + "?handler=Ban", new FormUrlEncodedContent(BanForm(Token(body))));
        Assert.Equal(HttpStatusCode.Found, revoked.StatusCode);
        Assert.Equal(0, api.BanPuts);
    }

    [Fact]
    public async Task BanPost_PreservesPrivateAdminViewAndSelectedAccountOnRedirect()
    {
        var api = new ManagementHandler { Admin = true, MakeProfilePrivateAfterBan = true };
        await using var factory = new ManagementFactory(api);
        using var client = Client(factory);
        await Login(client);
        var accountId = Guid.Parse("33333333-3333-3333-3333-333333333333");
        var path = $"/players/{ManagementHandler.TargetId:D}?mcid=Target&classId=adventurer&sort=level_asc&pageNumber=2&includePrivate=true&accountId={accountId:D}";
        var body = WebUtility.HtmlDecode(await client.GetStringAsync(path));
        var banForm = Regex.Match(body, "<form(?=[^>]*class=\"ar-ban-editor\")[^>]*>").Value;
        Assert.Contains("handler=Ban", banForm, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("includePrivate=true", banForm, StringComparison.OrdinalIgnoreCase);
        Assert.Contains($"accountId={accountId:D}", banForm, StringComparison.OrdinalIgnoreCase);

        using var saved = await client.PostAsync(path + "&handler=Ban", new FormUrlEncodedContent(BanForm(Token(body))));

        Assert.Equal(HttpStatusCode.Found, saved.StatusCode);
        var location = saved.Headers.Location!.ToString();
        Assert.Contains("includePrivate=True", location, StringComparison.OrdinalIgnoreCase);
        Assert.Contains($"accountId={accountId:D}", location, StringComparison.OrdinalIgnoreCase);
        using var redirected = await client.GetAsync(location);
        Assert.Equal(HttpStatusCode.OK, redirected.StatusCode);
        Assert.True(api.LastProfileIncludedPrivate);
        Assert.Equal(accountId, api.LastProfileAccountId);
    }

    [Fact]
    public async Task SettingsPost_PreservesDisabledDiscord_AndRejectsInvalidNumber()
    {
        var api = new ManagementHandler { Admin = true };
        await using var factory = new ManagementFactory(api);
        using var client = Client(factory);
        await Login(client);
        var body = await client.GetStringAsync("/Admin/Network");
        Assert.Contains("name=\"Input.Channels[0].DiscordEnabled\" type=\"hidden\" value=\"false\"", body);
        var fields = SettingsForm(Token(body));
        fields.RemoveAll(pair => pair.Key == "Input.Channels[0].DiscordEnabled");
        fields.Add(new("Input.Channels[0].DiscordEnabled", "false"));
        using var saved = await client.PostAsync("/Admin/Network?handler=Save", new FormUrlEncodedContent(fields));
        Assert.Equal(HttpStatusCode.Found, saved.StatusCode);
        using var json = JsonDocument.Parse(api.LastSettingsBody);
        Assert.False(json.RootElement.GetProperty("channels")[0].GetProperty("discordEnabled").GetBoolean());
        fields.RemoveAll(pair => pair.Key == "Input.Channels[0].MaxPlayers");
        fields.Add(new("Input.Channels[0].MaxPlayers", "invalid"));
        using var rejected = await client.PostAsync("/Admin/Network?handler=Save", new FormUrlEncodedContent(fields));
        Assert.Equal(HttpStatusCode.OK, rejected.StatusCode);
        Assert.Equal(1, api.SettingsPuts);
    }

    [Fact]
    public async Task LivePlayerSearch_RechecksAdminAndUsesCookieActorWithoutPostingSettings()
    {
        var api = new ManagementHandler();
        await using var factory = new ManagementFactory(api);
        using var client = Client(factory);
        var url = "/Admin/Network?handler=SearchPlayers&query=Target&actor_user_uuid=" + Guid.NewGuid();
        using var anonymous = await client.GetAsync(url);
        Assert.Equal(HttpStatusCode.Found, anonymous.StatusCode);
        await Login(client);
        using var denied = await client.GetAsync(url);
        Assert.Equal(HttpStatusCode.Found, denied.StatusCode);
        Assert.Equal(0, api.SearchGets);
        api.Admin = true;
        using var response = await client.GetAsync(url);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("application/json", response.Content.Headers.ContentType!.MediaType);
        Assert.True(response.Headers.CacheControl!.NoStore);
        using var json = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        Assert.Equal("TargetPlayer", json.RootElement.GetProperty("players")[0].GetProperty("mcid").GetString());
        Assert.Equal(ManagementHandler.ActorId, api.LastActor);
        Assert.Equal("Target", api.LastSearchQuery);
        Assert.Equal(0, api.SettingsPuts);
        api.Admin = false;
        using var revoked = await client.GetAsync(url);
        Assert.Equal(HttpStatusCode.Found, revoked.StatusCode);
        Assert.Equal(1, api.SearchGets);
    }

    [Fact]
    public async Task LivePlayerSearch_HandlesEmptyQueryAndUpstreamFailure()
    {
        var api = new ManagementHandler { Admin = true };
        await using var factory = new ManagementFactory(api);
        using var client = Client(factory);
        await Login(client);
        using var empty = await client.GetAsync("/Admin/Network?handler=SearchPlayers&query=%20");
        Assert.Equal(HttpStatusCode.OK, empty.StatusCode);
        Assert.Equal(0, api.SearchGets);
        using var tooLong = await client.GetAsync("/Admin/Network?handler=SearchPlayers&query=" + new string('x', 101));
        Assert.Equal(HttpStatusCode.BadRequest, tooLong.StatusCode);
        Assert.Equal(0, api.SearchGets);
        api.SearchFailure = true;
        using var failed = await client.GetAsync("/Admin/Network?handler=SearchPlayers&query=Target");
        Assert.Equal(HttpStatusCode.ServiceUnavailable, failed.StatusCode);
        Assert.Equal(0, api.SettingsPuts);
    }

    [Theory]
    [InlineData("2027-02-30")]
    [InlineData("0001-01-01")]
    [InlineData("2000-01-01")]
    public async Task InvalidBanDate_RejectsUpdateAndKeepsAdminEditorAndInput(string invalidDate)
    {
        var api = new ManagementHandler { Admin = true };
        await using var factory = new ManagementFactory(api);
        using var client = Client(factory);
        await Login(client);
        var path = $"/players/{ManagementHandler.TargetId:D}";
        var body = await client.GetStringAsync(path);
        var fields = BanForm(Token(body));
        fields.RemoveAll(pair => pair.Key == "BanInput.ExpiresOn");
        fields.Add(new("BanInput.ExpiresOn", invalidDate));
        using var failed = await client.PostAsync(path + "?handler=Ban", new FormUrlEncodedContent(fields));
        Assert.Equal(HttpStatusCode.OK, failed.StatusCode);
        Assert.Equal(0, api.BanPuts);
        var failedBody = WebUtility.HtmlDecode(await failed.Content.ReadAsStringAsync());
        Assert.Contains("data-ban-editor", failedBody);
        Assert.Contains(invalidDate, failedBody);
        Assert.Contains("テスト利用停止", failedBody);
    }

    private static List<KeyValuePair<string, string>> SettingsForm(string? token = null)
    {
        var fields = new List<KeyValuePair<string, string>>
        {
            new("Input.Revision", "7"), new("Input.LobbyServerId", "lobby"), new("Input.TransferCooldownSeconds", "30"),
            new("Input.TabRefreshSeconds", "2"), new("Input.PresenceHeartbeatSeconds", "10"),
            new("Input.AuthorityUsers[0]", ManagementHandler.TargetId.ToString()), new("Input.Channels[0].ServerId", "lobby"),
            new("Input.Channels[0].DisplayName", "ロビー"), new("Input.Channels[0].MaxPlayers", "100"),
            new("Input.Channels[0].DonorExtraPlayers", "0"), new("Input.Channels[0].AdminExtraPlayers", "0"),
            new("Input.Channels[0].DiscordEnabled", "true"),
        };
        if (token is not null) fields.Add(new("__RequestVerificationToken", token));
        return fields;
    }

    private static List<KeyValuePair<string, string>> BanForm(string? token = null)
    {
        var fields = new List<KeyValuePair<string, string>>
        {
            new("BanInput.ExpectedRevision", "4"), new("BanInput.IsBanned", "true"), new("BanInput.ExpiresOn", "2026-12-31"), new("BanInput.ExpiresTime", "14:00"), new("BanInput.Reason", "テスト利用停止"),
        };
        if (token is not null) fields.Add(new("__RequestVerificationToken", token));
        return fields;
    }

    private static HttpClient Client(ManagementFactory factory) => factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new Uri("https://localhost"), AllowAutoRedirect = false });
    private static string Token(string body) => WebUtility.HtmlDecode(Regex.Match(body, "name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\"").Groups[1].Value);

    private static async Task Login(HttpClient client)
    {
        var body = await client.GetStringAsync("/Login");
        using var response = await client.PostAsync("/Login", new FormUrlEncodedContent(new Dictionary<string, string> { ["__RequestVerificationToken"] = Token(body), ["LoginCode"] = "TEST-CODE" }));
        Assert.Equal(HttpStatusCode.Found, response.StatusCode);
    }

    private sealed class ManagementFactory(ManagementHandler handler) : WebApplicationFactory<Program>
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            builder.ConfigureAppConfiguration((_, config) => config.AddInMemoryCollection(new Dictionary<string, string?> { ["AstralRecordApi:ApiKey"] = "fixture-api-key" }));
            builder.ConfigureTestServices(services =>
            {
                services.AddHttpClient<WebAuthApiClient>().ConfigurePrimaryHttpMessageHandler(() => handler);
                services.AddHttpClient<PlayerProfileApiClient>().ConfigurePrimaryHttpMessageHandler(() => handler);
                services.AddHttpClient<NetworkManagementApiClient>().ConfigurePrimaryHttpMessageHandler(() => handler);
            });
        }
    }

    private sealed class ManagementHandler : HttpMessageHandler
    {
        public static readonly Guid ActorId = Guid.Parse("11111111-1111-1111-1111-111111111111");
        public static readonly Guid TargetId = Guid.Parse("22222222-2222-2222-2222-222222222222");
        public bool Admin { get; set; }
        public int SettingsGets { get; private set; }
        public int SettingsPuts { get; private set; }
        public int BanPuts { get; private set; }
        public int SearchGets { get; private set; }
        public string? LastSearchQuery { get; private set; }
        public bool SearchFailure { get; set; }
        public Guid? LastActor { get; private set; }
        public string? LastApiKey { get; private set; }
        public string LastSettingsBody { get; private set; } = string.Empty;
        public string LastBanBody { get; private set; } = string.Empty;
        public string? LastBanPath { get; private set; }
        public bool MakeProfilePrivateAfterBan { get; set; }
        public bool ProfileIsPrivate { get; private set; }
        public bool LastProfileIncludedPrivate { get; private set; }
        public Guid? LastProfileAccountId { get; private set; }

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            var path = request.RequestUri!.AbsolutePath;
            LastApiKey = request.Headers.TryGetValues("X-Api-Key", out var keys) ? keys.Single() : null;
            var query = Microsoft.AspNetCore.WebUtilities.QueryHelpers.ParseQuery(request.RequestUri.Query);
            if (query.TryGetValue("actor_user_uuid", out var actor) && Guid.TryParse(actor, out var parsed)) LastActor = parsed;
            if (path.EndsWith("/challenges/consume")) return Json(new { userUuid = ActorId, mcid = "AdminPlayer", permission = 0, accountIds = Array.Empty<Guid>() });
            if (path.EndsWith("/authorization")) return Json(new { webAdmin = Admin });
            if (path == "/api/network-management/settings")
            {
                if (request.Method == HttpMethod.Get) { SettingsGets++; return Json(Settings()); }
                SettingsPuts++;
                LastSettingsBody = await request.Content!.ReadAsStringAsync(ct);
                return Json(Settings());
            }
            if (path == "/api/network-management/players")
            {
                SearchGets++;
                LastSearchQuery = query["query"].ToString();
                return SearchFailure ? new(HttpStatusCode.ServiceUnavailable)
                    : Json(new[] { new { userUuid = TargetId, mcid = "TargetPlayer" } });
            }
            if (path.StartsWith("/api/network-management/bans/", StringComparison.Ordinal))
            {
                LastBanPath = path;
                if (request.Method == HttpMethod.Put)
                {
                    BanPuts++;
                    LastBanBody = await request.Content!.ReadAsStringAsync(ct);
                    ProfileIsPrivate = MakeProfilePrivateAfterBan;
                }
                return Json(Ban());
            }
            if (path.StartsWith("/api/web-profiles/", StringComparison.Ordinal))
            {
                LastProfileIncludedPrivate = query.TryGetValue("include_private", out var privateValue)
                    && bool.TryParse(privateValue, out var includePrivate) && includePrivate;
                LastProfileAccountId = query.TryGetValue("account_id", out var accountValue)
                    && Guid.TryParse(accountValue, out var accountId) ? accountId : null;
                return ProfileIsPrivate && !LastProfileIncludedPrivate ? new(HttpStatusCode.NotFound) : Json(Profile());
            }
            return new(HttpStatusCode.NotFound);
        }

        private static object Settings() => new
        {
            revision = 7, lobbyServerId = "lobby", transferCooldownSeconds = 30, tabRefreshSeconds = 2, presenceHeartbeatSeconds = 10,
            authorityUsers = new[] { TargetId }, players = new[] { new { userUuid = TargetId, mcid = "TargetPlayer" } },
            channels = new[] { new { serverId = "lobby", displayName = "ロビー", isGame = false, maxPlayers = 100, donorExtraPlayers = 0, adminExtraPlayers = 0, discordEnabled = true, whitelistEnabled = false, debugUsers = Array.Empty<Guid>(), whitelistUsers = Array.Empty<Guid>() } },
        };
        private static object Ban() => new { userUuid = TargetId, mcid = "TargetPlayer", revision = 4, isBanned = false, isActive = false, isIndefinite = false, serverTimeUtc = "2026-09-16T00:00:00Z" };
        private object Profile() => new
        {
            userUuid = TargetId, mcid = "TargetPlayer", permission = 0, isPublic = !ProfileIsPrivate,
            accounts = Array.Empty<object>(), currentAccount = (object?)null,
        };
        private static HttpResponseMessage Json(object value) => new(HttpStatusCode.OK) { Content = JsonContent.Create(value) };
    }
}
