using System.ComponentModel;
using System.Runtime.InteropServices;
using AstralRecordControl.Contracts;

namespace AstralRecordControl.Agent;

public interface IHostMetricsProbe
{
    HostMetricSample Sample();
}

public sealed class AgentMetricsSampler
{
    private readonly IRunnerClient runner;
    private readonly IHostMetricsProbe hostProbe;
    private readonly TimeProvider clock;
    private readonly Dictionary<Guid, (Guid SessionId, long Ticks, DateTimeOffset At)> prior = [];

    public AgentMetricsSampler(IRunnerClient runner, AgentConfiguration configuration, TimeProvider? clock = null, IHostMetricsProbe? hostProbe = null)
    {
        this.runner = runner;
        this.clock = clock ?? TimeProvider.System;
        this.hostProbe = hostProbe ?? new WindowsHostMetricsProbe(configuration.LocalResources);
    }

    public async Task<AgentMetricsBatch> SampleAsync(RunnerSnapshot snapshot, bool runnerAvailable, CancellationToken cancellationToken)
    {
        var now = clock.GetUtcNow();
        var host = hostProbe.Sample();
        var processes = new Dictionary<Guid, RunnerProcessMetric>();
        if (runnerAvailable)
        {
            try
            {
                var response = await runner.ReadMetricsAsync(cancellationToken);
                if (response.Processes is { Length: <= 32 })
                    foreach (var process in response.Processes.Where(p => p.ServerId != Guid.Empty && p.SessionId != Guid.Empty))
                        processes.TryAdd(process.ServerId, process);
            }
            catch (Exception exception) when (exception is IOException or AgentFault or OperationCanceledException or InvalidOperationException)
            {
                if (exception is OperationCanceledException && cancellationToken.IsCancellationRequested) throw;
            }
        }
        var servers = runnerAvailable ? snapshot.Servers.Select(server =>
        {
            processes.TryGetValue(server.ServerId, out var process);
            if (server.ProcessState != ProcessState.Running || process?.SessionId != server.CurrentSessionId) process = null;
            double? cpu = null;
            if (process?.TotalProcessorTicks is long ticks)
            {
                if (prior.TryGetValue(server.ServerId, out var previous) && previous.SessionId == process.SessionId)
                {
                    var elapsed = (now - previous.At).TotalSeconds;
                    var cpuSeconds = (ticks - previous.Ticks) / (double)TimeSpan.TicksPerSecond;
                    if (elapsed > 0 && cpuSeconds >= 0) cpu = Math.Clamp(cpuSeconds / elapsed / Environment.ProcessorCount * 100, 0, 100);
                }
                prior[server.ServerId] = (process.SessionId, ticks, now);
            }
            else prior.Remove(server.ServerId);
            return new ServerMetricSample
            {
                ServerId = server.ServerId, ProcessState = server.ProcessState,
                CpuPercent = cpu, MemoryUsedBytes = process?.WorkingSetBytes,
                UptimeSeconds = process?.UptimeSeconds
                // PlayerCount/Tps/Mspt require a configured game adapter; absent adapters stay null.
            };
        }).ToArray() : [];
        if (!runnerAvailable) prior.Clear();
        return new AgentMetricsBatch { ObservedAtUtc = now, Host = host, Servers = servers };
    }
}

public sealed class WindowsHostMetricsProbe(HostResources resources) : IHostMetricsProbe
{
    private (ulong Idle, ulong Kernel, ulong User)? previousCpu;

    public HostMetricSample Sample()
    {
        if (!OperatingSystem.IsWindows()) return new HostMetricSample();
        double? cpu = null;
        if (GetSystemTimes(out var idle, out var kernel, out var user))
        {
            var current = (Idle: ToUInt64(idle), Kernel: ToUInt64(kernel), User: ToUInt64(user));
            if (previousCpu is { } previous && current.Kernel >= previous.Kernel && current.User >= previous.User && current.Idle >= previous.Idle)
            {
                var total = current.Kernel - previous.Kernel + current.User - previous.User;
                if (total > 0) cpu = Math.Clamp((double)(total - (current.Idle - previous.Idle)) / total * 100, 0, 100);
            }
            previousCpu = current;
        }
        long? memoryUsed = null, memoryTotal = null;
        var memory = new MemoryStatus { Length = (uint)Marshal.SizeOf<MemoryStatus>() };
        if (GlobalMemoryStatusEx(ref memory) && memory.TotalPhysical <= long.MaxValue)
        {
            memoryUsed = (long)(memory.TotalPhysical - memory.AvailablePhysical);
            memoryTotal = (long)memory.TotalPhysical;
        }
        long? diskFree = null, diskTotal = null;
        try
        {
            var drives = resources.Roots.Select(root => Path.GetPathRoot(root.Path))
                .Append(Path.GetPathRoot(Environment.SystemDirectory))
                .Where(path => !string.IsNullOrWhiteSpace(path))
                .Distinct(StringComparer.OrdinalIgnoreCase).Select(path => new DriveInfo(path!))
                .Where(drive => drive.IsReady && drive.DriveType == DriveType.Fixed).ToArray();
            if (drives.Length > 0)
            {
                diskFree = drives.Sum(drive => drive.AvailableFreeSpace);
                diskTotal = drives.Sum(drive => drive.TotalSize);
            }
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException or ArgumentException or Win32Exception or OverflowException) { }
        return new HostMetricSample
        {
            CpuPercent = cpu, MemoryUsedBytes = memoryUsed, MemoryTotalBytes = memoryTotal,
            DiskFreeBytes = diskFree, DiskTotalBytes = diskTotal,
            UptimeSeconds = Math.Max(0, Environment.TickCount64 / 1000)
        };
    }

    private static ulong ToUInt64(System.Runtime.InteropServices.ComTypes.FILETIME value) =>
        ((ulong)(uint)value.dwHighDateTime << 32) | (uint)value.dwLowDateTime;

    [StructLayout(LayoutKind.Sequential)]
    private struct MemoryStatus
    {
        public uint Length;
        public uint MemoryLoad;
        public ulong TotalPhysical;
        public ulong AvailablePhysical;
        public ulong TotalPageFile;
        public ulong AvailablePageFile;
        public ulong TotalVirtual;
        public ulong AvailableVirtual;
        public ulong AvailableExtendedVirtual;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetSystemTimes(out System.Runtime.InteropServices.ComTypes.FILETIME idle,
        out System.Runtime.InteropServices.ComTypes.FILETIME kernel,
        out System.Runtime.InteropServices.ComTypes.FILETIME user);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GlobalMemoryStatusEx(ref MemoryStatus status);
}
