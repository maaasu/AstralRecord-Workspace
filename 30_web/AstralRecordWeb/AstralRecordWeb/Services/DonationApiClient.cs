using System.Net;
using System.Text.Json;
using AstralRecordWeb.Models;

namespace AstralRecordWeb.Services;

public sealed record DonationApiResult<T>(T? Value, HttpStatusCode Status, string? ErrorMessage = null)
{
    public bool Succeeded => Status == HttpStatusCode.OK && Value is not null;
}

public sealed class DonationApiClient(HttpClient client)
{
    public Task<DonationApiResult<DonationList>> ListAsync(Guid actor, bool admin, int page, CancellationToken ct) =>
        SendAsync<DonationList>(HttpMethod.Get, admin ? "admin" : "", actor, null, ct, $"&page={page}&page_size=20");
    public Task<DonationApiResult<DonationRequest>> GetAsync(Guid actor, Guid id, CancellationToken ct) =>
        SendAsync<DonationRequest>(HttpMethod.Get, id.ToString(), actor, null, ct);
    public Task<DonationApiResult<DonationRequest>> CreateAsync(Guid actor, Guid operationId, int amount, IReadOnlyList<DonationEntry> entries, CancellationToken ct) =>
        SendAsync<DonationRequest>(HttpMethod.Post, "", actor, new { operationId, declaredAmount = amount, termsVersion = "2026-09-26", entries }, ct);
    public Task<DonationApiResult<DonationRequest>> ActAsync(Guid actor, Guid id, string action, object body, CancellationToken ct) =>
        SendAsync<DonationRequest>(HttpMethod.Post, $"{id:D}/{action}", actor, body, ct);
    public Task<DonationApiResult<DiscordLink>> LinkAsync(Guid actor, string accessToken, string refreshToken, CancellationToken ct) =>
        SendAsync<DiscordLink>(HttpMethod.Post, "discord", actor, new { accessToken, refreshToken }, ct);

    private async Task<DonationApiResult<T>> SendAsync<T>(HttpMethod method, string path, Guid actor, object? body, CancellationToken ct, string query = "")
    {
        try
        {
            using var request = new HttpRequestMessage(method, $"/api/donations{(path.Length == 0 ? "" : "/" + path)}?actor_user_uuid={actor:D}{query}");
            if (body is not null) request.Content = JsonContent.Create(body);
            using var response = await client.SendAsync(request, ct);
            if (response.IsSuccessStatusCode)
            {
                var value = await response.Content.ReadFromJsonAsync<T>(ct);
                return new(value, value is null ? HttpStatusCode.ServiceUnavailable : HttpStatusCode.OK);
            }
            string? message = null;
            try
            {
                var error = await response.Content.ReadFromJsonAsync<JsonElement>(ct);
                if (error.ValueKind == JsonValueKind.Object && error.TryGetProperty("message", out var field) && field.ValueKind == JsonValueKind.String)
                    message = field.GetString();
            }
            catch (JsonException) { }
            return new(default, response.StatusCode, message);
        }
        catch (Exception ex) when (!ct.IsCancellationRequested && ex is HttpRequestException or TaskCanceledException or JsonException or NotSupportedException)
        {
            // Never log request bodies: these contain gift codes and OAuth credentials.
            return new(default, HttpStatusCode.ServiceUnavailable);
        }
    }
}
