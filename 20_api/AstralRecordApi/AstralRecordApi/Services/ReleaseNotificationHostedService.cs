using AstralRecordApi.Options;
using AstralRecordApi.Repositories;
using Microsoft.Extensions.Options;

namespace AstralRecordApi.Services;

public sealed class ReleaseNotificationHostedService(
    IServiceScopeFactory scopeFactory,
    IOptions<DiscordReleaseNotificationOptions> options,
    IOptions<ReleaseNoteOptions> releaseNoteOptions,
    ILogger<ReleaseNotificationHostedService> logger) : BackgroundService
{
    private readonly DiscordReleaseNotificationOptions discordOptions = options.Value;
    private readonly ReleaseNoteOptions releaseOptions = releaseNoteOptions.Value;

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var interval = TimeSpan.FromSeconds(Math.Clamp(discordOptions.PollIntervalSeconds, 5, 300));

        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await ProcessOneAsync(stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "Release notification worker failed.");
            }

            await Task.Delay(interval, stoppingToken);
        }
    }

    private async Task ProcessOneAsync(CancellationToken cancellationToken)
    {
        if (!discordOptions.Enabled)
            return;

        using var scope = scopeFactory.CreateScope();
        var repository = scope.ServiceProvider.GetRequiredService<IReleaseNoteRepository>();
        var sender = scope.ServiceProvider.GetRequiredService<IDiscordReleaseNotificationSender>();
        var outbox = await repository.ClaimNextNotificationAsync(
            releaseOptions.NotificationChannel,
            DateTime.UtcNow,
            cancellationToken);

        if (outbox is null || outbox.LeaseToken is not { } leaseToken)
            return;

        var result = await sender.SendAsync(outbox, cancellationToken);
        if (result.Succeeded)
        {
            await repository.MarkSentAsync(
                outbox.OutboxId,
                leaseToken,
                result.MessageId,
                DateTime.UtcNow,
                cancellationToken);
            logger.LogInformation("Release note Discord notification sent: {Slug}", outbox.ReleaseNote.Slug);
            return;
        }

        await repository.MarkFailedAsync(
            outbox.OutboxId,
            leaseToken,
            result.Error ?? "Discord notification failed.",
            result.Retryable,
            result.RetryAfter,
            DateTime.UtcNow,
            cancellationToken);
    }
}
