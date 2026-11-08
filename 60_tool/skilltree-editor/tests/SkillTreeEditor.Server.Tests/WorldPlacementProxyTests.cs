using System.Net;
using System.Text;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using SkillTreeEditor.Server.Options;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class WorldPlacementProxyTests
{
    private const string PlacementId = "waystone:demo";
    private const string ExistingPlacement = """
        {"placementId":"waystone:demo","kind":"waystone","worldId":"eriva","worldName":"worlds/eriva","waystone":{"id":"demo","name":"元の名前","createdAt":null,"createdBy":"operator"}}
        """;
    private static string EditBody(string revision = "old", string placement = ExistingPlacement) =>
        $$"""{"expectedRevision":"{{revision}}","placement":{{placement}}}""";

    [Fact]
    public async Task WriteUsesServerOnlyKeysAndPassesConflictWithoutChangingPayload()
    {
        var handler = new CaptureHandler();
        const string body = """{"expectedRevision":"old","placements":[]}""";
        var context = CreateContext(body);
        var result = await CreateProxy(handler).ForwardAsync(context, true, CancellationToken.None);
        await result.ExecuteAsync(context);

        Assert.Equal(StatusCodes.Status409Conflict, context.Response.StatusCode);
        var forwarded = Assert.Single(handler.Requests);
        Assert.Equal(new CapturedRequest(HttpMethod.Put, "/api/world-placements/dev", "read-secret", "write-secret", body), forwarded);
        context.Response.Body.Position = 0;
        Assert.Equal("{\"title\":\"stale\"}", await new StreamReader(context.Response.Body).ReadToEndAsync());
    }

    [Theory]
    [InlineData(HttpStatusCode.OK)]
    [InlineData(HttpStatusCode.Conflict)]
    public async Task SinglePlacementWriteTargetsOnlyTheRequestedDevPlacementAndPreservesRevision(HttpStatusCode putStatus)
    {
        var handler = new CaptureHandler { PutStatus = putStatus };
        var body = EditBody(placement: ExistingPlacement.Replace("元の名前", "新しい名前", StringComparison.Ordinal));
        var context = CreateContext(body);
        context.Request.Body = new NonSeekableBody(Encoding.UTF8.GetBytes(body));
        var result = await CreateProxy(handler).ForwardAsync(context, true, CancellationToken.None, PlacementId);
        await result.ExecuteAsync(context);

        Assert.Equal((int)putStatus, context.Response.StatusCode);
        Assert.Collection(handler.Requests,
            lookup => Assert.Equal(new CapturedRequest(HttpMethod.Get, "/api/world-placements/dev", "read-secret", null, null), lookup),
            update => Assert.Equal(new CapturedRequest(HttpMethod.Put, "/api/world-placements/dev/waystone:demo", "read-secret", "write-secret", body), update));
    }

    [Fact]
    public async Task SinglePlacementConflictReturnsWithoutForwardingWrite()
    {
        var handler = new CaptureHandler { LookupRevision = "new" };
        var context = CreateContext(EditBody());
        var result = await CreateProxy(handler).ForwardAsync(context, true, CancellationToken.None, PlacementId);
        await result.ExecuteAsync(context);

        Assert.Equal(StatusCodes.Status409Conflict, context.Response.StatusCode);
        Assert.Equal(HttpMethod.Get, Assert.Single(handler.Requests).Method);
    }

    [Fact]
    public async Task MissingPlacementCannotBeCreatedThroughEditor()
    {
        var handler = new CaptureHandler { LookupPlacement = "" };
        var context = CreateContext(EditBody());
        var result = await CreateProxy(handler).ForwardAsync(context, true, CancellationToken.None, PlacementId);
        await result.ExecuteAsync(context);

        Assert.Equal(StatusCodes.Status404NotFound, context.Response.StatusCode);
        Assert.Equal(HttpMethod.Get, Assert.Single(handler.Requests).Method);
    }

    [Fact]
    public async Task ExistingNonWaystoneCannotBeReplacedThroughEditor()
    {
        var handler = new CaptureHandler { LookupPlacement = ExistingPlacement.Replace("\"waystone\"", "\"npc\"", StringComparison.Ordinal) };
        var context = CreateContext(EditBody());
        var result = await CreateProxy(handler).ForwardAsync(context, true, CancellationToken.None, PlacementId);
        await result.ExecuteAsync(context);

        Assert.Equal(StatusCodes.Status400BadRequest, context.Response.StatusCode);
        Assert.Equal(HttpMethod.Get, Assert.Single(handler.Requests).Method);
    }

    [Theory]
    [InlineData("\"id\":\"demo\"", "\"id\":\"changed\"")]
    [InlineData("\"worldId\":\"eriva\"", "\"worldId\":\"changed\"")]
    [InlineData("\"worldName\":\"worlds/eriva\"", "\"worldName\":\"worlds/changed\"")]
    [InlineData("\"createdAt\":null", "\"createdAt\":\"2026-11-08T00:00:00Z\"")]
    [InlineData("\"createdBy\":\"operator\"", "\"createdBy\":\"changed\"")]
    public async Task ProtectedFieldsCannotBeChangedThroughEditor(string original, string changed)
    {
        var handler = new CaptureHandler();
        var context = CreateContext(EditBody(placement: ExistingPlacement.Replace(original, changed, StringComparison.Ordinal)));
        var result = await CreateProxy(handler).ForwardAsync(context, true, CancellationToken.None, PlacementId);
        await result.ExecuteAsync(context);

        Assert.Equal(StatusCodes.Status400BadRequest, context.Response.StatusCode);
        Assert.Equal(HttpMethod.Get, Assert.Single(handler.Requests).Method);
    }

    [Theory]
    [InlineData("\"placementId\":\"waystone:demo\"", "\"placementId\":\"other\"")]
    [InlineData("\"id\":\"demo\"", "\"id\":\"demo\",\"Id\":\"changed\"")]
    [InlineData("\"kind\":\"waystone\"", "\"kind\":\"npc\"")]
    public async Task InvalidSinglePlacementBodyIsRejectedBeforeForwarding(string original, string changed)
    {
        var handler = new CaptureHandler();
        var context = CreateContext(EditBody(placement: ExistingPlacement.Replace(original, changed, StringComparison.Ordinal)));
        var result = await CreateProxy(handler).ForwardAsync(context, true, CancellationToken.None, PlacementId);
        await result.ExecuteAsync(context);

        Assert.Equal(StatusCodes.Status400BadRequest, context.Response.StatusCode);
        Assert.Empty(handler.Requests);
    }

    [Theory]
    [InlineData("..")]
    [InlineData("../channels")]
    [InlineData("waystone%2fother")]
    public async Task InvalidSinglePlacementRouteIsRejectedBeforeForwarding(string placementId)
    {
        var handler = new CaptureHandler();
        var context = CreateContext();
        var result = await CreateProxy(handler).ForwardAsync(context, true, CancellationToken.None, placementId);
        await result.ExecuteAsync(context);

        Assert.Equal(StatusCodes.Status400BadRequest, context.Response.StatusCode);
        Assert.Empty(handler.Requests);
    }

    [Fact]
    public async Task ReadUsesOnlyReadKey()
    {
        var handler = new CaptureHandler();
        var context = CreateContext();
        var result = await CreateProxy(handler, writeKey: null).ForwardAsync(context, false, CancellationToken.None);
        await result.ExecuteAsync(context);

        Assert.Equal(StatusCodes.Status200OK, context.Response.StatusCode);
        Assert.Equal(new CapturedRequest(HttpMethod.Get, "/api/world-placements/dev", "read-secret", null, null), Assert.Single(handler.Requests));
    }

    [Fact]
    public async Task MissingWriteKeyRejectsBeforeCallingApi()
    {
        var handler = new CaptureHandler();
        var context = CreateContext();
        var result = await CreateProxy(handler, writeKey: null).ForwardAsync(context, true, CancellationToken.None);
        await result.ExecuteAsync(context);
        Assert.Equal(StatusCodes.Status503ServiceUnavailable, context.Response.StatusCode);
        Assert.Empty(handler.Requests);
    }

    private static WorldPlacementProxyService CreateProxy(CaptureHandler handler, string? writeKey = "write-secret") =>
        new(new HttpClient(handler), Microsoft.Extensions.Options.Options.Create(new EditorOptions
        {
            WorldPlacementApiBaseUrl = "http://127.0.0.1:5000", WorldPlacementApiKey = "read-secret", WorldPlacementWriteKey = writeKey
        }));

    private static DefaultHttpContext CreateContext(string body = "")
    {
        var context = new DefaultHttpContext();
        context.RequestServices = new ServiceCollection().AddLogging().AddProblemDetails().BuildServiceProvider();
        context.Request.Body = new MemoryStream(Encoding.UTF8.GetBytes(body));
        context.Response.Body = new MemoryStream();
        return context;
    }

    private sealed record CapturedRequest(HttpMethod Method, string Path, string ReadKey, string? WriteKey, string? Body);

    private sealed class NonSeekableBody(byte[] bytes) : MemoryStream(bytes)
    {
        public override bool CanSeek => false;
    }

    private sealed class CaptureHandler : HttpMessageHandler
    {
        public List<CapturedRequest> Requests { get; } = [];
        public string LookupRevision { get; init; } = "old";
        public string LookupPlacement { get; init; } = ExistingPlacement;
        public HttpStatusCode PutStatus { get; init; } = HttpStatusCode.Conflict;

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Requests.Add(new CapturedRequest(request.Method, Uri.UnescapeDataString(request.RequestUri!.AbsolutePath),
                request.Headers.GetValues("X-API-Key").Single(),
                request.Headers.TryGetValues("X-World-Placement-Key", out var writeKey) ? writeKey.Single() : null,
                request.Content is null ? null : await request.Content.ReadAsStringAsync(cancellationToken)));
            var snapshot = $$"""{"schemaVersion":1,"revision":"{{LookupRevision}}","placements":[{{LookupPlacement}}]}""";
            return request.Method == HttpMethod.Get || PutStatus == HttpStatusCode.OK
                ? new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(snapshot, Encoding.UTF8, "application/json") }
                : new HttpResponseMessage(PutStatus) { Content = new StringContent("""{"title":"stale"}""", Encoding.UTF8, "application/json") };
        }
    }
}
