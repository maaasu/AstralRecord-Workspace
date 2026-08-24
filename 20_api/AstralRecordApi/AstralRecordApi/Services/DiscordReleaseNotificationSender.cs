using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
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
            return new DiscordSendResult(false, false, null, "Discord Bot token file could not be read.");
        }

        if (string.IsNullOrWhiteSpace(token))
            return new DiscordSendResult(false, false, null, "Discord Bot token file is empty.");

        using var request = new HttpRequestMessage(
            HttpMethod.Post,
            $"channels/{discordOptions.ChannelId}/messages");
        request.Headers.Authorization = new AuthenticationHeaderValue("Bot", token);
        request.Content = JsonContent.Create(new
        {
            content = $"📢 **{outbox.ReleaseNote.Title}** ({outbox.ReleaseNote.Version})\n{outbox.ReleaseNote.ReleaseUrl}",
            allowed_mentions = new { parse = Array.Empty<string>() },
            nonce = CreateNonce(outbox.OutboxId),
            enforce_nonce = true,
        });

        using var response = await httpClient.SendAsync(request, cancellationToken);
        if (response.IsSuccessStatusCode)
        {
            var message = await response.Content.ReadFromJsonAsync<DiscordMessageResponse>(cancellationToken);
            return new DiscordSendResult(true, false, message?.Id, null);
        }

        var errorBody = await response.Content.ReadAsStringAsync(cancellationToken);
        var retryAfter = GetRetryAfter(response, errorBody);
        var retryable = response.StatusCode == (HttpStatusCode)429
                        || (int)response.StatusCode >= 500;
        var error = $"Discord API returned {(int)response.StatusCode}: {errorBody[..Math.Min(errorBody.Length, 500)]}";
        logger.LogWarning("Discord release notification failed. StatusCode={StatusCode}, Retryable={Retryable}",
            (int)response.StatusCode, retryable);
        return new DiscordSendResult(false, retryable, null, error, retryAfter);
    }

    private static string ResolveTokenPath(string configuredPath)
        => Path.IsPathRooted(configuredPath)
            ? configuredPath
            : Path.Combine(AppContext.BaseDirectory, configuredPath);

    private static string CreateNonce(Guid outboxId)
        => $"rn-{outboxId:N}"[..25];

    private static TimeSpan? GetRetryAfter(HttpResponseMessage response, string errorBody)
    {
        var headerRetryAfter = response.Headers.RetryAfter?.Delta;
        TimeSpan? bodyRetryAfter = null;
        if (response.StatusCode == (HttpStatusCode)429)
        {
            try
            {
                using var document = JsonDocument.Parse(errorBody);
                if (document.RootElement.TryGetProperty("retry_after", out var retryAfter)
                    && retryAfter.ValueKind == JsonValueKind.Number
                    && retryAfter.TryGetDouble(out var seconds)
                    && double.IsFinite(seconds)
                    && seconds >= 0)
                {
                    bodyRetryAfter = TimeSpan.FromSeconds(seconds);
                }
            }
            catch (JsonException)
            {
                // Fall back to the header and repository exponential backoff.
            }
        }

        if (headerRetryAfter is null)
            return bodyRetryAfter;
        if (bodyRetryAfter is null)
            return headerRetryAfter;
        return headerRetryAfter >= bodyRetryAfter ? headerRetryAfter : bodyRetryAfter;
    }

    private sealed class DiscordMessageResponse
    {
        [JsonPropertyName("id")]
        public string? Id { get; init; }
    }
}
