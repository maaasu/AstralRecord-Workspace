using System.Net.Http.Json;
using System.Text.Json;
using AstralRecordWeb.Models;

namespace AstralRecordWeb.Services;

public class WebAuthApiClient(HttpClient httpClient, ILogger<WebAuthApiClient> logger)
{
    public async Task<WebLoginChallengeConsumeResult> ConsumeAsync(string loginCode, CancellationToken cancellationToken, Guid? expectedUserUuid = null)
    {
        var normalizedCode = NormalizeLoginCode(loginCode);
        if (string.IsNullOrWhiteSpace(normalizedCode))
            return WebLoginChallengeConsumeResult.Invalid();

        try
        {
            using var response = await httpClient.PostAsJsonAsync(
                "/api/web-auth/challenges/consume",
                new WebLoginChallengeConsumeRequest { LoginCode = normalizedCode, ExpectedUserUuid = expectedUserUuid },
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

    public async Task<WebLoginChallengeConsumeResult> LoginWithPasswordAsync(string loginId, string password, CancellationToken ct)
    {
        try
        {
            using var response = await httpClient.PostAsJsonAsync("/api/web-auth/password/login", new { loginId, password }, ct);
            if (!response.IsSuccessStatusCode)
                return (int)response.StatusCode >= 500 ? WebLoginChallengeConsumeResult.ServiceUnavailable() : WebLoginChallengeConsumeResult.Invalid();
            var session = await response.Content.ReadFromJsonAsync<WebLoginChallengeConsumeResponse>(ct);
            return session is null ? WebLoginChallengeConsumeResult.ServiceUnavailable() : WebLoginChallengeConsumeResult.Success(session);
        }
        catch (Exception ex) when (!ct.IsCancellationRequested && ex is HttpRequestException or TaskCanceledException or JsonException or NotSupportedException)
        {
            logger.LogWarning(ex, "Web password login API request failed.");
            return WebLoginChallengeConsumeResult.ServiceUnavailable();
        }
    }

    public async Task<WebCredentialState?> GetCredentialsAsync(Guid userUuid, CancellationToken ct)
    {
        try
        {
            using var response = await httpClient.GetAsync($"/api/web-auth/users/{userUuid:D}/credentials", ct);
            return response.IsSuccessStatusCode ? await response.Content.ReadFromJsonAsync<WebCredentialState>(ct) : null;
        }
        catch (Exception ex) when (!ct.IsCancellationRequested && ex is HttpRequestException or TaskCanceledException or JsonException or NotSupportedException)
        {
            logger.LogWarning(ex, "Web credential state API request failed.");
            return null;
        }
    }

    public async Task<WebCredentialUpdateResult> UpdateCredentialsAsync(Guid userUuid, WebCredentialUpdateRequest request, CancellationToken ct)
    {
        try
        {
            using var response = await httpClient.PostAsJsonAsync($"/api/web-auth/users/{userUuid:D}/credentials", request, ct);
            if (!response.IsSuccessStatusCode)
                return new(null, (int)response.StatusCode >= 500, response.StatusCode == System.Net.HttpStatusCode.Unauthorized);
            var state = await response.Content.ReadFromJsonAsync<WebCredentialState>(ct);
            return new(state, state is null, false);
        }
        catch (Exception ex) when (!ct.IsCancellationRequested && ex is HttpRequestException or TaskCanceledException or JsonException or NotSupportedException)
        {
            logger.LogWarning(ex, "Web credential update API request failed.");
            return new(null, true, false);
        }
    }

    /// <summary>指定プレイヤーのWeb管理権限をAPIから取得します。</summary>
    public async Task<bool> IsWebAdminAsync(Guid userUuid, CancellationToken cancellationToken)
    {
        try
        {
            using var response = await httpClient.GetAsync(
                $"/api/web-auth/users/{userUuid:D}/authorization",
                cancellationToken);
            if (!response.IsSuccessStatusCode)
                return false;

            var authorization = await response.Content.ReadFromJsonAsync<WebAuthorizationResponse>(cancellationToken);
            return authorization?.WebAdmin == true;
        }
        catch (Exception ex) when (!cancellationToken.IsCancellationRequested &&
            ex is HttpRequestException or TaskCanceledException or JsonException or NotSupportedException)
        {
            logger.LogWarning(ex, "Web administrator authorization API request failed.");
            return false;
        }
    }

    private static string NormalizeLoginCode(string loginCode) =>
        loginCode.Trim().Replace(" ", string.Empty).Replace("-", string.Empty).ToUpperInvariant();
}
