using System.Net;
using System.Net.Http.Json;
using AstralRecordApi.Authentication;
using AstralRecordApi.Controllers;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Xunit;

namespace AstralRecordApi.Tests.Controllers;

public sealed class BuilderSchematicsControllerTests
{
    private static readonly byte[] GzipHeader = [0x1f, 0x8b, 8, 0];

    [Fact]
    public async Task CommonAndDevKeysAreEnforcedBeforeRequestBinding()
    {
        using var directory = new TestDirectory();
        await using var api = await TestApi.StartAsync(directory);
        var owner = Guid.NewGuid();

        using var unauthorizedDev = await api.SendAsync(HttpMethod.Post, "tickets", commonKey: false, devKey: "dev-secret");
        Assert.Equal(HttpStatusCode.Unauthorized, unauthorizedDev.StatusCode);
        using var unauthorizedWeb = await api.SendAsync(HttpMethod.Post, "uploads", commonKey: false);
        Assert.Equal(HttpStatusCode.Unauthorized, unauthorizedWeb.StatusCode);
        using var missingDev = await api.SendAsync(HttpMethod.Post, "tickets");
        Assert.Equal(HttpStatusCode.Unauthorized, missingDev.StatusCode);
        using var wrongDev = await api.SendAsync(HttpMethod.Get, "pending", devKey: "wrong");
        Assert.Equal(HttpStatusCode.Unauthorized, wrongDev.StatusCode);

        var token = await api.IssueTicketAsync(owner);
        var id = await api.UploadAsync(owner, token);
        using var privateFile = await api.SendAsync(HttpMethod.Get, $"files/{id}");
        Assert.Equal(HttpStatusCode.Unauthorized, privateFile.StatusCode);
        using var privateList = await api.SendAsync(HttpMethod.Get, $"registered?userUuid={owner}");
        Assert.Equal(HttpStatusCode.Unauthorized, privateList.StatusCode);
        using var privateDecision = await api.SendAsync(HttpMethod.Post, $"decisions/{id}");
        Assert.Equal(HttpStatusCode.Unauthorized, privateDecision.StatusCode);
        Assert.Equal(BuilderSchematicStatus.Pending, (await api.GetStatusAsync(id, owner)).Status);
    }

    [Theory]
    [InlineData(null, true)]
    [InlineData("relative", true)]
    [InlineData("web-root", true)]
    [InlineData("valid", false)]
    public async Task MissingOrUnsafeConfigurationDisablesAllRoutes(string? rootSetting, bool configureDevKey)
    {
        using var directory = new TestDirectory();
        await using var api = await TestApi.StartAsync(directory, rootSetting: rootSetting, configureDevKey: configureDevKey);

        using var dev = await api.SendAsync(HttpMethod.Get, "pending", devKey: "dev-secret");
        Assert.Equal(HttpStatusCode.ServiceUnavailable, dev.StatusCode);
        using var web = await api.SendAsync(HttpMethod.Get, $"uploads/{Guid.NewGuid()}?userUuid={Guid.NewGuid()}");
        Assert.Equal(HttpStatusCode.ServiceUnavailable, web.StatusCode);
    }

    [Fact]
    public async Task TicketReplacementOwnershipAndPreEnqueueValidationPreserveTheCurrentTicket()
    {
        using var directory = new TestDirectory();
        await using var api = await TestApi.StartAsync(directory);
        var owner = Guid.NewGuid();
        var oldToken = await api.IssueTicketAsync(owner);
        var token = await api.IssueTicketAsync(owner);
        Assert.NotEqual(oldToken, token);
        Assert.Matches("^[A-Za-z0-9_-]{43}$", token);
        using var old = await api.PostUploadAsync(owner, oldToken);
        Assert.Equal(HttpStatusCode.Forbidden, old.StatusCode);
        using var wrongOwner = await api.PostUploadAsync(Guid.NewGuid(), token);
        Assert.Equal(HttpStatusCode.Forbidden, wrongOwner.StatusCode);
        using var badName = await api.PostUploadAsync(owner, token, "../escape.schem");
        Assert.Equal(HttpStatusCode.BadRequest, badName.StatusCode);
        using var wrongExtension = await api.PostUploadAsync(owner, token, "build.zip");
        Assert.Equal(HttpStatusCode.BadRequest, wrongExtension.StatusCode);
        using var badGzip = await api.PostUploadAsync(owner, token, content: [0, 1, 2]);
        Assert.Equal(HttpStatusCode.BadRequest, badGzip.StatusCode);
        using var tooBig = await api.PostUploadAsync(owner, token, content: new byte[1024 * 1024 + 1]);
        Assert.Equal(HttpStatusCode.RequestEntityTooLarge, tooBig.StatusCode);

        var id = await api.UploadAsync(owner, token, "建築.SCHEM");
        using var repeat = await api.PostUploadAsync(owner, token);
        Assert.Equal(HttpStatusCode.Forbidden, repeat.StatusCode);
        var status = await api.GetStatusAsync(id, owner);
        Assert.Equal("建築.SCHEM", status.Name);
        Assert.Equal(BuilderSchematicStatus.Pending, status.Status);
        Assert.Null(status.Error);
        using var wrongOwnerStatus = await api.SendAsync(HttpMethod.Get, $"uploads/{id}?userUuid={Guid.NewGuid()}");
        Assert.Equal(HttpStatusCode.NotFound, wrongOwnerStatus.StatusCode);
    }

    [Fact]
    public async Task SameTicketEnqueuesExactlyOnceAcrossConcurrentApiInstances()
    {
        using var directory = new TestDirectory();
        await using var first = await TestApi.StartAsync(directory);
        await using var second = await TestApi.StartAsync(directory);
        var owner = Guid.NewGuid();
        var token = await first.IssueTicketAsync(owner);
        var responses = await Task.WhenAll(Enumerable.Range(0, 8)
            .Select(index => (index % 2 == 0 ? first : second).PostUploadAsync(owner, token)));
        try
        {
            Assert.Single(responses, response => response.StatusCode == HttpStatusCode.Accepted);
            Assert.Equal(7, responses.Count(response => response.StatusCode == HttpStatusCode.Forbidden));
            Assert.Single(await second.GetDevListAsync("pending"));
        }
        finally
        {
            foreach (var response in responses)
                response.Dispose();
        }
    }

    [Fact]
    public async Task RegisteredFilesRequireTheOwnerAndSurviveRestart()
    {
        using var directory = new TestDirectory();
        var owner = Guid.NewGuid();
        var other = Guid.NewGuid();
        Guid id;
        string secondTicket;
        await using (var api = await TestApi.StartAsync(directory))
        {
            id = await api.UploadAsync(owner, await api.IssueTicketAsync(owner));
            secondTicket = await api.IssueTicketAsync(other);
            Assert.Equal(id, Assert.Single(await api.GetDevListAsync("pending")).Id);
            Assert.Empty(await api.GetDevListAsync($"registered?userUuid={owner}"));
            using var pendingFile = await api.SendAsync(HttpMethod.Get, $"files/{id}", devKey: "dev-secret");
            Assert.Equal(HttpStatusCode.OK, pendingFile.StatusCode);
            Assert.Equal(GzipHeader, await pendingFile.Content.ReadAsByteArrayAsync());
            using var prematureFile = await api.SendAsync(HttpMethod.Get, $"files/{id}?userUuid={owner}", devKey: "dev-secret");
            Assert.Equal(HttpStatusCode.NotFound, prematureFile.StatusCode);
        }

        await using (var api = await TestApi.StartAsync(directory))
        {
            Assert.Equal(BuilderSchematicStatus.Pending, (await api.GetStatusAsync(id, owner)).Status);
            using var decision = await api.DecideAsync(id, true, null);
            using var sameDecision = await api.DecideAsync(id, true, null);
            using var oppositeDecision = await api.DecideAsync(id, false, null);
            Assert.Equal(HttpStatusCode.NoContent, decision.StatusCode);
            Assert.Equal(HttpStatusCode.NoContent, sameDecision.StatusCode);
            Assert.Equal(HttpStatusCode.Conflict, oppositeDecision.StatusCode);
            await api.UploadAsync(other, secondTicket);
        }

        await using (var api = await TestApi.StartAsync(directory))
        {
            Assert.Equal(BuilderSchematicStatus.Registered, (await api.GetStatusAsync(id, owner)).Status);
            Assert.Equal(id, Assert.Single(await api.GetDevListAsync($"registered?userUuid={owner}")).Id);
            Assert.Empty(await api.GetDevListAsync($"registered?userUuid={other}"));
            using var noOwner = await api.SendAsync(HttpMethod.Get, $"files/{id}", devKey: "dev-secret");
            Assert.Equal(HttpStatusCode.NotFound, noOwner.StatusCode);
            using var wrongOwner = await api.SendAsync(HttpMethod.Get, $"files/{id}?userUuid={other}", devKey: "dev-secret");
            Assert.Equal(HttpStatusCode.NotFound, wrongOwner.StatusCode);
            using var file = await api.SendAsync(HttpMethod.Get, $"files/{id}?userUuid={owner}", devKey: "dev-secret");
            Assert.Equal(HttpStatusCode.OK, file.StatusCode);
            Assert.Equal(GzipHeader, await file.Content.ReadAsByteArrayAsync());
        }
    }

    [Fact]
    public async Task RejectionReleasesPendingQuotaAndHidesUnrecognizedErrorText()
    {
        using var directory = new TestDirectory();
        await using var api = await TestApi.StartAsync(directory);
        var owner = Guid.NewGuid();
        var first = await api.UploadAsync(owner, await api.IssueTicketAsync(owner));
        for (var i = 0; i < 3; i++)
            await api.UploadAsync(owner, await api.IssueTicketAsync(owner));

        var token = await api.IssueTicketAsync(owner);
        using var quota = await api.PostUploadAsync(owner, token);
        Assert.Equal(HttpStatusCode.TooManyRequests, quota.StatusCode);
        using var decision = await api.DecideAsync(first, false, "raw parser diagnostic <script>");
        Assert.Equal(HttpStatusCode.NoContent, decision.StatusCode);
        var rejected = await api.GetStatusAsync(first, owner);
        Assert.Equal(BuilderSchematicStatus.Rejected, rejected.Status);
        Assert.Equal("invalid_schematic", rejected.Error);
        Assert.DoesNotContain(await api.GetDevListAsync("pending"), entry => entry.Id == first);
        using var rejectedFile = await api.SendAsync(HttpMethod.Get, $"files/{first}", devKey: "dev-secret");
        Assert.Equal(HttpStatusCode.NotFound, rejectedFile.StatusCode);

        var retry = await api.UploadAsync(owner, token);
        using var secondDecision = await api.DecideAsync(retry, false, "incompatible_schematic");
        Assert.Equal(HttpStatusCode.NoContent, secondDecision.StatusCode);
        Assert.Equal("incompatible_schematic", (await api.GetStatusAsync(retry, owner)).Error);
    }

    [Fact]
    public async Task ExpiredTicketsCannotEnqueue()
    {
        using var directory = new TestDirectory();
        var time = new TestClock();
        await using var api = await TestApi.StartAsync(directory, time);
        var owner = Guid.NewGuid();
        var expired = await api.IssueTicketAsync(owner);
        time.UtcNow += TimeSpan.FromMinutes(5);
        using var response = await api.PostUploadAsync(owner, expired);
        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
        Assert.Empty(await api.GetDevListAsync("pending"));
        await api.UploadAsync(owner, await api.IssueTicketAsync(owner));
    }

    private sealed class TestClock : TimeProvider
    {
        public DateTimeOffset UtcNow { get; set; } = DateTimeOffset.UtcNow;
        public override DateTimeOffset GetUtcNow() => UtcNow;
    }

    private sealed class TestDirectory : IDisposable
    {
        public string Path { get; } = Directory.CreateTempSubdirectory("astral-schem-test-").FullName;
        public void Dispose() => Directory.Delete(Path, recursive: true);
    }

    private sealed class TestApi(WebApplication app, HttpClient client) : IAsyncDisposable
    {
        public static async Task<TestApi> StartAsync(
            TestDirectory directory, TimeProvider? time = null, string? rootSetting = "valid", bool configureDevKey = true)
        {
            var webRoot = Directory.CreateDirectory(Path.Combine(directory.Path, "wwwroot")).FullName;
            var rootPath = rootSetting switch
            {
                "valid" => Path.Combine(directory.Path, "storage"),
                "web-root" => Path.Combine(webRoot, "storage"),
                _ => rootSetting
            };
            var builder = WebApplication.CreateBuilder(new WebApplicationOptions
            {
                ContentRootPath = directory.Path,
                WebRootPath = webRoot,
                ApplicationName = typeof(BuilderSchematicsController).Assembly.GetName().Name
            });
            builder.Logging.ClearProviders();
            builder.WebHost.UseUrls("http://127.0.0.1:0");
            builder.Configuration.AddInMemoryCollection(new Dictionary<string, string?>
            {
                ["ApiKey:Key"] = "common-secret",
                ["BuilderSchematics:DevKey"] = configureDevKey ? "dev-secret" : null,
                ["BuilderSchematics:RootPath"] = rootPath
            });
            builder.Services.AddSingleton(time ?? TimeProvider.System);
            builder.Services.AddSingleton<IBuilderSchematicRepository, BuilderSchematicRepository>();
            builder.Services.AddAuthentication(ApiKeyAuthenticationHandler.SchemeName)
                .AddScheme<AuthenticationSchemeOptions, ApiKeyAuthenticationHandler>(ApiKeyAuthenticationHandler.SchemeName, _ => { });
            builder.Services.AddAuthorization();
            builder.Services.AddControllers();
            var app = builder.Build();
            app.UseAuthentication();
            app.UseAuthorization();
            app.MapControllers().RequireAuthorization();
            await app.StartAsync();
            return new TestApi(app, new HttpClient
            {
                BaseAddress = new Uri($"{app.Urls.Single()}/api/builder-schematics/"),
                Timeout = TimeSpan.FromSeconds(15)
            });
        }

        public async Task<HttpResponseMessage> SendAsync(HttpMethod method, string path, HttpContent? content = null,
            bool commonKey = true, string? devKey = null)
        {
            using var request = new HttpRequestMessage(method, path) { Content = content };
            if (commonKey) request.Headers.Add(ApiKeyAuthenticationHandler.HeaderName, "common-secret");
            if (devKey is not null) request.Headers.Add(BuilderSchematicAccessAttribute.DevKeyHeader, devKey);
            return await client.SendAsync(request);
        }

        public async Task<string> IssueTicketAsync(Guid owner)
        {
            using var response = await SendAsync(HttpMethod.Post, "tickets",
                JsonContent.Create(new BuilderSchematicTicketRequest(owner)), devKey: "dev-secret");
            Assert.Equal(HttpStatusCode.Created, response.StatusCode);
            Assert.True(response.Headers.CacheControl?.NoStore);
            var ticket = await response.Content.ReadFromJsonAsync<BuilderSchematicTicketResponse>();
            return Assert.IsType<BuilderSchematicTicketResponse>(ticket).Token;
        }

        public Task<HttpResponseMessage> PostUploadAsync(Guid owner, string token, string name = "build.schem", byte[]? content = null)
        {
            var form = new MultipartFormDataContent();
            form.Add(new StringContent(owner.ToString()), "userUuid");
            form.Add(new StringContent(token), "token");
            form.Add(new ByteArrayContent(content ?? GzipHeader), "file", name);
            return SendAsync(HttpMethod.Post, "uploads", form);
        }

        public async Task<Guid> UploadAsync(Guid owner, string token, string name = "build.schem")
        {
            using var response = await PostUploadAsync(owner, token, name);
            Assert.Equal(HttpStatusCode.Accepted, response.StatusCode);
            var receipt = await response.Content.ReadFromJsonAsync<BuilderSchematicUploadReceipt>();
            Assert.NotNull(receipt);
            Assert.Equal(BuilderSchematicStatus.Pending, receipt.Status);
            return receipt.Id;
        }

        public async Task<BuilderSchematicResponse> GetStatusAsync(Guid id, Guid owner)
        {
            using var response = await SendAsync(HttpMethod.Get, $"uploads/{id}?userUuid={owner}");
            Assert.Equal(HttpStatusCode.OK, response.StatusCode);
            return Assert.IsType<BuilderSchematicResponse>(await response.Content.ReadFromJsonAsync<BuilderSchematicResponse>());
        }

        public async Task<BuilderSchematicResponse[]> GetDevListAsync(string path)
        {
            using var response = await SendAsync(HttpMethod.Get, path, devKey: "dev-secret");
            Assert.Equal(HttpStatusCode.OK, response.StatusCode);
            return Assert.IsType<BuilderSchematicResponse[]>(await response.Content.ReadFromJsonAsync<BuilderSchematicResponse[]>());
        }

        public Task<HttpResponseMessage> DecideAsync(Guid id, bool accepted, string? error) =>
            SendAsync(HttpMethod.Post, $"decisions/{id}",
                JsonContent.Create(new BuilderSchematicDecisionRequest(accepted, error)), devKey: "dev-secret");

        public async ValueTask DisposeAsync()
        {
            client.Dispose();
            await app.DisposeAsync();
        }
    }
}
