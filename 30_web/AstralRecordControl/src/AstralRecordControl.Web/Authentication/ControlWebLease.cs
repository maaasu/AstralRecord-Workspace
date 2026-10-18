using AstralRecordControl.Web.Data;

namespace AstralRecordControl.Web.Authentication;

public sealed class ControlWebLease(ControlStorage storage, IServiceScopeFactory scopes) : IHostedService, IDisposable
{
    private FileStream? lease;
    public async Task StartAsync(CancellationToken cancellationToken)
    {
        lease = storage.AcquireExclusiveLease();
        try
        {
            using var scope = scopes.CreateScope();
            await ControlDatabase.InitializeAsync(scope.ServiceProvider.GetRequiredService<ControlDbContext>(), cancellationToken);
        }
        catch { Dispose(); throw; }
    }
    public Task StopAsync(CancellationToken cancellationToken) { Dispose(); return Task.CompletedTask; }
    public void Dispose() { lease?.Dispose(); lease = null; }
}
