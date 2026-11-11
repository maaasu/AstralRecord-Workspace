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
        return await SendAsync(request, HttpStatusCode.Accepted, ct);
    }

    public async Task<(HttpStatusCode Code, BuilderSchematicUpload? Upload)> GetAsync(
        Guid playerId, Guid uploadId, CancellationToken ct)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, $"{UploadsPath}/{uploadId:D}?userUuid={playerId:D}");
        return await SendAsync(request, HttpStatusCode.OK, ct);
    }

    private async Task<(HttpStatusCode Code, BuilderSchematicUpload? Upload)> SendAsync(
        HttpRequestMessage request, HttpStatusCode successCode, CancellationToken ct)
    {
        try
        {
            using var response = await httpClient.SendAsync(request, ct);
            return response.StatusCode == successCode
                ? (response.StatusCode, await response.Content.ReadFromJsonAsync<BuilderSchematicUpload>(ct))
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
