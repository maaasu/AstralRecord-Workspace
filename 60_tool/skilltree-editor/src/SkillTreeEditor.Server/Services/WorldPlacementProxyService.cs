using System.Net.Http.Headers;
using System.Text.Json;
using Microsoft.Extensions.Options;
using SkillTreeEditor.Server.Options;

namespace SkillTreeEditor.Server.Services;

public sealed class WorldPlacementProxyService(HttpClient client, IOptions<EditorOptions> options)
{
    public async Task<IResult> ForwardAsync(HttpContext context, bool write, CancellationToken token, string? placementId = null)
    {
        if (placementId is not null && (placementId.Length is 0 or > 128 || placementId is "." or ".."
            || placementId.Any(character => !char.IsAsciiLetterOrDigit(character) && character is not ('_' or '-' or '.' or ':'))))
            return Results.Problem(title: "配置 ID が正しくありません。", statusCode: 400);

        var settings = options.Value;
        if (string.IsNullOrWhiteSpace(settings.WorldPlacementApiBaseUrl)
            || string.IsNullOrWhiteSpace(settings.WorldPlacementApiKey)
            || write && string.IsNullOrWhiteSpace(settings.WorldPlacementWriteKey))
            return Results.Problem(title: "Dev 配置 API の接続設定がありません。", statusCode: 503);
        var devUrl = settings.WorldPlacementApiBaseUrl.TrimEnd('/') + "/api/world-placements/dev";
        if (!Uri.TryCreate(devUrl, UriKind.Absolute, out var devDestination) || devDestination.Scheme is not ("http" or "https"))
            return Results.Problem(title: "Dev 配置 API の URL が正しくありません。", statusCode: 503);

        try
        {
            if (write && placementId is not null)
            {
                var failure = await CheckWaystoneEditAsync(context.Request, devDestination, settings.WorldPlacementApiKey, placementId, token);
                if (failure is not null) return failure;
            }

            var destination = placementId is null ? devDestination : new Uri(devUrl + "/" + Uri.EscapeDataString(placementId));
            using var request = new HttpRequestMessage(write ? HttpMethod.Put : HttpMethod.Get, destination);
            request.Headers.Add("X-API-Key", settings.WorldPlacementApiKey);
            if (write)
            {
                request.Headers.Add("X-World-Placement-Key", settings.WorldPlacementWriteKey);
                request.Content = new StreamContent(context.Request.Body);
                request.Content.Headers.ContentType = new MediaTypeHeaderValue("application/json");
            }
            using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, token);
            return await ForwardResponseAsync(response, token);
        }
        catch (HttpRequestException)
        {
            return Results.Problem(title: "Dev 配置 API に接続できません。", statusCode: 502);
        }
    }

    private async Task<IResult?> CheckWaystoneEditAsync(HttpRequest browserRequest, Uri destination, string readKey,
        string placementId, CancellationToken token)
    {
        browserRequest.EnableBuffering();
        string revision;
        WaystoneIdentity proposed;
        try
        {
            using var document = await JsonDocument.ParseAsync(browserRequest.Body, cancellationToken: token);
            var root = document.RootElement;
            if (!HasUniqueProperties(root) || ReadString(root, "expectedRevision") is not { Length: > 0 } expectedRevision
                || !root.TryGetProperty("placement", out var placement) || ReadString(placement, "placementId") != placementId
                || ReadWaystoneIdentity(placement) is not { } identity)
                return Results.Problem(title: "編集する道標の設定が正しくありません。", statusCode: 400);
            revision = expectedRevision;
            proposed = identity;
        }
        catch (JsonException)
        {
            return Results.Problem(title: "編集する道標の JSON が正しくありません。", statusCode: 400);
        }

        using var lookup = new HttpRequestMessage(HttpMethod.Get, destination);
        lookup.Headers.Add("X-API-Key", readKey);
        lookup.Headers.CacheControl = new CacheControlHeaderValue { NoCache = true };
        using var response = await client.SendAsync(lookup, HttpCompletionOption.ResponseHeadersRead, token);
        if (!response.IsSuccessStatusCode) return await ForwardResponseAsync(response, token);

        try
        {
            using var snapshot = await JsonDocument.ParseAsync(await response.Content.ReadAsStreamAsync(token), cancellationToken: token);
            var root = snapshot.RootElement;
            if (ReadString(root, "revision") is not { Length: > 0 } currentRevision
                || !root.TryGetProperty("placements", out var placements) || placements.ValueKind != JsonValueKind.Array)
                return InvalidSnapshot();
            if (revision != currentRevision)
                return Results.Problem(title: "Dev 配置が先に更新されました。", statusCode: 409);

            var current = placements.EnumerateArray().FirstOrDefault(placement => ReadString(placement, "placementId") == placementId);
            if (current.ValueKind == JsonValueKind.Undefined)
                return Results.Problem(title: "編集対象の道標が見つかりません。", statusCode: 404);
            if (ReadWaystoneIdentity(current) != proposed)
                return Results.Problem(title: "道標 ID、ワールド、作成時の情報は変更できません。", statusCode: 400);
        }
        catch (JsonException)
        {
            return InvalidSnapshot();
        }

        // Preserve the checked revision and the exact body. The Dev API checks the revision again under its write lock.
        browserRequest.Body.Position = 0;
        return null;
    }

    private static async Task<IResult> ForwardResponseAsync(HttpResponseMessage response, CancellationToken token)
    {
        var body = await response.Content.ReadAsStringAsync(token);
        return Results.Text(body, response.Content.Headers.ContentType?.ToString() ?? "application/json", statusCode: (int)response.StatusCode);
    }

    private static IResult InvalidSnapshot() => Results.Problem(title: "Dev 配置 API の応答形式を確認してください。", statusCode: 502);

    private static string? ReadString(JsonElement element, string name) => element.ValueKind == JsonValueKind.Object
        && element.TryGetProperty(name, out var property) && property.ValueKind == JsonValueKind.String ? property.GetString() : null;

    private static bool HasUniqueProperties(JsonElement element)
    {
        if (element.ValueKind != JsonValueKind.Object) return false;
        var names = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        return element.EnumerateObject().All(property => names.Add(property.Name));
    }

    private static bool ReadAuditField(JsonElement waystone, string name, out string? value)
    {
        value = ReadString(waystone, name);
        return value is not null || waystone.TryGetProperty(name, out var property) && property.ValueKind == JsonValueKind.Null;
    }

    private static WaystoneIdentity? ReadWaystoneIdentity(JsonElement placement)
    {
        if (!HasUniqueProperties(placement) || ReadString(placement, "kind") != "waystone"
            || ReadString(placement, "worldId") is not { Length: > 0 } worldId
            || ReadString(placement, "worldName") is not { Length: > 0 } worldName
            || !placement.TryGetProperty("waystone", out var waystone) || !HasUniqueProperties(waystone)
            || ReadString(waystone, "id") is not { Length: > 0 } id
            || !ReadAuditField(waystone, "createdAt", out var createdAt) || !ReadAuditField(waystone, "createdBy", out var createdBy))
            return null;
        return new WaystoneIdentity(worldId, worldName, id, createdAt, createdBy);
    }

    private sealed record WaystoneIdentity(string WorldId, string WorldName, string Id, string? CreatedAt, string? CreatedBy);
}
