using System.ComponentModel.DataAnnotations;

namespace AstralRecordApi.Models;

public sealed class ReleaseNotePublishRequest
{
    [Required, StringLength(80)]
    public string Slug { get; init; } = string.Empty;

    [Required, StringLength(64)]
    public string Version { get; init; } = string.Empty;

    [Required, StringLength(200)]
    public string Title { get; init; } = string.Empty;

    [StringLength(500)]
    public string Summary { get; init; } = string.Empty;

    [Required, StringLength(512)]
    public string ReleaseUrl { get; init; } = string.Empty;

    [Required, StringLength(260)]
    public string SourcePath { get; init; } = string.Empty;

    [Required, StringLength(64, MinimumLength = 64)]
    public string ContentSha256 { get; init; } = string.Empty;

    public DateTimeOffset PublishedAt { get; init; }
    public bool NotifyDiscord { get; init; }
}

public sealed class ReleaseNotePublishResponse
{
    public string Slug { get; init; } = string.Empty;
    public string ReleaseUrl { get; init; } = string.Empty;
    public bool Created { get; init; }
    public bool NotificationQueued { get; init; }
}

public sealed class ReleaseNotificationRetryResponse
{
    public string Slug { get; init; } = string.Empty;
    public bool NotificationQueued { get; init; }
}
