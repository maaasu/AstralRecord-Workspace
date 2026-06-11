using System.Net.Http.Json;
using AstralRecordWeb.Models;

namespace AstralRecordWeb.Services;

public class WebAuthApiClient(HttpClient httpClient)
{
    public async Task<WebLoginChallengeConsumeResponse?> ConsumeAsync(string loginCode, CancellationToken cancellationToken)
    {
        using var response = await httpClient.PostAsJsonAsync(
            "/api/web-auth/challenges/consume",
            new WebLoginChallengeConsumeRequest { LoginCode = loginCode },
            cancellationToken);

        if (!response.IsSuccessStatusCode)
            return null;

        return await response.Content.ReadFromJsonAsync<WebLoginChallengeConsumeResponse>(cancellationToken);
    }
}
