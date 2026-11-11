using System.Net;
using System.Net.Http.Json;
using System.Text.Json;

namespace AstralRecordWeb.Services;

public sealed class BuilderSchematicApiClient(HttpClient httpClient, ILogger<BuilderSchematicApiClient> logger)
{
    private const string UploadsPath = "/api/builder-schematics/uploads";

    public async Task<(HttpStatusCode Code, BuilderSchematicUpload? Upload)> UploadAsync(
        Guid playerId, string token, IFormFile file, CancellationToken ct)
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, UploadsPath);
        using var multipart = new MultipartFormDataContent();
        multipart.Add(new StringContent(playerId.ToString("D")), "userUuid");
        multipart.Add(new StringContent(token), "token");
        multipart.Add(new StreamContent(file.OpenReadStream()), "file", file.FileName);
        request.Content = multipart;
        return await SendAsync<BuilderSchematicUpload>(request, HttpStatusCode.Accepted, ct);
    }

    public async Task<(HttpStatusCode Code, BuilderSchematicUpload? Upload)> GetAsync(
        Guid playerId, Guid uploadId, CancellationToken ct)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, $"{UploadsPath}/{uploadId:D}?userUuid={playerId:D}");
        return await SendAsync<BuilderSchematicUpload>(request, HttpStatusCode.OK, ct);
    }

    public async Task<BuilderSchematicUpload[]?> ListAsync(Guid playerId, CancellationToken ct)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, $"{UploadsPath}?userUuid={playerId:D}");
        var (_, uploads) = await SendAsync<BuilderSchematicUpload[]>(request, HttpStatusCode.OK, ct);
        return uploads;
    }

    public async Task<HttpStatusCode> DeleteAsync(Guid playerId, Guid uploadId, CancellationToken ct)
    {
        using var request = new HttpRequestMessage(HttpMethod.Delete, $"{UploadsPath}/{uploadId:D}?userUuid={playerId:D}");
        var (code, _) = await SendAsync<object>(request, null, ct);
        return code;
    }

    private async Task<(HttpStatusCode Code, T? Payload)> SendAsync<T>(
        HttpRequestMessage request, HttpStatusCode? bodyStatus, CancellationToken ct) where T : class
    {
        try
        {
            using var response = await httpClient.SendAsync(request, ct);
            return response.StatusCode == bodyStatus
                ? (response.StatusCode, await response.Content.ReadFromJsonAsync<T>(ct))
                : (response.StatusCode, null);
        }
        catch (Exception ex) when (!ct.IsCancellationRequested &&
            ex is HttpRequestException or TaskCanceledException or JsonException or NotSupportedException)
        {
            logger.LogWarning("Builder schematic API {Method} request failed ({FailureType}).", request.Method, ex.GetType().Name);
            return (HttpStatusCode.ServiceUnavailable, null);
        }
    }
}

public sealed record BuilderSchematicUpload(Guid Id, string? Name, string? Status, string? Error);
