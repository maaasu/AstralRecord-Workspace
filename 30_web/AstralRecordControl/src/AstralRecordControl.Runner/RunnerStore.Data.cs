using AstralRecordControl.Contracts;

namespace AstralRecordControl.Runner;

public sealed partial class RunnerStore
{
    public ExecutionSnapshot BeginDataOperation(ExecuteRequest request)
    {
        if (!configuration.EnableProcessControl || !configuration.EnableFileControl) throw new RunnerFault(ErrorCode.UnsupportedCapability);
        OperationCommand command;
        try { command = OperationValidation.Read(request, configuration.HostId); }
        catch (Exception ex) when (ex is ArgumentException or System.Text.Json.JsonException) { throw new RunnerFault(ErrorCode.ProtocolMismatch); }
        if (!IsDataKind(command.Kind)) throw new RunnerFault(ErrorCode.UnsupportedCapability);
        lock (sync)
        {
            var previous = (string?)Scalar("SELECT commandHash FROM DataOperationLedger WHERE operationId=$id;", null, ("$id", request.OperationId.ToString()));
            if (previous != null)
            {
                if (previous != request.CommandSha256) throw new RunnerFault(ErrorCode.OperationConflict);
                return GetDataOperation(request.OperationId);
            }
            if (clock.GetUtcNow() > command.StartBeforeUtc) throw new RunnerFault(ErrorCode.DeadlineExpired);
            ValidateInitialDataGate(command);
            var serverId = command.ServerId!.Value;
            if (Convert.ToInt64(Scalar("SELECT COUNT(*) FROM DataOperationLedger WHERE serverId=$server AND status IN ('Accepted','Running','NeedsAttention');", null, ("$server", serverId.ToString()))) > 0) throw new RunnerFault(ErrorCode.ReservationConflict);
            if (Convert.ToInt64(Scalar("SELECT COUNT(*) FROM ExecutionLedger WHERE serverId=$server AND status IN ('Accepted','Running','NeedsAttention');", null, ("$server", serverId.ToString()))) > 0) throw new RunnerFault(ErrorCode.ReservationConflict);
            Execute("INSERT INTO DataOperationLedger(operationId,commandHash,commandJson,serverId,reservation,workflow,fence,kind,status,statusVersion,acceptedUtc) VALUES($id,$hash,$json,$server,$reservation,$workflow,$fence,$kind,'Running',1,$utc);", null,
                ("$id", command.OperationId.ToString()), ("$hash", request.CommandSha256), ("$json", request.CommandJson), ("$server", serverId.ToString()), ("$reservation", command.ReservationId!.Value.ToString()), ("$workflow", command.WorkflowId!.Value.ToString()), ("$fence", command.ReservationFence!.Value), ("$kind", command.Kind.ToString()), ("$utc", clock.GetUtcNow().ToString("O")));
            return GetDataOperation(request.OperationId);
        }
    }

    public ExecutionSnapshot VerifyDataOperation(Guid operationId)
    {
        lock (sync)
        {
            var current = GetDataOperation(operationId);
            if (current.Status != OperationStatus.Running) throw new RunnerFault(ErrorCode.RecoveryRequired);
            var command = ReadDataCommand(operationId);
            var server = GetSnapshot().Servers.SingleOrDefault(s => s.ServerId == command.ServerId) ?? throw new RunnerFault(ErrorCode.StateConflict);
            if (server.ConfigRevision != command.ConfigRevision) throw new RunnerFault(ErrorCode.StaleRevision);
            if (Convert.ToInt64(Scalar("SELECT COUNT(*) FROM ExecutionLedger WHERE serverId=$server AND status IN ('Accepted','Running','NeedsAttention');", null, ("$server", command.ServerId!.Value.ToString()))) > 0) throw new RunnerFault(ErrorCode.RecoveryRequired);
            if (command.Kind is OperationKind.ListFiles or OperationKind.ReadFile or OperationKind.ExportFiles)
            {
                if (server.ProcessState is not (ProcessState.Stopped or ProcessState.Running)) throw new RunnerFault(ErrorCode.RecoveryRequired);
            }
            else if (server.ProcessState != ProcessState.Stopped || server.CurrentSessionId is not null) throw new RunnerFault(ErrorCode.StateConflict);
            if (Convert.ToInt64(Scalar("SELECT recoveryRequired FROM Servers WHERE id=$id;", null, ("$id", command.ServerId!.Value.ToString()))) != 0) throw new RunnerFault(ErrorCode.RecoveryRequired);
            VerifyDataHold(command);
            return current;
        }
    }

    public ExecutionSnapshot MarkDataReadyForRestart(Guid operationId)
    {
        lock (sync)
        {
            var command = ReadDataCommand(operationId);
            if (command.Kind is not (OperationKind.CreateBackup or OperationKind.RestoreBackup)) throw new RunnerFault(ErrorCode.UnsupportedCapability);
            VerifyDataOperation(operationId);
            Execute("UPDATE DataOperationLedger SET readyForRestart=1,statusVersion=statusVersion+1 WHERE operationId=$id AND status='Running';", null, ("$id", operationId.ToString()));
            return GetDataOperation(operationId);
        }
    }

    public ExecutionSnapshot FinishDataOperation(FinishDataOperationRequest request)
    {
        lock (sync)
        {
            var previous = GetDataOperation(request.OperationId);
            if (previous.CommandSha256 != request.CommandSha256) throw new RunnerFault(ErrorCode.OperationConflict);
            if (request.Status is not (OperationStatus.Succeeded or OperationStatus.NeedsAttention)) throw new RunnerFault(ErrorCode.ProtocolMismatch);
            if (previous.Status == OperationStatus.NeedsAttention && request.Status == OperationStatus.NeedsAttention) return previous;
            if (previous.Status == OperationStatus.NeedsAttention) throw new RunnerFault(ErrorCode.RecoveryRequired);
            if (OperationValidation.IsTerminal(previous.Status))
            {
                if (previous.Status != request.Status) throw new RunnerFault(ErrorCode.OperationConflict);
                return previous;
            }
            if (request.Status == OperationStatus.Succeeded)
            {
                var command = ReadDataCommand(request.OperationId);
                var runtime = GetSnapshot().Servers.SingleOrDefault(s => s.ServerId == command.ServerId) ?? throw new RunnerFault(ErrorCode.StateConflict);
                if ((command.Kind is OperationKind.CreateBackup or OperationKind.RestoreBackup) && runtime.ProcessState == ProcessState.Running)
                {
                    if (runtime.ConfigRevision != command.ConfigRevision) throw new RunnerFault(ErrorCode.StaleRevision);
                    VerifyDataHold(command);
                    if (Convert.ToInt64(Scalar("SELECT readyForRestart FROM DataOperationLedger WHERE operationId=$id;", null, ("$id", request.OperationId.ToString()))) == 0) throw new RunnerFault(ErrorCode.RecoveryRequired);
                    var childId = DataExecution.ChildOperationId(request.OperationId, OperationKind.Start);
                    if ((string?)Scalar("SELECT status FROM ExecutionLedger WHERE operationId=$id;", null, ("$id", childId.ToString())) != OperationStatus.Succeeded.ToString()) throw new RunnerFault(ErrorCode.RecoveryRequired);
                }
                else VerifyDataOperation(request.OperationId);
            }
            using var transaction = database.BeginTransaction();
            Execute("UPDATE DataOperationLedger SET status=$status,statusVersion=statusVersion+1,finishedUtc=$utc WHERE operationId=$id;", transaction,
                ("$status", request.Status.ToString()), ("$utc", clock.GetUtcNow().ToString("O")), ("$id", request.OperationId.ToString()));
            if (request.Status == OperationStatus.NeedsAttention)
                Execute("UPDATE Servers SET recoveryRequired=1,stateRevision=stateRevision+1 WHERE id=(SELECT serverId FROM DataOperationLedger WHERE operationId=$id) AND recoveryRequired=0;", transaction, ("$id", request.OperationId.ToString()));
            transaction.Commit();
            return GetDataOperation(request.OperationId);
        }
    }

    public ExecutionSnapshot ResumeDataOperation(AgentResumeDataOperationRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.Reason) || request.Reason.Length > 1024 || request.Reason.Any(c => c is '\r' or '\n' or '\0')) throw new RunnerFault(ErrorCode.ProtocolMismatch);
        lock (sync)
        {
            var previous = GetDataOperation(request.OperationId);
            if (previous.Status != OperationStatus.NeedsAttention) throw new RunnerFault(ErrorCode.StateConflict);
            var command = ReadDataCommand(request.OperationId);
            if (command.Kind != OperationKind.RestoreBackup && !(command.Kind == OperationKind.CreateBackup && Convert.ToInt64(Scalar("SELECT readyForRestart FROM DataOperationLedger WHERE operationId=$id;", null, ("$id", request.OperationId.ToString()))) != 0)) throw new RunnerFault(ErrorCode.UnsupportedCapability);
            VerifyDataHold(command);
            var server = GetSnapshot().Servers.SingleOrDefault(s => s.ServerId == command.ServerId) ?? throw new RunnerFault(ErrorCode.StateConflict);
            if (server.ProcessState != ProcessState.Stopped || server.CurrentSessionId is not null || server.ConfigRevision != command.ConfigRevision) throw new RunnerFault(ErrorCode.RecoveryRequired);
            using var transaction = database.BeginTransaction();
            Execute("UPDATE DataOperationLedger SET status='Running',statusVersion=statusVersion+1,finishedUtc=NULL WHERE operationId=$id;", transaction, ("$id", request.OperationId.ToString()));
            Execute("INSERT INTO DataResumeAudit(id,operationId,reason,occurredUtc) VALUES($id,$operation,$reason,$utc);", transaction,
                ("$id", Guid.NewGuid().ToString()), ("$operation", request.OperationId.ToString()), ("$reason", request.Reason), ("$utc", clock.GetUtcNow().ToString("O")));
            Execute("UPDATE Servers SET recoveryRequired=0,stateRevision=stateRevision+1 WHERE id=$id AND recoveryRequired=1 AND NOT EXISTS (SELECT 1 FROM ExecutionLedger WHERE serverId=$id AND status='NeedsAttention');", transaction, ("$id", command.ServerId!.Value.ToString()));
            transaction.Commit();
            return GetDataOperation(request.OperationId);
        }
    }

    public ExecutionSnapshot GetDataOperation(Guid operationId)
    {
        if (operationId == Guid.Empty) throw new RunnerFault(ErrorCode.ProtocolMismatch);
        lock (sync)
        {
            using var query = Command("SELECT commandHash,status,statusVersion,COALESCE(finishedUtc,acceptedUtc) FROM DataOperationLedger WHERE operationId=$id;", null, ("$id", operationId.ToString()));
            using var reader = query.ExecuteReader();
            if (!reader.Read()) throw new RunnerFault(ErrorCode.StateConflict);
            return new ExecutionSnapshot { OperationId = operationId, CommandSha256 = reader.GetString(0), Status = Enum.Parse<OperationStatus>(reader.GetString(1)), StatusVersion = reader.GetInt64(2), Phase = ExecutionPhase.Running, UpdatedAtUtc = DateTimeOffset.Parse(reader.GetString(3)) };
        }
    }

    private OperationCommand ReadDataCommand(Guid operationId)
    {
        var json = (string?)Scalar("SELECT commandJson FROM DataOperationLedger WHERE operationId=$id;", null, ("$id", operationId.ToString())) ?? throw new RunnerFault(ErrorCode.StateConflict);
        return ControlJson.Deserialize<OperationCommand>(json);
    }

    private void ValidateInitialDataGate(OperationCommand command)
    {
        var server = GetSnapshot().Servers.SingleOrDefault(s => s.ServerId == command.ServerId) ?? throw new RunnerFault(ErrorCode.StateConflict);
        if (server.ConfigRevision != command.ConfigRevision) throw new RunnerFault(ErrorCode.StaleRevision);
        if (server.StateRevision != command.ExpectedStateRevision || server.CurrentSessionId != command.ExpectedServerSessionId) throw new RunnerFault(ErrorCode.StateConflict);
        if (server.ProcessState is not (ProcessState.Stopped or ProcessState.Running)) throw new RunnerFault(ErrorCode.RecoveryRequired);
        if ((command.Kind is OperationKind.WriteFile or OperationKind.FileMutation or OperationKind.ImportArtifact or OperationKind.ProvisionServer or OperationKind.PruneBackups or OperationKind.SetBackupProtection or OperationKind.DeleteBackup) && server.ProcessState != ProcessState.Stopped) throw new RunnerFault(ErrorCode.StateConflict);
        if (Convert.ToInt64(Scalar("SELECT recoveryRequired FROM Servers WHERE id=$id;", null, ("$id", command.ServerId!.Value.ToString()))) != 0) throw new RunnerFault(ErrorCode.RecoveryRequired);
        VerifyDataHold(command);
    }

    private void VerifyDataHold(OperationCommand command)
    {
        var hold = ReadHolds().SingleOrDefault(h => h.ReservationId == command.ReservationId);
        if (hold is null || hold.OwnerWorkflowId != command.WorkflowId || hold.Fence != command.ReservationFence || !hold.ResourceKeys.Contains("server:" + command.ServerId)) throw new RunnerFault(ErrorCode.ReservationConflict);
    }

    private static bool IsDataKind(OperationKind kind) => OperationValidation.IsP2Kind(kind);
}
