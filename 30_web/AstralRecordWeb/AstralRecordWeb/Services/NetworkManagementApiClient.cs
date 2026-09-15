using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using AstralRecordWeb.Models;

namespace AstralRecordWeb.Services;

public sealed record NetworkManagementApiResult<T>(T? Value, HttpStatusCode Status, string? ErrorMessage = null)
{
    public bool Succeeded => Status == HttpStatusCode.OK && Value is not null;
}

/// <summary>Web 管理画面から ManagementDB のネットワーク設定と利用停止状態を操作します。</summary>
public sealed class NetworkManagementApiClient(HttpClient httpClient, ILogger<NetworkManagementApiClient> logger)
{
    public Task<NetworkManagementApiResult<ManagedNetworkSettings>> GetSettingsAsync(Guid actorUserUuid, CancellationToken ct) =>
        GetAsync<ManagedNetworkSettings>($"/api/network-management/settings?actor_user_uuid={actorUserUuid:D}", ct);

    public Task<NetworkManagementApiResult<IReadOnlyList<NetworkManagedPlayer>>> SearchPlayersAsync(Guid actorUserUuid, string query, CancellationToken ct) =>
        GetAsync<IReadOnlyList<NetworkManagedPlayer>>($"/api/network-management/players?actor_user_uuid={actorUserUuid:D}&query={Uri.EscapeDataString(query)}", ct);

    public Task<NetworkManagementApiResult<NetworkBanStateResponse>> GetBanAsync(Guid actorUserUuid, Guid targetUserUuid, CancellationToken ct) =>
        GetAsync<NetworkBanStateResponse>($"/api/network-management/bans/{targetUserUuid:D}?actor_user_uuid={actorUserUuid:D}", ct);

    public Task<NetworkManagementApiResult<ManagedNetworkSettings>> SaveSettingsAsync(Guid actorUserUuid, ManagedNetworkSettings settings, CancellationToken ct) =>
        PutAsync<ManagedNetworkSettings>($"/api/network-management/settings?actor_user_uuid={actorUserUuid:D}", settings, ct);

    public Task<NetworkManagementApiResult<NetworkBanStateResponse>> UpdateBanAsync(Guid actorUserUuid, Guid targetUserUuid, NetworkBanUpdateRequest update, CancellationToken ct) =>
        PutAsync<NetworkBanStateResponse>($"/api/network-management/bans/{targetUserUuid:D}?actor_user_uuid={actorUserUuid:D}", update, ct);

    private async Task<NetworkManagementApiResult<T>> GetAsync<T>(string path, CancellationToken ct)
    {
        try
        {
            using var response = await httpClient.GetAsync(path, ct);
            return await ReadAsync<T>(response, ct);
        }
        catch (Exception ex) when (!ct.IsCancellationRequested && ex is HttpRequestException or TaskCanceledException or JsonException or NotSupportedException)
        {
            logger.LogWarning(ex, "Network management API GET request failed.");
            return new(default, HttpStatusCode.ServiceUnavailable);
        }
    }

    private async Task<NetworkManagementApiResult<T>> PutAsync<T>(string path, object body, CancellationToken ct)
    {
        try
        {
            using var response = await httpClient.PutAsJsonAsync(path, body, ct);
            return await ReadAsync<T>(response, ct);
        }
        catch (Exception ex) when (!ct.IsCancellationRequested && ex is HttpRequestException or TaskCanceledException or JsonException or NotSupportedException)
        {
            logger.LogWarning(ex, "Network management API PUT request failed.");
            return new(default, HttpStatusCode.ServiceUnavailable);
        }
    }

    private static async Task<NetworkManagementApiResult<T>> ReadAsync<T>(HttpResponseMessage response, CancellationToken ct)
    {
        if (response.IsSuccessStatusCode)
        {
            var value = await response.Content.ReadFromJsonAsync<T>(ct);
            return new(value, value is null ? HttpStatusCode.ServiceUnavailable : HttpStatusCode.OK);
        }

        var message = await ErrorMessageAsync(response, ct);
        return new(default, response.StatusCode, message);
    }

    private static async Task<string?> ErrorMessageAsync(HttpResponseMessage response, CancellationToken ct)
    {
        try
        {
            var document = await response.Content.ReadFromJsonAsync<JsonElement>(ct);
            return document.ValueKind == JsonValueKind.Object && document.TryGetProperty("message", out var message)
                ? message.GetString()
                : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }
}
