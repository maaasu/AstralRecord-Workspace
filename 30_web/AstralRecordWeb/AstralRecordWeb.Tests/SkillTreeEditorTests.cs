using System.Net;
using System.Net.Http.Json;
using System.Security.Claims;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.RegularExpressions;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using Xunit;

namespace AstralRecordWeb.Tests;

public sealed class SkillTreeEditorTests
{
    [Fact]
    public async Task Editor_RequiresAuthentication_AndUsesOnlyAuthenticatedActor()
    {
        var api = new EditorHandler();
        await using var factory = new EditorFactory(api);
        using var client = Client(factory);
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync(Path)).StatusCode);
        Assert.Empty(api.Calls);
        client.DefaultRequestHeaders.Add("X-Editor-Test-Identity", "yes");
        using var page = await client.GetAsync(Path + "?actor_user_id=" + Guid.NewGuid());
        Assert.Equal(HttpStatusCode.OK, page.StatusCode);
        Assert.True(page.Headers.CacheControl?.NoStore);
        Assert.Contains(api.Calls, call => call == $"GET /api/skilltree/editor/{AccountId}?actor_user_id={Actor}");
        Assert.Equal(HttpStatusCode.NotFound, (await client.GetAsync($"/skilltree/{Guid.NewGuid()}" )).StatusCode);
    }

    [Fact]
    public async Task Submit_RequiresAntiforgery_StripsForgedAuthorityAndCosts_AndKeepsPendingStatus()
    {
        var api = new EditorHandler();
        await using var factory = new EditorFactory(api);
        using var client = Client(factory);
        client.DefaultRequestHeaders.Add("X-Editor-Test-Identity", "yes");
        var payload = Payload();
        using var noToken = await client.PostAsJsonAsync(Path + "?handler=Operation", payload);
        Assert.Equal(HttpStatusCode.BadRequest, noToken.StatusCode);
        Assert.Null(api.Submitted);
        var html = await client.GetStringAsync(Path);
        client.DefaultRequestHeaders.Add("RequestVerificationToken", Token(html));
        using var submitted = await client.PostAsJsonAsync(Path + "?handler=Operation", payload);
        Assert.Equal(HttpStatusCode.OK, submitted.StatusCode);
        var result = await submitted.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("PENDING_ONLINE", result.GetProperty("status").GetString());
        var sent = api.Submitted!.Value;
        Assert.Equal(Actor, sent.GetProperty("actorUserId").GetGuid());
        Assert.False(sent.TryGetProperty("gold", out _));
        Assert.False(sent.TryGetProperty("canUnlock", out _));
        Assert.DoesNotContain(api.Calls, c => c.Contains("account-skilltree"));
    }

    [Fact]
    public async Task ResultLookup_PreservesOperationId_AndConflictDoesNotLeakInternalDetails()
    {
        var api = new EditorHandler();
        await using var factory = new EditorFactory(api);
        using var client = Client(factory);
        client.DefaultRequestHeaders.Add("X-Editor-Test-Identity", "yes");
        var html = await client.GetStringAsync(Path);
        client.DefaultRequestHeaders.Add("RequestVerificationToken", Token(html));
        var id = Guid.NewGuid();
        using var result = await client.GetAsync(Path + "?handler=Operation&operationId=" + id);
        Assert.Equal(HttpStatusCode.OK, result.StatusCode);
        Assert.Contains(api.Calls, c => c == $"GET /api/skilltree/editor/{AccountId}/operations/{id}?actor_user_id={Actor}");
        api.Conflict = true;
        using var conflict = await client.PostAsJsonAsync(Path + "?handler=Operation", Payload());
        Assert.Equal(HttpStatusCode.Conflict, conflict.StatusCode);
        Assert.DoesNotContain("secret-generation", await conflict.Content.ReadAsStringAsync());
        api.Conflict = false;
        using var canceled = await client.PostAsync(Path + "?handler=Cancel&operationId=" + id, null);
        Assert.Equal(HttpStatusCode.OK, canceled.StatusCode);
        Assert.Contains(api.Calls, c => c.StartsWith($"DELETE /api/skilltree/editor/{AccountId}/operations/{id}"));
    }

    [Fact]
    public async Task BatchSubmitKeepsOrderAndStripsNestedClientCosts()
    {
        var api = new EditorHandler();
        await using var factory = new EditorFactory(api);
        using var client = Client(factory);
        client.DefaultRequestHeaders.Add("X-Editor-Test-Identity", "yes");
        var html = await client.GetStringAsync(Path);
        client.DefaultRequestHeaders.Add("RequestVerificationToken", Token(html));
        var operationId = Guid.NewGuid();
        using var response = await client.PostAsJsonAsync(Path + "?handler=Operation", new {
            operationId, actorUserId = Guid.NewGuid(), targetServerId = "test", expectedDefinitionGenerationId = "fixture-generation",
            expectedPlayerStateVersion = 4, action = "BATCH", nodeId = "batch", changes = new[] {
                new { action = "UNLOCK", nodeId = "vitality", gold = -100, canUnlock = true },
                new { action = "UNLOCK", nodeId = "focus", gold = -200, canUnlock = true },
            },
        });
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var sent = api.Submitted!.Value;
        Assert.Equal(Actor, sent.GetProperty("actorUserId").GetGuid());
        var changes = sent.GetProperty("changes");
        Assert.Equal(2, changes.GetArrayLength());
        Assert.Equal("vitality", changes[0].GetProperty("nodeId").GetString());
        Assert.Equal("focus", changes[1].GetProperty("nodeId").GetString());
        Assert.False(changes[0].TryGetProperty("gold", out _));
        Assert.DoesNotContain("data-editor-location", html);
        Assert.Contains("最大表示", WebUtility.HtmlDecode(html));
    }

    [Fact]
    public async Task MyPageDisplaysConnectionAndUuidSkinWhileEditorKeepsLocationOutOfItsControls()
    {
        var api = new EditorHandler();
        await using var factory = new EditorFactory(api);
        using var client = Client(factory);
        client.DefaultRequestHeaders.Add("X-Editor-Test-Identity", "yes");
        var html = WebUtility.HtmlDecode(await client.GetStringAsync("/MyPage"));
        Assert.Contains("冒険チャンネル 1", html);
        Assert.Contains("はじまりの街", html);
        Assert.Contains($"https://crafatar.com/avatars/{Actor:N}", html);
        Assert.Contains("referrerpolicy=\"no-referrer\"", html);
        Assert.Contains("data-player-avatar", html);
    }

    internal static readonly Guid Actor = Guid.Parse("11111111-1111-1111-1111-111111111111");
    internal static readonly Guid AccountId = Guid.Parse("22222222-2222-2222-2222-222222222222");
    internal static string Path => $"/skilltree/{AccountId}";
    private static object Payload() => new { operationId = Guid.NewGuid(), actorUserId = Guid.NewGuid(), targetServerId = "test", expectedDefinitionGenerationId = "fixture-generation", expectedPlayerStateVersion = 4, action = "UNLOCK", nodeId = "power", sourceClassId = "swordsman", gold = -999, canUnlock = true };
    internal static string Token(string html) => WebUtility.HtmlDecode(Regex.Match(html, "name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\"").Groups[1].Value);
    internal static HttpClient Client(EditorFactory factory) => factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new Uri("https://localhost"), AllowAutoRedirect = false });

    internal sealed class EditorFactory(EditorHandler api) : WebApplicationFactory<Program>
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder) => builder.ConfigureTestServices(services =>
        {
            services.AddAuthentication(options => { options.DefaultAuthenticateScheme = "EditorTests"; options.DefaultChallengeScheme = "EditorTests"; })
                .AddScheme<AuthenticationSchemeOptions, EditorAuthentication>("EditorTests", _ => { });
            services.AddHttpClient<SkillTreeEditorApiClient>().ConfigurePrimaryHttpMessageHandler(() => api);
            services.AddHttpClient<PlayerProfileApiClient>().ConfigurePrimaryHttpMessageHandler(() => api);
            services.AddHttpClient<WebAuthApiClient>().ConfigurePrimaryHttpMessageHandler(() => api);
            services.AddSingleton<IMinecraftStatusProbe, FixedOnlineStatusProbe>();
        });
    }

    private sealed class EditorAuthentication(IOptionsMonitor<AuthenticationSchemeOptions> options, ILoggerFactory logger, UrlEncoder encoder)
        : AuthenticationHandler<AuthenticationSchemeOptions>(options, logger, encoder)
    {
        protected override Task<AuthenticateResult> HandleAuthenticateAsync() => Task.FromResult(Request.Headers.ContainsKey("X-Editor-Test-Identity")
            ? AuthenticateResult.Success(new AuthenticationTicket(new ClaimsPrincipal(new ClaimsIdentity([new Claim(ClaimTypes.NameIdentifier, Actor.ToString()), new Claim(ClaimTypes.Name, "テストの冒険者")], Scheme.Name)), Scheme.Name))
            : AuthenticateResult.NoResult());
    }

    internal sealed class EditorHandler : HttpMessageHandler
    {
        public List<string> Calls { get; } = [];
        public JsonElement? Submitted { get; private set; }
        public bool Conflict { get; set; }
        internal object? StateOverride { get; set; }
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            var uri = request.RequestUri!;
            if (uri.AbsolutePath.Contains("web-auth")) return Json(new { webAdmin = false, canAccessAdmin = false });
            Calls.Add($"{request.Method} {uri.PathAndQuery}");
            if (uri.AbsolutePath == "/api/web-profiles/me") return Json(new {
                userUuid = Actor, mcid = "テストの冒険者", permission = 0, isPublic = false, accounts = Array.Empty<object>(),
                currentAccount = new { accountId = AccountId, accountName = "旅する剣士", slotIndex = 0, playerLevel = 13,
                    classId = "swordsman", className = "剣士", classLevel = 9, classProgresses = Array.Empty<object>(), gold = 15420,
                    updatedAt = DateTime.UtcNow, skillTree = JsonSerializer.SerializeToElement(Fixture()).GetProperty("tree"),
                    connection = new { status = "online", channelName = "冒険チャンネル 1", worldName = "はじまりの街", x = 120, y = 64, z = -30, observedAtUtc = DateTime.UtcNow },
                },
            });
            if (!uri.AbsolutePath.StartsWith($"/api/skilltree/editor/{AccountId}")) return new(HttpStatusCode.NotFound);
            if (request.Method == HttpMethod.Post)
            {
                Submitted = await request.Content!.ReadFromJsonAsync<JsonElement>(ct);
                if (Conflict) return new(HttpStatusCode.Conflict) { Content = JsonContent.Create(new { error = "secret-generation" }) };
                return Json(new { operationId = Submitted.Value.GetProperty("operationId").GetGuid(), status = "PENDING_ONLINE" });
            }
            if (uri.AbsolutePath.Contains("/operations/")) return Json(new { operationId = Guid.Parse(uri.Segments[^1]), status = request.Method == HttpMethod.Delete ? "CANCELED" : "PENDING_ONLINE" });
            return Json(StateOverride ?? Fixture());
        }
        internal static object Fixture() => new
        {
            accountId = AccountId, accountName = "旅する剣士", generationId = "fixture-generation", stateRevision = 4, canEdit = true, supportsBatch = true, relockGoldCost = 100, balanceKind = "LIVE", hasFreshState = true,
            connection = new { status = "online", serverId = "test", channelName = "冒険チャンネル 1", worldName = "はじまりの街", x = 120, y = 64, z = -30, canEdit = true, observedAtUtc = DateTime.UtcNow },
            points = new { pp = 12, gold = 15420, classes = new[] { new { classId = "swordsman", className = "剣士", availableCp = 8 }, new { classId = "mage", className = "魔法使い", availableCp = 4 } } },
            tree = new { structureId = "main", name = "成長の星図", rootNodeId = "root", nodes = new object[]
            {
                new { nodeId = "root", name = "冒険のはじまり", icon = "COMPASS", pointType = "PP", pointCost = 0, costText = "0 PP", x = 0, y = 0, z = 0, isUnlocked = true, displayEffects = new[] { "最大HP +10" }, canRelock = false },
                new { nodeId = "power", name = "剣の力と揺るぎない守り", icon = "IRON_SWORD", pointType = "CP", pointCost = 3, costText = "消費元を選択 · 3 CP", x = 3, y = 0, z = 0, canUnlock = true, displayEffects = new[] { "物理攻撃力 +5", "物理防御力 +2", "クリティカル率 +2%" }, requiresCpSourceSelection = true, cpSources = new[] { new { classId = "swordsman", className = "剣士", availableCp = 8 }, new { classId = "mage", className = "魔法使い", availableCp = 4 } } },
                new { nodeId = "vitality", name = "生命の器", icon = "GOLDEN_APPLE", pointType = "PP", pointCost = 2, costText = "2 PP", x = 0, y = 0, z = 3, canUnlock = true, displayEffects = new[] { "最大HP +20", "HP自然回復 +1" } },
                new { nodeId = "focus", name = "精神統一", icon = "BOOK", pointType = "CP", pointCost = 2, costText = "魔法使い · 2 CP", x = 3, y = 0, z = 3, canUnlock = false, isConditionMet = false, requirementText = "魔法使い Lv.10", displayEffects = new[] { "最大MP +10", "魔法攻撃力 +3", "スキル：精神統一" } },
            }, edges = new[] { new { sourceNodeId = "root", targetNodeId = "power" }, new { sourceNodeId = "root", targetNodeId = "vitality" }, new { sourceNodeId = "power", targetNodeId = "focus" }, new { sourceNodeId = "vitality", targetNodeId = "focus" } } },
        };
        private static HttpResponseMessage Json(object value) => new(HttpStatusCode.OK) { Content = JsonContent.Create(value) };
    }
}
