using System.Net;
using System.Text.Json;
using AstralRecordWeb.Models;

namespace AstralRecordWeb.Services;

/// <summary>閲覧者UUIDは認証済みCookieのclaimsからのみ受け取る。</summary>
public sealed class BestiaryApiClient(HttpClient httpClient, ILogger<BestiaryApiClient> logger)
{
    public Task<ProfileApiResult<WebBestiaryListResponse>> GetListAsync(Guid viewer, Guid? accountId, CancellationToken ct) =>
        GetAsync<WebBestiaryListResponse>($"/api/web-bestiary?viewer_user_uuid={viewer:D}{AccountQuery(accountId)}", ct);

    public Task<ProfileApiResult<WebBestiaryDetailResponse>> GetDetailAsync(Guid viewer, Guid? accountId, string mobId, CancellationToken ct) =>
        GetAsync<WebBestiaryDetailResponse>($"/api/web-bestiary/{Uri.EscapeDataString(mobId)}?viewer_user_uuid={viewer:D}{AccountQuery(accountId)}", ct);

    private static string AccountQuery(Guid? id) => id.HasValue ? $"&account_id={id.Value:D}" : "";

    private async Task<ProfileApiResult<T>> GetAsync<T>(string path, CancellationToken ct)
    {
        try
        {
            using var response = await httpClient.GetAsync(path, ct);
            if (!response.IsSuccessStatusCode) return new(default, response.StatusCode);
            var value = await response.Content.ReadFromJsonAsync<T>(ct);
            return new(value, value is null ? HttpStatusCode.ServiceUnavailable : HttpStatusCode.OK);
        }
        catch (Exception ex) when (!ct.IsCancellationRequested && ex is HttpRequestException or TaskCanceledException or JsonException or NotSupportedException)
        {
            logger.LogWarning(ex, "Bestiary API request failed.");
            return new(default, HttpStatusCode.ServiceUnavailable);
        }
    }
}
