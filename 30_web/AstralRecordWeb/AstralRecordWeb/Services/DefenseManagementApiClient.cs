using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using AstralRecordWeb.Models;

namespace AstralRecordWeb.Services;

public sealed record DefenseApiResult<T>(T? Value, HttpStatusCode Status, string? ErrorMessage = null)
{
    public bool Succeeded => Status == HttpStatusCode.OK && Value is not null;
}

public sealed class DefenseManagementApiClient(HttpClient httpClient, ILogger<DefenseManagementApiClient> logger)
{
    public Task<DefenseApiResult<DefenseChannelState>> GetChannelAsync(Guid actor, string serverId, CancellationToken ct) =>
        SendAsync<DefenseChannelState>(HttpMethod.Get, ChannelPath(actor, serverId), null, ct);

    public Task<DefenseApiResult<IReadOnlyList<DefenseRun>>> GetRunsAsync(Guid actor, string serverId, CancellationToken ct) =>
        SendAsync<IReadOnlyList<DefenseRun>>(HttpMethod.Get, ChannelPath(serverId) + "/runs?actor_user_uuid=" + actor.ToString("D") + "&limit=20", null, ct);

    public Task<DefenseApiResult<DefenseChannelState>> SaveAsync(Guid actor, string serverId, DefenseSettingsUpdate update, CancellationToken ct) =>
        SendAsync<DefenseChannelState>(HttpMethod.Put, ChannelPath(serverId) + ActorQuery(actor), update, ct);

    public Task<DefenseApiResult<DefenseRun>> StartAsync(Guid actor, string serverId, DefenseStartRequest request, CancellationToken ct) =>
        SendAsync<DefenseRun>(HttpMethod.Post, ChannelPath(serverId) + "/start" + ActorQuery(actor), request, ct);

    public Task<DefenseApiResult<DefenseRun>> StopAsync(Guid actor, string serverId, DefenseStopRequest request, CancellationToken ct) =>
        SendAsync<DefenseRun>(HttpMethod.Post, ChannelPath(serverId) + "/stop" + ActorQuery(actor), request, ct);

    private static string ChannelPath(Guid actor, string serverId) => ChannelPath(serverId) + ActorQuery(actor);
    private static string ChannelPath(string serverId) => "/api/defense/admin/channels/" + Uri.EscapeDataString(serverId);
    private static string ActorQuery(Guid actor) => "?actor_user_uuid=" + actor.ToString("D");

    private async Task<DefenseApiResult<T>> SendAsync<T>(HttpMethod method, string path, object? body, CancellationToken ct)
    {
        try
        {
            using var request = new HttpRequestMessage(method, path);
            if (body is not null) request.Content = JsonContent.Create(body);
            using var response = await httpClient.SendAsync(request, ct);
            if (response.IsSuccessStatusCode)
            {
                var value = await response.Content.ReadFromJsonAsync<T>(ct);
                return new(value, value is null ? HttpStatusCode.ServiceUnavailable : HttpStatusCode.OK);
            }

            string? message = null;
            try
            {
                var error = await response.Content.ReadFromJsonAsync<JsonElement>(ct);
                if (error.ValueKind == JsonValueKind.Object && error.TryGetProperty("message", out var property))
                    message = property.GetString();
            }
            catch (JsonException) { }
            catch (NotSupportedException) { }
            return new(default, response.StatusCode, message);
        }
        catch (Exception ex) when (!ct.IsCancellationRequested && ex is HttpRequestException or TaskCanceledException or JsonException or NotSupportedException)
        {
            logger.LogWarning(ex, "Defense management API request failed: {Method}", method);
            return new(default, HttpStatusCode.ServiceUnavailable);
        }
    }
}
