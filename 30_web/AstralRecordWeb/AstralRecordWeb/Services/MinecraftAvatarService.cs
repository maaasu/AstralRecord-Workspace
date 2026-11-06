using System.Buffers.Binary;
using System.Globalization;
using System.Text.Json;
using Microsoft.Extensions.Caching.Memory;

namespace AstralRecordWeb.Services;

public sealed class MinecraftAvatarService(HttpClient http, IMemoryCache cache)
{
    private const string TextureOrigin = "https://textures.minecraft.net";

    public Task<string?> GetAsync(Guid uuid, CancellationToken ct) =>
        cache.GetOrCreateAsync((typeof(MinecraftAvatarService), uuid), async entry =>
        {
            string? avatar = null;
            try
            {
                var hash = await GetTextureHashAsync(uuid, ct);
                if (IsTextureHash(hash))
                {
                    using var response = await http.GetAsync($"{TextureOrigin}/texture/{hash}", ct);
                    if (response.IsSuccessStatusCode)
                    {
                        avatar = RenderFace(await response.Content.ReadAsByteArrayAsync(ct));
                    }
                }
            }
            catch (Exception ex) when (ex is HttpRequestException or JsonException or FormatException
                or InvalidOperationException || ex is OperationCanceledException && !ct.IsCancellationRequested)
            {
                // The profile's initials remain visible when a skin is unavailable.
            }
            entry.AbsoluteExpirationRelativeToNow = avatar is null ? TimeSpan.FromMinutes(1) : TimeSpan.FromMinutes(10);
            return avatar;
        });

    private async Task<string?> GetTextureHashAsync(Guid uuid, CancellationToken ct)
    {
        var compact = uuid.ToString("N");
        // Floodgate places the Xbox user ID in the lower 64 bits of the UUID.
        if (compact.StartsWith("0000000000000000", StringComparison.Ordinal))
        {
            var xuid = ulong.Parse(compact.AsSpan(16), NumberStyles.HexNumber, CultureInfo.InvariantCulture);
            using var skin = await FetchJsonAsync($"https://api.geysermc.org/v2/skin/{xuid.ToString(CultureInfo.InvariantCulture)}", ct);
            return skin?.RootElement.TryGetProperty("texture_id", out var id) == true ? id.GetString() : null;
        }

        using var profile = await FetchJsonAsync($"https://sessionserver.mojang.com/session/minecraft/profile/{compact}", ct);
        if (profile?.RootElement.TryGetProperty("properties", out var properties) != true) return null;
        foreach (var property in properties.EnumerateArray())
        {
            if (!property.TryGetProperty("name", out var name) || name.GetString() != "textures"
                || !property.TryGetProperty("value", out var value) || value.GetString() is not { } encoded) continue;

            using var texture = JsonDocument.Parse(Convert.FromBase64String(encoded));
            if (!texture.RootElement.TryGetProperty("textures", out var textures)
                || !textures.TryGetProperty("SKIN", out var skin)
                || !skin.TryGetProperty("url", out var url)
                || !Uri.TryCreate(url.GetString(), UriKind.Absolute, out var uri)
                || uri.Host != "textures.minecraft.net"
                || !uri.AbsolutePath.StartsWith("/texture/", StringComparison.Ordinal)) return null;

            return uri.AbsolutePath["/texture/".Length..];
        }
        return null;
    }

    private async Task<JsonDocument?> FetchJsonAsync(string url, CancellationToken ct)
    {
        using var response = await http.GetAsync(url, ct);
        if (response.StatusCode != System.Net.HttpStatusCode.OK) return null;
        await using var stream = await response.Content.ReadAsStreamAsync(ct);
        return await JsonDocument.ParseAsync(stream, cancellationToken: ct);
    }

    private static bool IsTextureHash(string? hash) =>
        hash is { Length: > 0 and <= 64 } && hash.All(Uri.IsHexDigit);

    private static string? RenderFace(byte[] png)
    {
        if (png.Length < 33 || !png.AsSpan(0, 8).SequenceEqual(new byte[] { 137, 80, 78, 71, 13, 10, 26, 10 })
            || !png.AsSpan(12, 4).SequenceEqual("IHDR"u8)) return null;

        var width = BinaryPrimitives.ReadUInt32BigEndian(png.AsSpan(16, 4));
        var height = BinaryPrimitives.ReadUInt32BigEndian(png.AsSpan(20, 4));
        if (width is < 64 or > 1024 || width % 64 != 0 || (height != width && height != width / 2)) return null;

        var textureHeight = height == width ? 64 : 32;
        var encoded = Convert.ToBase64String(png);
        return $"""
            <svg xmlns="http://www.w3.org/2000/svg" width="96" height="96" viewBox="0 0 8 8">
              <defs><image id="skin" width="64" height="{textureHeight}" href="data:image/png;base64,{encoded}" style="image-rendering:pixelated"/></defs>
              <use href="#skin" x="-8" y="-8"/>
              <use href="#skin" x="-40" y="-8"/>
            </svg>
            """;
    }
}
