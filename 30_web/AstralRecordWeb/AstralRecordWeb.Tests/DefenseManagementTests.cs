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

public sealed class DefenseManagementTests
{
    private const string PagePath = "/Admin/Defense?serverId=game";

    [Fact]
    public async Task AdminPageAndStartRequireRecentAdminAndCsrfAndUseCookieActor()
    {
        var api = new DefenseHandler();
        await using var factory = new DefenseFactory(api);
        using var client = Client(factory);
        using var anonymous = await client.GetAsync(PagePath);
        Assert.Equal(HttpStatusCode.Found, anonymous.StatusCode);
        await Login(client);
        using var denied = await client.GetAsync(PagePath);
        Assert.Equal(HttpStatusCode.Found, denied.StatusCode);
        Assert.Equal(0, api.DefenseGets);

        api.Admin = true;
        var html = WebUtility.HtmlDecode(await client.GetStringAsync(PagePath));
        Assert.Contains("実際の開始は状態表示", html);
        Assert.Contains("次回予定", html);
        Assert.Contains("受付中", html);
        Assert.Equal("fixture-defense-key", api.LastDefenseWebKey);
        Assert.Equal("fixture-api-key", api.LastApiKey);

        using var noCsrf = await client.PostAsync(PagePath + "&handler=Start", Form(new("Input.Revision", "7"), new("StartOperationId", Guid.NewGuid().ToString())));
        Assert.Equal(HttpStatusCode.BadRequest, noCsrf.StatusCode);
        Assert.Equal(0, api.Starts);

        api.Admin = false;
        using var revoked = await client.PostAsync(PagePath + "&handler=Start", Form(new("__RequestVerificationToken", Token(html)), new("Input.Revision", "7"), new("StartOperationId", Guid.NewGuid().ToString())));
        Assert.Equal(HttpStatusCode.Found, revoked.StatusCode);
        Assert.Equal(0, api.Starts);

        api.Admin = true;
        html = await client.GetStringAsync(PagePath);
        var operationId = Guid.NewGuid();
        using var accepted = await client.PostAsync(PagePath + "&handler=Start&actor_user_uuid=22222222-2222-2222-2222-222222222222", Form(
            new("__RequestVerificationToken", Token(html)), new("Input.Revision", "7"), new("StartOperationId", operationId.ToString())));
        Assert.Equal(HttpStatusCode.Found, accepted.StatusCode);
        Assert.Equal(1, api.Starts);
        Assert.Equal(DefenseHandler.Actor, api.LastActor);
        using var body = JsonDocument.Parse(api.LastStartBody!);
        Assert.Equal(7, body.RootElement.GetProperty("expectedRevision").GetInt32());
        Assert.Equal(operationId, body.RootElement.GetProperty("operationId").GetGuid());
    }

    [Fact]
    public async Task MissingSettingsDisableStartAndSavingAllowsIncompleteConfiguration()
    {
        var api = new DefenseHandler { Admin = true, Missing = true };
        await using var factory = new DefenseFactory(api);
        using var client = Client(factory);
        await Login(client);
        var html = WebUtility.HtmlDecode(await client.GetStringAsync(PagePath));
        Assert.Contains("防衛対象の最大HP", html);
        Assert.Contains("防衛戦に参加可能なMob", html);
        Assert.Contains("disabled=\"disabled\"", html);
        using var save = await client.PostAsync(PagePath + "&handler=Save", Form(
            new("__RequestVerificationToken", Token(html)), new("Input.Revision", "7"), new("Input.DailyEnabled", "false"), new("Input.WorldName", "defense_game")));
        Assert.Equal(HttpStatusCode.Found, save.StatusCode);
        Assert.Equal(1, api.Saves);
        using var body = JsonDocument.Parse(api.LastSaveBody!);
        Assert.Equal(7, body.RootElement.GetProperty("expectedRevision").GetInt32());
        Assert.Equal("defense_game", body.RootElement.GetProperty("configuration").GetProperty("worldName").GetString());
        Assert.Equal(JsonValueKind.Null, body.RootElement.GetProperty("configuration").GetProperty("coreMaxHealth").ValueKind);
    }

    [Fact]
    public async Task SaveConflictKeepsInputAndRequiresReload()
    {
        var api = new DefenseHandler { Admin = true, SaveConflict = true };
        await using var factory = new DefenseFactory(api);
        using var client = Client(factory);
        await Login(client);
        var html = await client.GetStringAsync(PagePath);
        using var response = await client.PostAsync(PagePath + "&handler=Save", Form(
            new("__RequestVerificationToken", Token(html)), new("Input.Revision", "7"),
            new("Input.DailyEnabled", "false"), new("Input.WorldName", "custom_world")));
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var body = WebUtility.HtmlDecode(await response.Content.ReadAsStringAsync());
        Assert.Contains("最新の設定と状態を読み直して", body);
        Assert.Contains("custom_world", body);
        Assert.Equal(1, api.Saves);
    }

    [Fact]
    public async Task LostStartResponseRetriesSameOperationAfterRegistrationClosesAndRunIsPending()
    {
        var api = new DefenseHandler { Admin = true, LoseFirstStartResponse = true };
        await using var factory = new DefenseFactory(api);
        using var client = Client(factory);
        await Login(client);
        var html = await client.GetStringAsync(PagePath);
        var operationId = Guid.NewGuid();
        using var first = await client.PostAsync(PagePath + "&handler=Start", Form(
            new("__RequestVerificationToken", Token(html)), new("Input.Revision", "7"),
            new("StartOperationId", operationId.ToString())));
        Assert.Equal(HttpStatusCode.OK, first.StatusCode);
        var retryHtml = WebUtility.HtmlDecode(await first.Content.ReadAsStringAsync());
        Assert.Contains("同じ開始要求を再試行", retryHtml);
        Assert.Contains("開始要求を受理（ゲーム内開始待ち）", retryHtml);
        Assert.Contains($"name=\"StartOperationId\" value=\"{operationId:D}\"", retryHtml);
        Assert.Contains("name=\"RetryStart\" value=\"true\"", retryHtml);
        Assert.DoesNotContain("disabled=\"disabled\">同じ開始要求を再試行", retryHtml);

        using var second = await client.PostAsync(PagePath + "&handler=Start", Form(
            new("__RequestVerificationToken", Token(retryHtml)), new("Input.Revision", "7"),
            new("StartOperationId", operationId.ToString()), new("RetryStart", "true")));
        Assert.Equal(HttpStatusCode.Found, second.StatusCode);
        Assert.Equal(2, api.Starts);
        Assert.Equal(new[] { operationId, operationId }, api.StartOperationIds);
        Assert.Equal(1, api.AcceptedRuns);
    }

    private static FormUrlEncodedContent Form(params KeyValuePair<string, string>[] fields) => new(fields);
    private static string Token(string html) => WebUtility.HtmlDecode(Regex.Match(html, "name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\"").Groups[1].Value);
    private static HttpClient Client(DefenseFactory factory) => factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new Uri("https://localhost"), AllowAutoRedirect = false });

    private static async Task Login(HttpClient client)
    {
        var html = await client.GetStringAsync("/Login");
        using var response = await client.PostAsync("/Login", Form(new("__RequestVerificationToken", Token(html)), new("LoginCode", "TEST-CODE")));
        Assert.Equal(HttpStatusCode.Found, response.StatusCode);
    }

    private sealed class DefenseFactory(DefenseHandler handler) : WebApplicationFactory<Program>
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            builder.ConfigureAppConfiguration((_, config) => config.AddInMemoryCollection(new Dictionary<string, string?>
            {
                ["AstralRecordApi:ApiKey"] = "fixture-api-key", ["Defense:WebKey"] = "fixture-defense-key",
            }));
            builder.WithIsolatedWebDependencies().ConfigureTestServices(services =>
            {
                services.AddHttpClient<WebAuthApiClient>().ConfigurePrimaryHttpMessageHandler(() => handler);
                services.AddHttpClient<NetworkManagementApiClient>().ConfigurePrimaryHttpMessageHandler(() => handler);
                services.AddHttpClient<DefenseManagementApiClient>().ConfigurePrimaryHttpMessageHandler(() => handler);
            });
        }
    }

    private sealed class DefenseHandler : HttpMessageHandler
    {
        public static readonly Guid Actor = Guid.Parse("11111111-1111-1111-1111-111111111111");
        public bool Admin { get; set; }
        public bool Missing { get; set; }
        public bool SaveConflict { get; set; }
        public bool LoseFirstStartResponse { get; set; }
        public bool StartPending { get; private set; }
        public int AcceptedRuns { get; private set; }
        public List<Guid> StartOperationIds { get; } = [];
        private Guid? acceptedStartOperationId;
        private readonly Guid acceptedRunId = Guid.NewGuid();
        public int DefenseGets { get; private set; }
        public int Starts { get; private set; }
        public int Saves { get; private set; }
        public Guid? LastActor { get; private set; }
        public string? LastApiKey { get; private set; }
        public string? LastDefenseWebKey { get; private set; }
        public string? LastStartBody { get; private set; }
        public string? LastSaveBody { get; private set; }

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            var path = request.RequestUri!.AbsolutePath;
            var query = Microsoft.AspNetCore.WebUtilities.QueryHelpers.ParseQuery(request.RequestUri.Query);
            LastApiKey = request.Headers.TryGetValues("X-Api-Key", out var apiKeys) ? apiKeys.Single() : null;
            LastDefenseWebKey = request.Headers.TryGetValues("X-Defense-Web-Key", out var defenseKeys) ? defenseKeys.Single() : LastDefenseWebKey;
            if (query.TryGetValue("actor_user_uuid", out var actor) && Guid.TryParse(actor, out var parsed)) LastActor = parsed;
            if (path.EndsWith("/challenges/consume")) return Json(new { codeAuthenticatedAt = DateTimeOffset.UtcNow, codeAuthenticationProof = "fixture-proof", sessionVersion = Actor, userUuid = Actor, mcid = "AdminPlayer", permission = 0, accountIds = Array.Empty<Guid>() });
            if (path.EndsWith("/credentials")) return Json(new { sessionVersion = Actor, enabled = false });
            if (path.EndsWith("/authorization")) return Json(new { webAdmin = Admin });
            if (path == "/api/network-management/settings") return Json(new
            {
                revision = 2, lobbyServerId = "lobby", channels = new[] { new { serverId = "game", displayName = "Game", isGame = true } },
            });
            if (path == "/api/defense/admin/channels/game")
            {
                if (request.Method == HttpMethod.Put)
                {
                    Saves++;
                    LastSaveBody = await request.Content!.ReadAsStringAsync(ct);
                    if (SaveConflict) return new(HttpStatusCode.Conflict) { Content = JsonContent.Create(new { message = "設定が更新されています。" }) };
                }
                else DefenseGets++;
                return Json(new
                {
                    serverId = "game", revision = 7, dailyEnabled = false, dailyStartTimeJst = "09:00",
                    configuration = new { worldName = (string?)null, coreMaxHealth = (double?)null },
                    missingSettings = Missing || StartPending ? new[] { "configuration.coreMaxHealth", "eligibleMobs" } : Array.Empty<string>(),
                    registrationStatus = StartPending ? "CLOSED" : "OPEN", registeredCount = StartPending ? 0 : 5,
                    activeRun = StartPending ? new { runId = acceptedRunId, serverId = "game", status = "PENDING" } : null,
                    nextScheduledAtUtc = (DateTimeOffset?)null,
                });
            }
            if (path == "/api/defense/admin/channels/game/runs") return Json(Array.Empty<object>());
            if (path == "/api/defense/admin/channels/game/start")
            {
                Starts++;
                LastStartBody = await request.Content!.ReadAsStringAsync(ct);
                using var startBody = JsonDocument.Parse(LastStartBody);
                var operationId = startBody.RootElement.GetProperty("operationId").GetGuid();
                StartOperationIds.Add(operationId);
                if (acceptedStartOperationId is Guid accepted && accepted != operationId)
                    return new(HttpStatusCode.Conflict);
                if (acceptedStartOperationId is null)
                {
                    acceptedStartOperationId = operationId;
                    AcceptedRuns++;
                    if (LoseFirstStartResponse)
                    {
                        StartPending = true;
                        throw new HttpRequestException("開始は受理されたが応答が失われた");
                    }
                }
                return Json(new { runId = acceptedRunId, serverId = "game", status = "PENDING" });
            }
            return new(HttpStatusCode.NotFound);
        }

        private static HttpResponseMessage Json(object value) => new(HttpStatusCode.OK) { Content = JsonContent.Create(value) };
    }
}
