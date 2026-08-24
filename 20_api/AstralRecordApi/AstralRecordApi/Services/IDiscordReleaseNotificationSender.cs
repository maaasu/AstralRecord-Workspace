using AstralRecordApi.Data.Entities;

namespace AstralRecordApi.Services;

public sealed record DiscordSendResult(
    bool Succeeded,
    bool Retryable,
    string? MessageId,
    string? Error,
    TimeSpan? RetryAfter = null);

public interface IDiscordReleaseNotificationSender
{
    Task<DiscordSendResult> SendAsync(
        ReleaseNotificationOutboxEntity outbox,
        CancellationToken cancellationToken);
}
