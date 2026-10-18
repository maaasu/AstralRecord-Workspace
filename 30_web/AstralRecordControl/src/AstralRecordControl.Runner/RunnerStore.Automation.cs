using AstralRecordControl.Contracts;
using Microsoft.Extensions.Hosting;

namespace AstralRecordControl.Runner;

public sealed partial class RunnerStore
{
    private readonly SemaphoreSlim automation = new(1, 1);

    private void ScheduleCrashRestart(Guid serverId, int exitCode)
    {
        var config = GetServerConfig(serverId);
        if (!configuration.EnableProcessControl || !config.RestartOnCrash || config.IgnoredExitCodes.Contains(exitCode))
        {
            SetRuntime(serverId, ProcessState.Stopped, null, "Stopped");
            return;
        }
        var now = clock.GetUtcNow();
        var window = Scalar("SELECT restartWindowUtc FROM Servers WHERE id=$server;", null, ("$server", serverId.ToString())) as string;
        var count = Convert.ToInt32(Scalar("SELECT restartCount FROM Servers WHERE id=$server;", null, ("$server", serverId.ToString())));
        if (window is null || now - DateTimeOffset.Parse(window) > TimeSpan.FromSeconds(config.RestartWindowSeconds))
        {
            window = now.ToString("O"); count = 0;
        }
        count++;
        if (count > config.RestartMaxCount)
        {
            SetRuntime(serverId, ProcessState.Stopped, null, "Stopped", true);
            Execute("UPDATE Servers SET restartCount=$count,restartWindowUtc=$window,nextAutoStartUtc=NULL WHERE id=$server;", null, ("$count", count), ("$window", window), ("$server", serverId.ToString()));
            return;
        }
        SetRuntime(serverId, ProcessState.Stopped, null, "RestartPending");
        Execute("UPDATE Servers SET restartCount=$count,restartWindowUtc=$window,nextAutoStartUtc=$next WHERE id=$server;", null,
            ("$count", count), ("$window", window), ("$next", now.AddSeconds(config.RestartDelaySeconds).ToString("O")), ("$server", serverId.ToString()));
    }

    public async Task RunAutomaticStartsAsync(CancellationToken cancellationToken = default)
    {
        if (!configuration.EnableProcessControl || !await automation.WaitAsync(0, cancellationToken)) return;
        try
        {
            Guid[] candidates;
            lock (sync)
            {
                var ids = new List<Guid>();
                using var command = Command("SELECT id FROM Servers WHERE processState='Stopped' AND recoveryRequired=0 AND desiredState IN ('AutoStartPending','RestartPending') AND nextAutoStartUtc<=$now;", null, ("$now", clock.GetUtcNow().ToString("O")));
                using var reader = command.ExecuteReader();
                while (reader.Read()) ids.Add(Guid.Parse(reader.GetString(0)));
                candidates = ids.ToArray();
            }
            foreach (var serverId in candidates)
            {
                cancellationToken.ThrowIfCancellationRequested();
                ResourceHold hold;
                try { hold = AcquireHold(new(Guid.NewGuid(), Guid.NewGuid(), ["server:" + serverId], HoldMode.Exclusive)); }
                catch (RunnerFault fault) when (fault.Code == ErrorCode.ReservationConflict) { continue; }
                ExecuteRequest request;
                lock (sync)
                {
                    var snapshot = GetSnapshot().Servers.Single(s => s.ServerId == serverId);
                    var config = GetServerConfig(serverId);
                    var command = new OperationCommand
                    {
                        OperationId = Guid.NewGuid(), HostId = configuration.HostId, ServerId = serverId,
                        WorkflowId = hold.OwnerWorkflowId, ReservationId = hold.ReservationId, ReservationFence = hold.Fence,
                        Kind = OperationKind.Start, ConfigRevision = config.Revision, ExpectedStateRevision = snapshot.StateRevision,
                        StartBeforeUtc = clock.GetUtcNow().AddSeconds(60), ExecutionTimeoutSeconds = config.StartupTimeoutSeconds
                    };
                    var json = ControlJson.Serialize(command);
                    request = new(command.OperationId, json, ControlProtocol.Hash(json));
                    Execute("UPDATE Servers SET desiredState='AutoStartInFlight',nextAutoStartUtc=NULL WHERE id=$server;", null, ("$server", serverId.ToString()));
                }
                try
                {
                    Execute(request);
                    _ = Task.Run(() => ReleaseAutomaticHoldAsync(request.OperationId, hold));
                }
                catch
                {
                    lock (sync) Execute("UPDATE Servers SET desiredState='Stopped' WHERE id=$server;", null, ("$server", serverId.ToString()));
                    ReleaseHold(new(hold.ReservationId, hold.OwnerWorkflowId, hold.Fence));
                    throw;
                }
            }
        }
        finally { automation.Release(); }
    }

    private async Task ReleaseAutomaticHoldAsync(Guid operationId, ResourceHold hold)
    {
        try
        {
            while (true)
            {
                await Task.Delay(250);
                ExecutionSnapshot result;
                lock (sync) { if (disposed) return; result = GetExecution(operationId); }
                if (result.Status == OperationStatus.NeedsAttention) return;
                if (!result.IsTerminal) continue;
                ReleaseHold(new(hold.ReservationId, hold.OwnerWorkflowId, hold.Fence));
                return;
            }
        }
        catch (Exception) { /* A persisted hold is safer than an unverified release. */ }
    }
}

public sealed class RunnerAutomationService(RunnerStore store) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(5));
        do
        {
            try { await store.RunAutomaticStartsAsync(stoppingToken); }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (Exception) { /* Durable state remains for inspection on the next tick. */ }
        }
        while (await timer.WaitForNextTickAsync(stoppingToken));
    }
}
