using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.RegularExpressions;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Xunit;

namespace AstralRecordWeb.Tests;

public sealed class PlayerAdminEditTests
{
    private static readonly Guid Actor = Guid.Parse("11111111-1111-1111-1111-111111111111");
    private static readonly Guid Target = Guid.Parse("22222222-2222-2222-2222-222222222222");
    private static readonly Guid Account = Guid.Parse("33333333-3333-3333-3333-333333333333");
    private static readonly Guid SessionId = Guid.Parse("44444444-4444-4444-4444-444444444444");
    private static readonly Guid EntryId = Guid.Parse("55555555-5555-5555-5555-555555555555");
    private static readonly Guid EquipmentEntryId = Guid.Parse("66666666-6666-6666-6666-666666666666");
    private static string Path => $"/admin/players/{Target:D}/accounts/{Account:D}/edit";

    [Fact]
    public async Task StartRequiresAdminAndCsrfAndUsesCookieActorAndDedicatedKey()
    {
        var api = new AdminHandler();
        await using var factory = new AdminFactory(api);
        Assert.IsType<EphemeralDataProtectionProvider>(factory.Services.GetRequiredService<IDataProtectionProvider>());
        using var client = Client(factory);
        using var anonymous = await client.GetAsync(Path);
        Assert.Equal(HttpStatusCode.Found, anonymous.StatusCode);
        await Login(client);
        using var denied = await client.GetAsync(Path);
        Assert.Equal(HttpStatusCode.Found, denied.StatusCode);
        api.Admin = true;
        var html = await client.GetStringAsync(Path);
        Assert.Contains("編集開始の内容を確認", html);
        using var noCsrf = await client.PostAsync(Path + "?handler=Start", Form(new("EditSessionId", SessionId.ToString()), new("Reason", "調査"), new("ConfirmTarget", "true")));
        Assert.Equal(HttpStatusCode.BadRequest, noCsrf.StatusCode);
        Assert.Equal(0, api.StartPosts);
        api.Admin = false;
        using var revoked = await client.PostAsync(Path + "?handler=Start", Form(new("__RequestVerificationToken", Token(html)), new("EditSessionId", SessionId.ToString()), new("Reason", "調査"), new("ConfirmTarget", "true")));
        Assert.Equal(HttpStatusCode.Found, revoked.StatusCode);
        Assert.Equal(0, api.StartPosts);
        api.Admin = true;
        using var started = await client.PostAsync(Path + "?handler=Start&actor_user_uuid=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", Form(new("__RequestVerificationToken", Token(html)), new("EditSessionId", SessionId.ToString()), new("Reason", "調査"), new("ConfirmTarget", "true")));
        Assert.Equal(HttpStatusCode.Found, started.StatusCode);
        Assert.Equal(0, api.StartPosts);
        var confirm = await client.GetStringAsync(Path);
        Assert.Contains("編集開始を確定", confirm);
        using var dispatched = await client.PostAsync(Path + "?handler=RetryStart", Form(Token(confirm)));
        Assert.Equal(HttpStatusCode.Found, dispatched.StatusCode);
        Assert.Equal(1, api.StartPosts);
        Assert.Equal(Actor.ToString("D"), api.LastActor);
        Assert.Equal("fixture-player-admin-key", api.LastAdminKey);
        Assert.Equal("fixture-api-key", api.LastApiKey);
    }

    [Fact]
    public async Task DrainingAndOtherOwnerNeverExposeEditorOrApply()
    {
        var api = new AdminHandler { Admin = true, Status = "DRAINING" };
        await using var factory = new AdminFactory(api);
        using var client = Client(factory);
        await Login(client);
        var draining = await client.GetStringAsync(Path);
        Assert.Contains("全サーバーの保存・切断確認を待っています", draining);
        Assert.DoesNotContain("変更内容を確認", draining);
        api.Status = "READY";
        api.Owner = Guid.NewGuid();
        var otherOwner = await client.GetStringAsync(Path);
        Assert.Contains("別の管理者が編集中です", otherOwner);
        Assert.DoesNotContain("変更内容を確認", otherOwner);
        Assert.Equal(0, api.EditorGets);
    }

    [Fact]
    public async Task ReadyApplyRejectsStaleHashAndAcceptsCurrentStateWithExpectedVersions()
    {
        var api = new AdminHandler { Admin = true, Status = "READY" };
        await using var factory = new AdminFactory(api);
        using var client = Client(factory);
        await Login(client);
        var html = await client.GetStringAsync(Path);
        Assert.Contains("変更内容を確認", html);
        Assert.DoesNotContain("currency_item", html);
        Assert.Contains("stack_item", html);
        Assert.Contains("Legendary Sword", html);
        var fields = ApplyFields(html);
        fields.RemoveAll(pair => pair.Key == "ExpectedStateHash");
        fields.Add(new("ExpectedStateHash", "stale"));
        using var stale = await client.PostAsync(Path + "?handler=Apply", new FormUrlEncodedContent(fields));
        Assert.Equal(HttpStatusCode.OK, stale.StatusCode);
        Assert.Equal(0, api.ApplyPosts);
        html = await client.GetStringAsync(Path);
        fields = ApplyFields(html);
        using var prepared = await client.PostAsync(Path + "?handler=Apply", new FormUrlEncodedContent(fields));
        Assert.Equal(HttpStatusCode.Found, prepared.StatusCode);
        var stateCookie = prepared.Headers.GetValues("Set-Cookie").Single(value => value.StartsWith("__Host-AstralRecordPlayerEdit-", StringComparison.Ordinal));
        Assert.True(stateCookie.Length < 4096, $"Protected apply cookie is {stateCookie.Length} characters.");
        Assert.Equal(0, api.ApplyPosts);
        var confirm = await client.GetStringAsync(Path);
        Assert.Contains("RetryOperation", confirm);
        Assert.Contains("同じ", confirm);
        using var applied = await client.PostAsync(Path + "?handler=RetryOperation", Form(Token(confirm)));
        Assert.Equal(HttpStatusCode.Found, applied.StatusCode);
        Assert.Equal(1, api.ApplyPosts);
        using var body = JsonDocument.Parse(api.LastApplyBody!);
        Assert.Equal("state-hash", body.RootElement.GetProperty("expectedStateHash").GetString());
        Assert.Equal("catalog-version", body.RootElement.GetProperty("expectedCatalogVersion").GetString());
        Assert.Equal("SET_QUANTITY", body.RootElement.GetProperty("inventoryChanges")[0].GetProperty("action").GetString());
        Assert.Equal(EntryId, body.RootElement.GetProperty("inventoryChanges")[0].GetProperty("inventoryEntryId").GetGuid());
        Assert.Equal(Actor.ToString("D"), api.LastActor);
    }

    [Fact]
    public async Task UncertainStartRetriesSameSessionIdAndReason()
    {
        var api = new AdminHandler { Admin = true, StartUncertainOnce = true };
        await using var factory = new AdminFactory(api);
        using var client = Client(factory);
        await Login(client);
        var html = await client.GetStringAsync(Path);
        using var prepared = await client.PostAsync(Path + "?handler=Start", Form(
            new("__RequestVerificationToken", Token(html)), new("EditSessionId", SessionId.ToString()),
            new("Reason", "調査"), new("ConfirmTarget", "true")));
        Assert.Equal(HttpStatusCode.Found, prepared.StatusCode);
        var pending = await client.GetStringAsync(Path);
        Assert.Contains("編集開始を確定", pending);
        Assert.DoesNotContain("編集開始の内容を確認", pending);
        using var uncertain = await client.PostAsync(Path + "?handler=RetryStart", Form(Token(pending)));
        Assert.Equal(HttpStatusCode.OK, uncertain.StatusCode);
        pending = await client.GetStringAsync(Path);
        using var retry = await client.PostAsync(Path + "?handler=RetryStart", Form(Token(pending)));
        Assert.Equal(HttpStatusCode.Found, retry.StatusCode);
        Assert.Equal(2, api.StartPosts);
        Assert.Equal(SessionId, api.LastStartId);
        Assert.Equal("調査", api.LastStartReason);
    }

    [Fact]
    public async Task MaximumJapaneseReasonFitsProtectedCookieAndCanBeConfirmed()
    {
        var api = new AdminHandler { Admin = true };
        await using var factory = new AdminFactory(api);
        using var client = Client(factory);
        await Login(client);
        var html = await client.GetStringAsync(Path);
        var reason = new string('調', 500);
        using var prepared = await client.PostAsync(Path + "?handler=Start", Form(
            new("__RequestVerificationToken", Token(html)), new("EditSessionId", SessionId.ToString()),
            new("Reason", reason), new("ConfirmTarget", "true")));
        Assert.Equal(HttpStatusCode.Found, prepared.StatusCode);
        var stateCookie = prepared.Headers.GetValues("Set-Cookie").Single(value => value.StartsWith("__Host-AstralRecordPlayerEdit-", StringComparison.Ordinal));
        Assert.True(stateCookie.Length < 4096, $"Protected edit cookie is {stateCookie.Length} characters.");
        var confirmation = await client.GetStringAsync(Path);
        Assert.Contains("編集開始を確定", confirmation);
        using var sent = await client.PostAsync(Path + "?handler=RetryStart", Form(Token(confirmation)));
        Assert.Equal(HttpStatusCode.Found, sent.StatusCode);
        Assert.Equal(reason, api.LastStartReason);
    }

    [Fact]
    public async Task UncertainApplyBlocksCancelAndResendsIdenticalPayloadAfterBrowserReload()
    {
        var api = new AdminHandler { Admin = true, Status = "READY", ApplyUncertainOnce = true };
        await using var factory = new AdminFactory(api);
        using var client = Client(factory);
        await Login(client);
        var html = await client.GetStringAsync(Path);
        using var prepared = await client.PostAsync(Path + "?handler=Apply", new FormUrlEncodedContent(ApplyFields(html)));
        Assert.Equal(HttpStatusCode.Found, prepared.StatusCode);
        var stateCookie = prepared.Headers.GetValues("Set-Cookie").Single(value => value.StartsWith("__Host-AstralRecordPlayerEdit-", StringComparison.Ordinal));
        Assert.True(stateCookie.Length < 4096, $"Protected apply cookie is {stateCookie.Length} characters.");
        var pending = await client.GetStringAsync(Path);
        Assert.Contains("RetryOperation", pending);
        Assert.DoesNotContain("変更内容を確認", pending);
        Assert.DoesNotContain("取消内容を確認", pending);
        using var blockedCancel = await client.PostAsync(Path + "?handler=Cancel", Form(
            new("__RequestVerificationToken", Token(pending)), new("EditSessionId", SessionId.ToString()),
            new("OperationId", Guid.NewGuid().ToString()), new("ExpectedRevision", "3"), new("ConfirmTarget", "true")));
        Assert.Equal(HttpStatusCode.OK, blockedCancel.StatusCode);
        Assert.Equal(0, api.CancelPosts);
        using var uncertain = await client.PostAsync(Path + "?handler=RetryOperation", Form(Token(pending)));
        Assert.Equal(HttpStatusCode.OK, uncertain.StatusCode);
        pending = await client.GetStringAsync(Path);
        using var retry = await client.PostAsync(Path + "?handler=RetryOperation", Form(Token(pending)));
        Assert.Equal(HttpStatusCode.Found, retry.StatusCode);
        Assert.Equal(2, api.ApplyPosts);
        Assert.Equal(api.FirstApplyBody, api.LastApplyBody);
        var receipt = await client.GetStringAsync(Path);
        Assert.Contains("編集セッションは終了しました", receipt);
    }

    [Fact]
    public async Task CancelRequiresDeliveredConfirmationBeforeUnlockRequest()
    {
        var api = new AdminHandler { Admin = true, Status = "READY" };
        await using var factory = new AdminFactory(api);
        using var client = Client(factory);
        await Login(client);
        var html = await client.GetStringAsync(Path);
        using var prepared = await client.PostAsync(Path + "?handler=Cancel", Form(
            new("__RequestVerificationToken", Token(html)), new("EditSessionId", SessionId.ToString()),
            new("OperationId", Guid.NewGuid().ToString()), new("ExpectedRevision", "3"), new("ConfirmTarget", "true")));
        Assert.Equal(HttpStatusCode.Found, prepared.StatusCode);
        Assert.Equal(0, api.CancelPosts);
        var confirmation = await client.GetStringAsync(Path);
        Assert.Contains("RetryOperation", confirmation);
        using var sent = await client.PostAsync(Path + "?handler=RetryOperation", Form(Token(confirmation)));
        Assert.Equal(HttpStatusCode.Found, sent.StatusCode);
        Assert.Equal(1, api.CancelPosts);
        Assert.Contains("編集セッションは終了しました", await client.GetStringAsync(Path));
    }

    [Fact]
    public async Task EquipmentGrantRequiresOneUnitAndNeverOffersExistingEquipmentForMutation()
    {
        var api = new AdminHandler { Admin = true, Status = "READY" };
        await using var factory = new AdminFactory(api);
        using var client = Client(factory);
        await Login(client);
        var html = await client.GetStringAsync(Path);
        Assert.Contains("legendary_sword", html);
        Assert.DoesNotContain($"<option value=\"{EquipmentEntryId:D}\">", html);
        var fields = ApplyFields(html);
        fields.RemoveAll(pair => pair.Key is "InventoryAction" or "InventoryEntryId" or "QuantityText");
        fields.Add(new("InventoryAction", "GRANT"));
        fields.Add(new("ItemId", "legendary_sword"));
        fields.Add(new("QuantityText", "2"));
        using var invalid = await client.PostAsync(Path + "?handler=Apply", new FormUrlEncodedContent(fields));
        Assert.Equal(HttpStatusCode.OK, invalid.StatusCode);
        Assert.Equal(0, api.ApplyPosts);
        fields.RemoveAll(pair => pair.Key == "QuantityText");
        fields.Add(new("QuantityText", "1"));
        using var prepared = await client.PostAsync(Path + "?handler=Apply", new FormUrlEncodedContent(fields));
        Assert.Equal(HttpStatusCode.Found, prepared.StatusCode);
        Assert.Equal(0, api.ApplyPosts);
        var confirmation = await client.GetStringAsync(Path);
        using var sent = await client.PostAsync(Path + "?handler=RetryOperation", Form(Token(confirmation)));
        Assert.Equal(HttpStatusCode.Found, sent.StatusCode);
        using var body = JsonDocument.Parse(api.LastApplyBody!);
        Assert.Equal("GRANT", body.RootElement.GetProperty("inventoryChanges")[0].GetProperty("action").GetString());
        Assert.Equal("legendary_sword", body.RootElement.GetProperty("inventoryChanges")[0].GetProperty("itemId").GetString());
        Assert.Equal(1, body.RootElement.GetProperty("inventoryChanges")[0].GetProperty("quantity").GetInt64());
    }

    private static List<KeyValuePair<string, string>> ApplyFields(string html) =>
    [
        new("__RequestVerificationToken", Token(html)), new("EditSessionId", SessionId.ToString()),
        new("OperationId", Guid.NewGuid().ToString()), new("ExpectedRevision", "3"),
        new("ExpectedStateHash", "state-hash"), new("ExpectedCatalogVersion", "catalog-version"),
        new("InventoryAction", "SET_QUANTITY"), new("InventoryEntryId", EntryId.ToString()),
        new("QuantityText", "5"), new("ConfirmTarget", "true"),
    ];

    private static FormUrlEncodedContent Form(params KeyValuePair<string, string>[] fields) => new(fields);
    private static FormUrlEncodedContent Form(string token) => Form(new KeyValuePair<string, string>("__RequestVerificationToken", token));
    private static string Token(string body) => WebUtility.HtmlDecode(Regex.Match(body, "name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\"").Groups[1].Value);
    private static HttpClient Client(AdminFactory factory) => factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new Uri("https://localhost"), AllowAutoRedirect = false });

    private static async Task Login(HttpClient client)
    {
        var html = await client.GetStringAsync("/Login");
        using var response = await client.PostAsync("/Login", Form(new("__RequestVerificationToken", Token(html)), new("LoginCode", "TEST-CODE")));
        Assert.Equal(HttpStatusCode.Found, response.StatusCode);
    }

    private sealed class AdminFactory(AdminHandler handler) : WebApplicationFactory<Program>
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            builder.WithIsolatedWebDependencies();
            builder.ConfigureAppConfiguration((_, config) => config.AddInMemoryCollection(new Dictionary<string, string?>
            {
                ["AstralRecordApi:ApiKey"] = "fixture-api-key", ["Api:PlayerAdminWebKey"] = "fixture-player-admin-key",
            }));
            builder.ConfigureTestServices(services =>
            {
                services.AddHttpClient<WebAuthApiClient>().ConfigurePrimaryHttpMessageHandler(() => handler);
                services.AddHttpClient<PlayerProfileApiClient>().ConfigurePrimaryHttpMessageHandler(() => handler);
                services.AddHttpClient<PlayerAdminApiClient>().ConfigurePrimaryHttpMessageHandler(() => handler);
                services.AddHttpClient<DonationApiClient>().ConfigurePrimaryHttpMessageHandler(() => handler);
            });
        }
    }

    private sealed class AdminHandler : HttpMessageHandler
    {
        public bool Admin { get; set; }
        public string? Status { get; set; }
        public Guid Owner { get; set; } = Actor;
        public int StartPosts { get; private set; }
        public int ApplyPosts { get; private set; }
        public int CancelPosts { get; private set; }
        public int EditorGets { get; private set; }
        public bool StartUncertainOnce { get; set; }
        public bool ApplyUncertainOnce { get; set; }
        public Guid LastStartId { get; private set; }
        public string? LastStartReason { get; private set; }
        public string? FirstApplyBody { get; private set; }
        public string? LastActor { get; private set; }
        public string? LastAdminKey { get; private set; }
        public string? LastApiKey { get; private set; }
        public string? LastApplyBody { get; private set; }

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            var uri = request.RequestUri!;
            if (uri.AbsolutePath.EndsWith("challenges/consume"))
                return Json(new { codeAuthenticatedAt = DateTimeOffset.UtcNow, codeAuthenticationProof = "fixture-proof", sessionVersion = Actor, userUuid = Actor, mcid = "Admin", permission = 0, accountIds = Array.Empty<Guid>() });
            if (uri.AbsolutePath.EndsWith("credentials")) return Json(new { sessionVersion = Actor, enabled = false });
            if (uri.AbsolutePath.EndsWith("authorization")) return Json(new { webAdmin = Admin });
            if (uri.AbsolutePath.EndsWith("/admin/pending-count")) return Json(new { pendingCount = 0 });
            if (uri.AbsolutePath.StartsWith("/api/web-profiles/", StringComparison.Ordinal))
                return Json(new { userUuid = Target, mcid = "Target", isPublic = false, currentAccount = new { accountId = Account, accountName = "Main", slotIndex = 0, playerLevel = 10, classId = "adventurer", className = "冒険者", classLevel = 2, classProgresses = Array.Empty<object>(), gold = 0, updatedAt = DateTime.UtcNow, skillTree = new { structureId = "main", name = "Main", rootNodeId = "root", nodes = Array.Empty<object>(), edges = Array.Empty<object>() } }, accounts = Array.Empty<object>() });
            if (uri.AbsolutePath.StartsWith("/api/player-admin/", StringComparison.Ordinal))
            {
                LastActor = Microsoft.AspNetCore.WebUtilities.QueryHelpers.ParseQuery(uri.Query)["actor_user_uuid"].ToString();
                LastAdminKey = request.Headers.TryGetValues("X-Player-Admin-Web-Key", out var adminKey) ? adminKey.Single() : null;
                LastApiKey = request.Headers.TryGetValues("X-Api-Key", out var apiKey) ? apiKey.Single() : null;
                if (uri.AbsolutePath.EndsWith("/edit-session"))
                    return Status is null or "COMPLETED" or "CANCELED" ? new(HttpStatusCode.NotFound) : Json(Session());
                if (uri.AbsolutePath.EndsWith($"/edit-sessions/{SessionId:D}") && request.Method == HttpMethod.Get)
                    return Status is null ? new(HttpStatusCode.NotFound) : Json(Session());
                if (uri.AbsolutePath.EndsWith("/edit-sessions") && request.Method == HttpMethod.Post)
                {
                    StartPosts++;
                    var start = await request.Content!.ReadFromJsonAsync<JsonElement>(ct);
                    LastStartId = start.GetProperty("editSessionId").GetGuid();
                    LastStartReason = start.GetProperty("reason").GetString();
                    if (StartUncertainOnce) { StartUncertainOnce = false; throw new HttpRequestException("Simulated upstream disconnect after dispatch."); }
                    Status = "DRAINING";
                    return Json(Session());
                }
                if (uri.AbsolutePath.EndsWith("/editor"))
                {
                    EditorGets++;
                    return Json(new
                    {
                        session = Session(), expectedStateHash = "state-hash", catalogVersion = "catalog-version",
                        account = new { accountId = Account, userUuid = Target, level = 10, totalExperience = 0, highestLevel = 10, classId = "adventurer", classLevel = 2, classExperience = 0, progressVersion = 1 },
                        inventories = new[]
                        {
                            new { inventoryId = Guid.NewGuid(), inventoryType = "BAG", inventoryProfile = "GAME", isEnabled = true,
                                entries = new object[]
                                {
                                    new { inventoryEntryId = EntryId, slotIndex = 0, itemCategory = "MATERIAL", itemId = "stack_item", quantity = 2, updatedAt = "2026-09-01T00:00:00Z" },
                                    new { inventoryEntryId = Guid.NewGuid(), slotIndex = 1, itemCategory = "CURRENCY", itemId = "古い通貨", quantity = 2, updatedAt = "2026-09-01T00:00:00Z" },
                                    new { inventoryEntryId = EquipmentEntryId, slotIndex = 2, itemCategory = "EQUIPMENT", itemId = (string?)null, resolvedItemId = "legendary_sword", displayName = "Legendary Sword", instanceType = "EQUIPMENT", instanceId = Guid.NewGuid(), quantity = 1, updatedAt = "2026-09-01T00:00:00Z" },
                                },
                            },
                        },
                        availableItems = new[]
                        {
                            new { itemId = "stack_item", name = "Stack", category = "MATERIAL", maxStack = 64 },
                            new { itemId = "currency_item", name = "Currency", category = "CURRENCY", maxStack = 64 },
                            new { itemId = "legendary_sword", name = "Legendary Sword", category = "equipment", maxStack = 1 },
                        },
                    });
                }
                if (uri.AbsolutePath.EndsWith("/apply"))
                {
                    ApplyPosts++;
                    LastApplyBody = await request.Content!.ReadAsStringAsync(ct);
                    FirstApplyBody ??= LastApplyBody;
                    if (ApplyUncertainOnce) { ApplyUncertainOnce = false; throw new HttpRequestException("Simulated upstream disconnect after dispatch."); }
                    Status = "COMPLETED";
                    return Json(Session());
                }
                if (uri.AbsolutePath.EndsWith("/cancel")) { CancelPosts++; Status = "CANCELED"; return Json(Session()); }
                if (uri.AbsolutePath.EndsWith("/refresh")) return Json(Session());
            }
            return new(HttpStatusCode.NotFound);
        }

        private object Session() => new { editSessionId = SessionId, accountId = Account, userUuid = Target, actorUserUuid = Owner, reason = "調査", status = Status, revision = 3L, createdAtUtc = DateTimeOffset.UtcNow, updatedAtUtc = DateTimeOffset.UtcNow, expiresAtUtc = DateTimeOffset.UtcNow.AddHours(1), expectedServerCount = 1, acknowledgedServerCount = Status == "READY" ? 1 : 0 };
        private static HttpResponseMessage Json(object value) => new(HttpStatusCode.OK) { Content = JsonContent.Create(value) };
    }
}
