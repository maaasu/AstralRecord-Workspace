using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using AstralRecordControl.Agent.Storage;
using AstralRecordControl.Agent.Transfers;
using AstralRecordControl.Contracts;

namespace AstralRecordControl.Agent;

public sealed partial class AgentOperationHandler
{
    private readonly ConcurrentDictionary<Guid, byte> dataJobs = new();
    private AgentTransferClient? transferClient;
    private Func<Guid, string, string, CancellationToken, Task>? publishResult;
    public void SetDataSession(AgentTransferClient? transfer, Func<Guid, string, string, CancellationToken, Task>? publisher)
    {
        transferClient = transfer;
        publishResult = publisher;
    }

    private static bool IsDataKind(OperationKind kind) => OperationValidation.IsP2Kind(kind);

    private async Task<ExecutionSnapshot> DispatchDataAsync(ExecuteRequest execute, OperationCommand command, CancellationToken cancellationToken)
    {
        if (!configuration.EnableFileControl || processGate?.Enabled == false || storage is null) throw new AgentFault(ErrorCode.UnsupportedCapability);
        await gate.WaitAsync(cancellationToken);
        try
        {
            var existing = store.GetOperation(command.OperationId);
            if (existing is not null)
            {
                if (existing.CommandSha256 != execute.CommandSha256) throw new AgentFault(ErrorCode.OperationConflict);
                return existing;
            }
            var accepted = store.AcceptOperation(execute);
            if (clock.GetUtcNow() > command.StartBeforeUtc) return Expire(accepted);
            StartDataJob(execute, command, false);
            return accepted;
        }
        finally { gate.Release(); }
    }

    private void StartDataJob(ExecuteRequest execute, OperationCommand command, bool resume, string? resumeReason = null)
    {
        if (!dataJobs.TryAdd(command.OperationId, 0)) return;
        _ = Task.Run(async () =>
        {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(command.ExecutionTimeoutSeconds));
            try { await RunDataJobAsync(execute, command, resume, resumeReason, timeout.Token); }
            finally { dataJobs.TryRemove(command.OperationId, out _); }
        });
    }

    private async Task RunDataJobAsync(ExecuteRequest execute, OperationCommand command, bool resume, string? resumeReason, CancellationToken cancellationToken)
    {
        var begun = false;
        var restoreBeforeJournal = false;
        var pendingAtStart = store.GetPendingResultState(command.OperationId);
        try
        {
            var inventory = store.GetAppliedInventory();
            var server = inventory.Servers.SingleOrDefault(s => s.ServerId == command.ServerId) ?? throw new AgentFault(ErrorCode.StaleRevision);
            if (server.Revision != command.ConfigRevision) throw new AgentFault(ErrorCode.StaleRevision);
            var profile = command.Kind is OperationKind.CreateBackup or OperationKind.PruneBackups ? ResolveProfile(inventory, command) : null;
            if (resume)
            {
                if (command.Kind is not (OperationKind.RestoreBackup or OperationKind.CreateBackup)) throw new AgentFault(ErrorCode.UnsupportedCapability);
                if (command.Kind == OperationKind.RestoreBackup && pendingAtStart is null)
                {
                    var journal = storage!.InspectRecovery(server);
                    if (journal is not null && journal.OperationId != command.OperationId) throw new AgentFault(ErrorCode.RecoveryRequired);
                    restoreBeforeJournal = journal is null && storage.GetRestoreReceipt(server, command.OperationId) is null;
                }
                await runner.ResumeDataOperationAsync(new AgentResumeDataOperationRequest(Guid.Empty, command.OperationId, resumeReason ?? "Explicit restore journal resume"), cancellationToken);
            }
            else
            {
                store.MarkDispatchAttempted(command.OperationId);
                var started = await runner.BeginDataOperationAsync(execute, cancellationToken);
                if (started.CommandSha256 != execute.CommandSha256 || started.Status != OperationStatus.Running) throw new AgentFault(ErrorCode.RecoveryRequired);
            }
            begun = true;
            store.AdvanceOperation(command.OperationId, OperationStatus.Running, ExecutionPhase.Running);
            var initial = await runner.GetSnapshotAsync(cancellationToken);
            var runtime = initial.Servers.SingleOrDefault(s => s.ServerId == command.ServerId) ?? throw new AgentFault(ErrorCode.StateConflict);
            if (!resume && (runtime.StateRevision != command.ExpectedStateRevision || runtime.CurrentSessionId != command.ExpectedServerSessionId)) throw new AgentFault(ErrorCode.StateConflict);
            var previouslyRunning = resume ? command.ExpectedServerSessionId is not null : runtime.ProcessState == ProcessState.Running;
            var restartRequired = pendingAtStart?.RestartRequired ?? false;
            if (pendingAtStart is null)
            {
                if ((command.Kind is OperationKind.CreateBackup or OperationKind.RestoreBackup) && previouslyRunning)
                    await RunChildProcessOperationAsync(command, OperationKind.Stop, cancellationToken);
                if (command.Kind is not (OperationKind.ListFiles or OperationKind.ReadFile)) await VerifyDataGuardAsync(command.OperationId, cancellationToken);
                object result;
                if (resume && command.Kind == OperationKind.CreateBackup)
                {
                    var completed = await storage!.GetCompletedBackupAsync(server, FileOperationValidation.Read<CreateBackupPayload>(command.Payload), cancellationToken, command.OperationId)
                        ?? throw new AgentFault(ErrorCode.RecoveryRequired);
                    result = BackupResult(completed);
                }
                else if (resume && command.Kind == OperationKind.RestoreBackup && !restoreBeforeJournal)
                    result = await storage!.ResumeRestoreAsync(server, command.OperationId, token => VerifyDataGuardAsync(command.OperationId, token), cancellationToken);
                else result = await ExecuteStorageAsync(command, server, profile, cancellationToken);
                if (result is RestoreResult restored) result = RestoreResultSummary(restored);
                restartRequired = command.Kind == OperationKind.CreateBackup && previouslyRunning && profile!.RestartIfPreviouslyRunning
                    || command.Kind == OperationKind.RestoreBackup && FileOperationValidation.Read<RestoreBackupPayload>(command.Payload).RestartAfterRestore;
                store.SavePendingResult(command.OperationId, ControlJson.Serialize(result), restartRequired);
            }
            if (restartRequired)
            {
                var beforeRestart = (await runner.GetSnapshotAsync(cancellationToken)).Servers.Single(s => s.ServerId == server.ServerId);
                if (beforeRestart.ProcessState == ProcessState.Stopped)
                {
                    await runner.MarkDataReadyForRestartAsync(command.OperationId, cancellationToken);
                    await RunChildProcessOperationAsync(command, OperationKind.Start, cancellationToken);
                }
                else await ConfirmChildStartAsync(command.OperationId, cancellationToken);
            }
            await runner.FinishDataOperationAsync(new FinishDataOperationRequest(command.OperationId, execute.CommandSha256, OperationStatus.Succeeded), cancellationToken);
            await PublishPendingResultAsync(command.OperationId, execute.CommandSha256, cancellationToken);
        }
        catch (Exception exception)
        {
            if (begun && exception is FileConflictException)
            {
                try
                {
                    await runner.FinishDataOperationAsync(new FinishDataOperationRequest(command.OperationId, execute.CommandSha256, OperationStatus.Rejected), CancellationToken.None);
                    store.AdvanceOperation(command.OperationId, OperationStatus.Rejected, ExecutionPhase.Completed, error: ErrorCode.StateConflict);
                    return;
                }
                catch { /* An unknown local RPC result still needs inspection. */ }
            }
            var uncertainBegin = !begun && store.GetDispatch(command.OperationId).DispatchAttempted && exception is IOException or OperationCanceledException;
            var error = exception is AgentFault fault ? fault.Code : ErrorCode.RecoveryRequired;
            if (store.GetPendingResult(command.OperationId) is not null)
            {
                try
                {
                    var remote = await runner.GetDataOperationAsync(command.OperationId, CancellationToken.None);
                    if (remote.Status == OperationStatus.Succeeded) return;
                    if (remote.Status == OperationStatus.Running)
                        await runner.FinishDataOperationAsync(new FinishDataOperationRequest(command.OperationId, execute.CommandSha256, OperationStatus.NeedsAttention), CancellationToken.None);
                    if (!OperationValidation.IsTerminal(store.GetOperation(command.OperationId)!.Status) && store.GetOperation(command.OperationId)!.Status != OperationStatus.NeedsAttention)
                        store.AdvanceOperation(command.OperationId, OperationStatus.NeedsAttention, ExecutionPhase.Recovering, error: error);
                }
                catch (Exception) { /* A later ledger query resolves an unknown transport result. */ }
                return;
            }
            if (begun)
            {
                try { await runner.FinishDataOperationAsync(new FinishDataOperationRequest(command.OperationId, execute.CommandSha256, OperationStatus.NeedsAttention), CancellationToken.None); }
                catch (Exception) { }
            }
            var current = store.GetOperation(command.OperationId);
            if (current is not null && !OperationValidation.IsTerminal(current.Status) && current.Status != OperationStatus.NeedsAttention)
                store.AdvanceOperation(command.OperationId, begun || uncertainBegin ? OperationStatus.NeedsAttention : OperationStatus.Failed, ExecutionPhase.Unknown, error: error);
        }
    }

    private async Task ConfirmChildStartAsync(Guid parentId, CancellationToken cancellationToken)
    {
        ExecutionSnapshot child;
        try { child = await runner.GetExecutionAsync(DataExecution.ChildOperationId(parentId, OperationKind.Start), cancellationToken); }
        catch (AgentFault fault) when (fault.Code == ErrorCode.StateConflict) { throw new AgentFault(ErrorCode.RecoveryRequired); }
        if (child.Status != OperationStatus.Succeeded) throw new AgentFault(ErrorCode.RecoveryRequired);
    }

    private async Task<object> ExecuteStorageAsync(OperationCommand command, ServerConfig server, BackupProfile? profile, CancellationToken cancellationToken)
    {
        var id = command.OperationId;
        Func<CancellationToken, Task> verify = token => VerifyDataGuardAsync(id, token);
        var payload = command.Payload;
        switch (command.Kind)
        {
            case OperationKind.ListFiles: return await storage!.ListFilesAsync(server, FileOperationValidation.Read<ListFilesPayload>(payload), cancellationToken);
            case OperationKind.ReadFile: return await storage!.ReadFileAsync(server, FileOperationValidation.Read<ReadFilePayload>(payload), cancellationToken);
            case OperationKind.WriteFile: return await storage!.WriteFileAsync(server, FileOperationValidation.Read<WriteFilePayload>(payload), id, verify, cancellationToken);
            case OperationKind.FileMutation: return await storage!.MutateAsync(server, FileOperationValidation.Read<FileMutationPayload>(payload), id, verify, cancellationToken);
            case OperationKind.ExportFiles:
            {
                var request = FileOperationValidation.Read<ExportFilesPayload>(payload);
                if (request.Target is FileTarget target)
                {
                    var root = configuration.LocalResources.Roots.Single(r => r.Id == target.ManagedRootId);
                    var path = AgentResourceBoundary.Resolve(root.Path, target.RelativePath);
                    if (Directory.Exists(path))
                    {
                        var runtime = (await runner.GetSnapshotAsync(cancellationToken)).Servers.Single(s => s.ServerId == server.ServerId);
                        if (runtime.ProcessState != ProcessState.Stopped) throw new FileConflictException("Directory export requires a stopped server.");
                    }
                }
                var artifact = request.BackupId is Guid backupId
                    ? await storage!.ExportBackupAsync(server, backupId, id, cancellationToken)
                    : await storage!.ExportFilesAsync(server, request, id, cancellationToken);
                var transfer = transferClient ?? throw new AgentFault(ErrorCode.HostOffline);
                await transfer.UploadAsync(request.TransferId, artifact.Path, true, cancellationToken, id, server.ServerId);
                return new { request.TransferId, artifact.SizeBytes, artifact.Sha256 };
            }
            case OperationKind.ImportArtifact:
            {
                var request = FileOperationValidation.Read<ImportArtifactPayload>(payload);
                var path = await DownloadArtifactAsync(request.Artifact, id, server.ServerId, cancellationToken);
                return await storage!.ImportArtifactAsync(server, request, path, id, verify, cancellationToken);
            }
            case OperationKind.ProvisionServer:
            {
                var request = FileOperationValidation.Read<ProvisionServerPayload>(payload);
                var path = await DownloadArtifactAsync(request.Artifact, id, server.ServerId, cancellationToken);
                return await storage!.ProvisionServerAsync(server, request, path, id, verify, cancellationToken);
            }
            case OperationKind.CreateBackup:
            {
                var backup = await storage!.CreateBackupAsync(server, profile!, FileOperationValidation.Read<CreateBackupPayload>(payload), id, verify, cancellationToken);
                return BackupResult(backup);
            }
            case OperationKind.RestoreBackup:
            {
                var restored = await storage!.RestoreBackupAsync(server, FileOperationValidation.Read<RestoreBackupPayload>(payload), id, verify, cancellationToken);
                return RestoreResultSummary(restored);
            }
            case OperationKind.PruneBackups: return await storage!.PruneBackupsAsync(server, profile!, verify, cancellationToken);
            case OperationKind.SetBackupProtection:
            {
                var backup = await storage!.SetBackupProtectionAsync(server, FileOperationValidation.Read<SetBackupProtectionPayload>(payload), verify, cancellationToken);
                return BackupResult(backup);
            }
            case OperationKind.DeleteBackup: await storage!.DeleteBackupAsync(server, FileOperationValidation.Read<DeleteBackupPayload>(payload), verify, cancellationToken); return new { deleted = true };
            default: throw new AgentFault(ErrorCode.UnsupportedCapability);
        }
    }

    private static object BackupResult(BackupManifest backup) => new
    {
        backup.BackupId, backup.ServerId, backup.BackupProfileId, backup.ProfileRevision,
        backup.ManifestSha256, backup.SizeBytes, entryCount = backup.Entries.Length,
        backup.Scope, backup.State, backup.Protected
    };
    private static object RestoreResultSummary(RestoreResult result) => new { result.BackupId, result.Mode, result.RestoredFiles, preRestoreRetained = true };

    private async Task<string> DownloadArtifactAsync(Artifact artifact, Guid operationId, Guid serverId, CancellationToken cancellationToken)
    {
        var transfer = transferClient ?? throw new AgentFault(ErrorCode.HostOffline);
        var path = await transfer.DownloadAsync(artifact.TransferId, serverId, operationId, cancellationToken);
        var info = new FileInfo(path);
        if (info.Length != artifact.SizeBytes) throw new AgentFault(ErrorCode.HashMismatch);
        await using var stream = File.OpenRead(path);
        if (Convert.ToHexStringLower(await SHA256.HashDataAsync(stream, cancellationToken)) != artifact.Sha256) throw new AgentFault(ErrorCode.HashMismatch);
        return path;
    }

    private BackupProfile ResolveProfile(InventorySnapshot inventory, OperationCommand command)
    {
        var id = command.Kind == OperationKind.CreateBackup ? FileOperationValidation.Read<CreateBackupPayload>(command.Payload).BackupProfileId : FileOperationValidation.Read<PruneBackupsPayload>(command.Payload).BackupProfileId;
        var profile = inventory.BackupProfiles.Select(x => ControlJson.Deserialize<BackupProfile>(x.GetRawText())).SingleOrDefault(x => x.BackupProfileId == id && x.ServerId == command.ServerId) ?? throw new AgentFault(ErrorCode.StaleRevision);
        if (command.Kind == OperationKind.CreateBackup && profile.Revision != FileOperationValidation.Read<CreateBackupPayload>(command.Payload).ProfileRevision) throw new AgentFault(ErrorCode.StaleRevision);
        return profile;
    }

    private async Task VerifyDataGuardAsync(Guid operationId, CancellationToken cancellationToken)
    {
        var state = await runner.VerifyDataOperationAsync(operationId, cancellationToken);
        if (state.Status != OperationStatus.Running) throw new AgentFault(ErrorCode.RecoveryRequired);
    }

    private async Task RunChildProcessOperationAsync(OperationCommand parent, OperationKind kind, CancellationToken cancellationToken)
    {
        var snapshot = await runner.GetSnapshotAsync(cancellationToken);
        var server = snapshot.Servers.Single(s => s.ServerId == parent.ServerId);
        if (kind == OperationKind.Stop && server.ProcessState == ProcessState.Stopped) return;
        if (kind == OperationKind.Start && server.ProcessState == ProcessState.Running) return;
        var childId = DataExecution.ChildOperationId(parent.OperationId, kind);
        var child = parent with { OperationId = childId, Kind = kind, ExpectedStateRevision = server.StateRevision, ExpectedServerSessionId = server.CurrentSessionId,
            StartBeforeUtc = clock.GetUtcNow().AddMinutes(1), Payload = ControlJson.Element(new { }) };
        var json = ControlJson.Serialize(child);
        var accepted = await runner.ExecuteAsync(new ExecuteRequest(childId, json, ControlProtocol.Hash(json)), cancellationToken);
        var deadline = clock.GetUtcNow().AddSeconds(Math.Min(parent.ExecutionTimeoutSeconds, 3600));
        while (!OperationValidation.IsTerminal(accepted.Status) && accepted.Status != OperationStatus.NeedsAttention)
        {
            if (clock.GetUtcNow() > deadline) throw new AgentFault(ErrorCode.Timeout);
            await Task.Delay(500, cancellationToken);
            accepted = await runner.GetExecutionAsync(childId, cancellationToken);
        }
        if (accepted.Status != OperationStatus.Succeeded) throw new AgentFault(ErrorCode.RecoveryRequired);
    }

    private async Task PublishPendingResultAsync(Guid operationId, string commandHash, CancellationToken cancellationToken)
    {
        var json = store.GetPendingResult(operationId) ?? throw new AgentFault(ErrorCode.RecoveryRequired);
        object summary;
        if (Encoding.UTF8.GetByteCount(json) > 4096)
        {
            var publisher = publishResult ?? throw new AgentFault(ErrorCode.HostOffline);
            await publisher(operationId, commandHash, json, cancellationToken);
            summary = new { resultStored = true };
        }
        else
        {
            using var parsed = JsonDocument.Parse(json);
            summary = parsed.RootElement.Clone();
        }
        store.AdvanceOperation(operationId, OperationStatus.Succeeded, ExecutionPhase.Completed, summary);
        store.DeletePendingResult(operationId);
    }

    private async Task<ExecutionSnapshot> QueryDataAsync(Guid operationId, CancellationToken cancellationToken)
    {
        var local = store.GetOperation(operationId) ?? throw new AgentFault(ErrorCode.OperationConflict);
        if (OperationValidation.IsTerminal(local.Status)) return local;
        if (dataJobs.ContainsKey(operationId)) return local;
        if (local.Status == OperationStatus.NeedsAttention)
        {
            var recovered = await runner.GetDataOperationAsync(operationId, cancellationToken);
            if (recovered.CommandSha256 != local.CommandSha256) throw new AgentFault(ErrorCode.RecoveryRequired);
            if (recovered.Status is OperationStatus.Running or OperationStatus.Accepted)
            {
                await runner.FinishDataOperationAsync(new(operationId, recovered.CommandSha256, OperationStatus.NeedsAttention), cancellationToken);
                return local;
            }
            if (recovered.Status == OperationStatus.Failed) return store.AdvanceOperation(operationId, OperationStatus.Failed, ExecutionPhase.Completed, error: ErrorCode.RecoveryRequired);
            if (recovered.Status == OperationStatus.Succeeded && store.GetPendingResult(operationId) is not null)
            {
                await PublishPendingResultAsync(operationId, recovered.CommandSha256, cancellationToken);
                return store.GetOperation(operationId)!;
            }
            return local;
        }
        var dispatch = store.GetDispatch(operationId);
        var pending = store.GetPendingResultState(operationId);
        if (pending is not null)
        {
            var runnerState = await runner.GetDataOperationAsync(operationId, cancellationToken);
            if (runnerState.Status == OperationStatus.Running)
            {
                if (pending.Value.RestartRequired)
                {
                    try { await ConfirmChildStartAsync(operationId, cancellationToken); }
                    catch (AgentFault)
                    {
                        await runner.FinishDataOperationAsync(new FinishDataOperationRequest(operationId, dispatch.Command.CommandSha256, OperationStatus.NeedsAttention), cancellationToken);
                        return store.AdvanceOperation(operationId, OperationStatus.NeedsAttention, ExecutionPhase.Recovering, error: ErrorCode.RecoveryRequired);
                    }
                }
                await runner.FinishDataOperationAsync(new FinishDataOperationRequest(operationId, dispatch.Command.CommandSha256, OperationStatus.Succeeded), cancellationToken);
            }
            else if (runnerState.Status != OperationStatus.Succeeded) return store.AdvanceOperation(operationId, OperationStatus.NeedsAttention, ExecutionPhase.Recovering, error: ErrorCode.RecoveryRequired);
            await PublishPendingResultAsync(operationId, dispatch.Command.CommandSha256, cancellationToken);
            return store.GetOperation(operationId)!;
        }
        ExecutionSnapshot remote;
        try { remote = await runner.GetDataOperationAsync(operationId, cancellationToken); }
        catch (AgentFault fault) when (fault.Code == ErrorCode.StateConflict)
        {
            var command = OperationValidation.Read(dispatch.Command, configuration.HostId);
            if (clock.GetUtcNow() > command.StartBeforeUtc) return Expire(local);
            StartDataJob(dispatch.Command, command, false);
            return local;
        }
        if (remote.CommandSha256 != local.CommandSha256) throw new AgentFault(ErrorCode.RecoveryRequired);
        if (remote.Status is OperationStatus.Running or OperationStatus.Accepted)
        {
            await runner.FinishDataOperationAsync(new FinishDataOperationRequest(operationId, local.CommandSha256, OperationStatus.NeedsAttention), cancellationToken);
            return store.AdvanceOperation(operationId, OperationStatus.NeedsAttention, ExecutionPhase.Recovering, error: ErrorCode.RecoveryRequired);
        }
        if (remote.Status == OperationStatus.NeedsAttention) return store.AdvanceOperation(operationId, OperationStatus.NeedsAttention, ExecutionPhase.Recovering, error: ErrorCode.RecoveryRequired);
        return store.AdvanceOperation(operationId, OperationStatus.NeedsAttention, ExecutionPhase.Recovering, error: ErrorCode.RecoveryRequired);
    }

    public BackupPage ListBackups(AgentListBackupsRequest request, Guid activeEpoch)
    {
        RequireEpoch(request.ConnectionEpoch, activeEpoch);
        if (!configuration.EnableFileControl || processGate?.Enabled == false || storage is null) throw new AgentFault(ErrorCode.UnsupportedCapability);
        if (request.Limit is < 1 or > 100 || request.Cursor == Guid.Empty || request.BackupId == Guid.Empty || request.Cursor.HasValue && request.BackupId.HasValue) throw new AgentFault(ErrorCode.StateConflict);
        var server = store.GetAppliedInventory().Servers.SingleOrDefault(s => s.ServerId == request.ServerId) ?? throw new AgentFault(ErrorCode.StateConflict);
        var all = storage.ListBackups(server).Where(b => !request.BackupId.HasValue || b.BackupId == request.BackupId).OrderByDescending(b => b.CreatedAtUtc).ThenBy(b => b.BackupId).ToArray();
        var offset = request.Cursor.HasValue ? Array.FindIndex(all, b => b.BackupId == request.Cursor) + 1 : 0;
        if (request.Cursor.HasValue && offset == 0) throw new AgentFault(ErrorCode.StateConflict);
        var rows = all.Skip(offset).Take(request.Limit).ToArray();
        return new BackupPage(rows, offset + rows.Length < all.Length ? rows[^1].BackupId : null);
    }

    public async Task<ExecutionSnapshot> ResumeDataAsync(AgentResumeDataOperationRequest request, Guid activeEpoch, CancellationToken cancellationToken)
    {
        RequireEpoch(request.ConnectionEpoch, activeEpoch);
        if (!configuration.EnableFileControl || processGate?.Enabled == false || storage is null) throw new AgentFault(ErrorCode.UnsupportedCapability);
        var existing = store.GetOperation(request.OperationId) ?? throw new AgentFault(ErrorCode.OperationConflict);
        if (existing.Status != OperationStatus.NeedsAttention) throw new AgentFault(ErrorCode.StateConflict);
        var dispatch = store.GetDispatch(request.OperationId);
        var command = OperationValidation.Read(dispatch.Command, configuration.HostId);
        if (command.Kind is not (OperationKind.RestoreBackup or OperationKind.CreateBackup)) throw new AgentFault(ErrorCode.UnsupportedCapability);
        if (command.Kind == OperationKind.CreateBackup && store.GetPendingResultState(request.OperationId) is null)
        {
            var server = store.GetAppliedInventory().Servers.SingleOrDefault(s => s.ServerId == command.ServerId) ?? throw new AgentFault(ErrorCode.StaleRevision);
            if (server.Revision != command.ConfigRevision || storage is null ||
                await storage.GetCompletedBackupAsync(server, FileOperationValidation.Read<CreateBackupPayload>(command.Payload), cancellationToken, command.OperationId) is null)
                throw new AgentFault(ErrorCode.RecoveryRequired);
        }
        if (dataJobs.ContainsKey(request.OperationId)) return existing;
        StartDataJob(dispatch.Command, command, true, request.Reason);
        return existing;
    }
}
