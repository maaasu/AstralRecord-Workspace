using System.Net;
using System.Net.Http.Json;
using System.Text.RegularExpressions;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Xunit;

namespace AstralRecordWeb.Tests;

public sealed class ActivityHistoryTests
{
    [Theory]
    [InlineData("SameIp")]
    [InlineData("Trades")]
    [InlineData("Dungeons")]
    [InlineData("Mobs")]
    public async Task History_RequiresCurrentAdmin_AndCannotOverrideCookieActor(string page)
    {
        var api = new HistoryHandler();
        await using var factory = new HistoryFactory(api);
        using var client = factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new("https://localhost"), AllowAutoRedirect = false });
        var path = "/Admin/History/" + page;
        using var anonymous = await client.GetAsync(path);
        Assert.Equal(HttpStatusCode.Found, anonymous.StatusCode);
        Assert.Equal(0, api.HistoryGets);
        await Login(client);
        using var denied = await client.GetAsync(path);
        Assert.Equal(HttpStatusCode.Found, denied.StatusCode);
        Assert.Equal(0, api.HistoryGets);
        api.Admin = true;
        using var allowed = await client.GetAsync(path + "?actor_user_uuid=22222222-2222-2222-2222-222222222222");
        Assert.Equal(HttpStatusCode.OK, allowed.StatusCode);
        Assert.True(api.HistoryGets > 0);
        Assert.Equal(HistoryHandler.ActorId.ToString(), api.LastActor);
        Assert.Equal("fixture-history-key", api.LastApiKey);
        Assert.True(allowed.Headers.CacheControl?.NoStore);
        api.Admin = false;
        var before = api.HistoryGets;
        using var revoked = await client.GetAsync(path);
        Assert.Equal(HttpStatusCode.Found, revoked.StatusCode);
        Assert.Equal(before, api.HistoryGets);
    }

    [Fact]
    public async Task History_InvalidRangeDoesNotQuery_AndUnavailableIsNotEmptySuccess()
    {
        var api = new HistoryHandler { Admin = true };
        await using var factory = new HistoryFactory(api);
        using var client = factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new("https://localhost"), AllowAutoRedirect = false });
        await Login(client);
        var invalid = WebUtility.HtmlDecode(await client.GetStringAsync("/Admin/History/Trades?From=2020-01-01&To=2026-01-01"));
        Assert.Contains("最大366日", invalid);
        Assert.Equal(0, api.HistoryGets);
        api.Unavailable = true;
        var failed = WebUtility.HtmlDecode(await client.GetStringAsync("/Admin/History/Trades"));
        Assert.Contains("取得できません", failed);
        Assert.DoesNotContain("条件に一致する記録はありません", failed);
    }

    private static async Task Login(HttpClient client)
    {
        var page = await client.GetStringAsync("/Login");
        var token = WebUtility.HtmlDecode(Regex.Match(page, "name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\"").Groups[1].Value);
        using var result = await client.PostAsync("/Login", new FormUrlEncodedContent(new Dictionary<string, string> { ["__RequestVerificationToken"] = token, ["LoginCode"] = "TEST-CODE" }));
        Assert.Equal(HttpStatusCode.Found, result.StatusCode);
    }

    [Theory]
    [InlineData("SameIp", "Supplier", "Receiver")]
    [InlineData("Trades", "星の素材", "Supplier")]
    [InlineData("Dungeons", "観測なし", "120.5 m")]
    [InlineData("Dungeons?View=players", "3 回", "Supplier")]
    [InlineData("Mobs", "テストモブ", "125.5")]
    [InlineData("Mobs?MobId=test-mob&View=players", "Supplier", "125.5")]
    [InlineData("Mobs?MobId=test-mob&View=deaths", "テストモブ", "Supplier")]
    public async Task History_RendersRecordedEvidenceAndEscapesNames(string path, string expected, string second)
    {
        var api = new HistoryHandler { Admin = true, Evidence = true };
        await using var factory = new HistoryFactory(api);
        using var client = factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new("https://localhost"), AllowAutoRedirect = false });
        await Login(client);
        var body = await client.GetStringAsync("/Admin/History/" + path);
        var text = WebUtility.HtmlDecode(body);
        Assert.Contains(expected, text);
        Assert.Contains(second, text);
        Assert.DoesNotContain("<script>fixture</script>", body);
        if (path == "Dungeons") Assert.Contains("0.0 m", text);
    }

    [Fact]
    public async Task TradeSearch_PreservesPairAndUsesExclusiveUtcEndDate()
    {
        var api = new HistoryHandler { Admin = true };
        await using var factory = new HistoryFactory(api);
        using var client = factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new("https://localhost"), AllowAutoRedirect = false });
        await Login(client);
        using var result = await client.GetAsync("/Admin/History/Trades?From=2026-09-01&To=2026-09-02&PageNumber=2&Query=A%26B&UserUuid=11111111-1111-1111-1111-111111111111&OtherUserUuid=22222222-2222-2222-2222-222222222222");
        Assert.Equal(HttpStatusCode.OK, result.StatusCode);
        var query = Microsoft.AspNetCore.WebUtilities.QueryHelpers.ParseQuery(api.LastHistoryUri!.Query);
        Assert.Equal("2026-08-31T15:00:00.0000000+00:00", query["from"]);
        Assert.Equal("2026-09-02T15:00:00.0000000+00:00", query["to"]);
        Assert.Equal("A&B", query["query"]);
        Assert.Equal("2", query["page"]);
        Assert.Equal("50", query["pageSize"]);
        Assert.Equal("22222222-2222-2222-2222-222222222222", query["otherUserUuid"]);
    }

    [Fact]
    public async Task History_UsesJapanTime_MasterItemNames_AndSafeMobColors()
    {
        var api = new HistoryHandler { Admin = true, Evidence = true };
        await using var factory = new HistoryFactory(api);
        using var client = factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new("https://localhost"), AllowAutoRedirect = false });
        await Login(client);

        var trades = await client.GetStringAsync("/Admin/History/Trades");
        Assert.Contains("2026-09-21 10:02:03", trades);
        Assert.Contains("<span class=\"mc-yellow\">星の素材</span>", trades);
        Assert.DoesNotContain("PAPER", trades);

        var ranking = await client.GetStringAsync("/Admin/History/Mobs");
        Assert.Contains("<span class=\"mc-red\">テストモブ&lt;script&gt;fixture&lt;/script&gt;</span>", ranking);
        Assert.DoesNotContain("<script>fixture</script>", ranking);

        var deaths = await client.GetStringAsync("/Admin/History/Mobs?MobId=test-mob&View=deaths");
        Assert.Contains("<span class=\"mc-red\">テストモブ&lt;script&gt;fixture&lt;/script&gt;</span>", deaths);
        Assert.DoesNotContain("<script>fixture</script>", deaths);
    }

    [Fact]
    public async Task TradeHistory_FallsBackToRecordedNameWhenItemMasterIsUnavailable()
    {
        var api = new HistoryHandler { Admin = true, Evidence = true, ItemUnavailable = true };
        await using var factory = new HistoryFactory(api);
        using var client = factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new("https://localhost"), AllowAutoRedirect = false });
        await Login(client);

        var body = WebUtility.HtmlDecode(await client.GetStringAsync("/Admin/History/Trades"));

        Assert.Contains("PAPER", body);
        Assert.Contains("履歴保存時の名称を表示しています", body);
    }

    private sealed class HistoryFactory(HistoryHandler handler) : WebApplicationFactory<Program>
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            builder.ConfigureAppConfiguration((_, configuration) => configuration.AddInMemoryCollection(new Dictionary<string, string?> { ["AstralRecordApi:ApiKey"] = "fixture-history-key" }));
            builder.ConfigureTestServices(services =>
            {
                services.AddHttpClient<WebAuthApiClient>().ConfigurePrimaryHttpMessageHandler(() => handler);
                services.AddHttpClient<ActivityHistoryApiClient>().ConfigurePrimaryHttpMessageHandler(() => handler);
                services.AddHttpClient<ItemMasterApiClient>().ConfigurePrimaryHttpMessageHandler(() => handler);
            });
        }
    }

    private sealed class HistoryHandler : HttpMessageHandler
    {
        public static readonly Guid ActorId = Guid.Parse("11111111-1111-1111-1111-111111111111");
        public bool Admin { get; set; }
        public bool Unavailable { get; set; }
        public bool Evidence { get; set; }
        public bool ItemUnavailable { get; set; }
        public int HistoryGets { get; private set; }
        public string? LastActor { get; private set; }
        public string? LastApiKey { get; private set; }
        public Uri? LastHistoryUri { get; private set; }
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            var path = request.RequestUri!.AbsolutePath;
            HttpResponseMessage result;
            if (path.EndsWith("/challenges/consume")) result = Json(new { codeAuthenticatedAt = DateTimeOffset.UtcNow, codeAuthenticationProof = "fixture-proof", sessionVersion = ActorId, userUuid = ActorId, mcid = "Admin", permission = 0, accountIds = Array.Empty<Guid>() });
            else if (path.EndsWith("/credentials")) result = Json(new { sessionVersion = ActorId, enabled = false });
            else if (path.EndsWith("/authorization")) result = Json(new { webAdmin = Admin });
            else if (path == "/api/item")
                result = ItemUnavailable
                    ? new(HttpStatusCode.ServiceUnavailable)
                    : Json(new[] { new { id = "test-item", category = "material", name = "&e星の素材" } });
            else if (path.StartsWith("/api/item/", StringComparison.Ordinal)) result = new(HttpStatusCode.NotFound);
            else if (path.StartsWith("/api/admin/player-activity/", StringComparison.Ordinal))
            {
                HistoryGets++;
                LastHistoryUri = request.RequestUri;
                LastActor = Microsoft.AspNetCore.WebUtilities.QueryHelpers.ParseQuery(request.RequestUri.Query)["actor_user_uuid"];
                LastApiKey = request.Headers.TryGetValues("X-Api-Key", out var keys) ? keys.Single() : null;
                var items = Evidence ? EvidenceItems(path) : [];
                result = Unavailable ? new(HttpStatusCode.ServiceUnavailable) : Json(new { page = 1, pageSize = 50, totalCount = items.Length, items });
            }
            else result = new(HttpStatusCode.NotFound);
            return Task.FromResult(result);
        }
        private static HttpResponseMessage Json(object value) => new(HttpStatusCode.OK) { Content = JsonContent.Create(value) };
        private static object[] EvidenceItems(string path)
        {
            var player = new { userUuid = ActorId, accountId = ActorId, mcid = "Supplier", accountName = "<script>fixture</script>" };
            var other = new { userUuid = Guid.Parse("22222222-2222-2222-2222-222222222222"), accountId = Guid.Parse("22222222-2222-2222-2222-222222222222"), mcid = "Receiver", accountName = "受取役" };
            var at = "2026-09-21T01:02:03Z";
            if (path.EndsWith("same-ip")) return [new { firstObservedAt = at, lastObservedAt = at, tradeCount = 3, players = new[] { player, other } }];
            if (path.EndsWith("trades")) return [new { eventId = ActorId, completedAt = at, source = player, destination = other, items = new[] { new { itemId = "test-item", itemName = "PAPER", quantity = 2 } }, gold = 50 }];
            if (path.EndsWith("dungeons/players")) return [new { player, clearCount = 3, firstClearedAt = at, lastClearedAt = at, totalDistanceMeters = 120.5m }];
            if (path.EndsWith("dungeons")) return [new { eventId = ActorId, dungeonId = "test-dungeon", dungeonName = "テスト迷宮", startedAt = at, clearedAt = at, durationSeconds = 100, participants = new object[] { new { player, distanceMeters = 120.5m, movementSampleCount = 25 }, new { player = other, distanceMeters = (decimal?)null, movementSampleCount = 0 }, new { player = new { userUuid = Guid.Parse("33333333-3333-3333-3333-333333333333"), accountId = Guid.Parse("33333333-3333-3333-3333-333333333333"), mcid = "Stationary", accountName = "静止" }, distanceMeters = 0m, movementSampleCount = 25 } } }];
            if (path.EndsWith("/players")) return [new { player, deathCount = 2, damageTaken = 125.5m, hitCount = 4, lastOccurredAt = at }];
            if (path.EndsWith("/kills")) return [new { eventId = ActorId, occurredAt = at, mobId = "test-mob", mobName = "§cテストモブ<script>fixture</script>", victim = player }];
            return [new { mobId = "test-mob", mobName = "§cテストモブ<script>fixture</script>", playerKillCount = 2, damageToPlayers = 125.5m, hitCount = 4, lastOccurredAt = at }];
        }
    }
}
