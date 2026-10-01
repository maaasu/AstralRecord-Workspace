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

public sealed class PaidServicesTests
{
    [Fact]
    public async Task Shop_UsesCurrentAccountFromLiveProfile_AndRejectsForgedAccount()
    {
        var api = new PaidHandler();
        await using var factory = new PaidFactory(api);
        using var client = Client(factory);
        Assert.Equal(HttpStatusCode.Found, (await client.GetAsync("/AstraldShop")).StatusCode);
        await Login(client);
        var html = WebUtility.HtmlDecode(await client.GetStringAsync("/AstraldShop"));
        Assert.Contains("現在の冒険者", html);
        Assert.Contains("1,200", html);
        Assert.Contains("経験値ブースト", html);
        Assert.Contains("type=\"hidden\" name=\"ChannelId\" value=\"channel-1\"", html);
        Assert.DoesNotContain("<select", html);
        Assert.Contains("詳しい説明", html);
        Assert.DoesNotContain("商品一覧を取得できません", html);
        Assert.DoesNotContain("古いアカウント", html);
        var operationId = Guid.NewGuid();
        var fields = ShopForm(Token(html), operationId, PaidHandler.OldAccount);
        Assert.Equal(HttpStatusCode.OK, (await client.PostAsync("/AstraldShop?handler=Purchase", new FormUrlEncodedContent(fields))).StatusCode);
        Assert.Equal(0, api.Purchases);
        fields["AccountId"] = PaidHandler.CurrentAccount.ToString();
        Assert.Equal(HttpStatusCode.Found, (await client.PostAsync("/AstraldShop?handler=Purchase&actor_user_uuid=" + Guid.NewGuid(), new FormUrlEncodedContent(fields))).StatusCode);
        Assert.Equal(1, api.Purchases);
        Assert.Equal(PaidHandler.Actor, api.LastActor);
        Assert.Equal(PaidHandler.CurrentAccount, api.LastAccount);
        Assert.Equal("fixture-web-key", api.LastWebKey);
        Assert.Equal(operationId, api.LastOperationId);
        Assert.Equal(HttpStatusCode.BadRequest, (await client.PostAsync("/AstraldShop?handler=Purchase", new FormUrlEncodedContent(ShopForm("", Guid.NewGuid(), PaidHandler.CurrentAccount)))).StatusCode);
    }

    [Fact]
    public async Task Mail_UsesApiClaimEligibilityForMixedRewards_AndCurrentAccount()
    {
        var api = new PaidHandler();
        await using var factory = new PaidFactory(api);
        using var client = Client(factory);
        Assert.Equal(HttpStatusCode.Found, (await client.GetAsync("/Mail")).StatusCode);
        await Login(client);
        var html = WebUtility.HtmlDecode(await client.GetStringAsync("/Mail"));
        Assert.Contains("混在メール", html);
        Assert.Contains("通貨を受け取る", html);
        Assert.Contains("有償アストラルド", html);
        Assert.Contains("旅の素材", html);
        Assert.DoesNotContain("99a00021", html);
        Assert.DoesNotContain("（CURRENCY）", html);
        var fields = new Dictionary<string, string>
        {
            ["__RequestVerificationToken"] = Token(html), ["AccountId"] = PaidHandler.OldAccount.ToString(),
            ["OperationId"] = Guid.NewGuid().ToString(), ["MailId"] = "mixed-mail",
        };
        Assert.Equal(HttpStatusCode.OK, (await client.PostAsync("/Mail?handler=Claim", new FormUrlEncodedContent(fields))).StatusCode);
        Assert.Equal(0, api.Claims);
        fields["AccountId"] = PaidHandler.CurrentAccount.ToString();
        Assert.Equal(HttpStatusCode.Found, (await client.PostAsync("/Mail?handler=Claim", new FormUrlEncodedContent(fields))).StatusCode);
        Assert.Equal(1, api.Claims);
        Assert.Equal(PaidHandler.Actor, api.LastActor);
        api.AllowClaim = false;
        Assert.Equal(HttpStatusCode.OK, (await client.PostAsync("/Mail?handler=Claim", new FormUrlEncodedContent(fields))).StatusCode);
        Assert.Equal(1, api.Claims);
    }

    [Theory]
    [InlineData("insufficient_paid_astrald", "有償アストラルドの残高が不足")]
    [InlineData("boost_already_active", "同種のブーストが有効")]
    [InlineData("price_changed", "商品価格または内容が変わりました")]
    public async Task RejectedPurchase_ExplainsConcreteReason_WithoutClaimingCompletion(string reason, string message)
    {
        var api = new PaidHandler { RejectionReason = reason };
        await using var factory = new PaidFactory(api);
        using var client = Client(factory);
        await Login(client);
        var html = await client.GetStringAsync("/AstraldShop");
        var response = await client.PostAsync("/AstraldShop?handler=Purchase", new FormUrlEncodedContent(
            ShopForm(Token(html), Guid.NewGuid(), PaidHandler.CurrentAccount)));
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var result = WebUtility.HtmlDecode(await response.Content.ReadAsStringAsync());
        Assert.Contains(message, result);
        Assert.Contains("購入は成立していません", result);
        Assert.DoesNotContain("効果を直接適用しました", result);
    }

    [Fact]
    public async Task SingleChannel_HidesSelector_AndBlocksPurchaseWhileSameKindBoostIsActive()
    {
        var api = new PaidHandler { ActiveExpBoost = true };
        await using var factory = new PaidFactory(api);
        using var client = Client(factory);
        await Login(client);
        var html = WebUtility.HtmlDecode(await client.GetStringAsync("/AstraldShop"));
        Assert.Contains("type=\"hidden\" name=\"ChannelId\" value=\"channel-1\"", html);
        Assert.DoesNotContain("<select", html);
        Assert.Contains("同種ブーストが発動中", html);
        Assert.Matches("<button[^>]*type=\"submit\"[^>]*disabled", html);
    }

    [Fact]
    public async Task MultipleChannels_ShowSelector_AndDisableOnlyActiveChannel()
    {
        var api = new PaidHandler { MultipleChannels = true, ActiveExpBoost = true };
        await using var factory = new PaidFactory(api);
        using var client = Client(factory);
        await Login(client);
        var html = WebUtility.HtmlDecode(await client.GetStringAsync("/AstraldShop"));
        Assert.Contains("<select", html);
        Assert.Matches("<option[^>]*value=\"channel-1\"[^>]*disabled", html);
        Assert.Contains("value=\"channel-2\"", html);
        Assert.DoesNotContain("type=\"hidden\" name=\"ChannelId\"", html);
    }

    [Fact]
    public async Task NoChannels_ShowsUnavailableInsteadOfPurchaseForm()
    {
        var api = new PaidHandler { NoChannels = true };
        await using var factory = new PaidFactory(api);
        using var client = Client(factory);
        await Login(client);
        var html = WebUtility.HtmlDecode(await client.GetStringAsync("/AstraldShop"));
        Assert.Contains("対象チャンネルを取得できないため、現在購入できません", html);
        Assert.DoesNotContain("name=\"ItemId\"", html);
    }

    [Theory]
    [InlineData(true, false)]
    [InlineData(false, true)]
    public async Task SpecialBoost_DisablesPurchaseWhenEitherBoostIsActive(bool expActive, bool dropActive)
    {
        var api = new PaidHandler { SpecialOffer = true, ActiveExpBoost = expActive, ActiveDropBoost = dropActive };
        await using var factory = new PaidFactory(api);
        using var client = Client(factory);
        await Login(client);
        var html = WebUtility.HtmlDecode(await client.GetStringAsync("/AstraldShop"));
        Assert.Contains("EXP・ドロップ同時ブースト", html);
        Assert.Contains("同種ブーストが発動中", html);
        Assert.Matches("<button[^>]*type=\"submit\"[^>]*disabled", html);
    }

    [Fact]
    public async Task VipList_RequiresRecheckedAdmin_AndIsHiddenFromOrdinaryNavigation()
    {
        var api = new PaidHandler();
        await using var factory = new PaidFactory(api);
        using var client = Client(factory);
        Assert.Equal(HttpStatusCode.Found, (await client.GetAsync("/Players/Vip")).StatusCode);
        await Login(client);
        var page = await client.GetStringAsync("/MyPage");
        Assert.DoesNotContain("VIPプレイヤー一覧", page);
        Assert.Equal(HttpStatusCode.Found, (await client.GetAsync("/Players/Vip")).StatusCode);
        Assert.Equal(0, api.VipReads);
        api.Admin = true;
        var admin = await client.GetStringAsync("/Players/Vip");
        Assert.Contains("VIPプレイヤー一覧", admin);
        Assert.Equal(1, api.VipReads);
        Assert.Equal(PaidHandler.Actor, api.LastActor);
        Assert.Equal("fixture-web-key", api.VipWebKey);
    }

    private static Dictionary<string, string> ShopForm(string token, Guid operationId, Guid accountId) => new()
    {
        ["__RequestVerificationToken"] = token, ["AccountId"] = accountId.ToString(),
        ["OperationId"] = operationId.ToString(), ["ItemId"] = "30a00014", ["ChannelId"] = "channel-1", ["ExpectedPricePaidAstrald"] = "500",
    };
    private static string Token(string html) => Regex.Match(html, "name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\"").Groups[1].Value;
    private static HttpClient Client(PaidFactory factory) => factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new Uri("https://localhost"), AllowAutoRedirect = false });
    private static async Task Login(HttpClient client)
    {
        var html = await client.GetStringAsync("/Login");
        Assert.Equal(HttpStatusCode.Found, (await client.PostAsync("/Login", new FormUrlEncodedContent(new Dictionary<string, string>
        { ["__RequestVerificationToken"] = Token(html), ["LoginCode"] = "TEST-CODE" }))).StatusCode);
    }

    private sealed class PaidFactory(PaidHandler handler) : WebApplicationFactory<Program>
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            builder.ConfigureAppConfiguration((_, config) => config.AddInMemoryCollection(new Dictionary<string, string?>
            { ["Donations:WebKey"] = "fixture-web-key" }));
            builder.ConfigureTestServices(services =>
            {
                services.AddHttpClient<WebAuthApiClient>().ConfigurePrimaryHttpMessageHandler(() => handler);
                services.AddHttpClient<PlayerProfileApiClient>().ConfigurePrimaryHttpMessageHandler(() => handler);
                services.AddHttpClient<PaidServicesApiClient>().ConfigurePrimaryHttpMessageHandler(() => handler);
                services.AddHttpClient<ItemMasterApiClient>().ConfigurePrimaryHttpMessageHandler(() => handler);
            });
        }
    }

    private sealed class PaidHandler : HttpMessageHandler
    {
        public static readonly Guid Actor = Guid.Parse("11111111-1111-1111-1111-111111111111");
        public static readonly Guid OldAccount = Guid.Parse("22222222-2222-2222-2222-222222222222");
        public static readonly Guid CurrentAccount = Guid.Parse("33333333-3333-3333-3333-333333333333");
        public bool Admin { get; set; }
        public bool AllowClaim { get; set; } = true;
        public bool ActiveExpBoost { get; set; }
        public bool ActiveDropBoost { get; set; }
        public bool MultipleChannels { get; set; }
        public bool NoChannels { get; set; }
        public bool SpecialOffer { get; set; }
        public string? RejectionReason { get; set; }
        public int Purchases { get; private set; }
        public int Claims { get; private set; }
        public int VipReads { get; private set; }
        public Guid LastActor { get; private set; }
        public Guid LastAccount { get; private set; }
        public Guid LastOperationId { get; private set; }
        public string? LastWebKey { get; private set; }
        public string? VipWebKey { get; private set; }
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            var uri = request.RequestUri!;
            var path = uri.AbsolutePath;
            var query = Microsoft.AspNetCore.WebUtilities.QueryHelpers.ParseQuery(uri.Query);
            if (query.TryGetValue("actor_user_uuid", out var actorText) && Guid.TryParse(actorText, out var actor)) LastActor = actor;
            LastWebKey = request.Headers.TryGetValues("X-Donation-Web-Key", out var values) ? values.Single() : null;
            if (path.EndsWith("/challenges/consume")) return Json(new { codeAuthenticatedAt = DateTimeOffset.UtcNow, codeAuthenticationProof = "proof", sessionVersion = Actor, userUuid = Actor, mcid = "TestPlayer", permission = 0, currentAccountId = OldAccount, accountIds = new[] { OldAccount, CurrentAccount } });
            if (path.EndsWith("/credentials")) return Json(new { sessionVersion = Actor, enabled = false });
            if (path.EndsWith("/authorization")) return Json(new { webAdmin = Admin });
            if (path == "/api/web-profiles/me") return Json(new
            {
                userUuid = Actor, mcid = "TestPlayer", permission = 0, isPublic = false,
                currentAccount = new { accountId = CurrentAccount, accountName = "現在の冒険者", slotIndex = 1, playerLevel = 1, classId = "class", className = "戦士", classLevel = 1, gold = 0, updatedAt = DateTime.UtcNow, classProgresses = Array.Empty<object>(), skillTree = new { structureId = "tree", name = "tree", rootNodeId = "root", nodes = Array.Empty<object>(), edges = Array.Empty<object>() } },
                accounts = new[] { new { accountId = OldAccount, accountName = "古いアカウント", slotIndex = 0, playerLevel = 1, classId = "class", className = "戦士" } },
            });
            if (path == "/api/web/account-benefits") return Json(new { accountId = CurrentAccount, instancePriorityUses = 5, vipTier = "DONER", remainingDays = 2, paidAstraldBalance = 1200L });
            if (path == "/api/web/astrald-shop/catalog")
            {
                var channels = NoChannels ? Array.Empty<object>() : MultipleChannels
                    ? new object[] { new { channelId = "channel-1", displayName = "チャンネル1" }, new { channelId = "channel-2", displayName = "チャンネル2" } }
                    : new object[] { new { channelId = "channel-1", displayName = "チャンネル1" } };
                return SpecialOffer
                    ? Json(new { items = new[] { new { itemId = "30a00024", name = "スペシャルブーストチケット", pricePaidAstrald = 10000, effectType = "CHANNEL_SPECIAL_BOOST", effectValue = 2.0, durationSeconds = 3600, requiresChannel = true } }, channels })
                    : Json(new { items = new[] { new { itemId = "30a00014", name = "経験値ブースト", pricePaidAstrald = 500, effectType = "CHANNEL_EXP_BOOST", effectValue = 1.1, durationSeconds = 3600, requiresChannel = true } }, channels });
            }
            if (path == "/api/item") return Json(new[] { new { id = "99a00021", category = "currency", name = "&b有償アストラルド" }, new { id = "material", category = "material", name = "&a旅の素材" } });
            if (path == "/api/channel-boosts") return Json(new { eventCursor = 1, channels = ActiveExpBoost || ActiveDropBoost
                ? new object[] { new { channelId = "channel-1", exp = ActiveExpBoost ? new { multiplier = 1.5, expiresAt = DateTimeOffset.UtcNow.AddHours(1) } : null,
                    drop = ActiveDropBoost ? new { multiplier = 1.5, expiresAt = DateTimeOffset.UtcNow.AddHours(1) } : null } }
                : Array.Empty<object>() });
            if (path == "/api/web/astrald-shop/purchases" && request.Method == HttpMethod.Post)
            {
                Purchases++;
                var body = await request.Content!.ReadFromJsonAsync<JsonElement>(ct);
                LastAccount = body.GetProperty("accountId").GetGuid();
                LastOperationId = body.GetProperty("operationId").GetGuid();
                Assert.Equal(500, body.GetProperty("expectedPricePaidAstrald").GetInt64());
                if (RejectionReason is { } reason)
                    return new(HttpStatusCode.Conflict) { Content = JsonContent.Create(new { operationId = LastOperationId, status = "REJECTED", reason, accountId = LastAccount, itemId = "30a00014" }) };
                return Json(new { operationId = LastOperationId, status = "COMPLETED", accountId = LastAccount, itemId = "30a00014" });
            }
            if (path == "/api/web/mail") return Json(new[] { new { id = "mixed-mail", title = "混在メール", body = "報酬", isRead = false, canClaimCurrency = AllowClaim, rewards = new[] { new { itemId = "99a00021", category = "CURRENCY", amount = 500 }, new { itemId = "material", category = "ITEM", amount = 1 } } } });
            if (path == "/api/web/mail/mixed-mail/claim-currency" && request.Method == HttpMethod.Post)
            {
                Claims++;
                var body = await request.Content!.ReadFromJsonAsync<JsonElement>(ct);
                LastAccount = body.GetProperty("accountId").GetGuid();
                return Json(new { operationId = body.GetProperty("operationId").GetGuid(), status = "COMPLETED", accountId = LastAccount, mailId = "mixed-mail" });
            }
            if (path == "/api/web/vip-supporters") { VipReads++; VipWebKey = LastWebKey; return Json(Array.Empty<object>()); }
            return new(HttpStatusCode.NotFound);
        }
        private static HttpResponseMessage Json(object body) => new(HttpStatusCode.OK) { Content = JsonContent.Create(body) };
    }
}
