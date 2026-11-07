using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using AstralRecordControl.Contracts;
using Microsoft.Win32.SafeHandles;

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
        var stopError = ErrorCode.Timeout;
        try
        {
            try
            {
                await process.StandardInput.WriteLineAsync(ServerProcessSupport.EffectiveStopCommand(config).AsMemory(), timeout.Token);
                await process.StandardInput.FlushAsync(timeout.Token);
            }
            catch (Exception exception) when (exception is IOException or InvalidOperationException)
            {
                // The process can close stdin after receiving the command. Wait for the owned process to exit.
                stopError = ErrorCode.RecoveryRequired;
            }
            await process.WaitForExitAsync(timeout.Token);
        }
        catch (OperationCanceledException) when (timeout.IsCancellationRequested) { }
        lock (sync)
        {
            // Serialize the timeout with the exit monitor. If it observed the exit first, do not leave a pending stop.
            if (!process.HasExited)
            {
                ChangeExecution(command.OperationId, OperationStatus.NeedsAttention, ExecutionPhase.Stopping, stopError);
                return;
            }
            var current = CurrentSession(serverId);
            if (current == sessionId.ToString()) SetRuntime(serverId, ProcessState.Stopped, null, "Stopped");
            if (!fromRestart) ChangeExecution(command.OperationId, OperationStatus.Succeeded, ExecutionPhase.Completed);
            else if (timeout.IsCancellationRequested) ChangeExecution(command.OperationId, OperationStatus.Failed, ExecutionPhase.Completed, stopError);
        }
    }

    private async Task KillAsync(OperationCommand command)
    {
        var serverId = command.ServerId!.Value;
        Process? opened = null;
        try
        {
            Process process;
            Guid sessionId;
            bool rescue;
            var terminationPerformed = false;
            lock (sync)
            {
                var current = CurrentSession(serverId);
                if (current is null)
                {
                    if ((string?)Scalar("SELECT processState FROM Servers WHERE id=$id;", null, ("$id", serverId.ToString())) != nameof(ProcessState.Stopped) ||
                        !IsExitedRescueTarget(command)) throw new RunnerFault(ErrorCode.StateConflict);
                    ChangeExecution(command.OperationId, OperationStatus.Cancelled, ExecutionPhase.Completed);
                    return;
                }
                sessionId = Guid.Parse(current);
                if (sessionId != command.ExpectedServerSessionId) throw new RunnerFault(ErrorCode.StateConflict);
                rescue = HasRescueTarget(command);
                if (rescue)
                {
                    var session = GetSession(sessionId);
                    if (session?.ProcessId is not int pid || session.ServerId != serverId) throw new RunnerFault(ErrorCode.RecoveryRequired);
                    var tracked = session.RunnerEpoch == Epoch && ownedProcesses.TryGetValue(serverId, out var owned) && owned.Id == pid ? owned : null;
                    process = tracked ?? (opened = OpenPinnedProcess(pid));
                    if (!IdentityMatches(process, session))
                    {
                        if (tracked is null || !process.HasExited) throw new RunnerFault(ErrorCode.RecoveryRequired);
                        CompleteRescueKill(command, sessionId, process, terminationPerformed: false);
                        return;
                    }
                }
                else process = VerifyOwnedProcess(serverId, sessionId);

                SetRuntime(serverId, ProcessState.Stopping, sessionId, "Stopped", rescue);
                Execute("UPDATE ServerSessions SET lastExitWasExpected=1 WHERE sessionId=$session AND exitedUtc IS NULL;", null, ("$session", sessionId.ToString()));
                ChangeExecution(command.OperationId, OperationStatus.Running, ExecutionPhase.Stopping);
                if (rescue)
                {
                    // Process.Kill reports success when the target already exited. Use the pinned handle to distinguish the outcomes.
                    terminationPerformed = TerminateProcess(process.SafeHandle, uint.MaxValue);
                    if (terminationPerformed) SettleRescueStop(command, sessionId, terminationPerformed);
                }
                else process.Kill(entireProcessTree: false);
            }

            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(Math.Min(30, command.ExecutionTimeoutSeconds)));
            try { await process.WaitForExitAsync(timeout.Token); }
            catch (OperationCanceledException) when (timeout.IsCancellationRequested) { }
            lock (sync)
            {
                if (!process.HasExited)
                {
                    ChangeExecution(command.OperationId, OperationStatus.NeedsAttention, rescue ? ExecutionPhase.Stopping : ExecutionPhase.Unknown,
                        rescue && !terminationPerformed ? ErrorCode.RecoveryRequired : ErrorCode.Timeout,
                        rescue ? new { terminationPerformed } : null);
                    return;
                }
                if (rescue) CompleteRescueKill(command, sessionId, process, terminationPerformed);
                else
                {
                    if (CurrentSession(serverId) == sessionId.ToString()) SetRuntime(serverId, ProcessState.Stopped, null, "Stopped");
                    ChangeExecution(command.OperationId, OperationStatus.Succeeded, ExecutionPhase.Completed);
                }
            }
        }
        finally { opened?.Dispose(); }
    }

    private void CompleteRescueKill(OperationCommand command, Guid sessionId, Process process, bool terminationPerformed)
    {
        var serverId = command.ServerId!.Value;
        Execute("UPDATE ServerSessions SET exitedUtc=$now,exitCode=$code WHERE sessionId=$session AND exitedUtc IS NULL;", null,
            ("$now", clock.GetUtcNow().ToString("O")), ("$code", process.ExitCode), ("$session", sessionId.ToString()));
        SettleRescueStop(command, sessionId, terminationPerformed);
        ChangeExecution(command.OperationId, terminationPerformed ? OperationStatus.Succeeded : OperationStatus.Cancelled, ExecutionPhase.Completed);
        var current = CurrentSession(serverId);
        var pendingRecovery = HasPendingRecovery(serverId);
        if (current == sessionId.ToString()) SetRuntime(serverId, ProcessState.Stopped, null, "Stopped", pendingRecovery);
        else if (current is null)
            Execute("UPDATE Servers SET recoveryRequired=$pending,stateRevision=stateRevision+1 WHERE id=$server AND processState='Stopped' AND recoveryRequired<>$pending;", null,
                ("$server", serverId.ToString()), ("$pending", pendingRecovery ? 1 : 0));
    }

    private void SettleRescueStop(OperationCommand command, Guid sessionId, bool terminationPerformed) => Execute("""
        UPDATE ExecutionLedger
        SET status=CASE WHEN $terminated=0 AND kind='Stop' THEN 'Succeeded' ELSE 'Failed' END,
            phase='Completed',statusVersion=statusVersion+1,finishedUtc=$now,
            errorCode=CASE WHEN $terminated=1 THEN 'RecoveryRequired' WHEN kind='Stop' THEN NULL ELSE COALESCE(errorCode,'RecoveryRequired') END
        WHERE serverId=$server AND reservation=$reservation AND workflow=$workflow AND fence=$fence AND kind IN ('Stop','Restart')
          AND status='NeedsAttention' AND json_extract(commandJson,'$.expectedServerSessionId')=$session;
        """, null, ("$server", command.ServerId!.Value.ToString()), ("$reservation", command.ReservationId!.Value.ToString()),
        ("$workflow", command.WorkflowId!.Value.ToString()), ("$fence", command.ReservationFence!.Value), ("$session", sessionId.ToString()),
        ("$terminated", terminationPerformed ? 1 : 0), ("$now", clock.GetUtcNow().ToString("O")));

    private static Process OpenPinnedProcess(int pid)
    {
        Process? process = null;
        try
        {
            process = Process.GetProcessById(pid);
            _ = process.SafeHandle;
            return process;
        }
        catch (Exception exception) when (exception is ArgumentException or InvalidOperationException or Win32Exception)
        {
            process?.Dispose();
            throw new RunnerFault(ErrorCode.RecoveryRequired);
        }
    }

    [DllImport("kernel32.dll", ExactSpelling = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool TerminateProcess(SafeProcessHandle process, uint exitCode);

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
                using var process = OpenPinnedProcess(pid);
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
                var unresolvedData = Convert.ToInt64(Scalar("SELECT COUNT(*) FROM DataOperationLedger WHERE serverId=$server AND status IN ('Accepted','Running','NeedsAttention');", null, ("$server", request.ServerId.ToString())));
                var acknowledgeConsole = inspected.IdentityMatches && ownedProcesses.ContainsKey(request.ServerId) && unresolved == 0 && unresolvedData == 0;
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
