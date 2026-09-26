using System.Net;
using System.Net.Http.Headers;
using System.Text.Json;
using AstralRecordApi.Options;
using Microsoft.Extensions.Options;

namespace AstralRecordApi.Services;

public sealed class DiscordDonationClient
{
    private readonly HttpClient http;
    private readonly DonationDiscordOptions options;

    public DiscordDonationClient(HttpClient http, IOptions<DonationDiscordOptions> options)
    {
        this.http = http;
        this.options = options.Value;
        http.BaseAddress ??= new Uri("https://discord.com/api/v10/");
    }

    public void EnsureConfigured()
    {
        if (string.IsNullOrWhiteSpace(options.DiscordClientId)
            || string.IsNullOrWhiteSpace(options.DiscordClientSecret)
            || !IsSnowflake(options.DiscordGuildId))
            throw new InvalidOperationException("Discord donation integration is not configured.");
    }

    public async Task<DiscordDonationUser> GetCurrentUserAsync(string accessToken)
    {
        using var response = await SendBearerAsync($"users/@me", accessToken);
        if (response.StatusCode is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden)
            throw new DiscordDonationTokenRejectedException();
        EnsureSuccess(response);
        using var document = await ParseAsync(response);
        var root = document.RootElement;
        var id = ReadString(root, "id");
        var name = ReadString(root, "global_name") ?? ReadString(root, "username");
        if (!IsSnowflake(id) || string.IsNullOrWhiteSpace(name))
            throw new InvalidOperationException("Discord returned an invalid user response.");
        return new DiscordDonationUser(id!, name!.Length > 128 ? name[..128] : name);
    }

    public async Task<bool> IsGuildMemberAsync(string accessToken)
    {
        EnsureConfigured();
        using var response = await SendBearerAsync($"users/@me/guilds/{options.DiscordGuildId}/member", accessToken);
        if (response.StatusCode == HttpStatusCode.Unauthorized)
            throw new DiscordDonationTokenRejectedException();
        if (response.StatusCode is HttpStatusCode.Forbidden or HttpStatusCode.NotFound)
            return false;
        EnsureSuccess(response);
        return true;
    }

    public async Task<DiscordDonationTokens> RefreshAsync(string refreshToken)
    {
        EnsureConfigured();
        using var request = new HttpRequestMessage(HttpMethod.Post, "oauth2/token")
        {
            Content = new FormUrlEncodedContent(new Dictionary<string, string>
            {
                ["client_id"] = options.DiscordClientId,
                ["client_secret"] = options.DiscordClientSecret,
                ["grant_type"] = "refresh_token",
                ["refresh_token"] = refreshToken,
            }),
        };
        using var response = await SendAsync(request);
        if (response.StatusCode is HttpStatusCode.BadRequest or HttpStatusCode.Unauthorized)
            throw new DiscordDonationTokenRejectedException();
        EnsureSuccess(response);
        using var document = await ParseAsync(response);
        var root = document.RootElement;
        var access = ReadString(root, "access_token");
        var refresh = ReadString(root, "refresh_token");
        if (string.IsNullOrWhiteSpace(access) || string.IsNullOrWhiteSpace(refresh)
            || ReadString(root, "token_type") is not "Bearer")
            throw new InvalidOperationException("Discord returned an invalid token response.");
        return new DiscordDonationTokens(access, refresh);
    }

    private async Task<HttpResponseMessage> SendBearerAsync(string path, string accessToken)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, path);
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", accessToken);
        return await SendAsync(request);
    }

    private async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request)
    {
        try { return await http.SendAsync(request); }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
        {
            throw new InvalidOperationException("Discord is temporarily unavailable.");
        }
    }

    private static async Task<JsonDocument> ParseAsync(HttpResponseMessage response)
    {
        try { return JsonDocument.Parse(await response.Content.ReadAsStringAsync()); }
        catch (JsonException) { throw new InvalidOperationException("Discord returned an invalid response."); }
    }

    private static string? ReadString(JsonElement root, string name)
        => root.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String
            ? value.GetString() : null;

    private static void EnsureSuccess(HttpResponseMessage response)
    {
        if (!response.IsSuccessStatusCode)
            throw new InvalidOperationException("Discord verification is temporarily unavailable.");
    }

    public static bool IsSnowflake(string? value)
        => !string.IsNullOrWhiteSpace(value) && value.Length <= 32 && value.All(char.IsAsciiDigit);
}

public sealed record DiscordDonationUser(string Id, string Name);
public sealed record DiscordDonationTokens(string AccessToken, string RefreshToken);

public sealed class DiscordDonationTokenRejectedException : Exception;
