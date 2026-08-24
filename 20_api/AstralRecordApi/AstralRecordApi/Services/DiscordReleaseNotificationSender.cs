using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json.Serialization;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Options;
using Microsoft.Extensions.Options;

namespace AstralRecordApi.Services;

public sealed class DiscordReleaseNotificationSender(
    HttpClient httpClient,
    IOptions<DiscordReleaseNotificationOptions> options,
    ILogger<DiscordReleaseNotificationSender> logger) : IDiscordReleaseNotificationSender
{
    private readonly DiscordReleaseNotificationOptions discordOptions = options.Value;

    public async Task<DiscordSendResult> SendAsync(
        ReleaseNotificationOutboxEntity outbox,
        CancellationToken cancellationToken)
    {
        var tokenPath = ResolveTokenPath(discordOptions.TokenFilePath);
        string token;
        try
        {
            token = (await File.ReadAllTextAsync(tokenPath, cancellationToken)).Trim();
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            logger.LogError(ex, "Discord release notification token file could not be read: {TokenPath}", tokenPath);
            return new DiscordSendResult(false, true, null, "Discord Bot token file could not be read.");
        }

        if (string.IsNullOrWhiteSpace(token))
            return new DiscordSendResult(false, true, null, "Discord Bot token file is empty.");

        using var request = new HttpRequestMessage(
            HttpMethod.Post,
            $"channels/{discordOptions.ChannelId}/messages");
        request.Headers.Authorization = new AuthenticationHeaderValue("Bot", token);
        request.Content = JsonContent.Create(new
        {
            content = $"📢 **{outbox.ReleaseNote.Title}** ({outbox.ReleaseNote.Version})\n{outbox.ReleaseNote.ReleaseUrl}",
            allowed_mentions = new { parse = Array.Empty<string>() },
        });

        using var response = await httpClient.SendAsync(request, cancellationToken);
        if (response.IsSuccessStatusCode)
        {
            var message = await response.Content.ReadFromJsonAsync<DiscordMessageResponse>(cancellationToken);
            return new DiscordSendResult(true, false, message?.Id, null);
        }

        var errorBody = await response.Content.ReadAsStringAsync(cancellationToken);
        var retryAfter = GetRetryAfter(response);
        var retryable = response.StatusCode == (HttpStatusCode)429
                        || (int)response.StatusCode >= 500
                        || response.StatusCode is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden;
        var error = $"Discord API returned {(int)response.StatusCode}: {errorBody[..Math.Min(errorBody.Length, 500)]}";
        logger.LogWarning("Discord release notification failed. StatusCode={StatusCode}, Retryable={Retryable}",
            (int)response.StatusCode, retryable);
        return new DiscordSendResult(false, retryable, null, error, retryAfter);
    }

    private static string ResolveTokenPath(string configuredPath)
        => Path.IsPathRooted(configuredPath)
            ? configuredPath
            : Path.Combine(AppContext.BaseDirectory, configuredPath);

    private static TimeSpan? GetRetryAfter(HttpResponseMessage response)
    {
        if (response.Headers.RetryAfter?.Delta is { } retryAfter)
            return retryAfter;

        return null;
    }

    private sealed class DiscordMessageResponse
    {
        [JsonPropertyName("id")]
        public string? Id { get; init; }
    }
}
