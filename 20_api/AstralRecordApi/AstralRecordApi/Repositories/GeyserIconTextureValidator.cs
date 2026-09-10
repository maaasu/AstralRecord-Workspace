using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace AstralRecordApi.Repositories;

/// <summary>Geyser が使用するカスタムヘッドテクスチャの形式を検証する。</summary>
internal static class GeyserIconTextureValidator
{
    private const int MaxBase64Length = 16_384;
    private const string TextureHost = "textures.minecraft.net";
    private const string TexturePathPrefix = "/texture/";

    public static bool IsValid(string? iconTexture)
    {
        if (string.IsNullOrWhiteSpace(iconTexture) || iconTexture.Length > MaxBase64Length
            || iconTexture.Any(char.IsWhiteSpace))
            return false;

        try
        {
            var json = Encoding.UTF8.GetString(Convert.FromBase64String(iconTexture));
            using var document = JsonDocument.Parse(json);
            if (document.RootElement.ValueKind != JsonValueKind.Object
                || !document.RootElement.TryGetProperty("textures", out var textures)
                || textures.ValueKind is not JsonValueKind.Object
                || !textures.TryGetProperty("SKIN", out var skin)
                || skin.ValueKind is not JsonValueKind.Object
                || !skin.TryGetProperty("url", out var urlValue)
                || urlValue.ValueKind is not JsonValueKind.String)
                return false;

            return IsValidTextureUrl(urlValue.GetString());
        }
        catch (FormatException)
        {
            return false;
        }
        catch (DecoderFallbackException)
        {
            return false;
        }
        catch (JsonException)
        {
            return false;
        }
    }

    private static bool IsValidTextureUrl(string? rawUrl)
    {
        if (rawUrl is null || !Regex.IsMatch(rawUrl,
                @"\Ahttps?://(?i:textures\.minecraft\.net)/texture/[0-9a-fA-F]{1,64}\z", RegexOptions.CultureInvariant)
            || !Uri.TryCreate(rawUrl, UriKind.Absolute, out var uri)
            || uri.Scheme is not ("http" or "https")
            || !string.Equals(uri.Host, TextureHost, StringComparison.OrdinalIgnoreCase)
            || !string.Equals(uri.Authority, TextureHost, StringComparison.OrdinalIgnoreCase)
            || !string.IsNullOrEmpty(uri.UserInfo)
            || !string.IsNullOrEmpty(uri.Query)
            || !string.IsNullOrEmpty(uri.Fragment))
            return false;

        var path = uri.AbsolutePath;
        if (!path.StartsWith(TexturePathPrefix, StringComparison.Ordinal)
            || path.Length <= TexturePathPrefix.Length)
            return false;

        var textureId = path[TexturePathPrefix.Length..];
        return textureId.Length <= 64 && textureId.All(Uri.IsHexDigit);
    }
}
