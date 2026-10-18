using System.Net.Http.Headers;
using System.Text;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Notifications;

public sealed class NotificationDeliveryService(ControlDbContext db, NotificationService notifications, InventoryStore store, HttpClient client, TimeProvider clock)
{
    private static readonly TimeSpan[] RetryDelays = [TimeSpan.FromMinutes(1), TimeSpan.FromMinutes(5), TimeSpan.FromMinutes(30)];

    public async Task<int> SendDueAsync(CancellationToken cancellationToken = default)
    {
        var now = clock.GetUtcNow();
        var candidates = await db.NotificationOutbox.AsNoTracking()
            .Where(x => x.SentAtUtc == null && x.DeadLetteredAtUtc == null && x.NextAttemptUnixMs <= now.ToUnixTimeMilliseconds())
            .OrderBy(x => x.NextAttemptUnixMs).Take(16).ToArrayAsync(cancellationToken);
        var due = candidates;
        foreach (var delivery in due)
        {
            var destination = await db.NotificationDestinations.AsNoTracking().SingleOrDefaultAsync(x => x.DestinationProfileId == delivery.DestinationProfileId, cancellationToken);
            var errorCode = "DestinationDisabled";
            var success = false;
            if (destination?.Enabled == true)
            {
                try
                {
                    var url = NotificationHttpHandler.ValidateUrl(notifications.UnprotectUrl(destination));
                    using var request = new HttpRequestMessage(HttpMethod.Post, url);
                    request.Headers.Add("X-Control-Event-Id", delivery.EventId.ToString("D"));
                    request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
                    request.Content = new StringContent(delivery.PayloadJson, Encoding.UTF8, "application/json");
                    using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
                    success = response.IsSuccessStatusCode;
                    if (!success) errorCode = response.StatusCode switch
                    {
                        >= System.Net.HttpStatusCode.InternalServerError => "Http5xx",
                        >= System.Net.HttpStatusCode.BadRequest => "Http4xx",
                        _ => "HttpUnexpected"
                    };
                }
                catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { throw; }
                catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or ArgumentException or System.Security.Cryptography.CryptographicException)
                {
                    errorCode = ex is TaskCanceledException ? "Timeout" : "DeliveryUnavailable";
                }
            }
            await store.WriteAsync(async () =>
            {
                var current = await db.NotificationOutbox.SingleOrDefaultAsync(x => x.EventId == delivery.EventId && x.DestinationProfileId == delivery.DestinationProfileId, cancellationToken);
                if (current is null || current.SentAtUtc != null || current.DeadLetteredAtUtc != null || current.Attempt != delivery.Attempt) return true;
                current.Attempt++;
                if (success) { current.SentAtUtc = clock.GetUtcNow(); current.TerminalAtUnixMs = clock.GetUtcNow().ToUnixTimeMilliseconds(); current.LastErrorCode = null; }
                else
                {
                    current.LastErrorCode = errorCode;
                    if (current.Attempt > RetryDelays.Length) { current.DeadLetteredAtUtc = clock.GetUtcNow(); current.TerminalAtUnixMs = clock.GetUtcNow().ToUnixTimeMilliseconds(); }
                    else { current.NextAttemptAtUtc = clock.GetUtcNow() + RetryDelays[current.Attempt - 1]; current.NextAttemptUnixMs = current.NextAttemptAtUtc.ToUnixTimeMilliseconds(); }
                }
                return true;
            });
        }
        return due.Length;
    }
}

public sealed class NotificationDispatcher(IServiceScopeFactory scopes, ILogger<NotificationDispatcher> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                using var scope = scopes.CreateScope();
                await scope.ServiceProvider.GetRequiredService<NotificationDeliveryService>().SendDueAsync(stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (Exception ex) { logger.LogWarning("Notification outbox cycle failed: {ErrorType}", ex.GetType().Name); }
            await Task.Delay(TimeSpan.FromSeconds(10), stoppingToken);
        }
    }
}
