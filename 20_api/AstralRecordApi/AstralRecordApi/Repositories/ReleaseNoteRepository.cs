using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using System.Data;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public sealed class ReleaseNoteRepository(AstralRecordDbContext dbContext) : IReleaseNoteRepository
{
    public async Task<ReleaseNotePublicationResult> PublishAsync(
        ReleaseNotePublishRequest request,
        string notificationChannel,
        CancellationToken cancellationToken)
    {
        var executionStrategy = dbContext.Database.CreateExecutionStrategy();
        return await executionStrategy.ExecuteAsync(async () =>
        {
            await using var transaction = await dbContext.Database.BeginTransactionAsync(
                IsolationLevel.Serializable,
                cancellationToken);

            var slug = request.Slug.Trim().ToLowerInvariant();
            var now = DateTime.UtcNow;
            var releaseNote = await dbContext.ReleaseNotes
                .SingleOrDefaultAsync(note => note.Slug == slug, cancellationToken);

            var created = releaseNote is null;
            if (releaseNote is null)
            {
                releaseNote = new ReleaseNoteEntity
                {
                    ReleaseNoteId = Guid.NewGuid(),
                    Slug = slug,
                    CreatedAtUtc = now,
                };
                await dbContext.ReleaseNotes.AddAsync(releaseNote, cancellationToken);
            }

            releaseNote.Version = request.Version.Trim();
            releaseNote.Title = request.Title.Trim();
            releaseNote.Summary = request.Summary?.Trim() ?? string.Empty;
            releaseNote.ReleaseUrl = request.ReleaseUrl.Trim();
            releaseNote.SourcePath = request.SourcePath.Trim();
            releaseNote.ContentSha256 = request.ContentSha256.Trim().ToUpperInvariant();
            releaseNote.PublishedAtUtc = request.PublishedAt.UtcDateTime;
            releaseNote.IsPublished = true;
            releaseNote.NotifyDiscord = request.NotifyDiscord;
            releaseNote.UpdatedAtUtc = now;

            var notificationQueued = false;
            if (request.NotifyDiscord && !await dbContext.ReleaseNotificationOutboxes
                    .AnyAsync(outbox =>
                        outbox.ReleaseNoteId == releaseNote.ReleaseNoteId
                        && outbox.Channel == notificationChannel,
                        cancellationToken))
            {
                await dbContext.ReleaseNotificationOutboxes.AddAsync(new ReleaseNotificationOutboxEntity
                {
                    OutboxId = Guid.NewGuid(),
                    ReleaseNoteId = releaseNote.ReleaseNoteId,
                    Channel = notificationChannel,
                    Status = ReleaseNotificationStatus.Pending,
                    AttemptCount = 0,
                    NextAttemptAtUtc = releaseNote.PublishedAtUtc > now
                        ? releaseNote.PublishedAtUtc
                        : now,
                    CreatedAtUtc = now,
                    UpdatedAtUtc = now,
                }, cancellationToken);
                notificationQueued = true;
            }

            await dbContext.SaveChangesAsync(cancellationToken);
            await transaction.CommitAsync(cancellationToken);

            return new ReleaseNotePublicationResult(releaseNote, created, notificationQueued);
        });
    }

    public async Task<ReleaseNotificationOutboxEntity?> ClaimNextNotificationAsync(
        string notificationChannel,
        DateTime nowUtc,
        CancellationToken cancellationToken)
    {
        var candidateId = await dbContext.ReleaseNotificationOutboxes
            .Where(outbox =>
                outbox.Channel == notificationChannel
                && outbox.ReleaseNote.IsPublished
                && outbox.ReleaseNote.PublishedAtUtc <= nowUtc
                && (((outbox.Status == ReleaseNotificationStatus.Pending
                      || outbox.Status == ReleaseNotificationStatus.Failed)
                     && outbox.NextAttemptAtUtc <= nowUtc)
                    || (outbox.Status == ReleaseNotificationStatus.Processing
                        && outbox.LeaseUntilUtc != null
                        && outbox.LeaseUntilUtc <= nowUtc)))
            .OrderBy(outbox => outbox.NextAttemptAtUtc)
            .Select(outbox => outbox.OutboxId)
            .FirstOrDefaultAsync(cancellationToken);

        if (candidateId == Guid.Empty)
            return null;

        var leaseToken = Guid.NewGuid();
        var leaseUntil = nowUtc.AddMinutes(2);
        var affected = await dbContext.ReleaseNotificationOutboxes
            .Where(outbox =>
                outbox.OutboxId == candidateId
                && outbox.Channel == notificationChannel
                && outbox.ReleaseNote.IsPublished
                && outbox.ReleaseNote.PublishedAtUtc <= nowUtc
                && (((outbox.Status == ReleaseNotificationStatus.Pending
                      || outbox.Status == ReleaseNotificationStatus.Failed)
                     && outbox.NextAttemptAtUtc <= nowUtc)
                    || (outbox.Status == ReleaseNotificationStatus.Processing
                        && outbox.LeaseUntilUtc != null
                        && outbox.LeaseUntilUtc <= nowUtc)))
            .ExecuteUpdateAsync(setters => setters
                .SetProperty(outbox => outbox.Status, ReleaseNotificationStatus.Processing)
                .SetProperty(outbox => outbox.AttemptCount, outbox => outbox.AttemptCount + 1)
                .SetProperty(outbox => outbox.LeaseToken, leaseToken)
                .SetProperty(outbox => outbox.LeaseUntilUtc, leaseUntil)
                .SetProperty(outbox => outbox.UpdatedAtUtc, nowUtc), cancellationToken);

        if (affected != 1)
            return null;

        return await dbContext.ReleaseNotificationOutboxes
            .Include(outbox => outbox.ReleaseNote)
            .AsNoTracking()
            .SingleAsync(outbox => outbox.OutboxId == candidateId && outbox.LeaseToken == leaseToken, cancellationToken);
    }

    public async Task<bool> MarkSentAsync(
        Guid outboxId,
        Guid leaseToken,
        string? discordMessageId,
        DateTime nowUtc,
        CancellationToken cancellationToken)
        => await dbContext.ReleaseNotificationOutboxes
            .Where(outbox =>
                outbox.OutboxId == outboxId
                && outbox.Status == ReleaseNotificationStatus.Processing
                && outbox.LeaseToken == leaseToken)
            .ExecuteUpdateAsync(setters => setters
                .SetProperty(outbox => outbox.Status, ReleaseNotificationStatus.Sent)
                .SetProperty(outbox => outbox.SentAtUtc, nowUtc)
                .SetProperty(outbox => outbox.DiscordMessageId, discordMessageId)
                .SetProperty(outbox => outbox.LastError, (string?)null)
                .SetProperty(outbox => outbox.LeaseUntilUtc, (DateTime?)null)
                .SetProperty(outbox => outbox.LeaseToken, (Guid?)null)
                .SetProperty(outbox => outbox.UpdatedAtUtc, nowUtc), cancellationToken) == 1;

    public async Task<bool> MarkFailedAsync(
        Guid outboxId,
        Guid leaseToken,
        string error,
        bool retryable,
        TimeSpan? retryAfter,
        DateTime nowUtc,
        CancellationToken cancellationToken)
    {
        var attemptCount = await dbContext.ReleaseNotificationOutboxes
            .Where(outbox =>
                outbox.OutboxId == outboxId
                && outbox.Status == ReleaseNotificationStatus.Processing
                && outbox.LeaseToken == leaseToken)
            .Select(outbox => (int?)outbox.AttemptCount)
            .SingleOrDefaultAsync(cancellationToken);

        if (attemptCount is null)
            return false;

        var backoffMinutes = Math.Min(60, Math.Pow(2, Math.Min(6, Math.Max(0, attemptCount.Value - 1))));
        var backoff = TimeSpan.FromMinutes(backoffMinutes);
        var nextAttemptAt = retryable
            ? nowUtc.Add(Max(backoff, retryAfter.GetValueOrDefault()))
            : nowUtc.AddYears(10);

        return await dbContext.ReleaseNotificationOutboxes
            .Where(outbox =>
                outbox.OutboxId == outboxId
                && outbox.Status == ReleaseNotificationStatus.Processing
                && outbox.LeaseToken == leaseToken)
            .ExecuteUpdateAsync(setters => setters
                .SetProperty(outbox => outbox.Status, ReleaseNotificationStatus.Failed)
                .SetProperty(outbox => outbox.NextAttemptAtUtc, nextAttemptAt)
                .SetProperty(outbox => outbox.LastError, error[..Math.Min(error.Length, 2000)])
                .SetProperty(outbox => outbox.LeaseUntilUtc, (DateTime?)null)
                .SetProperty(outbox => outbox.LeaseToken, (Guid?)null)
                .SetProperty(outbox => outbox.UpdatedAtUtc, nowUtc), cancellationToken) == 1;
    }

    private static TimeSpan Max(TimeSpan left, TimeSpan right)
        => left >= right ? left : right;

    public async Task<bool> RetryNotificationAsync(
        string slug,
        string notificationChannel,
        DateTime nowUtc,
        CancellationToken cancellationToken)
    {
        var releaseNote = await dbContext.ReleaseNotes
            .SingleOrDefaultAsync(note => note.Slug == slug && note.IsPublished, cancellationToken);

        if (releaseNote is null)
            return false;

        var outbox = await dbContext.ReleaseNotificationOutboxes
            .SingleOrDefaultAsync(notification =>
                notification.ReleaseNoteId == releaseNote.ReleaseNoteId
                && notification.Channel == notificationChannel,
                cancellationToken);

        if (outbox is null)
        {
            outbox = new ReleaseNotificationOutboxEntity
            {
                OutboxId = Guid.NewGuid(),
                ReleaseNoteId = releaseNote.ReleaseNoteId,
                Channel = notificationChannel,
                CreatedAtUtc = nowUtc,
            };
            await dbContext.ReleaseNotificationOutboxes.AddAsync(outbox, cancellationToken);
        }

        outbox.Status = ReleaseNotificationStatus.Pending;
        outbox.NextAttemptAtUtc = releaseNote.PublishedAtUtc > nowUtc
            ? releaseNote.PublishedAtUtc
            : nowUtc;
        outbox.LeaseUntilUtc = null;
        outbox.LeaseToken = null;
        outbox.LastError = null;
        outbox.UpdatedAtUtc = nowUtc;
        await dbContext.SaveChangesAsync(cancellationToken);
        return true;
    }
}
