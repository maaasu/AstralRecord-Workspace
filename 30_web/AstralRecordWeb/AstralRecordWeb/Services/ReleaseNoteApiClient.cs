using System.Net.Http.Json;
using AstralRecordWeb.Models;

namespace AstralRecordWeb.Services;

public sealed class ReleaseNoteApiClient(
    HttpClient httpClient,
    ILogger<ReleaseNoteApiClient> logger)
{
    public async Task<bool> PublishAsync(
        ReleaseNoteDocument document,
        CancellationToken cancellationToken)
    {
        var request = new ReleaseNotePublishRequest
        {
            Slug = document.Slug,
            Version = document.Version,
            Title = document.Title,
            Summary = document.Summary,
            PublishedAt = document.PublishedAt,
            SourcePath = document.SourcePath,
            ContentSha256 = document.ContentSha256,
            ReleaseUrl = document.ReleaseUrl,
            NotifyDiscord = document.NotifyDiscord,
        };

        using var response = await httpClient.PostAsJsonAsync(
            "api/release-notes/publish",
            request,
            cancellationToken);
        if (response.IsSuccessStatusCode)
            return true;

        var error = await response.Content.ReadAsStringAsync(cancellationToken);
        logger.LogWarning(
            "Release note synchronization failed for {Slug}. StatusCode={StatusCode}, Response={Response}",
            document.Slug,
            (int)response.StatusCode,
            error[..Math.Min(error.Length, 500)]);
        return false;
    }
}
