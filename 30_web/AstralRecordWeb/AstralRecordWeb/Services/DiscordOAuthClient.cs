using System.Text.Json;
using AstralRecordWeb.Options;
using Microsoft.Extensions.Options;

namespace AstralRecordWeb.Services;

public sealed record DiscordOAuthTokens(string AccessToken, string RefreshToken);

public sealed class DiscordOAuthClient(HttpClient client, IOptions<DonationOptions> options)
{
    public async Task<DiscordOAuthTokens?> ExchangeAsync(string code, string verifier, CancellationToken ct)
    {
        try
        {
            var settings = options.Value;
            using var body = new FormUrlEncodedContent(new Dictionary<string, string>
            {
                ["grant_type"] = "authorization_code", ["client_id"] = settings.DiscordClientId,
                ["client_secret"] = settings.DiscordClientSecret, ["redirect_uri"] = settings.DiscordRedirectUri,
                ["code"] = code, ["code_verifier"] = verifier,
            });
            using var response = await client.PostAsync("https://discord.com/api/oauth2/token", body, ct);
            if (!response.IsSuccessStatusCode) return null;
            var json = await response.Content.ReadFromJsonAsync<JsonElement>(ct);
            if (!json.TryGetProperty("access_token", out var access) || !json.TryGetProperty("refresh_token", out var refresh)) return null;
            var accessToken = access.GetString();
            var refreshToken = refresh.GetString();
            return string.IsNullOrWhiteSpace(accessToken) || string.IsNullOrWhiteSpace(refreshToken) ? null : new(accessToken, refreshToken);
        }
        catch (Exception ex) when (!ct.IsCancellationRequested && ex is HttpRequestException or TaskCanceledException or JsonException or InvalidOperationException)
        {
            return null;
        }
    }
}
