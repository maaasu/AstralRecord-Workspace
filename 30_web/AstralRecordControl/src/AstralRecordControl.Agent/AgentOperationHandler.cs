using AstralRecordControl.Contracts;

namespace AstralRecordControl.Agent;

public sealed class AgentProcessControlGate(string markerPath)
{
    public bool Enabled => File.Exists(markerPath);
}

public sealed class AgentOperationHandler(AgentConfiguration configuration, AgentStore store, IRunnerClient runner, AgentProcessControlGate? processGate = null)
{
    private readonly SemaphoreSlim gate = new(1, 1);

    public async Task<ExecutionSnapshot> DispatchAsync(DispatchOperationRequest request, Guid activeEpoch, CancellationToken cancellationToken)
    {
        RequireEpoch(request.ConnectionEpoch, activeEpoch);
        if (processGate?.Enabled == false) throw new AgentFault(ErrorCode.UnsupportedCapability);
        if (request.ProtocolVersion != ControlProtocol.Version || request.OperationId == Guid.Empty) throw new AgentFault(ErrorCode.ProtocolMismatch);
        var execute = new ExecuteRequest(request.OperationId, request.CommandJson, request.CommandSha256);
        var command = OperationValidation.Read(execute, configuration.HostId);
        await gate.WaitAsync(cancellationToken);
        try
        {
            var existing = store.GetOperation(request.OperationId);
            if (existing != null)
            {
                if (existing.CommandSha256 != request.CommandSha256) throw new AgentFault(ErrorCode.OperationConflict);
                if (OperationValidation.IsTerminal(existing.Status)) return existing;
                // A previous Execute response may have been lost. Query the Runner ledger;
                // never issue Execute again for an operation whose result is uncertain.
                return await RefreshAsync(existing, cancellationToken);
            }
            if (DateTimeOffset.UtcNow > command.StartBeforeUtc) throw new AgentFault(ErrorCode.DeadlineExpired);
            var accepted = store.AcceptOperation(execute);
            try { return store.SaveOperation(await runner.ExecuteAsync(execute, cancellationToken)); }
            catch (Exception exception) when (exception is IOException or OperationCanceledException or AgentFault)
            {
                try { return await RefreshAsync(accepted, cancellationToken); }
                catch (Exception query) when (query is IOException or OperationCanceledException or AgentFault)
                {
                    return store.SaveOperation(accepted with { Status = OperationStatus.NeedsAttention, StatusVersion = accepted.StatusVersion + 1, Phase = ExecutionPhase.Unknown, ErrorCode = ErrorCode.RecoveryRequired.ToString(), UpdatedAtUtc = DateTimeOffset.UtcNow });
                }
            }
        }
        finally { gate.Release(); }
    }

    public async Task<ExecutionSnapshot> QueryAsync(AgentExecutionRequest request, Guid activeEpoch, CancellationToken cancellationToken)
    {
        RequireEpoch(request.ConnectionEpoch, activeEpoch);
        var existing = store.GetOperation(request.OperationId) ?? throw new AgentFault(ErrorCode.OperationConflict);
        return await RefreshAsync(existing, cancellationToken);
    }

    public async Task<PreparedHold> PrepareHoldAsync(PrepareHoldRequest request, Guid activeEpoch, CancellationToken cancellationToken)
    {
        RequireEpoch(request.ConnectionEpoch, activeEpoch);
        if (processGate?.Enabled == false) throw new AgentFault(ErrorCode.UnsupportedCapability);
        if (request.Hold.ReservationId == Guid.Empty || request.Hold.OwnerWorkflowId == Guid.Empty || request.Hold.ResourceKeys.Length == 0) throw new AgentFault(ErrorCode.ReservationConflict);
        var hold = await runner.AcquireHoldAsync(request.Hold, cancellationToken);
        if (hold.ReservationId != request.Hold.ReservationId || hold.OwnerWorkflowId != request.Hold.OwnerWorkflowId || hold.Fence < 1) throw new AgentFault(ErrorCode.RecoveryRequired);
        var snapshot = await runner.GetSnapshotAsync(cancellationToken);
        return new PreparedHold(hold, snapshot);
    }

    public async Task<ReleasePreparedHoldResponse> ReleaseHoldAsync(ReleasePreparedHoldRequest request, Guid activeEpoch, CancellationToken cancellationToken)
    {
        RequireEpoch(request.ConnectionEpoch, activeEpoch);
        var released = await runner.ReleaseHoldAsync(request.Hold, cancellationToken);
        return new ReleasePreparedHoldResponse(request.Hold.ReservationId, released);
    }

    public async Task<LogBatch> ReadLogsAsync(AgentReadLogsRequest request, Guid activeEpoch, CancellationToken cancellationToken)
    {
        RequireEpoch(request.ConnectionEpoch, activeEpoch);
        if (request.Query.ServerId == Guid.Empty || request.Query.Limit is < 1 or > 1000 || request.Query.AfterSequence < 0) throw new AgentFault(ErrorCode.ProtocolMismatch);
        return await runner.ReadLogsAsync(request.Query, cancellationToken);
    }

    public async Task<RecoveryInspection> InspectRecoveryAsync(AgentInspectRecoveryRequest request, Guid activeEpoch, CancellationToken cancellationToken)
    {
        RequireEpoch(request.ConnectionEpoch, activeEpoch);
        if (request.ServerId == Guid.Empty) throw new AgentFault(ErrorCode.ProtocolMismatch);
        return await runner.InspectRecoveryAsync(request.ServerId, cancellationToken);
    }

    public async Task RefreshOutstandingAsync(CancellationToken cancellationToken)
    {
        foreach (var pending in store.ReadNonTerminalOperations())
        {
            var current = store.GetOperation(pending.OperationId)!;
            try { await RefreshAsync(current, cancellationToken); }
            catch (Exception exception) when (exception is IOException or AgentFault or OperationCanceledException) { }
        }
    }

    private async Task<ExecutionSnapshot> RefreshAsync(ExecutionSnapshot existing, CancellationToken cancellationToken)
    {
        var runnerState = await runner.GetExecutionAsync(existing.OperationId, cancellationToken);
        if (runnerState.OperationId != existing.OperationId || runnerState.CommandSha256 != existing.CommandSha256) throw new AgentFault(ErrorCode.RecoveryRequired);
        return store.SaveOperation(runnerState);
    }

    private static void RequireEpoch(Guid received, Guid active)
    {
        if (received == Guid.Empty || received != active) throw new AgentFault(ErrorCode.StateConflict);
    }
}
