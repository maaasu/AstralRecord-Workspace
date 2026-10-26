using System.Net.Http.Headers;
using Microsoft.Extensions.Options;
using SkillTreeEditor.Server.Options;

namespace SkillTreeEditor.Server.Services;

public sealed class WorldPlacementProxyService(HttpClient client, IOptions<EditorOptions> options)
{
    public async Task<IResult> ForwardAsync(HttpContext context, bool write, CancellationToken token)
    {
        var settings = options.Value;
        if (string.IsNullOrWhiteSpace(settings.WorldPlacementApiBaseUrl)
            || string.IsNullOrWhiteSpace(settings.WorldPlacementApiKey)
            || write && string.IsNullOrWhiteSpace(settings.WorldPlacementWriteKey))
            return Results.Problem(title: "Dev 配置 API の接続設定がありません。", statusCode: 503);
        if (!Uri.TryCreate(settings.WorldPlacementApiBaseUrl.TrimEnd('/') + "/api/world-placements/dev", UriKind.Absolute, out var destination)
            || destination.Scheme is not ("http" or "https"))
            return Results.Problem(title: "Dev 配置 API の URL が正しくありません。", statusCode: 503);

        using var request = new HttpRequestMessage(write ? HttpMethod.Put : HttpMethod.Get, destination);
        request.Headers.Add("X-API-Key", settings.WorldPlacementApiKey);
        if (write)
        {
            request.Headers.Add("X-World-Placement-Key", settings.WorldPlacementWriteKey);
            request.Content = new StreamContent(context.Request.Body);
            request.Content.Headers.ContentType = new MediaTypeHeaderValue("application/json");
        }
        try
        {
            using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, token);
            var body = await response.Content.ReadAsStringAsync(token);
            return Results.Text(body, response.Content.Headers.ContentType?.ToString() ?? "application/json", statusCode: (int)response.StatusCode);
        }
        catch (HttpRequestException)
        {
            return Results.Problem(title: "Dev 配置 API に接続できません。", statusCode: 502);
        }
    }
}
