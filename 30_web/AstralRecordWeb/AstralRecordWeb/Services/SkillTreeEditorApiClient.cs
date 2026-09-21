using System.Net;
using System.Text.Json;
using AstralRecordWeb.Models;

namespace AstralRecordWeb.Services;

/// <summary>スキルツリーの変更要求を仲介する。actorは認証済みCookieからのみ取得する。</summary>
public sealed class SkillTreeEditorApiClient(HttpClient httpClient, ILogger<SkillTreeEditorApiClient> logger)
{
    public Task<ProfileApiResult<JsonElement>> GetStateAsync(Guid actor, Guid accountId, CancellationToken ct) =>
        SendAsync(HttpMethod.Get, $"/api/skilltree/editor/{accountId:D}?actor_user_id={actor:D}", null, ct);

    public Task<ProfileApiResult<JsonElement>> SubmitAsync(Guid actor, Guid accountId, SkillTreeOperationInput input, CancellationToken ct) =>
        SendAsync(HttpMethod.Post, $"/api/skilltree/editor/{accountId:D}/operations", new
        {
            input.OperationId, ActorUserId = actor, input.TargetServerId, input.ExpectedDefinitionGenerationId,
            input.ExpectedPlayerStateVersion, input.Action, input.NodeId, input.SourceClassId, input.Changes,
        }, ct);

    public Task<ProfileApiResult<JsonElement>> GetOperationAsync(Guid actor, Guid accountId, Guid operationId, CancellationToken ct) =>
        SendAsync(HttpMethod.Get, $"/api/skilltree/editor/{accountId:D}/operations/{operationId:D}?actor_user_id={actor:D}", null, ct);

    public Task<ProfileApiResult<JsonElement>> CancelAsync(Guid actor, Guid accountId, Guid operationId, CancellationToken ct) =>
        SendAsync(HttpMethod.Delete, $"/api/skilltree/editor/{accountId:D}/operations/{operationId:D}?actor_user_id={actor:D}", null, ct);

    private async Task<ProfileApiResult<JsonElement>> SendAsync(HttpMethod method, string path, object? body, CancellationToken ct)
    {
        try
        {
            using var request = new HttpRequestMessage(method, path);
            if (body is not null) request.Content = JsonContent.Create(body);
            using var response = await httpClient.SendAsync(request, ct);
            // Error payloads are deliberately not forwarded: they may contain internal identifiers.
            if (!response.IsSuccessStatusCode) return new(default, response.StatusCode);
            var value = await response.Content.ReadFromJsonAsync<JsonElement>(ct);
            return value.ValueKind == JsonValueKind.Object ? new(value, HttpStatusCode.OK) : new(default, HttpStatusCode.ServiceUnavailable);
        }
        catch (Exception ex) when (!ct.IsCancellationRequested && ex is HttpRequestException or TaskCanceledException or JsonException or NotSupportedException)
        {
            logger.LogWarning(ex, "Skill tree editor API request failed.");
            return new(default, HttpStatusCode.ServiceUnavailable);
        }
    }
}
