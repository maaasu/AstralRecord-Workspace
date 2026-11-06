using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using AstralRecordWeb.Services;
using Microsoft.Extensions.Caching.Memory;
using Xunit;

namespace AstralRecordWeb.Tests;

public class MinecraftAvatarTests
{
    private const string SkinPng =
        "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAYAAACqaXHeAAAAJ0lEQVR4nO3BAQ0AAADCoPdPbQ43oAAAAAAAAAAAAAAAAAAAAIDODUBAAAENBzWNAAAAAElFTkSuQmCC";

    [Theory]
    [InlineData("11111111-1111-1111-1111-111111111111", "https://sessionserver.mojang.com/session/minecraft/profile/11111111111111111111111111111111")]
    [InlineData("00000000-0000-0000-0009-01f64f65c7c3", "https://api.geysermc.org/v2/skin/2535432196048835")]
    public async Task FetchesTheEditionSpecificSkinAndCachesTheAvatar(string userId, string profileUrl)
    {
        const string textureUrl = "https://textures.minecraft.net/texture/12ab";
        var javaTextures = Convert.ToBase64String(JsonSerializer.SerializeToUtf8Bytes(
            new { textures = new { SKIN = new { url = "http://textures.minecraft.net/texture/12ab" } } }));
        using var cache = new MemoryCache(new MemoryCacheOptions());
        using var handler = new SkinHandler(url => new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = url switch
            {
                textureUrl => new ByteArrayContent(Convert.FromBase64String(SkinPng)),
                _ when url.StartsWith("https://api.geysermc.org/") => JsonContent.Create(new { texture_id = "12ab" }),
                _ => JsonContent.Create(new { properties = new[] { new { name = "textures", value = javaTextures } } })
            }
        });
        using var http = new HttpClient(handler);
        var avatars = new MinecraftAvatarService(http, cache);

        var avatar = await avatars.GetAsync(Guid.Parse(userId), default);

        Assert.NotNull(avatar);
        Assert.Contains("x=\"-8\" y=\"-8\"", avatar);
        Assert.Contains("x=\"-40\" y=\"-8\"", avatar);
        Assert.Equal(avatar, await avatars.GetAsync(Guid.Parse(userId), default));
        Assert.Equal(new[] { profileUrl, textureUrl }, handler.Calls);
    }

    [Fact]
    public async Task MissingBedrockSkinLeavesTheAvatarUnavailable()
    {
        using var cache = new MemoryCache(new MemoryCacheOptions());
        using var handler = new SkinHandler(_ => new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = JsonContent.Create(new { })
        });
        using var http = new HttpClient(handler);
        var avatars = new MinecraftAvatarService(http, cache);

        Assert.Null(await avatars.GetAsync(Guid.Parse("00000000-0000-0000-0009-01f64f65c7c3"), default));
        Assert.Single(handler.Calls);
    }

    private sealed class SkinHandler(Func<string, HttpResponseMessage> respond) : HttpMessageHandler
    {
        public List<string> Calls { get; } = [];

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var url = request.RequestUri!.AbsoluteUri;
            Calls.Add(url);
            return Task.FromResult(respond(url));
        }
    }
}
