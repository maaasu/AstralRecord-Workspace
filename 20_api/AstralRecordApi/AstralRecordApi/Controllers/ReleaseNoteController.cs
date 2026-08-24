using System.Text.RegularExpressions;
using AstralRecordApi.Models;
using AstralRecordApi.Options;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Options;

namespace AstralRecordApi.Controllers;

/// <summary>Web公開済みリリースノートの登録とDiscord通知再試行を管理するAPI</summary>
[ApiController]
[Route("api/release-notes")]
public sealed partial class ReleaseNoteController(
    IReleaseNoteRepository repository,
    IOptions<ReleaseNoteOptions> options) : ControllerBase
{
    /// <summary>公開済みリリースノートを登録し、Discord通知をOutboxへ追加します。</summary>
    [HttpPost("publish")]
    [ProducesResponseType<ReleaseNotePublishResponse>(StatusCodes.Status200OK)]
    public async Task<ActionResult<ReleaseNotePublishResponse>> Publish(
        [FromBody] ReleaseNotePublishRequest request,
        CancellationToken cancellationToken)
    {
        var validationError = Validate(request, options.Value);
        if (validationError is not null)
            return BadRequest(new { error = validationError });

        var result = await repository.PublishAsync(
            request,
            options.Value.NotificationChannel,
            cancellationToken);

        return Ok(new ReleaseNotePublishResponse
        {
            Slug = result.ReleaseNote.Slug,
            ReleaseUrl = result.ReleaseNote.ReleaseUrl,
            Created = result.Created,
            NotificationQueued = result.NotificationQueued,
        });
    }

    /// <summary>指定リリースノートのDiscord通知を手動再試行キューへ戻します。</summary>
    [HttpPost("{slug}/retry-notification")]
    [ProducesResponseType<ReleaseNotificationRetryResponse>(StatusCodes.Status200OK)]
    public async Task<ActionResult<ReleaseNotificationRetryResponse>> RetryNotification(
        string slug,
        CancellationToken cancellationToken)
    {
        if (!SlugRegex().IsMatch(slug))
            return BadRequest(new { error = "slug is invalid." });

        var queued = await repository.RetryNotificationAsync(
            slug,
            options.Value.NotificationChannel,
            DateTime.UtcNow,
            cancellationToken);

        return queued
            ? Ok(new ReleaseNotificationRetryResponse { Slug = slug, NotificationQueued = true })
            : NotFound();
    }

    private static string? Validate(ReleaseNotePublishRequest request, ReleaseNoteOptions options)
    {
        if (!SlugRegex().IsMatch(request.Slug.Trim()))
            return "slug is invalid.";

        if (request.PublishedAt == default)
            return "publishedAt is required.";

        if (!Sha256Regex().IsMatch(request.ContentSha256.Trim()))
            return "contentSha256 must be a SHA-256 hex string.";

        if (Path.IsPathRooted(request.SourcePath) || request.SourcePath.Contains("..", StringComparison.Ordinal))
            return "sourcePath must be a repository-relative path.";

        var publicBaseUrl = options.PublicBaseUrl.TrimEnd('/');
        var expectedUrl = $"{publicBaseUrl}/releases/{request.Slug.Trim().ToLowerInvariant()}";
        if (!Uri.TryCreate(request.ReleaseUrl.Trim(), UriKind.Absolute, out var releaseUri)
            || releaseUri.Scheme != Uri.UriSchemeHttps
            || !string.Equals(request.ReleaseUrl.TrimEnd('/'), expectedUrl, StringComparison.OrdinalIgnoreCase))
            return "releaseUrl is invalid.";

        return null;
    }

    [GeneratedRegex("^[a-z0-9]+(?:-[a-z0-9]+)*$")]
    private static partial Regex SlugRegex();

    [GeneratedRegex("^[0-9a-fA-F]{64}$")]
    private static partial Regex Sha256Regex();
}
