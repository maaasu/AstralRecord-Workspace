namespace AstralRecordApi.Data.Entities;

public sealed class ReleaseNoteEntity
{
    public Guid ReleaseNoteId { get; set; }
    public string Slug { get; set; } = string.Empty;
    public string Version { get; set; } = string.Empty;
    public string Title { get; set; } = string.Empty;
    public string Summary { get; set; } = string.Empty;
    public string ReleaseUrl { get; set; } = string.Empty;
    public string SourcePath { get; set; } = string.Empty;
    public string ContentSha256 { get; set; } = string.Empty;
    public DateTime PublishedAtUtc { get; set; }
    public bool IsPublished { get; set; }
    public bool NotifyDiscord { get; set; }
    public DateTime CreatedAtUtc { get; set; }
    public DateTime UpdatedAtUtc { get; set; }

    public ICollection<ReleaseNotificationOutboxEntity> Notifications { get; set; } = [];
}
