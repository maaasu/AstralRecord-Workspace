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

public sealed class DonationTests
{
    [Fact]
    public async Task Submission_RequiresLoginCsrfConsentAndMatchingAmounts_UsesCookieActor()
    {
        var api = new DonationHandler();
        await using var factory = new DonationFactory(api);
        using var client = Client(factory);
        Assert.Equal(HttpStatusCode.Found, (await client.GetAsync("/Donations")).StatusCode);
        await Login(client);
        var html = await client.GetStringAsync("/Donations");
        Assert.Contains("Input.Entries[0].Value", html);
        Assert.Equal(HttpStatusCode.BadRequest, (await client.PostAsync("/Donations", new FormUrlEncodedContent(Form()))).StatusCode);
        var fields = Form(Token(html));
        fields["Input.DeclaredAmount"] = "600";
        await client.PostAsync("/Donations", new FormUrlEncodedContent(fields));
        Assert.Equal(0, api.Creates);
        fields["Input.DeclaredAmount"] = "500";
        fields["Input.Agree"] = "false";
        await client.PostAsync("/Donations", new FormUrlEncodedContent(fields));
        Assert.Equal(0, api.Creates);
        fields["Input.Agree"] = "true";
        var saved = await client.PostAsync("/Donations?actor_user_uuid=" + Guid.NewGuid(), new FormUrlEncodedContent(fields));
        Assert.Equal(HttpStatusCode.Found, saved.StatusCode);
        Assert.Equal(1, api.Creates);
        Assert.Equal(DonationHandler.Actor, api.LastActor);
        Assert.Equal("fixture-donation-key", api.LastWebKey);
        Assert.Contains("2026-09-26", api.LastBody);
    }

    [Theory]
    [InlineData("0")]
    [InlineData("-1")]
    [InlineData(" ")]
    [InlineData("one")]
    [InlineData("1000001")]
    public async Task Approval_InvalidOverrideNeverFallsBackToDeclaredAmount(string amount)
    {
        var api = new DonationHandler { Admin = true };
        await using var factory = new DonationFactory(api);
        using var client = Client(factory);
        await Login(client);
        var url = "/Admin/Donations/Detail/" + DonationHandler.Id;
        var html = await client.GetStringAsync(url);
        var response = await client.PostAsync(url + "?handler=Approve", new FormUrlEncodedContent(new Dictionary<string, string>
        { ["__RequestVerificationToken"] = Token(html), ["ApprovedAmount"] = amount, ["ConfirmReceived"] = "true" }));
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal(0, api.Approvals);
    }

    [Theory]
    [InlineData("", "null")]
    [InlineData("400", "400")]
    public async Task Approval_EmptyMeansDeclared_ExplicitAmountIsForwarded(string amount, string expectedJson)
    {
        var api = new DonationHandler { Admin = true };
        await using var factory = new DonationFactory(api);
        using var client = Client(factory);
        await Login(client);
        var url = "/Admin/Donations/Detail/" + DonationHandler.Id;
        var html = await client.GetStringAsync(url);
        var response = await client.PostAsync(url + "?handler=Approve", new FormUrlEncodedContent(new Dictionary<string, string>
        { ["__RequestVerificationToken"] = Token(html), ["ApprovedAmount"] = amount, ["ConfirmReceived"] = "true" }));
        Assert.Equal(HttpStatusCode.Found, response.StatusCode);
        Assert.Equal(1, api.Approvals);
        Assert.Equal("{\"approvedAmount\":" + expectedJson + "}", api.LastBody);
    }

    [Fact]
    public async Task AdminPermissionIsRechecked_AndOAuthCallbackRejectsMissingState()
    {
        var api = new DonationHandler();
        await using var factory = new DonationFactory(api);
        using var client = Client(factory);
        await Login(client);
        Assert.Equal(HttpStatusCode.Found, (await client.GetAsync("/Admin/Donations")).StatusCode);
        Assert.Equal(0, api.AdminLists);
        var callback = await client.GetAsync("/Donations/Discord?code=untrusted&state=forged");
        Assert.Equal(HttpStatusCode.Found, callback.StatusCode);
        Assert.Equal(0, api.Links);
        var html = await client.GetStringAsync("/Donations");
        var start = await client.PostAsync("/Donations/Discord", new FormUrlEncodedContent(new Dictionary<string, string> { ["__RequestVerificationToken"] = Token(html) }));
        Assert.Equal(HttpStatusCode.Found, start.StatusCode);
        Assert.Equal("discord.com", start.Headers.Location!.Host);
        var query = Microsoft.AspNetCore.WebUtilities.QueryHelpers.ParseQuery(start.Headers.Location.Query);
        Assert.Equal("identify guilds.members.read", query["scope"].ToString());
        Assert.Equal("S256", query["code_challenge_method"].ToString());
        var cookie = start.Headers.GetValues("Set-Cookie").Single(x => x.StartsWith("__Host-AstralRecordDiscord="));
        Assert.Contains("secure", cookie);
        Assert.Contains("httponly", cookie);
        Assert.Contains("samesite=lax", cookie);
    }

    [Theory]
    [InlineData("https://pay.paypay.ne.jp/example", true)]
    [InlineData("https://pay.paypay.ne.jp.evil.test/example", false)]
    [InlineData("http://pay.paypay.ne.jp/example", false)]
    [InlineData("https://attacker@pay.paypay.ne.jp/example", false)]
    public void PayPayUrlAllowsOnlyOfficialHttpsHost(string value, bool valid) =>
        Assert.Equal(valid, AstralRecordWeb.Pages.Donations.IndexModel.IsPayPayUrl(value));

    [Fact]
    public async Task OAuth_ValidCallbackExchangesCodeServerSide_AndConsumedStateCannotReplay()
    {
        var api = new DonationHandler();
        await using var factory = new DonationFactory(api);
        using var client = Client(factory);
        await Login(client);
        var html = await client.GetStringAsync("/Donations");
        var start = await client.PostAsync("/Donations/Discord", new FormUrlEncodedContent(new Dictionary<string, string> { ["__RequestVerificationToken"] = Token(html) }));
        var query = Microsoft.AspNetCore.WebUtilities.QueryHelpers.ParseQuery(start.Headers.Location!.Query);
        var callbackUrl = "/Donations/Discord?code=fixture-code&state=" + query["state"].ToString();
        var callback = await client.GetAsync(callbackUrl);
        Assert.Equal(HttpStatusCode.Found, callback.StatusCode);
        Assert.Equal(1, api.TokenExchanges);
        Assert.Equal(1, api.Links);
        Assert.Contains("fixture-access", api.LastBody);
        Assert.DoesNotContain("fixture-access", callback.Headers.ToString());
        await client.GetAsync(callbackUrl);
        Assert.Equal(1, api.TokenExchanges);
        Assert.Equal(1, api.Links);
    }

    [Fact]
    public async Task OAuth_CookieWithMismatchedStateNeverExchangesCode()
    {
        var api = new DonationHandler();
        await using var factory = new DonationFactory(api);
        using var client = Client(factory);
        await Login(client);
        var html = await client.GetStringAsync("/Donations");
        await client.PostAsync("/Donations/Discord", new FormUrlEncodedContent(new Dictionary<string, string> { ["__RequestVerificationToken"] = Token(html) }));
        await client.GetAsync("/Donations/Discord?code=fixture-code&state=" + new string('A', 43));
        Assert.Equal(0, api.TokenExchanges);
        Assert.Equal(0, api.Links);
    }

    private static Dictionary<string, string> Form(string? token = null)
    {
        var fields = new Dictionary<string, string>
        {
            ["Input.OperationId"] = Guid.NewGuid().ToString(), ["Input.DeclaredAmount"] = "500", ["Input.Agree"] = "true",
            ["Input.Entries[0].Method"] = "amazon", ["Input.Entries[0].Value"] = "fixture-gift-code", ["Input.Entries[0].DeclaredAmount"] = "500",
        };
        if (token is not null) fields["__RequestVerificationToken"] = token;
        return fields;
    }
    private static string Token(string html) => WebUtility.HtmlDecode(Regex.Match(html, "name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\"").Groups[1].Value);
    private static HttpClient Client(DonationFactory factory) => factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new Uri("https://localhost"), AllowAutoRedirect = false });
    private static async Task Login(HttpClient client)
    {
        var html = await client.GetStringAsync("/Login");
        Assert.Equal(HttpStatusCode.Found, (await client.PostAsync("/Login", new FormUrlEncodedContent(new Dictionary<string, string>
        { ["__RequestVerificationToken"] = Token(html), ["LoginCode"] = "TEST-CODE" }))).StatusCode);
    }
    private sealed class DonationFactory(DonationHandler handler) : WebApplicationFactory<Program>
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            builder.ConfigureAppConfiguration((_, config) => config.AddInMemoryCollection(new Dictionary<string, string?>
            {
                ["Donations:WebKey"] = "fixture-donation-key", ["Donations:DiscordClientId"] = "fixture-client",
                ["Donations:DiscordClientSecret"] = "fixture-secret", ["Donations:DiscordRedirectUri"] = "https://localhost/Donations/Discord",
            }));
            builder.ConfigureTestServices(services =>
            {
                services.AddHttpClient<WebAuthApiClient>().ConfigurePrimaryHttpMessageHandler(() => handler);
                services.AddHttpClient<DonationApiClient>().ConfigurePrimaryHttpMessageHandler(() => handler);
                services.AddHttpClient<DiscordOAuthClient>().ConfigurePrimaryHttpMessageHandler(() => handler);
            });
        }
    }
    private sealed class DonationHandler : HttpMessageHandler
    {
        public static readonly Guid Actor = Guid.Parse("11111111-1111-1111-1111-111111111111");
        public static readonly Guid Id = Guid.Parse("22222222-2222-2222-2222-222222222222");
        public bool Admin { get; init; }
        public int Creates { get; private set; }
        public int Approvals { get; private set; }
        public int Links { get; private set; }
        public int TokenExchanges { get; private set; }
        public int AdminLists { get; private set; }
        public Guid? LastActor { get; private set; }
        public string? LastWebKey { get; private set; }
        public string LastBody { get; private set; } = "";
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            var path = request.RequestUri!.AbsolutePath;
            if (path == "/api/oauth2/token")
            {
                TokenExchanges++;
                var form = await request.Content!.ReadAsStringAsync(ct);
                Assert.Contains("code_verifier=", form);
                Assert.Contains("client_secret=fixture-secret", form);
                return Json(new { access_token = "fixture-access", refresh_token = "fixture-refresh" });
            }
            if (path.EndsWith("/challenges/consume")) return Json(new { codeAuthenticatedAt = DateTimeOffset.UtcNow, codeAuthenticationProof = "fixture-proof", sessionVersion = Actor, userUuid = Actor, mcid = "TestPlayer", permission = 0, accountIds = Array.Empty<Guid>() });
            if (path.EndsWith("/credentials")) return Json(new { sessionVersion = Actor, enabled = false });
            if (path.EndsWith("/authorization")) return Json(new { webAdmin = Admin });
            var query = Microsoft.AspNetCore.WebUtilities.QueryHelpers.ParseQuery(request.RequestUri.Query);
            if (query.TryGetValue("actor_user_uuid", out var value) && Guid.TryParse(value, out var actor)) LastActor = actor;
            LastWebKey = request.Headers.TryGetValues("X-Donation-Web-Key", out var keys) ? keys.Single() : null;
            if (request.Content is not null) LastBody = await request.Content.ReadAsStringAsync(ct);
            if (path == "/api/donations/discord") { Links++; return Json(new { }); }
            if (path.EndsWith("/approve")) { Approvals++; return Json(Donation()); }
            if (path == "/api/donations" && request.Method == HttpMethod.Post) { Creates++; return Json(Donation()); }
            if (path == "/api/donations" || path == "/api/donations/admin")
            {
                if (path.EndsWith("/admin")) AdminLists++;
                return Json(new { totalApprovedAmount = 500, totalCount = 1, requests = new[] { Donation() }, discordLink = new { discordUserId = "123", discordName = "Fixture", isGuildMember = true } });
            }
            if (path == "/api/donations/" + Id) return Json(Donation());
            return new(HttpStatusCode.NotFound);
        }
        private static object Donation() => new { id = Id, userUuid = Actor, mcid = "TestPlayer", declaredAmount = 500, status = "Reviewing", createdAtUtc = DateTimeOffset.UtcNow, entries = Array.Empty<object>() };
        private static HttpResponseMessage Json(object body) => new(HttpStatusCode.OK) { Content = JsonContent.Create(body) };
    }
}
