using AstralRecordWeb.Options;
using Microsoft.Extensions.Options;

namespace AstralRecordWeb.Services;

public sealed class ReleaseNotePublicationHostedService(
    ReleaseNoteCatalog catalog,
    ReleaseNoteApiClient apiClient,
    IOptions<ReleaseNoteOptions> options,
    ILogger<ReleaseNotePublicationHostedService> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        if (!options.Value.SyncOnStartup)
            return;

        while (!stoppingToken.IsCancellationRequested)
        {
            var allSucceeded = true;
            try
            {
                var documents = await catalog.GetPublishedAsync(stoppingToken);
                foreach (var document in documents)
                {
                    if (!await apiClient.PublishAsync(document, stoppingToken))
                        allSucceeded = false;
                }
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                allSucceeded = false;
                logger.LogError(ex, "Release note synchronization failed.");
            }

            var delay = allSucceeded
                ? TimeSpan.FromMinutes(Math.Clamp(options.Value.SyncIntervalMinutes, 1, 1440))
                : TimeSpan.FromSeconds(30);
            try
            {
                await Task.Delay(delay, stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
        }
    }
}
