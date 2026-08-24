namespace AstralRecordApi.Data.Entities;

public enum ReleaseNotificationStatus
{
    Pending = 0,
    Processing = 1,
    Sent = 2,
    Failed = 3,
}

public sealed class ReleaseNotificationOutboxEntity
{
    public Guid OutboxId { get; set; }
    public Guid ReleaseNoteId { get; set; }
    public string Channel { get; set; } = string.Empty;
    public ReleaseNotificationStatus Status { get; set; }
    public int AttemptCount { get; set; }
    public DateTime NextAttemptAtUtc { get; set; }
    public DateTime? LeaseUntilUtc { get; set; }
    public Guid? LeaseToken { get; set; }
    public DateTime? SentAtUtc { get; set; }
    public string? DiscordMessageId { get; set; }
    public string? LastError { get; set; }
    public DateTime CreatedAtUtc { get; set; }
    public DateTime UpdatedAtUtc { get; set; }

    public ReleaseNoteEntity ReleaseNote { get; set; } = null!;
}
