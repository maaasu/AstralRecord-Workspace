using System.Net;
using System.Net.Http.Json;
using System.Text.RegularExpressions;
using AstralRecordWeb.Models;
using AstralRecordWeb.Options;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.AspNetCore.WebUtilities;
using Microsoft.Extensions.DependencyInjection;
using Xunit;

namespace AstralRecordWeb.Tests;

public sealed class BuilderSchematicTests
{
    private const string PagePath = "/Builder/Schematics";
    private const string Ticket = "fixture-upload-ticket";
    private const string ApiKey = "fixture-server-api-key";
    private const string UploadPath = PagePath + "?token=" + Ticket;

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Login_ReturnsToTheOriginalUploadLink(bool password)
    {
        var api = new ApiHandler();
        await using var factory = new Factory(api);
        using var client = CreateClient(factory);

        using var challenge = await client.GetAsync(UploadPath);
        Assert.Equal(HttpStatusCode.Found, challenge.StatusCode);
        AssertPrivate(challenge);
        Assert.Equal("/Login", challenge.Headers.Location!.AbsolutePath);
        Assert.Equal(UploadPath, QueryHelpers.ParseQuery(challenge.Headers.Location.Query)["ReturnUrl"].ToString());

        await Login(client, challenge.Headers.Location.PathAndQuery, password, UploadPath);
        using var uploadForm = await client.GetAsync(UploadPath);
        Assert.Equal(HttpStatusCode.OK, uploadForm.StatusCode);
        AssertPrivate(uploadForm);
        var body = await uploadForm.Content.ReadAsStringAsync();
        Assert.Contains("action=\"/Builder/Schematics\"", body);
        Assert.Contains("accept=\".schem\"", body);
        Assert.Equal(Ticket, HiddenValue(body, "Token"));
        Assert.DoesNotContain(ApiKey, body);
        Assert.Equal(0, api.UploadCalls);
    }

    [Theory]
    [InlineData("https://example.com/")]
    [InlineData("//example.com/")]
    public async Task Login_RejectsAnExternalReturnUrl(string returnUrl)
    {
        await using var factory = new Factory(new ApiHandler());
        using var client = CreateClient(factory);

        await Login(client, QueryHelpers.AddQueryString("/Login", "returnUrl", returnUrl), false, "/MyPage");
    }

    [Fact]
    public async Task Upload_UsesSessionIdentityAndShowsRegistrationStatus()
    {
        var api = new ApiHandler();
        await using var factory = new Factory(api);
        using var client = CreateClient(factory);
        await Login(client, "/Login", false, "/MyPage");
        var antiforgery = HiddenValue(await client.GetStringAsync(UploadPath), "__RequestVerificationToken");

        using var submission = await Upload(client, "tower.SCHEM", [1, 2, 3], antiforgery);
        Assert.Equal(HttpStatusCode.Found, submission.StatusCode);
        AssertPrivate(submission);
        Assert.Equal(PagePath + "?id=" + api.UploadId, submission.Headers.Location!.OriginalString);
        Assert.Equal(1, api.UploadCalls);

        var statusPath = submission.Headers.Location.OriginalString + "&userUuid=" + Guid.NewGuid();
        using var pending = await client.GetAsync(statusPath);
        AssertPrivate(pending);
        var pendingBody = WebUtility.HtmlDecode(await pending.Content.ReadAsStringAsync());
        Assert.Contains("受付済みです", pendingBody);
        Assert.Contains("登録状況を更新", pendingBody);
        Assert.DoesNotContain(Ticket, pendingBody);
        Assert.DoesNotContain(ApiKey, pendingBody);

        api.UploadStatus = "rejected";
        api.ErrorCode = "incompatible_schematic";
        var rejected = WebUtility.HtmlDecode(await client.GetStringAsync(statusPath));
        Assert.Contains("登録できませんでした", rejected);
        Assert.Contains("WorldEdit で読み込めませんでした", rejected);
        Assert.Contains("<code>/builder upload</code>", rejected);
        Assert.DoesNotContain(api.ErrorCode, rejected);

        api.UploadStatus = "registered";
        api.ErrorCode = null;
        var registered = WebUtility.HtmlDecode(await client.GetStringAsync(statusPath));
        Assert.Contains("登録されました", registered);
        Assert.Contains("登録済み建築", registered);
        Assert.Contains("<code>/builder load</code>", registered);

        using var notFound = await client.GetAsync(PagePath + "?id=" + Guid.NewGuid());
        Assert.Equal(HttpStatusCode.NotFound, notFound.StatusCode);
        Assert.DoesNotContain("tower", await notFound.Content.ReadAsStringAsync());
    }

    [Fact]
    public async Task Upload_RejectsInvalidFileAndMissingAntiforgeryBeforeCallingApi()
    {
        var api = new ApiHandler { SubmissionStatus = HttpStatusCode.Forbidden };
        await using var factory = new Factory(api);
        using var client = CreateClient(factory);
        await Login(client, "/Login", false, "/MyPage");
        var antiforgery = HiddenValue(await client.GetStringAsync(UploadPath), "__RequestVerificationToken");

        using var noAntiforgery = await Upload(client, "tower.schem", [1, 2, 3], null);
        Assert.Equal(HttpStatusCode.BadRequest, noAntiforgery.StatusCode);
        AssertPrivate(noAntiforgery);

        using var wrongExtension = await Upload(client, "tower.nbt", [1, 2, 3], antiforgery);
        Assert.Contains(".schem ファイルを選択してください", WebUtility.HtmlDecode(await wrongExtension.Content.ReadAsStringAsync()));

        using var tooLarge = await Upload(client, "tower.schem", new byte[1024 * 1024 + 1], antiforgery);
        Assert.Contains("1 MiB 以下", WebUtility.HtmlDecode(await tooLarge.Content.ReadAsStringAsync()));
        Assert.Equal(0, api.UploadCalls);

        using var rejected = await Upload(client, "tower.schem", [1, 2, 3], antiforgery);
        var body = WebUtility.HtmlDecode(await rejected.Content.ReadAsStringAsync());
        Assert.Contains("新しいリンクを発行してください", body);
        Assert.DoesNotContain(Ticket, body);
        Assert.Equal(1, api.UploadCalls);
    }

    [Fact]
    public async Task ListAndDelete_UseSessionIdentityAndRequireAntiforgeryForDeletion()
    {
        var api = new ApiHandler { UploadStatus = "registered" };
        await using var factory = new Factory(api);
        using var client = CreateClient(factory);
        await Login(client, "/Login", false, "/MyPage");

        using var list = await client.GetAsync(PagePath + "?userUuid=" + Guid.NewGuid());
        AssertPrivate(list);
        var receiptPath = PagePath + "?id=" + api.UploadId;
        var listBody = WebUtility.HtmlDecode(await list.Content.ReadAsStringAsync());
        Assert.Contains("自分のアップロード", listBody);
        Assert.Contains($"href=\"{receiptPath}\"", listBody);
        Assert.Contains("tower", listBody);
        Assert.DoesNotContain(ApiKey, listBody);

        var receipt = await client.GetStringAsync(receiptPath);
        Assert.Contains("handler=Delete", receipt);
        var deletePath = receiptPath + "&handler=Delete&userUuid=" + Guid.NewGuid();
        using var noAntiforgery = await client.PostAsync(deletePath, new FormUrlEncodedContent([]));
        Assert.Equal(HttpStatusCode.BadRequest, noAntiforgery.StatusCode);
        Assert.Equal(0, api.DeleteCalls);

        using var removed = await client.PostAsync(deletePath, new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["__RequestVerificationToken"] = HiddenValue(receipt, "__RequestVerificationToken"),
            ["userUuid"] = Guid.NewGuid().ToString(),
        }));
        Assert.Equal(HttpStatusCode.Found, removed.StatusCode);
        AssertPrivate(removed);
        Assert.Equal(PagePath, removed.Headers.Location!.OriginalString);
        Assert.Equal(1, api.DeleteCalls);

        var updated = WebUtility.HtmlDecode(await client.GetStringAsync(PagePath));
        Assert.Contains("アップロードを削除しました", updated);
        Assert.Contains("アップロードはありません", updated);
        Assert.DoesNotContain(receiptPath, updated);
        using var notFound = await client.GetAsync(receiptPath);
        Assert.Equal(HttpStatusCode.NotFound, notFound.StatusCode);
    }

    private static HttpClient CreateClient(Factory factory) => factory.CreateClient(new WebApplicationFactoryClientOptions
    {
        BaseAddress = new Uri("https://localhost"),
        AllowAutoRedirect = false,
    });

    private static async Task Login(HttpClient client, string url, bool password, string expectedDestination)
    {
        using var page = await client.GetAsync(url);
        AssertPrivate(page);
        var body = await page.Content.ReadAsStringAsync();
        var form = new Dictionary<string, string>
        {
            ["__RequestVerificationToken"] = HiddenValue(body, "__RequestVerificationToken"),
            ["ReturnUrl"] = HiddenValue(body, "ReturnUrl"),
        };
        var action = password ? "/Login?handler=Password" : "/Login";
        Assert.Contains($"action=\"{action}\"", body);
        if (password)
        {
            form["LoginId"] = "builder";
            form["Password"] = "password";
        }
        else
        {
            form["LoginCode"] = "ABCD-EFGH";
        }

        using var response = await client.PostAsync(action, new FormUrlEncodedContent(form));
        Assert.Equal(HttpStatusCode.Found, response.StatusCode);
        AssertPrivate(response);
        Assert.Equal(expectedDestination, response.Headers.Location!.OriginalString);
    }

    private static async Task<HttpResponseMessage> Upload(HttpClient client, string name, byte[] bytes, string? antiforgery)
    {
        using var form = new MultipartFormDataContent();
        form.Add(new StringContent(Ticket), "Token");
        form.Add(new StringContent(Guid.NewGuid().ToString()), "userUuid");
        if (antiforgery is not null) form.Add(new StringContent(antiforgery), "__RequestVerificationToken");
        form.Add(new ByteArrayContent(bytes), "SchematicFile", name);
        return await client.PostAsync(PagePath, form);
    }

    private static string HiddenValue(string body, string name) =>
        WebUtility.HtmlDecode(Regex.Match(body, $"name=\"{name}\"[^>]*value=\"([^\"]*)\"").Groups[1].Value);

    private static void AssertPrivate(HttpResponseMessage response)
    {
        Assert.True(response.Headers.CacheControl?.NoStore);
        Assert.Equal("no-referrer", Assert.Single(response.Headers.GetValues("Referrer-Policy")));
    }

    private sealed class Factory(ApiHandler api) : WebApplicationFactory<Program>
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder) =>
            builder.WithIsolatedWebDependencies().ConfigureTestServices(services =>
            {
                services.Configure<AstralRecordApiOptions>(options => options.ApiKey = ApiKey);
                services.AddHttpClient<WebAuthApiClient>().ConfigurePrimaryHttpMessageHandler(() => api);
                services.AddHttpClient<BuilderSchematicApiClient>().ConfigurePrimaryHttpMessageHandler(() => api);
            });
    }

    private sealed class ApiHandler : HttpMessageHandler
    {
        private readonly Guid userId = Guid.NewGuid();
        private readonly Guid sessionVersion = Guid.NewGuid();

        public Guid UploadId { get; } = Guid.NewGuid();
        public int UploadCalls { get; private set; }
        public int DeleteCalls { get; private set; }
        public bool Deleted { get; private set; }
        public string UploadStatus { get; set; } = "pending";
        public string? ErrorCode { get; set; }
        public HttpStatusCode SubmissionStatus { get; init; } = HttpStatusCode.Accepted;

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            var path = request.RequestUri!.AbsolutePath;
            if (path is "/api/web-auth/challenges/consume" or "/api/web-auth/password/login")
                return Json(HttpStatusCode.OK, new WebLoginChallengeConsumeResponse
                {
                    UserUuid = userId, SessionVersion = sessionVersion, Mcid = "Builder",
                });

            if (path == $"/api/web-auth/users/{userId:D}/credentials")
                return Json(HttpStatusCode.OK, new WebCredentialState { SessionVersion = sessionVersion });

            if (path == "/api/builder-schematics/uploads" && request.Method == HttpMethod.Post)
            {
                UploadCalls++;
                Assert.Equal(ApiKey, Assert.Single(request.Headers.GetValues("X-Api-Key")));
                var parts = Assert.IsType<MultipartFormDataContent>(request.Content)
                    .ToDictionary(part => part.Headers.ContentDisposition!.Name!.Trim('"'));
                Assert.Equal(userId.ToString(), await parts["userUuid"].ReadAsStringAsync(ct));
                Assert.Equal(Ticket, await parts["token"].ReadAsStringAsync(ct));
                Assert.Equal(new byte[] { 1, 2, 3 }, await parts["file"].ReadAsByteArrayAsync(ct));
                Assert.Equal("tower.schem", parts["file"].Headers.ContentDisposition!.FileNameStar, ignoreCase: true);
                return SubmissionStatus == HttpStatusCode.Accepted
                    ? Json(SubmissionStatus, new { id = UploadId, status = "pending" })
                    : new HttpResponseMessage(SubmissionStatus);
            }

            var upload = new { id = UploadId, name = "tower", status = UploadStatus, error = ErrorCode };
            if (path == "/api/builder-schematics/uploads" && request.Method == HttpMethod.Get)
            {
                Assert.Equal(ApiKey, Assert.Single(request.Headers.GetValues("X-Api-Key")));
                Assert.Equal("?userUuid=" + userId, request.RequestUri.Query);
                return Json(HttpStatusCode.OK, Deleted ? [] : new[] { upload });
            }

            if (path == $"/api/builder-schematics/uploads/{UploadId:D}" && !Deleted)
            {
                Assert.Equal(ApiKey, Assert.Single(request.Headers.GetValues("X-Api-Key")));
                Assert.Equal("?userUuid=" + userId, request.RequestUri.Query);
                if (request.Method == HttpMethod.Delete)
                {
                    DeleteCalls++;
                    Deleted = true;
                    return new HttpResponseMessage(HttpStatusCode.NoContent);
                }
                return Json(HttpStatusCode.OK, upload);
            }
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        }

        private static HttpResponseMessage Json(HttpStatusCode status, object value) =>
            new(status) { Content = JsonContent.Create(value) };
    }
}
