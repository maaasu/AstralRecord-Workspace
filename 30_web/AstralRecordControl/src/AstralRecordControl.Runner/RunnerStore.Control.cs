using System.Diagnostics;
using AstralRecordControl.Contracts;

namespace AstralRecordControl.Runner;

public sealed partial class RunnerStore
{
    private async Task StopAsync(OperationCommand command, bool fromRestart = false)
    {
        var serverId = command.ServerId!.Value;
        Process process;
        ServerConfig config;
        Guid sessionId;
        lock (sync)
        {
            config = GetServerConfig(serverId);
            var current = CurrentSession(serverId);
            if (current is null)
            {
                if (fromRestart) throw new RunnerFault(ErrorCode.StateConflict);
                ChangeExecution(command.OperationId, OperationStatus.Succeeded, ExecutionPhase.Completed);
                return;
            }
            sessionId = Guid.Parse(current);
            process = VerifyOwnedProcess(serverId, sessionId);
            SetRuntime(serverId, ProcessState.Stopping, sessionId, "Stopped");
            Execute("UPDATE ServerSessions SET lastExitWasExpected=1 WHERE sessionId=$session AND exitedUtc IS NULL;", null, ("$session", sessionId.ToString()));
            ChangeExecution(command.OperationId, OperationStatus.Running, ExecutionPhase.Stopping);
        }
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(Math.Min(config.StopTimeoutSeconds, command.ExecutionTimeoutSeconds)));
        try
        {
            await process.StandardInput.WriteLineAsync(config.StopCommand.AsMemory(), timeout.Token);
            await process.StandardInput.FlushAsync(timeout.Token);
        }
        catch
        {
            lock (sync) ChangeExecution(command.OperationId, OperationStatus.NeedsAttention, ExecutionPhase.Unknown, ErrorCode.RecoveryRequired);
            return;
        }
        try { await process.WaitForExitAsync(timeout.Token); }
        catch (OperationCanceledException)
        {
            lock (sync) ChangeExecution(command.OperationId, OperationStatus.NeedsAttention, ExecutionPhase.Stopping, ErrorCode.Timeout);
            return;
        }
        lock (sync)
        {
            var current = CurrentSession(serverId);
            if (current == sessionId.ToString()) SetRuntime(serverId, ProcessState.Stopped, null, "Stopped");
            if (!fromRestart) ChangeExecution(command.OperationId, OperationStatus.Succeeded, ExecutionPhase.Completed);
        }
    }

    private async Task KillAsync(OperationCommand command)
    {
        var serverId = command.ServerId!.Value;
        Process process;
        Guid sessionId;
        var rescue = false;
        lock (sync)
        {
            var current = CurrentSession(serverId) ?? throw new RunnerFault(ErrorCode.StateConflict);
            sessionId = Guid.Parse(current);
            rescue = IsRescueKill(command);
            if (rescue)
            {
                var inspected = InspectRecovery(new(serverId));
                if (!inspected.ProcessAlive || !inspected.IdentityMatches || inspected.Session?.ProcessId is not int pid) throw new RunnerFault(ErrorCode.RecoveryRequired);
                process = Process.GetProcessById(pid);
            }
            else process = VerifyOwnedProcess(serverId, sessionId);
            SetRuntime(serverId, ProcessState.Stopping, sessionId, "Stopped", rescue);
            Execute("UPDATE ServerSessions SET lastExitWasExpected=1 WHERE sessionId=$session AND exitedUtc IS NULL;", null, ("$session", sessionId.ToString()));
            ChangeExecution(command.OperationId, OperationStatus.Running, ExecutionPhase.Stopping);
        }
        process.Kill(entireProcessTree: false);
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(Math.Min(30, command.ExecutionTimeoutSeconds)));
        try { await process.WaitForExitAsync(timeout.Token); }
        catch (OperationCanceledException)
        {
            lock (sync) ChangeExecution(command.OperationId, OperationStatus.NeedsAttention, ExecutionPhase.Unknown, ErrorCode.Timeout);
            return;
        }
        lock (sync)
        {
            var current = CurrentSession(serverId);
            if (current == sessionId.ToString()) SetRuntime(serverId, ProcessState.Stopped, null, "Stopped");
            if (rescue)
                Execute("UPDATE ExecutionLedger SET status='Failed',phase='Completed',statusVersion=statusVersion+1,errorCode='RecoveryRequired',finishedUtc=$now WHERE serverId=$server AND reservation=$reservation AND workflow=$workflow AND fence=$fence AND kind IN ('Stop','Restart') AND status='NeedsAttention';", null,
                    ("$server", serverId.ToString()), ("$reservation", command.ReservationId!.Value.ToString()), ("$workflow", command.WorkflowId!.Value.ToString()), ("$fence", command.ReservationFence!.Value), ("$now", clock.GetUtcNow().ToString("O")));
            ChangeExecution(command.OperationId, OperationStatus.Succeeded, ExecutionPhase.Completed);
        }
    }

    private async Task ConsoleAsync(OperationCommand command)
    {
        Process process;
        lock (sync)
        {
            var serverId = command.ServerId!.Value;
            var session = CurrentSession(serverId) ?? throw new RunnerFault(ErrorCode.StateConflict);
            process = VerifyOwnedProcess(serverId, Guid.Parse(session));
            // This commit is the replay boundary. A crash after it leaves the result unknown.
            ChangeExecution(command.OperationId, OperationStatus.Running, ExecutionPhase.Dispatching);
        }
        try
        {
            var line = command.Payload.GetProperty("command").GetString()!;
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(command.ExecutionTimeoutSeconds));
            await process.StandardInput.WriteLineAsync(line.AsMemory(), timeout.Token);
            await process.StandardInput.FlushAsync(timeout.Token);
            lock (sync)
            {
                ChangeExecution(command.OperationId, OperationStatus.Running, ExecutionPhase.Sent);
                ChangeExecution(command.OperationId, OperationStatus.Succeeded, ExecutionPhase.Completed);
            }
        }
        catch
        {
            lock (sync) ChangeExecution(command.OperationId, OperationStatus.NeedsAttention, ExecutionPhase.Unknown, ErrorCode.RecoveryRequired);
        }
    }

    private Process VerifyOwnedProcess(Guid serverId, Guid sessionId)
    {
        if (!ownedProcesses.TryGetValue(serverId, out var process)) throw new RunnerFault(ErrorCode.RecoveryRequired);
        var session = GetSession(sessionId) ?? throw new RunnerFault(ErrorCode.RecoveryRequired);
        if (session.ServerId != serverId || session.RunnerEpoch != Epoch || session.ProcessId != process.Id || !IdentityMatches(process, session)) throw new RunnerFault(ErrorCode.RecoveryRequired);
        return process;
    }

    private static bool IdentityMatches(Process process, ServerSessionSnapshot session)
    {
        try
        {
            if (process.HasExited || process.Id != session.ProcessId || session.ProcessCreatedAtUtc is null) return false;
            var creation = new DateTimeOffset(process.StartTime.ToUniversalTime(), TimeSpan.Zero);
            if (Math.Abs((creation - session.ProcessCreatedAtUtc.Value).TotalMilliseconds) > 1) return false;
            var currentPath = process.MainModule?.FileName;
            return currentPath is not null && Fingerprint(currentPath) == session.ExecutableFingerprint;
        }
        catch (Exception ex) when (ex is InvalidOperationException or System.ComponentModel.Win32Exception or FileNotFoundException or UnauthorizedAccessException) { return false; }
    }

    private ServerSessionSnapshot? GetSession(Guid sessionId)
    {
        using var command = Command("SELECT serverId,startOperationId,runnerEpoch,pid,processCreatedUtc,executableFingerprint,configRevision,startedUtc,exitedUtc,exitCode FROM ServerSessions WHERE sessionId=$session;", null, ("$session", sessionId.ToString()));
        using var reader = command.ExecuteReader();
        if (!reader.Read()) return null;
        return new ServerSessionSnapshot
        {
            ServerSessionId = sessionId, ServerId = Guid.Parse(reader.GetString(0)), StartOperationId = Guid.Parse(reader.GetString(1)), RunnerEpoch = Guid.Parse(reader.GetString(2)),
            ProcessId = reader.IsDBNull(3) ? null : reader.GetInt32(3), ProcessCreatedAtUtc = reader.IsDBNull(4) ? null : DateTimeOffset.Parse(reader.GetString(4)),
            ExecutableFingerprint = reader.GetString(5), ConfigRevision = reader.GetInt64(6), StartedAtUtc = DateTimeOffset.Parse(reader.GetString(7)),
            ExitedAtUtc = reader.IsDBNull(8) ? null : DateTimeOffset.Parse(reader.GetString(8)), ExitCode = reader.IsDBNull(9) ? null : reader.GetInt32(9)
        };
    }

    public RecoveryInspection InspectRecovery(InspectRecoveryRequest request)
    {
        if (request.ServerId == Guid.Empty) throw new RunnerFault(ErrorCode.ProtocolMismatch);
        lock (sync)
        {
            var runtime = GetSnapshot().Servers.SingleOrDefault(s => s.ServerId == request.ServerId) ?? throw new RunnerFault(ErrorCode.StateConflict);
            var session = runtime.CurrentSessionId is Guid id ? GetSession(id) : null;
            var alive = false;
            var matches = false;
            if (session?.ProcessId is int pid)
            {
                try { using var process = Process.GetProcessById(pid); alive = !process.HasExited; matches = IdentityMatches(process, session); }
                catch (ArgumentException) { }
            }
            var recovery = Convert.ToInt64(Scalar("SELECT recoveryRequired FROM Servers WHERE id=$server;", null, ("$server", request.ServerId.ToString()))) != 0;
            return new RecoveryInspection(runtime, session, alive, matches, recovery);
        }
    }

    public RecoveryInspection Recover(RunnerRecoveryRequest request, bool isLocalAdministrator)
    {
        if (!isLocalAdministrator) throw new RunnerFault(ErrorCode.AgentDisabled);
        if (request.ServerId == Guid.Empty || string.IsNullOrWhiteSpace(request.Reason) || request.Reason.Length > 1024 || request.Reason.Any(c => c is '\r' or '\n' or '\0')) throw new RunnerFault(ErrorCode.ProtocolMismatch);
        lock (sync)
        {
            var inspected = InspectRecovery(new InspectRecoveryRequest(request.ServerId));
            if (inspected.Runtime.CurrentSessionId != request.ExpectedServerSessionId || !inspected.RecoveryRequired) throw new RunnerFault(ErrorCode.StateConflict);
            if (Convert.ToInt64(Scalar("SELECT COUNT(*) FROM DataOperationLedger WHERE serverId=$server AND kind='RestoreBackup' AND status IN ('Accepted','Running','NeedsAttention');", null, ("$server", request.ServerId.ToString()))) != 0) throw new RunnerFault(ErrorCode.RecoveryRequired);
            if (request.Action == WorkflowRecoveryAction.Resume) throw new RunnerFault(ErrorCode.UnsupportedCapability);
            if (request.Action == WorkflowRecoveryAction.KillAfterInspection)
            {
                if (!inspected.ProcessAlive || !inspected.IdentityMatches || inspected.Session?.ProcessId is not int pid) throw new RunnerFault(ErrorCode.RecoveryRequired);
                using var process = Process.GetProcessById(pid);
                if (!IdentityMatches(process, inspected.Session)) throw new RunnerFault(ErrorCode.RecoveryRequired);
                process.Kill(entireProcessTree: false);
                if (!process.WaitForExit(30000)) throw new RunnerFault(ErrorCode.Timeout);
                object exitCode;
                try { exitCode = process.ExitCode; }
                catch (InvalidOperationException) { exitCode = DBNull.Value; }
                Execute("UPDATE ServerSessions SET exitedUtc=$now,exitCode=$code WHERE sessionId=$session;", null, ("$now", clock.GetUtcNow().ToString("O")), ("$code", exitCode), ("$session", inspected.Session.ServerSessionId.ToString()));
            }
            else if (request.Action == WorkflowRecoveryAction.AbortAfterInspection && inspected.ProcessAlive)
            {
                // A mismatching executable is not proof of exit; a different creation time is.
                using var process = Process.GetProcessById(inspected.Session!.ProcessId!.Value);
                bool pidReused;
                try { pidReused = inspected.Session.ProcessCreatedAtUtc is DateTimeOffset created && Math.Abs((process.StartTime.ToUniversalTime() - created.UtcDateTime).TotalMilliseconds) > 1; }
                catch (System.ComponentModel.Win32Exception) { throw new RunnerFault(ErrorCode.RecoveryRequired); }
                var unresolved = Convert.ToInt64(Scalar("SELECT COUNT(*) FROM ExecutionLedger WHERE serverId=$server AND status='NeedsAttention' AND kind<>'ConsoleCommand';", null, ("$server", request.ServerId.ToString())));
                var acknowledgeConsole = inspected.IdentityMatches && ownedProcesses.ContainsKey(request.ServerId) && unresolved == 0;
                if (!pidReused && !acknowledgeConsole) throw new RunnerFault(ErrorCode.RecoveryRequired);
                if (acknowledgeConsole)
                {
                    Execute("UPDATE ExecutionLedger SET status='Failed',phase='Completed',statusVersion=statusVersion+1,errorCode='RecoveryRequired',finishedUtc=$now WHERE serverId=$server AND status='NeedsAttention' AND kind='ConsoleCommand';", null, ("$server", request.ServerId.ToString()), ("$now", clock.GetUtcNow().ToString("O")));
                    SetRuntime(request.ServerId, ProcessState.Running, inspected.Runtime.CurrentSessionId, "Running");
                    AuditRecovery(request);
                    return InspectRecovery(new(request.ServerId));
                }
            }
            else if (request.Action != WorkflowRecoveryAction.AbortAfterInspection) throw new RunnerFault(ErrorCode.ProtocolMismatch);
            AuditRecovery(request);
            SetRuntime(request.ServerId, ProcessState.Stopped, null, "Stopped");
            Execute("UPDATE ExecutionLedger SET status='Failed',phase='Completed',statusVersion=statusVersion+1,errorCode='RecoveryRequired',finishedUtc=$now WHERE serverId=$server AND status='NeedsAttention';", null, ("$server", request.ServerId.ToString()), ("$now", clock.GetUtcNow().ToString("O")));
            Execute("UPDATE DataOperationLedger SET status='Failed',statusVersion=statusVersion+1,finishedUtc=$now WHERE serverId=$server AND status='NeedsAttention' AND kind<>'RestoreBackup';", null, ("$server", request.ServerId.ToString()), ("$now", clock.GetUtcNow().ToString("O")));
            return InspectRecovery(new InspectRecoveryRequest(request.ServerId));
        }
    }

    private void AuditRecovery(RunnerRecoveryRequest request) => Execute("INSERT INTO RecoveryAudit VALUES($id,$server,$session,$action,$reason,$utc);", null,
        ("$id", Guid.NewGuid().ToString()), ("$server", request.ServerId.ToString()), ("$session", request.ExpectedServerSessionId?.ToString() ?? (object)DBNull.Value), ("$action", request.Action.ToString()), ("$reason", request.Reason), ("$utc", clock.GetUtcNow().ToString("O")));
}
