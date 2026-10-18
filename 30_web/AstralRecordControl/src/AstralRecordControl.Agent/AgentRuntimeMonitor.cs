using AstralRecordControl.Contracts;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace AstralRecordControl.Agent;

public sealed class AgentRuntimeMonitor(AgentStore store, IRunnerClient runner, TimeProvider clock, ILogger<AgentRuntimeMonitor> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(10), clock);
        do
        {
            try { store.ObserveRuntime(await runner.GetSnapshotAsync(stoppingToken), clock.GetUtcNow()); }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (Exception error) when (error is IOException or OperationCanceledException or AgentFault or ArgumentException)
            {
                // A missing Runner never becomes a synthetic stop or zero-player observation.
                logger.LogDebug("Runtime observation unavailable ({ErrorCode}).", error is AgentFault fault ? fault.Code : ErrorCode.HostOffline);
            }
        } while (await timer.WaitForNextTickAsync(stoppingToken));
    }
}
