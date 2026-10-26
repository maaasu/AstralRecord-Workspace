using System.Net;
using System.Text;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Options;
using SkillTreeEditor.Server.Options;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class WorldPlacementProxyTests
{
    [Fact]
    public async Task WriteUsesServerOnlyKeysAndPassesConflictWithoutChangingPayload()
    {
        var handler = new CaptureHandler();
        var proxy = new WorldPlacementProxyService(new HttpClient(handler), Microsoft.Extensions.Options.Options.Create(new EditorOptions
        {
            WorldPlacementApiBaseUrl = "http://127.0.0.1:5000",
            WorldPlacementApiKey = "read-secret",
            WorldPlacementWriteKey = "write-secret"
        }));
        var context = new DefaultHttpContext();
        context.RequestServices = new ServiceCollection().AddLogging().AddProblemDetails().BuildServiceProvider();
        context.Request.Body = new MemoryStream(Encoding.UTF8.GetBytes("{\"expectedRevision\":\"old\",\"placements\":[]}"));
        context.Response.Body = new MemoryStream();

        var result = await proxy.ForwardAsync(context, true, CancellationToken.None);
        await result.ExecuteAsync(context);

        Assert.Equal(StatusCodes.Status409Conflict, context.Response.StatusCode);
        Assert.Equal(HttpMethod.Put, handler.Method);
        Assert.Equal("/api/world-placements/dev", handler.Path);
        Assert.Equal("read-secret", handler.ReadKey);
        Assert.Equal("write-secret", handler.WriteKey);
        Assert.Contains("old", handler.Body);
        context.Response.Body.Position = 0;
        Assert.Equal("{\"title\":\"stale\"}", await new StreamReader(context.Response.Body).ReadToEndAsync());
    }

    [Fact]
    public async Task MissingWriteKeyRejectsBeforeCallingApi()
    {
        var handler = new CaptureHandler();
        var proxy = new WorldPlacementProxyService(new HttpClient(handler), Microsoft.Extensions.Options.Options.Create(new EditorOptions
        {
            WorldPlacementApiBaseUrl = "http://127.0.0.1:5000", WorldPlacementApiKey = "read-secret"
        }));
        var context = new DefaultHttpContext();
        context.RequestServices = new ServiceCollection().AddLogging().AddProblemDetails().BuildServiceProvider();
        var result = await proxy.ForwardAsync(context, true, CancellationToken.None);
        Assert.Null(handler.Method);
        context.Response.Body = new MemoryStream();
        await result.ExecuteAsync(context);
        Assert.Equal(StatusCodes.Status503ServiceUnavailable, context.Response.StatusCode);
    }

    private sealed class CaptureHandler : HttpMessageHandler
    {
        public HttpMethod? Method { get; private set; }
        public string? Path { get; private set; }
        public string? ReadKey { get; private set; }
        public string? WriteKey { get; private set; }
        public string? Body { get; private set; }
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Method = request.Method;
            Path = request.RequestUri?.AbsolutePath;
            ReadKey = request.Headers.GetValues("X-API-Key").Single();
            WriteKey = request.Headers.GetValues("X-World-Placement-Key").Single();
            Body = await request.Content!.ReadAsStringAsync(cancellationToken);
            return new HttpResponseMessage(HttpStatusCode.Conflict) { Content = new StringContent("{\"title\":\"stale\"}", Encoding.UTF8, "application/json") };
        }
    }
}
