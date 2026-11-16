using System.Text.Json;
using AstralRecordApi.Controllers;
using Microsoft.AspNetCore.Hosting;
using Microsoft.Extensions.FileProviders;
using Xunit;

namespace AstralRecordApi.Tests.Controllers;

public sealed class ResourcePacksControllerTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "astralrecord-pack-test-" + Guid.NewGuid());
    private static readonly JsonSerializerOptions WebJson = new(JsonSerializerDefaults.Web);

    [Theory]
    [InlineData("2")]
    [InlineData("1")]
    [InlineData("99")]
    public async Task ManifestPreservesMossZombieCapabilityThroughFileReadAndResponse(string version)
    {
        await WriteManifest($$"""
            {"sha1":"0123456789abcdef0123456789abcdef01234567",
             "mossZombiePresentation":"{{version}}","sunbirdPresentation":"2"}
            """);

        var result = await Controller().GetManifest(CancellationToken.None);
        Assert.NotNull(result.Value);
        using var response = JsonDocument.Parse(JsonSerializer.Serialize(result.Value, WebJson));
        var java = response.RootElement.GetProperty("java");
        Assert.Equal(version, java.GetProperty("mossZombiePresentation").GetString());
        Assert.Equal("2", java.GetProperty("sunbirdPresentation").GetString());
        Assert.Equal("0123456789abcdef0123456789abcdef01234567", java.GetProperty("sha1").GetString());
        Assert.Equal("12345678-1234-4234-8234-123456789abc",
            response.RootElement.GetProperty("bedrock").GetProperty("uuid").GetString());
    }

    [Theory]
    [InlineData("{\"sha1\":\"0123456789abcdef0123456789abcdef01234567\"}")]
    [InlineData("{\"sha1\":\"0123456789abcdef0123456789abcdef01234567\",\"mossZombiePresentation\":null}")]
    public async Task LegacyManifestDoesNotAdvertiseMossZombieCapability(string java)
    {
        await WriteManifest(java);

        var result = await Controller().GetManifest(CancellationToken.None);
        Assert.NotNull(result.Value);
        using var response = JsonDocument.Parse(JsonSerializer.Serialize(result.Value, WebJson));
        Assert.Equal(JsonValueKind.Null,
            response.RootElement.GetProperty("java").GetProperty("mossZombiePresentation").ValueKind);
    }

    [Theory]
    [InlineData(null, null)]
    [InlineData("null", null)]
    [InlineData("\"1\"", "1")]
    [InlineData("\"99\"", "99")]
    public async Task ManifestRoundTripsOptionalActionRingCooldownHudCapability(string? capabilityJson, string? expected)
    {
        var option = capabilityJson is null ? "" : ",\"actionRingCooldownHud\":" + capabilityJson;
        await WriteManifest("{\"sha1\":\"0123456789abcdef0123456789abcdef01234567\"" + option + "}");

        var result = await Controller().GetManifest(CancellationToken.None);
        Assert.NotNull(result.Value);
        using var response = JsonDocument.Parse(JsonSerializer.Serialize(result.Value, WebJson));
        Assert.Equal(expected, response.RootElement.GetProperty("java").GetProperty("actionRingCooldownHud").GetString());
        Assert.False(response.RootElement.GetProperty("bedrock").TryGetProperty("actionRingCooldownHud", out _));
    }

    private async Task WriteManifest(string java)
    {
        var directory = Path.Combine(root, "resource-packs");
        Directory.CreateDirectory(directory);
        await File.WriteAllTextAsync(Path.Combine(directory, "manifest.json"), $$$"""
            {"java":{{{java}}},"bedrock":{
              "sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123",
              "uuid":"12345678-1234-4234-8234-123456789abc"}}
            """);
    }

    private ResourcePacksController Controller() => new(new TestEnvironment { ContentRootPath = root });

    public void Dispose()
    {
        if (Directory.Exists(root)) Directory.Delete(root, true);
    }

    private sealed class TestEnvironment : IWebHostEnvironment
    {
        public string ApplicationName { get; set; } = "AstralRecordApi.Tests";
        public string EnvironmentName { get; set; } = "Testing";
        public string ContentRootPath { get; set; } = "";
        public IFileProvider ContentRootFileProvider { get; set; } = new NullFileProvider();
        public string WebRootPath { get; set; } = "";
        public IFileProvider WebRootFileProvider { get; set; } = new NullFileProvider();
    }
}
