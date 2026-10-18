using System.Collections.Concurrent;

namespace AstralRecordControl.Web.Authentication;

public sealed class OwnerSessionConnections
{
    private readonly ConcurrentDictionary<string, (Guid Session, Action Abort)> connections = new();
    public void Register(Guid sessionId, string connectionId, Action abort) => connections[connectionId] = (sessionId, abort);
    public void Unregister(string connectionId) => connections.TryRemove(connectionId, out _);
    public Guid[] SessionIds => connections.Values.Select(x => x.Session).Distinct().ToArray();
    public void Revoke(Guid sessionId)
    {
        foreach (var pair in connections.Where(x => x.Value.Session == sessionId))
            if (connections.TryRemove(pair.Key, out var value)) value.Abort();
    }
}

public sealed class OwnerSessionMonitor(IServiceScopeFactory scopes, OwnerSessionConnections connections) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        // Checking each second covers the exact absolute expiration as well as the 60-second revalidation bound.
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(1));
        while (await timer.WaitForNextTickAsync(stoppingToken))
        {
            foreach (var id in connections.SessionIds)
            {
                using var scope = scopes.CreateScope();
                if (await scope.ServiceProvider.GetRequiredService<OwnerAuthService>().ValidateSessionAsync(id) is null) connections.Revoke(id);
            }
        }
    }
}
