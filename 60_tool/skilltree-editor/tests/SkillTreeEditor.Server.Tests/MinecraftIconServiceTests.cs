using System.Collections.Concurrent;
using System.Net;
using System.Net.Http.Headers;
using SkillTreeEditor.Server.Options;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class MinecraftIconServiceTests
{
    private static readonly byte[] PngBytes = [137, 80, 78, 71, 13, 10, 26, 10];

    [Theory]
    [InlineData("NETHER_STAR", "nether_star")]
    [InlineData("minecraft:Diamond_Sword", "diamond_sword")]
    [InlineData("  BOOK  ", "book")]
    public void NormalizeMaterialProducesMcIconsId(string source, string expected)
    {
        Assert.Equal(expected, MinecraftIconService.NormalizeMaterial(source));
    }

    [Theory]
    [InlineData("")]
    [InlineData("minecraft:../stone")]
    [InlineData("stone slab")]
    public void NormalizeMaterialRejectsUnsafeIds(string source)
    {
        Assert.Throws<ArgumentException>(() => MinecraftIconService.NormalizeMaterial(source));
    }

    [Fact]
    public async Task GetIconPathDownloadsOnceAndThenUsesWorkspaceCache()
    {
        var workspace = Path.Combine(Path.GetTempPath(), $"skilltree-icon-test-{Guid.NewGuid():N}");
        var handler = new StubHandler();
        using var client = new HttpClient(handler) { BaseAddress = new Uri("https://mc-icons.example/") };
        var service = new MinecraftIconService(client, new WorkspacePaths(workspace));

        try
        {
            var first = await service.GetIconPathAsync("NETHER_STAR", refresh: false, CancellationToken.None);
            var second = await service.GetIconPathAsync("minecraft:nether_star", refresh: false, CancellationToken.None);

            Assert.Equal(first, second);
            Assert.NotNull(first);
            Assert.True(File.Exists(first));
            Assert.Equal([137, 80, 78, 71], await File.ReadAllBytesAsync(first));
            Assert.Equal(1, handler.RequestCount);
            Assert.Equal("download/nether_star/thumb", handler.LastRequestPath);
        }
        finally
        {
            if (Directory.Exists(workspace))
                Directory.Delete(workspace, recursive: true);
        }
    }

    [Theory]
    [InlineData(HttpStatusCode.NotFound)]
    [InlineData(HttpStatusCode.BadGateway)]
    [InlineData(HttpStatusCode.RequestTimeout)]
    public async Task PrimaryHttpFailureUsesUppercaseFallbackAndCachesResult(HttpStatusCode status)
    {
        using var context = new IconTestContext((request, _) => Task.FromResult(
            request.RequestUri!.Host == "mc-icons.example" ? new HttpResponseMessage(status) : PngResponse()));

        var first = await context.Service.GetIconPathAsync("minecraft:porkchop", refresh: false, CancellationToken.None);
        var second = await context.Service.GetIconPathAsync("PORKCHOP", refresh: false, CancellationToken.None);

        Assert.Equal(context.CachePath, first);
        Assert.Equal(first, second);
        Assert.Equal(PngBytes, await File.ReadAllBytesAsync(first!));
        Assert.Equal(["https://mc-icons.example/download/porkchop/thumb", "https://native-icons.example/1.21.11/PORKCHOP.png"],
            context.Handler.Requests.Select(uri => uri.AbsoluteUri));
    }

    [Theory]
    [InlineData("network")]
    [InlineData("timeout")]
    [InlineData("content-type")]
    public async Task PrimarySourceExceptionsUseFallback(string failure)
    {
        using var context = new IconTestContext((request, _) => request.RequestUri!.Host != "mc-icons.example"
            ? Task.FromResult(PngResponse())
            : failure switch
            {
                "network" => Task.FromException<HttpResponseMessage>(new HttpRequestException("source unavailable")),
                "timeout" => Task.FromException<HttpResponseMessage>(new TaskCanceledException("source timeout")),
                _ => Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("not an image") })
            });

        var result = await context.Service.GetIconPathAsync("PORKCHOP", refresh: false, CancellationToken.None);

        Assert.Equal(context.CachePath, result);
        Assert.Equal(PngBytes, await File.ReadAllBytesAsync(result!));
        Assert.Equal(2, context.Handler.Requests.Count);
    }

    [Fact]
    public async Task SuccessfulPrimaryDoesNotContactFallback()
    {
        using var context = new IconTestContext((_, _) => Task.FromResult(PngResponse()));

        await context.Service.GetIconPathAsync("PORKCHOP", refresh: false, CancellationToken.None);

        Assert.Equal("mc-icons.example", Assert.Single(context.Handler.Requests).Host);
    }

    [Fact]
    public async Task ExistingCacheDoesNotContactEitherSource()
    {
        using var context = new IconTestContext((_, _) => throw new InvalidOperationException("cache should avoid HTTP"));
        Directory.CreateDirectory(context.Paths.MinecraftIconCache);
        await File.WriteAllBytesAsync(context.CachePath, PngBytes);

        var result = await context.Service.GetIconPathAsync("minecraft:PORKCHOP", refresh: false, CancellationToken.None);

        Assert.Equal(context.CachePath, result);
        Assert.Empty(context.Handler.Requests);
        Assert.Equal(PngBytes, await File.ReadAllBytesAsync(result!));
    }

    [Fact]
    public async Task ConstructorWithoutOptionsKeepsSingleSourceNotFoundBehavior()
    {
        using var context = new IconTestContext((_, _) => Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotFound)), withFallback: false);

        Assert.Null(await context.Service.GetIconPathAsync("PORKCHOP", refresh: false, CancellationToken.None));
        Assert.Single(context.Handler.Requests);
        Assert.False(File.Exists(context.CachePath));
    }

    [Fact]
    public async Task BothSourcesNotFoundReturnNullWithoutCaching()
    {
        using var context = new IconTestContext((_, _) => Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotFound)));

        Assert.Null(await context.Service.GetIconPathAsync("PORKCHOP", refresh: false, CancellationToken.None));
        Assert.Equal(2, context.Handler.Requests.Count);
        Assert.False(Directory.Exists(context.Paths.MinecraftIconCache));
    }

    [Fact]
    public async Task InvalidFallbackContentTypeDoesNotReplaceExistingCache()
    {
        using var context = new IconTestContext((request, _) => Task.FromResult(request.RequestUri!.Host == "mc-icons.example"
            ? new HttpResponseMessage(HttpStatusCode.BadGateway)
            : new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("not an image") }));
        Directory.CreateDirectory(context.Paths.MinecraftIconCache);
        await File.WriteAllBytesAsync(context.CachePath, PngBytes);

        await Assert.ThrowsAsync<InvalidDataException>(() => context.Service.GetIconPathAsync("PORKCHOP", refresh: true, CancellationToken.None));

        Assert.Equal(PngBytes, await File.ReadAllBytesAsync(context.CachePath));
        Assert.Equal(context.CachePath, Assert.Single(Directory.GetFiles(context.Paths.MinecraftIconCache)));
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task OversizedFallbackCannotBeCachedAndRemovesTemporaryFiles(bool knownLength)
    {
        using var context = new IconTestContext((request, _) => Task.FromResult(request.RequestUri!.Host == "mc-icons.example"
            ? new HttpResponseMessage(HttpStatusCode.BadGateway)
            : OversizedResponse(knownLength)));

        await Assert.ThrowsAsync<InvalidDataException>(() => context.Service.GetIconPathAsync("PORKCHOP", refresh: false, CancellationToken.None));

        Assert.False(File.Exists(context.CachePath));
        if (Directory.Exists(context.Paths.MinecraftIconCache))
            Assert.Empty(Directory.GetFiles(context.Paths.MinecraftIconCache));
    }

    [Fact]
    public async Task OversizedPrimaryStreamUsesFallbackAndRemovesTemporaryFiles()
    {
        using var context = new IconTestContext((request, _) => Task.FromResult(request.RequestUri!.Host == "mc-icons.example"
            ? OversizedResponse(knownLength: false)
            : PngResponse()));

        var result = await context.Service.GetIconPathAsync("PORKCHOP", refresh: false, CancellationToken.None);

        Assert.Equal(PngBytes, await File.ReadAllBytesAsync(result!));
        Assert.Equal(context.CachePath, Assert.Single(Directory.GetFiles(context.Paths.MinecraftIconCache)));
    }

    [Fact]
    public async Task ConcurrentRequestsShareOnePrimaryAndFallbackDownload()
    {
        using var context = new IconTestContext(async (request, token) =>
        {
            if (request.RequestUri!.Host == "mc-icons.example")
                return new HttpResponseMessage(HttpStatusCode.BadGateway);
            await Task.Delay(20, token);
            return PngResponse();
        });

        var results = await Task.WhenAll(Enumerable.Range(0, 8).Select(_ =>
            context.Service.GetIconPathAsync("PORKCHOP", refresh: false, CancellationToken.None)));

        Assert.All(results, result => Assert.Equal(context.CachePath, result));
        Assert.Equal(2, context.Handler.Requests.Count);
        Assert.Equal(context.CachePath, Assert.Single(Directory.GetFiles(context.Paths.MinecraftIconCache)));
    }

    [Fact]
    public async Task CallerCancellationDoesNotContactFallback()
    {
        using var cancellation = new CancellationTokenSource();
        using var context = new IconTestContext((_, token) =>
        {
            cancellation.Cancel();
            return Task.FromCanceled<HttpResponseMessage>(token);
        });

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() =>
            context.Service.GetIconPathAsync("PORKCHOP", refresh: false, cancellation.Token));

        Assert.Equal("mc-icons.example", Assert.Single(context.Handler.Requests).Host);
        Assert.False(File.Exists(context.CachePath));
    }

    private static HttpResponseMessage PngResponse()
    {
        var content = new ByteArrayContent(PngBytes);
        content.Headers.ContentType = new MediaTypeHeaderValue("image/png");
        return new HttpResponseMessage(HttpStatusCode.OK) { Content = content };
    }

    private static HttpResponseMessage OversizedResponse(bool knownLength)
    {
        var bytes = new byte[2 * 1024 * 1024 + 1];
        HttpContent content = knownLength ? new ByteArrayContent(bytes) : new UnknownLengthContent(bytes);
        content.Headers.ContentType = new MediaTypeHeaderValue("image/png");
        return new HttpResponseMessage(HttpStatusCode.OK) { Content = content };
    }

    private sealed class UnknownLengthContent(byte[] bytes) : HttpContent
    {
        protected override Task SerializeToStreamAsync(Stream stream, TransportContext? context) => stream.WriteAsync(bytes).AsTask();
        protected override Task<Stream> CreateContentReadStreamAsync() => Task.FromResult<Stream>(new MemoryStream(bytes, writable: false));
        protected override bool TryComputeLength(out long length) { length = 0; return false; }
    }

    private sealed class IconTestContext : IDisposable
    {
        private readonly string _workspace = Path.Combine(Path.GetTempPath(), $"skilltree-icon-test-{Guid.NewGuid():N}");
        private readonly HttpClient _client;
        public RoutingHandler Handler { get; }
        public WorkspacePaths Paths { get; }
        public MinecraftIconService Service { get; }
        public string CachePath => Path.Combine(Paths.MinecraftIconCache, "porkchop.png");

        public IconTestContext(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> send, bool withFallback = true)
        {
            Handler = new(send);
            _client = new(Handler) { BaseAddress = new Uri("https://mc-icons.example/") };
            Paths = new(_workspace);
            Service = new(_client, Paths, withFallback
                ? Microsoft.Extensions.Options.Options.Create(new EditorOptions { MinecraftIconsFallbackBaseUrl = "https://native-icons.example/1.21.11/" }) : null);
        }

        public void Dispose()
        {
            _client.Dispose();
            if (Directory.Exists(_workspace))
                Directory.Delete(_workspace, recursive: true);
        }
    }

    private sealed class RoutingHandler(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> send) : HttpMessageHandler
    {
        public ConcurrentQueue<Uri> Requests { get; } = new();

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Requests.Enqueue(request.RequestUri!);
            return send(request, cancellationToken);
        }
    }

    private sealed class StubHandler : HttpMessageHandler
    {
        public int RequestCount { get; private set; }
        public string? LastRequestPath { get; private set; }

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            RequestCount++;
            LastRequestPath = request.RequestUri?.PathAndQuery.TrimStart('/');
            var content = new ByteArrayContent([137, 80, 78, 71]);
            content.Headers.ContentType = new MediaTypeHeaderValue("image/png");
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = content });
        }
    }
}
