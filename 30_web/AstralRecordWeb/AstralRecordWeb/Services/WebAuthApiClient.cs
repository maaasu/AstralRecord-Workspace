using System.Net.Http.Json;
using System.Text.Json;
using AstralRecordWeb.Models;

namespace AstralRecordWeb.Services;

public class WebAuthApiClient(HttpClient httpClient, ILogger<WebAuthApiClient> logger)
{
    public async Task<WebLoginChallengeConsumeResult> ConsumeAsync(string loginCode, CancellationToken cancellationToken)
    {
        var normalizedCode = NormalizeLoginCode(loginCode);
        if (string.IsNullOrWhiteSpace(normalizedCode))
            return WebLoginChallengeConsumeResult.Invalid();

        try
        {
            using var response = await httpClient.PostAsJsonAsync(
                "/api/web-auth/challenges/consume",
                new WebLoginChallengeConsumeRequest { LoginCode = normalizedCode },
                cancellationToken);

            if (!response.IsSuccessStatusCode)
            {
                return (int)response.StatusCode >= 500
                    ? WebLoginChallengeConsumeResult.ServiceUnavailable()
                    : WebLoginChallengeConsumeResult.Invalid();
            }

            var consumed = await response.Content.ReadFromJsonAsync<WebLoginChallengeConsumeResponse>(cancellationToken);
            return consumed is null
                ? WebLoginChallengeConsumeResult.ServiceUnavailable()
                : WebLoginChallengeConsumeResult.Success(consumed);
        }
        catch (Exception ex) when (!cancellationToken.IsCancellationRequested &&
            ex is HttpRequestException or TaskCanceledException or JsonException or NotSupportedException)
        {
            logger.LogWarning(ex, "Web login challenge consume API request failed.");
            return WebLoginChallengeConsumeResult.ServiceUnavailable();
        }
    }

    private static string NormalizeLoginCode(string loginCode) =>
        loginCode.Trim().Replace(" ", string.Empty).Replace("-", string.Empty).ToUpperInvariant();
}
