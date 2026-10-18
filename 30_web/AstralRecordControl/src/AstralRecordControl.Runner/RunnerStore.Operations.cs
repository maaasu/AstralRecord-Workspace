using System.Diagnostics;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using AstralRecordControl.Contracts;

namespace AstralRecordControl.Runner;

public sealed partial class RunnerStore
{
    private readonly Dictionary<Guid, Process> ownedProcesses = [];

    public ExecutionSnapshot Execute(ExecuteRequest request)
    {
        if (!configuration.EnableProcessControl) throw new RunnerFault(ErrorCode.UnsupportedCapability);
        OperationCommand command;
        try { command = OperationValidation.Read(request, configuration.HostId); }
        catch (Exception ex) when (ex is ArgumentException or System.Text.Json.JsonException) { throw new RunnerFault(ErrorCode.ProtocolMismatch); }
        lock (sync)
        {
            var priorHash = (string?)Scalar("SELECT commandHash FROM ExecutionLedger WHERE operationId=$id;", null, ("$id", request.OperationId.ToString()));
            if (priorHash is not null)
            {
                if (priorHash != request.CommandSha256) throw new RunnerFault(ErrorCode.OperationConflict);
                return GetExecution(request.OperationId);
            }
            if (clock.GetUtcNow() > command.StartBeforeUtc) throw new RunnerFault(ErrorCode.DeadlineExpired);
            ValidateExecutionGate(command);
            var serverId = command.ServerId!.Value;
            if (Convert.ToInt64(Scalar("SELECT COUNT(*) FROM ExecutionLedger WHERE serverId=$server AND status IN ('Accepted','Running');", null, ("$server", serverId.ToString()))) > 0) throw new RunnerFault(ErrorCode.ReservationConflict);
            Execute("INSERT INTO ExecutionLedger(operationId,commandHash,commandJson,serverId,reservation,workflow,fence,kind,status,phase,statusVersion,acceptedUtc,deadlineUtc) VALUES($id,$hash,$json,$server,$reservation,$workflow,$fence,$kind,'Accepted','Accepted',1,$utc,$deadline);", null,
                ("$id", request.OperationId.ToString()), ("$hash", request.CommandSha256), ("$json", request.CommandJson), ("$server", serverId.ToString()), ("$reservation", command.ReservationId!.Value.ToString()), ("$workflow", command.WorkflowId!.Value.ToString()), ("$fence", command.ReservationFence!.Value), ("$kind", command.Kind.ToString()), ("$utc", clock.GetUtcNow().ToString("O")), ("$deadline", command.StartBeforeUtc.ToString("O")));
            var accepted = GetExecution(request.OperationId);
            _ = Task.Run(() => RunOperationAsync(command));
            return accepted;
        }
    }

    public ExecutionSnapshot GetExecution(Guid operationId)
    {
        if (operationId == Guid.Empty) throw new RunnerFault(ErrorCode.ProtocolMismatch);
        lock (sync)
        {
            using var command = Command("SELECT commandHash,status,phase,statusVersion,result,errorCode,COALESCE(finishedUtc,startedUtc,acceptedUtc),serverId,commandJson FROM ExecutionLedger WHERE operationId=$id;", null, ("$id", operationId.ToString()));
            using var reader = command.ExecuteReader();
            if (!reader.Read()) throw new RunnerFault(ErrorCode.StateConflict);
            var serverId = Guid.Parse(reader.GetString(7));
            var commandJson = reader.GetString(8);
            var result = reader.IsDBNull(4) ? ControlJson.Element(new { }) : ControlJson.Deserialize<System.Text.Json.JsonElement>(reader.GetString(4));
            var snapshot = new ExecutionSnapshot
            {
                OperationId = operationId, CommandSha256 = reader.GetString(0), Status = Enum.Parse<OperationStatus>(reader.GetString(1)), Phase = Enum.Parse<ExecutionPhase>(reader.GetString(2)), StatusVersion = reader.GetInt64(3), Result = result,
                ErrorCode = reader.IsDBNull(5) ? null : reader.GetString(5), UpdatedAtUtc = DateTimeOffset.Parse(reader.GetString(6))
            };
            reader.Close();
            var session = Scalar("SELECT currentSessionId FROM Servers WHERE id=$server;", null, ("$server", serverId.ToString()));
            var targetSession = ControlJson.Deserialize<OperationCommand>(commandJson).ExpectedServerSessionId;
            return snapshot with { ServerSessionId = session is string value ? Guid.Parse(value) : targetSession };
        }
    }

    private void ValidateExecutionGate(OperationCommand command)
    {
        var serverId = command.ServerId!.Value;
        using var query = Command("SELECT config,stateRevision,processState,currentSessionId,recoveryRequired FROM Servers WHERE id=$id;", null, ("$id", serverId.ToString()));
        using var reader = query.ExecuteReader();
        if (!reader.Read()) throw new RunnerFault(ErrorCode.StateConflict);
        var config = ControlJson.Deserialize<ServerConfig>(reader.GetString(0));
        var revision = reader.GetInt64(1);
        var state = Enum.Parse<ProcessState>(reader.GetString(2));
        var session = reader.IsDBNull(3) ? (Guid?)null : Guid.Parse(reader.GetString(3));
        var recovery = reader.GetInt64(4) != 0;
        reader.Close();
        if (config.Kind != ServerKind.Java || config.DependencyReadiness != DependencyReadiness.Running || config.DependsOnServerIds.Length != 0) throw new RunnerFault(ErrorCode.UnsupportedCapability);
        if (config.Revision != command.ConfigRevision) throw new RunnerFault(ErrorCode.StaleRevision);
        if (revision != command.ExpectedStateRevision || session != command.ExpectedServerSessionId) throw new RunnerFault(ErrorCode.StateConflict);
        var rescueKill = command.Kind == OperationKind.Kill && IsRescueKill(command);
        using (var pendingData = Command("SELECT operationId,kind,reservation,workflow,fence,status,readyForRestart FROM DataOperationLedger WHERE serverId=$server AND status IN ('Accepted','Running','NeedsAttention');", null, ("$server", serverId.ToString())))
        using (var pendingReader = pendingData.ExecuteReader())
            while (pendingReader.Read())
            {
                var parentId = Guid.Parse(pendingReader.GetString(0));
                var parentKind = Enum.Parse<OperationKind>(pendingReader.GetString(1));
                var sameHold = pendingReader.GetString(2) == command.ReservationId?.ToString() && pendingReader.GetString(3) == command.WorkflowId?.ToString() && pendingReader.GetInt64(4) == command.ReservationFence;
                var active = pendingReader.GetString(5) == OperationStatus.Running.ToString();
                var childStop = active && command.Kind == OperationKind.Stop && (parentKind is OperationKind.CreateBackup or OperationKind.RestoreBackup) && command.OperationId == DataExecution.ChildOperationId(parentId, OperationKind.Stop);
                var childStart = active && pendingReader.GetBoolean(6) && command.Kind == OperationKind.Start && (parentKind is OperationKind.CreateBackup or OperationKind.RestoreBackup) && command.OperationId == DataExecution.ChildOperationId(parentId, OperationKind.Start);
                if (!sameHold || !(childStop || childStart || rescueKill)) throw new RunnerFault(ErrorCode.RecoveryRequired);
            }
        if ((recovery || state == ProcessState.Unmanaged) && !rescueKill) throw new RunnerFault(ErrorCode.RecoveryRequired);
        if (command.Kind == OperationKind.Start && state != ProcessState.Stopped) throw new RunnerFault(ErrorCode.StateConflict);
        if (command.Kind != OperationKind.Start && state != ProcessState.Running && !(command.Kind == OperationKind.Stop && state == ProcessState.Stopped) && !(rescueKill && state is ProcessState.Stopping or ProcessState.Unmanaged)) throw new RunnerFault(ErrorCode.StateConflict);
        if (state == ProcessState.Running && session is null) throw new RunnerFault(ErrorCode.RecoveryRequired);
        var hold = ReadHolds().SingleOrDefault(h => h.ReservationId == command.ReservationId);
        if (hold is null || hold.OwnerWorkflowId != command.WorkflowId || hold.Fence != command.ReservationFence || !hold.ResourceKeys.Contains("server:" + serverId)) throw new RunnerFault(ErrorCode.ReservationConflict);
    }

    private bool IsRescueKill(OperationCommand command) => Convert.ToInt64(Scalar("SELECT COUNT(*) FROM ExecutionLedger WHERE serverId=$server AND reservation=$reservation AND workflow=$workflow AND fence=$fence AND kind IN ('Stop','Restart') AND status='NeedsAttention';", null,
        ("$server", command.ServerId!.Value.ToString()), ("$reservation", command.ReservationId!.Value.ToString()), ("$workflow", command.WorkflowId!.Value.ToString()), ("$fence", command.ReservationFence!.Value))) > 0;

    private async Task RunOperationAsync(OperationCommand command)
    {
        try
        {
            lock (sync)
            {
                if (clock.GetUtcNow() > command.StartBeforeUtc)
                {
                    ChangeExecution(command.OperationId, OperationStatus.Expired, ExecutionPhase.Completed, ErrorCode.DeadlineExpired);
                    return;
                }
                // A gate may have changed after durable acceptance; recheck against current hold and state.
                ValidateExecutionGate(command);
                ChangeExecution(command.OperationId, OperationStatus.Running, ExecutionPhase.Running);
            }
            switch (command.Kind)
            {
                case OperationKind.Start: await StartAsync(command); break;
                case OperationKind.Stop: await StopAsync(command); break;
                case OperationKind.Restart:
                    await StopAsync(command, true);
                    if (GetExecution(command.OperationId).Status == OperationStatus.Running) await StartAsync(command, true);
                    break;
                case OperationKind.Kill: await KillAsync(command); break;
                case OperationKind.ConsoleCommand: await ConsoleAsync(command); break;
                default: throw new RunnerFault(ErrorCode.UnsupportedCapability);
            }
        }
        catch (RunnerFault fault)
        {
            lock (sync) if (!OperationValidation.IsTerminal(GetExecution(command.OperationId).Status) && GetExecution(command.OperationId).Status != OperationStatus.NeedsAttention)
                ChangeExecution(command.OperationId, fault.Code == ErrorCode.RecoveryRequired ? OperationStatus.NeedsAttention : OperationStatus.Failed, ExecutionPhase.Completed, fault.Code);
        }
        catch (Exception)
        {
            lock (sync) if (!OperationValidation.IsTerminal(GetExecution(command.OperationId).Status))
                ChangeExecution(command.OperationId, OperationStatus.NeedsAttention, ExecutionPhase.Unknown, ErrorCode.RecoveryRequired);
        }
    }

    private void ChangeExecution(Guid id, OperationStatus status, ExecutionPhase phase, ErrorCode? error = null, object? result = null)
    {
        var terminal = OperationValidation.IsTerminal(status) || status == OperationStatus.NeedsAttention;
        Execute("UPDATE ExecutionLedger SET status=$status,phase=$phase,statusVersion=statusVersion+1,startedUtc=COALESCE(startedUtc,$now),finishedUtc=CASE WHEN $terminal=1 THEN $now ELSE finishedUtc END,errorCode=$error,result=$result WHERE operationId=$id;", null,
            ("$status", status.ToString()), ("$phase", phase.ToString()), ("$now", clock.GetUtcNow().ToString("O")), ("$terminal", terminal ? 1 : 0), ("$error", error?.ToString() ?? (object)DBNull.Value), ("$result", result is null ? DBNull.Value : ControlJson.Serialize(result)), ("$id", id.ToString()));
        if (status == OperationStatus.NeedsAttention)
            Execute("UPDATE Servers SET recoveryRequired=1,stateRevision=stateRevision+1 WHERE id=(SELECT serverId FROM ExecutionLedger WHERE operationId=$id) AND recoveryRequired=0;", null, ("$id", id.ToString()));
    }

    private ServerConfig GetServerConfig(Guid serverId)
    {
        var json = (string?)Scalar("SELECT config FROM Servers WHERE id=$id;", null, ("$id", serverId.ToString())) ?? throw new RunnerFault(ErrorCode.StateConflict);
        return ControlJson.Deserialize<ServerConfig>(json);
    }

    private void SetRuntime(Guid serverId, ProcessState state, Guid? sessionId, string desiredState, bool recovery = false)
        => Execute("UPDATE Servers SET processState=$state,currentSessionId=$session,desiredState=$desired,recoveryRequired=$recovery,stateRevision=stateRevision+1 WHERE id=$server;", null,
            ("$state", state.ToString()), ("$session", sessionId?.ToString() ?? (object)DBNull.Value), ("$desired", desiredState), ("$recovery", recovery ? 1 : 0), ("$server", serverId.ToString()));

    private async Task StartAsync(OperationCommand command, bool fromRestart = false)
    {
        var serverId = command.ServerId!.Value;
        ServerConfig config;
        string executable;
        string directory;
        Guid sessionId;
        lock (sync)
        {
            config = GetServerConfig(serverId);
            var root = configuration.LocalResources.Roots.Single(r => r.Id == config.ManagedRootId);
            var binary = configuration.LocalResources.Executables.Single(e => e.Id == config.ExecutableId);
            executable = binary.Path;
            directory = LocalResourceBoundary.Resolve(root.Path, config.ServerDirectory);
            LocalResourceBoundary.ValidateResources(configuration.LocalResources);
            LocalResourceBoundary.ValidateInventory(new InventorySnapshot { HostId = configuration.HostId, Revision = 1, Servers = [config] }, configuration.LocalResources);
            if (!Directory.Exists(directory)) throw new RunnerFault(ErrorCode.PathOutsideRoot);
            if (fromRestart && (string?)Scalar("SELECT processState FROM Servers WHERE id=$id;", null, ("$id", serverId.ToString())) != ProcessState.Stopped.ToString()) throw new RunnerFault(ErrorCode.StateConflict);
            sessionId = Guid.NewGuid();
            var fingerprint = Fingerprint(executable);
            using var transaction = database.BeginTransaction();
            Execute("INSERT INTO ServerSessions(sessionId,serverId,startOperationId,runnerEpoch,executableFingerprint,configRevision,startedUtc) VALUES($session,$server,$operation,$epoch,$fingerprint,$revision,$now);", transaction,
                ("$session", sessionId.ToString()), ("$server", serverId.ToString()), ("$operation", command.OperationId.ToString()), ("$epoch", Epoch.ToString()), ("$fingerprint", fingerprint), ("$revision", config.Revision), ("$now", clock.GetUtcNow().ToString("O")));
            Execute("UPDATE Servers SET processState='Starting',currentSessionId=$session,desiredState='Running',stateRevision=stateRevision+1 WHERE id=$server;", transaction, ("$session", sessionId.ToString()), ("$server", serverId.ToString()));
            Execute("UPDATE ExecutionLedger SET phase='Starting',statusVersion=statusVersion+1 WHERE operationId=$id;", transaction, ("$id", command.OperationId.ToString()));
            transaction.Commit();
        }
        Process? process = null;
        try
        {
            var startInfo = new ProcessStartInfo(executable) { UseShellExecute = false, RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true, WorkingDirectory = directory, CreateNoWindow = true };
            startInfo.Environment.Clear();
            foreach (var arg in config.ArgumentList) startInfo.ArgumentList.Add(arg);
            process = Process.Start(startInfo) ?? throw new RunnerFault(ErrorCode.RecoveryRequired);
            var created = process.StartTime.ToUniversalTime();
            lock (sync)
            {
                ownedProcesses[serverId] = process;
                Execute("UPDATE ServerSessions SET pid=$pid,processCreatedUtc=$created WHERE sessionId=$session;", null, ("$pid", process.Id), ("$created", new DateTimeOffset(created, TimeSpan.Zero).ToString("O")), ("$session", sessionId.ToString()));
            }
            _ = Task.Run(() => PumpLogAsync(process.StandardOutput, serverId, sessionId, "stdout"));
            _ = Task.Run(() => PumpLogAsync(process.StandardError, serverId, sessionId, "stderr"));
            _ = Task.Run(() => MonitorExitAsync(process, serverId, sessionId));
            using var startup = new CancellationTokenSource(TimeSpan.FromSeconds(Math.Min(config.StartupTimeoutSeconds, command.ExecutionTimeoutSeconds)));
            if (config.ListenPort is int port) await WaitForPortAsync(process, port, startup.Token);
            if (process.HasExited) throw new RunnerFault(ErrorCode.StateConflict);
            lock (sync)
            {
                if ((string?)Scalar("SELECT processState FROM Servers WHERE id=$server;", null, ("$server", serverId.ToString())) == ProcessState.Starting.ToString())
                {
                    SetRuntime(serverId, ProcessState.Running, sessionId, "Running");
                    ChangeExecution(command.OperationId, OperationStatus.Succeeded, ExecutionPhase.Completed, result: new { serverSessionId = sessionId, processId = process.Id });
                }
            }
        }
        catch
        {
            lock (sync)
            {
                var alive = process is not null && !process.HasExited;
                SetRuntime(serverId, alive ? ProcessState.Unmanaged : ProcessState.Stopped, alive ? sessionId : null, "Stopped", alive);
                if (!alive) Execute("UPDATE ServerSessions SET exitedUtc=$now,exitCode=$code,lastExitWasExpected=0 WHERE sessionId=$session;", null,
                    ("$now", clock.GetUtcNow().ToString("O")), ("$code", process?.ExitCode ?? -1), ("$session", sessionId.ToString()));
            }
            throw;
        }
    }

    private static async Task WaitForPortAsync(Process process, int port, CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            if (process.HasExited) throw new RunnerFault(ErrorCode.StateConflict);
            try { using var client = new TcpClient(); await client.ConnectAsync("127.0.0.1", port, cancellationToken); return; }
            catch (SocketException) { }
            await Task.Delay(250, cancellationToken);
        }
        throw new RunnerFault(ErrorCode.Timeout);
    }

    private static string Fingerprint(string executable)
    {
        LocalResourceBoundary.ValidateLocalPath(executable);
        using var file = new FileStream(executable, FileMode.Open, FileAccess.Read, FileShare.Read);
        return Path.GetFullPath(executable).ToLowerInvariant() + ":" + Convert.ToHexStringLower(SHA256.HashData(file));
    }

    private async Task PumpLogAsync(StreamReader reader, Guid serverId, Guid sessionId, string stream)
    {
        var buffer = new char[4096];
        var line = new StringBuilder();
        try
        {
            int count;
            while ((count = await reader.ReadAsync(buffer)) > 0)
            {
                for (var i = 0; i < count; i++)
                {
                    if (buffer[i] == '\n') { AppendLog(serverId, sessionId, stream, line.ToString().TrimEnd('\r')); line.Clear(); }
                    else line.Append(buffer[i]);
                    if (line.Length >= 32768) { AppendLog(serverId, sessionId, stream, line.ToString(), true); line.Clear(); }
                }
            }
            if (line.Length > 0) AppendLog(serverId, sessionId, stream, line.ToString());
        }
        catch (Exception) { lock (sync) if (!disposed) MarkUnmanaged(serverId, sessionId); }
    }

    private async Task MonitorExitAsync(Process process, Guid serverId, Guid sessionId)
    {
        try { await process.WaitForExitAsync(); }
        catch { return; }
        lock (sync)
        {
            if (disposed) return;
            var desiredAtExit = (string?)Scalar("SELECT desiredState FROM Servers WHERE id=$server;", null, ("$server", serverId.ToString()));
            Execute("UPDATE ServerSessions SET exitedUtc=$now,exitCode=$code,lastExitWasExpected=COALESCE(lastExitWasExpected,$expected) WHERE sessionId=$session AND exitedUtc IS NULL;", null,
                ("$now", clock.GetUtcNow().ToString("O")), ("$code", process.ExitCode),
                ("$expected", desiredAtExit == "Stopped" ? 1 : 0), ("$session", sessionId.ToString()));
            if (ownedProcesses.TryGetValue(serverId, out var owned) && ReferenceEquals(owned, process)) ownedProcesses.Remove(serverId);
            var current = CurrentSession(serverId);
            if (current == sessionId.ToString())
            {
                var desired = (string?)Scalar("SELECT desiredState FROM Servers WHERE id=$server;", null, ("$server", serverId.ToString()));
                if (desired == "Stopped")
                {
                    // A delayed exit settles a timed-out Stop; Restart did not reach its new Start.
                    Execute("UPDATE ExecutionLedger SET status=CASE WHEN kind='Stop' THEN 'Succeeded' ELSE 'Failed' END,phase='Completed',statusVersion=statusVersion+1,finishedUtc=$now WHERE serverId=$server AND kind IN ('Stop','Restart') AND status='NeedsAttention' AND phase='Stopping';", null,
                        ("$server", serverId.ToString()), ("$now", clock.GetUtcNow().ToString("O")));
                    var pending = Convert.ToInt64(Scalar("SELECT COUNT(*) FROM ExecutionLedger WHERE serverId=$server AND status='NeedsAttention';", null, ("$server", serverId.ToString()))) != 0;
                    SetRuntime(serverId, ProcessState.Stopped, null, "Stopped", pending);
                }
                else ScheduleCrashRestart(serverId, process.ExitCode);
            }
        }
    }

    private void MarkUnmanaged(Guid serverId, Guid sessionId)
    {
        if (CurrentSession(serverId) == sessionId.ToString()) SetRuntime(serverId, ProcessState.Unmanaged, sessionId, "Stopped", true);
    }

    private string? CurrentSession(Guid serverId) => Scalar("SELECT currentSessionId FROM Servers WHERE id=$server;", null, ("$server", serverId.ToString())) as string;
}
