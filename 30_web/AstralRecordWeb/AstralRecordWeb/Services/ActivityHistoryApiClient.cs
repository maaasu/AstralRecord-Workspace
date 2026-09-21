using System.Net;
using System.Text.Json;
using Microsoft.AspNetCore.WebUtilities;

namespace AstralRecordWeb.Services;

public sealed record ActivityHistoryResult<T>(T? Value, HttpStatusCode Status)
{
    public bool Succeeded => Status == HttpStatusCode.OK && Value is not null;
}

/// <summary>管理者の活動履歴を API 経由で照会します。呼出者は認証済み Cookie から取得します。</summary>
public sealed class ActivityHistoryApiClient(HttpClient httpClient, ILogger<ActivityHistoryApiClient> logger)
{
    public async Task<ActivityHistoryResult<T>> GetAsync<T>(string endpoint, Guid actor,
        DateTimeOffset from, DateTimeOffset to, int page, string? query, CancellationToken ct,
        params (string Key, string? Value)[] filters)
    {
        var parameters = new Dictionary<string, string?>
        {
            ["actor_user_uuid"] = actor.ToString("D"),
            ["from"] = from.ToString("O"), ["to"] = to.ToString("O"),
            ["page"] = page.ToString(System.Globalization.CultureInfo.InvariantCulture),
            ["pageSize"] = "50", ["query"] = query,
        };
        foreach (var (key, value) in filters) parameters[key] = value;
        var path = QueryHelpers.AddQueryString("/api/admin/player-activity/" + endpoint, parameters);
        try
        {
            using var response = await httpClient.GetAsync(path, ct);
            if (!response.IsSuccessStatusCode) return new(default, response.StatusCode);
            var value = await response.Content.ReadFromJsonAsync<T>(ct);
            return new(value, value is null ? HttpStatusCode.ServiceUnavailable : HttpStatusCode.OK);
        }
        catch (Exception ex) when (!ct.IsCancellationRequested && ex is HttpRequestException or TaskCanceledException or JsonException or NotSupportedException)
        {
            logger.LogWarning(ex, "Activity history API request failed.");
            return new(default, HttpStatusCode.ServiceUnavailable);
        }
    }
}
