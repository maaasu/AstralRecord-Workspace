using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.RegularExpressions;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Xunit;

namespace AstralRecordWeb.Tests;

public sealed class PlayerProfileTests
{
    [Fact]
    public async Task MyPage_UsesLiveSelectedAccount_AndOnlyVisibilityCanBeChanged()
    {
        var api = new ProfileHandler();
        await using var factory = new ProfileFactory(api);
        using var client = Client(factory);
        await Login(client);
        using var response = await client.GetAsync("/MyPage?viewer_user_uuid=ffffffff-ffff-ffff-ffff-ffffffffffff");
        var body = WebUtility.HtmlDecode(await response.Content.ReadAsStringAsync());
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Contains("no-store", response.Headers.CacheControl?.ToString());
        Assert.Contains("LivePlayer", body);
        Assert.Contains("選択中の冒険者", body);
        Assert.Contains("543,210", body);
        Assert.Contains("剣士", body);
        Assert.Contains("権限レベル", body);
        Assert.Contains("プロフィールを公開する", body);
        Assert.Contains("閲覧専用", body);
        Assert.Contains("data-tree-json", body);
        Assert.DoesNotContain("stale-account-name", body);
        Assert.Contains(api.Calls, uri => uri.Contains($"/me?viewer_user_uuid={ProfileHandler.UserId:D}"));

        using var changed = await client.PostAsync("/MyPage?handler=Visibility", new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["__RequestVerificationToken"] = Token(body), ["IsPublic"] = "true", ["viewer_user_uuid"] = Guid.NewGuid().ToString(),
        }));
        Assert.Equal(HttpStatusCode.Found, changed.StatusCode);
        Assert.True(api.Published);
        Assert.Equal(ProfileHandler.UserId.ToString(), api.LastVisibilityViewer);
        using var published = await client.GetAsync("/MyPage");
        var publishedBody = WebUtility.HtmlDecode(await published.Content.ReadAsStringAsync());
        Assert.Contains("非公開にする", publishedBody);
        using var hidden = await client.PostAsync("/MyPage?handler=Visibility", new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["__RequestVerificationToken"] = Token(publishedBody), ["IsPublic"] = "false",
        }));
        Assert.Equal(HttpStatusCode.Found, hidden.StatusCode);
        Assert.False(api.Published);
    }

    [Fact]
    public async Task Visibility_RequiresLoginAndAntiforgery()
    {
        var api = new ProfileHandler();
        await using var factory = new ProfileFactory(api);
        using var client = Client(factory);
        using var anonymous = await client.GetAsync("/MyPage");
        Assert.Equal(HttpStatusCode.Found, anonymous.StatusCode);
        await Login(client);
        using var noToken = await client.PostAsync("/MyPage?handler=Visibility", new FormUrlEncodedContent(new Dictionary<string, string> { ["IsPublic"] = "true" }));
        Assert.Equal(HttpStatusCode.BadRequest, noToken.StatusCode);
        Assert.False(api.Published);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task NonAdminCannotRequestPrivateProfiles_EvenWithForgedViewer(bool signedIn)
    {
        var api = new ProfileHandler();
        await using var factory = new ProfileFactory(api);
        using var client = Client(factory);
        if (signedIn) await Login(client);
        foreach (var path in new[] { "/players", $"/players/{Guid.NewGuid():D}" })
        {
            using var response = await client.GetAsync(path + "?includePrivate=true&viewer_user_uuid=ffffffff-ffff-ffff-ffff-ffffffffffff");
            Assert.Equal(HttpStatusCode.Found, response.StatusCode);
        }
        Assert.DoesNotContain(api.Calls, uri => uri.Contains("/api/web-profiles"));
    }

    [Fact]
    public async Task PublicDirectory_AndOtherProfile_HidePrivateControls()
    {
        var api = new ProfileHandler { Published = true };
        await using var factory = new ProfileFactory(api);
        using var client = Client(factory);
        var body = WebUtility.HtmlDecode(await client.GetStringAsync("/players?mcid=Live&classId=swordsman&sort=level_asc&pageNumber=2"));
        Assert.DoesNotContain("すべてのプレイヤーの情報を検索", body);
        Assert.Contains("LivePlayer", body);
        Assert.Contains(api.Calls, uri => uri.Contains("sort=level_asc") && uri.Contains("page=2") && uri.Contains("mcid=Live") && uri.Contains("class_id=swordsman"));
        var detail = WebUtility.HtmlDecode(await client.GetStringAsync($"/players/{ProfileHandler.UserId}"));
        Assert.Contains("プレイヤーのプロフィール", detail);
        Assert.DoesNotContain("プロフィールを公開する", detail);
        Assert.DoesNotContain("権限レベル", detail);
    }

    [Fact]
    public async Task PlayerDirectoryDetailAndBackLink_PreserveSearchState_WithoutGrantingPrivateAccess()
    {
        var api = new ProfileHandler { Published = true };
        await using var factory = new ProfileFactory(api);
        using var client = Client(factory);

        var publicDirectory = WebUtility.HtmlDecode(await client.GetStringAsync("/players?mcid=Live%20Player&classId=swordsman&sort=level_asc&pageNumber=2"));
        var publicDetailPath = Link(publicDirectory, "ar-player-card");
        AssertSearchState(publicDetailPath, "Live Player", "swordsman", "level_asc", "2", "false");
        Assert.DoesNotContain("includePrivate=true", publicDetailPath, StringComparison.OrdinalIgnoreCase);

        await Login(client);
        api.Admin = true;
        var adminDirectory = WebUtility.HtmlDecode(await client.GetStringAsync("/players?mcid=Live%20Player&classId=swordsman&sort=level_asc&pageNumber=2&includePrivate=true"));
        var adminDetailPath = Link(adminDirectory, "ar-player-card");
        AssertSearchState(adminDetailPath, "Live Player", "swordsman", "level_asc", "2", "true");

        var adminDetail = WebUtility.HtmlDecode(await client.GetStringAsync(adminDetailPath));
        var backPath = Link(adminDetail, "ar-btn-outline");
        Assert.Equal("/players", new Uri(client.BaseAddress!, backPath).AbsolutePath);
        AssertSearchState(backPath, "Live Player", "swordsman", "level_asc", "2", "true");
    }

    [Fact]
    public async Task AdminDirectory_UsesRecheckedWebAdmin_AndLosesAccessWhenRevoked()
    {
        var api = new ProfileHandler { Admin = true };
        await using var factory = new ProfileFactory(api);
        using var client = Client(factory);
        await Login(client);
        var body = WebUtility.HtmlDecode(await client.GetStringAsync("/players?includePrivate=true"));
        Assert.Contains("すべてのプレイヤーの情報を検索", body);
        Assert.Contains("非公開・管理者閲覧", body);
        Assert.Contains(api.Calls, uri => uri.Contains("include_private=true"));
        api.Admin = false;
        using var revoked = await client.GetAsync("/players?includePrivate=true");
        Assert.Equal(HttpStatusCode.Found, revoked.StatusCode);
    }

    [Fact]
    public async Task ApiFailure_ShowsRetryMessage_AndNotInventedZeroValues()
    {
        var api = new ProfileHandler { FailProfiles = true };
        await using var factory = new ProfileFactory(api);
        using var client = Client(factory);
        await Login(client);
        var body = WebUtility.HtmlDecode(await client.GetStringAsync("/MyPage"));
        Assert.Contains("プレイヤー情報を取得できませんでした", body);
        Assert.DoesNotContain("ar-stats", body);
    }

    [Fact]
    public async Task Logout_IsConfirmed_AndGetDoesNotSignOut()
    {
        var api = new ProfileHandler();
        await using var factory = new ProfileFactory(api);
        using var client = Client(factory);
        await Login(client);
        var body = WebUtility.HtmlDecode(await client.GetStringAsync("/Logout"));
        Assert.Contains("logout-confirm", body);
        Assert.Contains("ログアウトしますか？", body);
        Assert.Contains("キャンセル", body);
        using var stillLoggedIn = await client.GetAsync("/MyPage");
        Assert.Equal(HttpStatusCode.OK, stillLoggedIn.StatusCode);
        using var logout = await client.PostAsync("/Logout", new FormUrlEncodedContent(new Dictionary<string, string> { ["__RequestVerificationToken"] = Token(body) }));
        Assert.Equal(HttpStatusCode.Found, logout.StatusCode);
        using var after = await client.GetAsync("/MyPage");
        Assert.Equal(HttpStatusCode.Found, after.StatusCode);
    }

    private static HttpClient Client(ProfileFactory factory) => factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new Uri("https://localhost"), AllowAutoRedirect = false });
    private static string Link(string body, string className) => Regex.Match(body, $"<a[^>]*class=\"[^\"]*{className}[^\"]*\"[^>]*href=\"([^\"]+)\"").Groups[1].Value;
    private static void AssertSearchState(string path, string mcid, string classId, string sort, string pageNumber, string includePrivate)
    {
        Assert.NotEmpty(path);
        var query = Microsoft.AspNetCore.WebUtilities.QueryHelpers.ParseQuery(new Uri("https://localhost" + path).Query);
        Assert.Equal(mcid, query["mcid"].ToString());
        Assert.Equal(classId, query["classId"].ToString());
        Assert.Equal(sort, query["sort"].ToString());
        Assert.Equal(pageNumber, query["pageNumber"].ToString());
        Assert.Equal(includePrivate, query["includePrivate"].ToString(), ignoreCase: true);
    }
    private static string Token(string body) => WebUtility.HtmlDecode(Regex.Match(body, "name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\"").Groups[1].Value);
    private static async Task Login(HttpClient client)
    {
        var body = await client.GetStringAsync("/Login");
        using var response = await client.PostAsync("/Login", new FormUrlEncodedContent(new Dictionary<string, string> { ["__RequestVerificationToken"] = Token(body), ["LoginCode"] = "TEST-CODE" }));
        Assert.Equal(HttpStatusCode.Found, response.StatusCode);
    }

    private sealed class ProfileFactory(ProfileHandler handler) : WebApplicationFactory<Program>
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder) => builder.ConfigureTestServices(services =>
        {
            services.AddHttpClient<WebAuthApiClient>().ConfigurePrimaryHttpMessageHandler(() => handler);
            services.AddHttpClient<PlayerProfileApiClient>().ConfigurePrimaryHttpMessageHandler(() => handler);
        });
    }

    private sealed class ProfileHandler : HttpMessageHandler
    {
        public static readonly Guid UserId = Guid.Parse("11111111-1111-1111-1111-111111111111");
        public List<string> Calls { get; } = [];
        public bool Published { get; set; }
        public bool Admin { get; set; }
        public bool FailProfiles { get; set; }
        public string? LastVisibilityViewer { get; private set; }

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            var uri = request.RequestUri!;
            Calls.Add(uri.PathAndQuery);
            if (uri.AbsolutePath.EndsWith("challenges/consume")) return Json(new { userUuid = UserId, mcid = "CookiePlayer", permission = 99, accountIds = Array.Empty<Guid>() });
            if (uri.AbsolutePath.EndsWith("authorization")) return Json(new { webAdmin = Admin });
            if (FailProfiles) return new(HttpStatusCode.ServiceUnavailable);
            if (request.Method == HttpMethod.Put)
            {
                var data = await request.Content!.ReadFromJsonAsync<JsonElement>(ct);
                Published = data.GetProperty("isPublic").GetBoolean();
                LastVisibilityViewer = Microsoft.AspNetCore.WebUtilities.QueryHelpers.ParseQuery(uri.Query)["viewer_user_uuid"];
                return Json(new { isPublic = Published });
            }
            var profile = new
            {
                userUuid = UserId, mcid = "LivePlayer", permission = 0, isPublic = Published,
                currentAccount = new
                {
                    accountId = Guid.Parse("22222222-2222-2222-2222-222222222222"), accountName = "選択中の冒険者", playerLevel = 37,
                    classId = "swordsman", className = "剣士", classLevel = 12, gold = 543210L, updatedAt = "2026-09-15T12:34:56Z",
                    classProgresses = new[] { new { classId = "swordsman", className = "剣士", level = 12 } },
                    skillTree = new { structureId = "main", name = "冒険の始まり", rootNodeId = "1000", nodes = new[] { new { nodeId = "1000", name = "力の覚醒", icon = "DIAMOND_SWORD", pointType = "PP", pointCost = 1, x = 0, y = 0, z = 0, isUnlocked = true } }, edges = Array.Empty<object>() },
                },
            };
            return uri.AbsolutePath == "/api/web-profiles"
                ? Json(new { profiles = new[] { profile }, classes = new[] { new { id = "swordsman", name = "剣士" } }, page = 1, pageSize = 20, totalCount = 40 })
                : Json(profile);
        }
        private static HttpResponseMessage Json(object data) => new(HttpStatusCode.OK) { Content = JsonContent.Create(data) };
    }
}
