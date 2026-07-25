using System.Net;
using System.Net.Http.Headers;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class MinecraftIconServiceTests
{
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
