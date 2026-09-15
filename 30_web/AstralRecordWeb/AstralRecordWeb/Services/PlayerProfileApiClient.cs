using System.Net;
using System.Text.Json;
using AstralRecordWeb.Models;

namespace AstralRecordWeb.Services;

public sealed record ProfileApiResult<T>(T? Value, HttpStatusCode Status)
{
    public bool Succeeded => Status == HttpStatusCode.OK && Value is not null;
}

/// <summary>閲覧者UUIDは呼出元の認証済みセッションから取得する。</summary>
public class PlayerProfileApiClient(HttpClient httpClient, ILogger<PlayerProfileApiClient> logger)
{
    public Task<ProfileApiResult<WebPlayerProfileResponse>> GetMeAsync(Guid viewer, CancellationToken ct) =>
        GetAsync<WebPlayerProfileResponse>($"/api/web-profiles/me?viewer_user_uuid={viewer:D}", ct);

    public Task<ProfileApiResult<WebPlayerProfileResponse>> GetProfileAsync(Guid target, Guid? viewer, bool includePrivate, CancellationToken ct) =>
        GetAsync<WebPlayerProfileResponse>($"/api/web-profiles/{target:D}?include_private={includePrivate.ToString().ToLowerInvariant()}{ViewerQuery(viewer)}", ct);

    public Task<ProfileApiResult<WebPlayerProfileSearchResponse>> SearchAsync(Guid? viewer, string? mcid, string? classId, string sort, int page, bool includePrivate, CancellationToken ct) =>
        GetAsync<WebPlayerProfileSearchResponse>($"/api/web-profiles?mcid={Uri.EscapeDataString(mcid ?? "")}&class_id={Uri.EscapeDataString(classId ?? "")}&sort={Uri.EscapeDataString(sort)}&page={page}&page_size=20&include_private={includePrivate.ToString().ToLowerInvariant()}{ViewerQuery(viewer)}", ct);

    private static string ViewerQuery(Guid? viewer) => viewer.HasValue ? $"&viewer_user_uuid={viewer.Value:D}" : string.Empty;

    public async Task<bool> SetVisibilityAsync(Guid viewer, bool isPublic, CancellationToken ct)
    {
        try
        {
            using var response = await httpClient.PutAsJsonAsync($"/api/web-profiles/me/visibility?viewer_user_uuid={viewer:D}",
                new WebPlayerProfileVisibilityUpdateRequest { IsPublic = isPublic }, ct);
            return response.IsSuccessStatusCode;
        }
        catch (Exception ex) when (!ct.IsCancellationRequested && ex is HttpRequestException or TaskCanceledException or JsonException)
        {
            logger.LogWarning(ex, "Profile visibility request failed.");
            return false;
        }
    }

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
            logger.LogWarning(ex, "Player profile API request failed.");
            return new(default, HttpStatusCode.ServiceUnavailable);
        }
    }
}
