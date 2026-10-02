using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using AstralRecordWeb.Models;

namespace AstralRecordWeb.Services;

public sealed record PlayerAdminApiResult<T>(T? Value, HttpStatusCode Status, string? ErrorMessage = null)
{
    public bool Succeeded => (int)Status is >= 200 and < 300 && Value is not null;
}

public sealed class PlayerAdminApiClient(HttpClient client, ILogger<PlayerAdminApiClient> logger)
{
    public Task<PlayerAdminApiResult<PlayerAdminEditSession>> GetSessionAsync(Guid actor, Guid accountId, CancellationToken ct) =>
        SendAsync<PlayerAdminEditSession>(HttpMethod.Get, $"/api/player-admin/accounts/{accountId:D}/edit-session{Actor(actor)}", null, ct);

    public Task<PlayerAdminApiResult<PlayerAdminEditSession>> GetSessionByIdAsync(Guid actor, Guid editSessionId, CancellationToken ct) =>
        SendAsync<PlayerAdminEditSession>(HttpMethod.Get, $"/api/player-admin/edit-sessions/{editSessionId:D}{Actor(actor)}", null, ct);

    public Task<PlayerAdminApiResult<PlayerAdminEditSession>> StartAsync(Guid actor, Guid accountId, Guid editSessionId, string reason, CancellationToken ct) =>
        SendAsync<PlayerAdminEditSession>(HttpMethod.Post, $"/api/player-admin/accounts/{accountId:D}/edit-sessions{Actor(actor)}", new { editSessionId, reason }, ct);

    public Task<PlayerAdminApiResult<PlayerAdminEditSession>> RefreshAsync(Guid actor, Guid editSessionId, CancellationToken ct) =>
        SendAsync<PlayerAdminEditSession>(HttpMethod.Post, $"/api/player-admin/edit-sessions/{editSessionId:D}/refresh{Actor(actor)}", new { }, ct);

    public Task<PlayerAdminApiResult<PlayerAdminEditor>> GetEditorAsync(Guid actor, Guid editSessionId, CancellationToken ct) =>
        SendAsync<PlayerAdminEditor>(HttpMethod.Get, $"/api/player-admin/edit-sessions/{editSessionId:D}/editor{Actor(actor)}", null, ct);

    public Task<PlayerAdminApiResult<PlayerAdminEditSession>> ApplyAsync(Guid actor, Guid editSessionId, Guid operationId,
        long expectedRevision, string expectedStateHash, string expectedCatalogVersion, int? level, string? classId, IReadOnlyList<PlayerAdminInventoryChange> inventoryChanges, CancellationToken ct) =>
        SendAsync<PlayerAdminEditSession>(HttpMethod.Post, $"/api/player-admin/edit-sessions/{editSessionId:D}/apply{Actor(actor)}",
            new { operationId, expectedRevision, expectedStateHash, expectedCatalogVersion, level, classId, inventoryChanges }, ct);

    public Task<PlayerAdminApiResult<PlayerAdminEditSession>> CancelAsync(Guid actor, Guid editSessionId, Guid operationId, long expectedRevision, CancellationToken ct) =>
        SendAsync<PlayerAdminEditSession>(HttpMethod.Post, $"/api/player-admin/edit-sessions/{editSessionId:D}/cancel{Actor(actor)}",
            new { operationId, expectedRevision }, ct);

    private static string Actor(Guid actor) => $"?actor_user_uuid={actor:D}";

    private async Task<PlayerAdminApiResult<T>> SendAsync<T>(HttpMethod method, string path, object? body, CancellationToken ct)
    {
        try
        {
            using var request = new HttpRequestMessage(method, path);
            if (body is not null) request.Content = JsonContent.Create(body);
            using var response = await client.SendAsync(request, ct);
            if (!response.IsSuccessStatusCode)
            {
                string? message = null;
                try
                {
                    using var error = await response.Content.ReadFromJsonAsync<JsonDocument>(ct);
                    if (error?.RootElement.TryGetProperty("message", out var errorValue) == true)
                        message = errorValue.GetString();
                }
                catch (JsonException) { }
                return new(default, response.StatusCode, message);
            }
            var value = await response.Content.ReadFromJsonAsync<T>(ct);
            return new(value, value is null ? HttpStatusCode.ServiceUnavailable : response.StatusCode);
        }
        catch (Exception ex) when (!ct.IsCancellationRequested && ex is HttpRequestException or TaskCanceledException or JsonException or NotSupportedException)
        {
            logger.LogWarning(ex, "Player admin API request failed.");
            return new(default, HttpStatusCode.ServiceUnavailable);
        }
    }
}
