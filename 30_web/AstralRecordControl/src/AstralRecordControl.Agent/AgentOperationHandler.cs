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
                return await RefreshAsync(existing, cancellationToken);
            }
            var accepted = store.AcceptOperation(execute);
            if (DateTimeOffset.UtcNow > command.StartBeforeUtc) return Expire(accepted);
            store.MarkDispatchAttempted(request.OperationId);
            try { return store.SaveOperation(await runner.ExecuteAsync(execute, cancellationToken)); }
            catch (Exception exception) when (exception is IOException or OperationCanceledException or AgentFault)
            {
                try { return await RefreshAsync(accepted, cancellationToken); }
                catch (Exception query) when (query is IOException or OperationCanceledException)
                {
                    // Neither transport call established what the Runner did. Keep the
                    // durable Accepted command for a later authoritative ledger query.
                    return accepted;
                }
            }
        }
        finally { gate.Release(); }
    }

    public async Task<ExecutionSnapshot> QueryAsync(AgentExecutionRequest request, Guid activeEpoch, CancellationToken cancellationToken)
    {
        RequireEpoch(request.ConnectionEpoch, activeEpoch);
        await gate.WaitAsync(cancellationToken);
        try
        {
            var existing = store.GetOperation(request.OperationId) ?? throw new AgentFault(ErrorCode.OperationConflict);
            return await RefreshAsync(existing, cancellationToken);
        }
        finally { gate.Release(); }
    }

    public async Task<PreparedHold> PrepareHoldAsync(PrepareHoldRequest request, Guid activeEpoch, CancellationToken cancellationToken)
    {
        RequireEpoch(request.ConnectionEpoch, activeEpoch);
        if (processGate?.Enabled == false) throw new AgentFault(ErrorCode.UnsupportedCapability);
        if (request.Hold.ReservationId == Guid.Empty || request.Hold.OwnerWorkflowId == Guid.Empty || request.Hold.ResourceKeys.Length == 0) throw new AgentFault(ErrorCode.ReservationConflict);
        await gate.WaitAsync(cancellationToken);
        try
        {
            var hold = await runner.AcquireHoldAsync(request.Hold, cancellationToken);
            if (hold.ReservationId != request.Hold.ReservationId || hold.OwnerWorkflowId != request.Hold.OwnerWorkflowId || hold.Fence < 1) throw new AgentFault(ErrorCode.RecoveryRequired);
            return new PreparedHold(hold, await runner.GetSnapshotAsync(cancellationToken));
        }
        finally { gate.Release(); }
    }

    public async Task<ReleasePreparedHoldResponse> ReleaseHoldAsync(ReleasePreparedHoldRequest request, Guid activeEpoch, CancellationToken cancellationToken)
    {
        RequireEpoch(request.ConnectionEpoch, activeEpoch);
        await gate.WaitAsync(cancellationToken);
        try { return new ReleasePreparedHoldResponse(request.Hold.ReservationId, await runner.ReleaseHoldAsync(request.Hold, cancellationToken)); }
        finally { gate.Release(); }
    }

    public async Task<RunnerSnapshot> GetSnapshotAsync(CancellationToken cancellationToken)
    {
        await gate.WaitAsync(cancellationToken);
        try { return await runner.GetSnapshotAsync(cancellationToken); }
        finally { gate.Release(); }
    }

    public Task<RunnerSnapshot> SnapshotAsync(AgentSnapshotRequest request, Guid activeEpoch, CancellationToken cancellationToken)
    {
        RequireEpoch(request.ConnectionEpoch, activeEpoch);
        return GetSnapshotAsync(cancellationToken);
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
            await gate.WaitAsync(cancellationToken);
            try
            {
                var current = store.GetOperation(pending.OperationId)!;
                await RefreshAsync(current, cancellationToken);
            }
            catch (Exception exception) when (exception is IOException or AgentFault or OperationCanceledException) { }
            finally { gate.Release(); }
        }
    }

    private async Task<ExecutionSnapshot> RefreshAsync(ExecutionSnapshot existing, CancellationToken cancellationToken)
    {
        ExecutionSnapshot runnerState;
        try { runnerState = await runner.GetExecutionAsync(existing.OperationId, cancellationToken); }
        catch (AgentFault fault) when (fault.Code == ErrorCode.StateConflict)
        {
            var dispatch = store.GetDispatch(existing.OperationId);
            // StateConflict from GetExecution means this ID is absent from the Runner
            // ledger. A transport failure never enters this branch. Execute with the
            // original ID/hash so a concurrent acceptance remains idempotent.
            var command = OperationValidation.Read(dispatch.Command, configuration.HostId);
            if (DateTimeOffset.UtcNow > command.StartBeforeUtc) return Expire(existing);
            store.MarkDispatchAttempted(existing.OperationId);
            try { runnerState = await runner.ExecuteAsync(dispatch.Command, cancellationToken); }
            catch (AgentFault error) when (error.Code == ErrorCode.DeadlineExpired) { return Expire(existing); }
        }
        if (runnerState.OperationId != existing.OperationId || runnerState.CommandSha256 != existing.CommandSha256) throw new AgentFault(ErrorCode.RecoveryRequired);
        return store.SaveOperation(runnerState);
    }

    private ExecutionSnapshot Expire(ExecutionSnapshot accepted) => store.SaveOperation(accepted with
    {
        Status = OperationStatus.Expired, StatusVersion = accepted.StatusVersion + 1,
        Phase = ExecutionPhase.Completed, ErrorCode = ErrorCode.DeadlineExpired.ToString(), UpdatedAtUtc = DateTimeOffset.UtcNow
    });

    private static void RequireEpoch(Guid received, Guid active)
    {
        if (received == Guid.Empty || received != active) throw new AgentFault(ErrorCode.StateConflict);
    }
}
