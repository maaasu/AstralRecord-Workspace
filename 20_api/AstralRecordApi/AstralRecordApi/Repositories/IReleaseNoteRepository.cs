using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public sealed record ReleaseNotePublicationResult(
    ReleaseNoteEntity ReleaseNote,
    bool Created,
    bool NotificationQueued);

public interface IReleaseNoteRepository
{
    Task<ReleaseNotePublicationResult> PublishAsync(
        ReleaseNotePublishRequest request,
        string notificationChannel,
        CancellationToken cancellationToken);

    Task<ReleaseNotificationOutboxEntity?> ClaimNextNotificationAsync(
        string notificationChannel,
        DateTime nowUtc,
        CancellationToken cancellationToken);

    Task<bool> MarkSentAsync(
        Guid outboxId,
        Guid leaseToken,
        string? discordMessageId,
        DateTime nowUtc,
        CancellationToken cancellationToken);

    Task<bool> MarkFailedAsync(
        Guid outboxId,
        Guid leaseToken,
        string error,
        bool retryable,
        TimeSpan? retryAfter,
        DateTime nowUtc,
        CancellationToken cancellationToken);

    Task<bool> RetryNotificationAsync(
        string slug,
        string notificationChannel,
        DateTime nowUtc,
        CancellationToken cancellationToken);
}
