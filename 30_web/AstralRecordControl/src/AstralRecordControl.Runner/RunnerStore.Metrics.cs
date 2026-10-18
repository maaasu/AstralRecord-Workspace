using System.ComponentModel;
using AstralRecordControl.Contracts;

namespace AstralRecordControl.Runner;

public sealed partial class RunnerStore
{
    public RunnerMetricsSnapshot ReadMetrics()
    {
        lock (sync)
        {
            var processes = new List<RunnerProcessMetric>();
            foreach (var server in GetSnapshot().Servers)
            {
                if (server.ProcessState != ProcessState.Running || server.CurrentSessionId is not Guid sessionId ||
                    !ownedProcesses.TryGetValue(server.ServerId, out var process)) continue;
                try
                {
                    process.Refresh();
                    if (process.HasExited) continue;
                    var started = new DateTimeOffset(process.StartTime.ToUniversalTime(), TimeSpan.Zero);
                    processes.Add(new RunnerProcessMetric
                    {
                        ServerId = server.ServerId, SessionId = sessionId,
                        TotalProcessorTicks = process.TotalProcessorTime.Ticks,
                        WorkingSetBytes = process.WorkingSet64,
                        UptimeSeconds = Math.Max(0, (long)(clock.GetUtcNow() - started).TotalSeconds)
                    });
                }
                catch (Exception exception) when (exception is InvalidOperationException or Win32Exception or NotSupportedException)
                {
                    // An exiting process is a missing measurement, never a stopped observation.
                }
            }
            return new RunnerMetricsSnapshot { Processes = processes.ToArray() };
        }
    }
}
