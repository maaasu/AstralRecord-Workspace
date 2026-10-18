using System.IO.Compression;
using AstralRecordControl.Contracts;

namespace AstralRecordControl.Agent.Storage;

public sealed partial class P2StorageService
{
    private string RestoreJournalPath(ServerConfig server) => Data(Path.Combine("restore-journals", server.ServerId.ToString("N") + ".json"));

    public RestoreJournal? InspectRecovery(ServerConfig server)
    {
        var file = RestoreJournalPath(server);
        if (!File.Exists(file)) return null;
        var journal = ReadJson<RestoreJournal>(file);
        if (journal.ServerId != server.ServerId) throw new InvalidDataException("Restore journal server mismatch.");
        return journal;
    }

    private async Task PersistBoundaryAsync(RestoreJournal journal, CancellationToken cancellationToken)
    {
        await AtomicJsonAsync(RestoreJournalPath(new ServerConfig { ServerId = journal.ServerId }), journal, cancellationToken);
        JournalBoundary?.Invoke(journal);
    }

    public async Task<RestoreResult> RestoreBackupAsync(ServerConfig server, RestoreBackupPayload payload, Guid operationId, Func<CancellationToken, Task> verifyGuard, CancellationToken cancellationToken = default)
    {
        RequireId(operationId);
        if (payload.BackupId == Guid.Empty || !Enum.IsDefined(payload.Mode)) throw new ArgumentException("Invalid restore request.");
        await gate.WaitAsync(cancellationToken);
        try
        {
            EnsureNoRecovery(server);
            var stored = LoadBackup(server, payload.BackupId);
            if (stored.Manifest.State != BackupState.Completed) throw new InvalidOperationException("Backup is incomplete.");
            var live = paths.Server(server);
            if (!Directory.Exists(live)) throw new DirectoryNotFoundException(live);
            var destination = BackupDestination(server, stored.DestinationRootId, payload.BackupId);
            await VerifyStoredBackupAsync(destination, stored.Manifest, cancellationToken);
            await Guard(verifyGuard, cancellationToken);
            var stage = Path.Combine(Path.GetDirectoryName(live)!, ".control-restore-" + operationId.ToString("N"));
            var preRestore = Path.Combine(Path.GetDirectoryName(live)!, ".control-prerestore-" + operationId.ToString("N"));
            if (Directory.Exists(stage) || Directory.Exists(preRestore)) throw new IOException("Restore stage already exists.");
            EnsureFreeSpace(stage, stored.Manifest.SizeBytes + (payload.Mode == RestoreMode.Merge ? TreeSize(live) : 0));
            Directory.CreateDirectory(stage);
            try
            {
                if (payload.Mode == RestoreMode.Merge) await CopyTreeContentsAsync(live, stage, cancellationToken);
                await OverlayBackupAsync(destination, stored.Manifest, stage, cancellationToken);
                await VerifyStageAsync(stage, stored.Manifest, cancellationToken);
            }
            catch
            {
                if (Directory.Exists(stage)) Directory.Delete(stage, true);
                throw;
            }
            var journal = new RestoreJournal(operationId, server.ServerId, payload.BackupId, payload.Mode, "Prepared", stage, preRestore, clock.GetUtcNow());
            await AtomicJsonAsync(BackupIndex(payload.BackupId), stored with { Manifest = stored.Manifest with { State = BackupState.Restoring } }, cancellationToken);
            await PersistBoundaryAsync(journal, cancellationToken);
            return await CompleteRestoreUnderGateAsync(server, stored, journal, verifyGuard, cancellationToken);
        }
        finally { gate.Release(); }
    }

    public async Task<RestoreResult> ResumeRestoreAsync(ServerConfig server, Guid operationId, Func<CancellationToken, Task> verifyGuard, CancellationToken cancellationToken = default)
    {
        RequireId(operationId);
        await gate.WaitAsync(cancellationToken);
        try
        {
            var journal = InspectRecovery(server) ?? throw new InvalidOperationException("No restore to resume.");
            if (journal.OperationId != operationId) throw new InvalidOperationException("Restore operation mismatch.");
            var stored = LoadBackup(server, journal.BackupId);
            return await CompleteRestoreUnderGateAsync(server, stored, journal, verifyGuard, cancellationToken);
        }
        finally { gate.Release(); }
    }

    private async Task<RestoreResult> CompleteRestoreUnderGateAsync(ServerConfig server, StoredBackup stored, RestoreJournal journal, Func<CancellationToken, Task> verifyGuard, CancellationToken cancellationToken)
    {
        var live = paths.Server(server);
        var stage = journal.StagePath;
        var pre = journal.PreRestorePath;
        var expectedStage = Path.Combine(Path.GetDirectoryName(live)!, ".control-restore-" + journal.OperationId.ToString("N"));
        var expectedPre = Path.Combine(Path.GetDirectoryName(live)!, ".control-prerestore-" + journal.OperationId.ToString("N"));
        if (stage != expectedStage || pre != expectedPre) throw new InvalidDataException("Restore journal path mismatch.");
        if (journal.Phase == "Prepared")
        {
            if (Directory.Exists(live) && !Directory.Exists(pre))
            {
                if (!Directory.Exists(stage)) throw new InvalidDataException("Restore stage missing.");
                await VerifyStageAsync(stage, stored.Manifest, cancellationToken);
                EnsureFreeSpace(pre, TreeSize(live));
                await Guard(verifyGuard, cancellationToken);
                paths.Server(server);
                Directory.Move(live, pre);
            }
            if (!Directory.Exists(pre) || Directory.Exists(live)) throw new InvalidDataException("Restore pre-copy state requires inspection.");
            journal = journal with { Phase = "OriginalMoved", UpdatedAtUtc = clock.GetUtcNow() };
            await PersistBoundaryAsync(journal, cancellationToken);
        }
        if (journal.Phase == "OriginalMoved")
        {
            if (!Directory.Exists(live) && Directory.Exists(stage))
            {
                await Guard(verifyGuard, cancellationToken);
                StoragePaths.CheckNoReparse(stage);
                Directory.Move(stage, live);
            }
            if (!Directory.Exists(live) || Directory.Exists(stage)) throw new InvalidDataException("Restore exchange state requires inspection.");
            journal = journal with { Phase = "Installed", UpdatedAtUtc = clock.GetUtcNow() };
            await PersistBoundaryAsync(journal, cancellationToken);
        }
        if (journal.Phase == "Installed")
        {
            await VerifyStageAsync(live, stored.Manifest, cancellationToken);
            journal = journal with { Phase = "Completed", UpdatedAtUtc = clock.GetUtcNow() };
            await PersistBoundaryAsync(journal, cancellationToken);
        }
        if (journal.Phase != "Completed") throw new InvalidDataException("Unknown restore journal phase.");
        await AtomicJsonAsync(BackupIndex(journal.BackupId), stored with { Manifest = stored.Manifest with { State = BackupState.Completed } }, cancellationToken);
        File.Delete(RestoreJournalPath(server));
        return new RestoreResult(journal.BackupId, journal.Mode, stored.Manifest.Entries.Length, pre);
    }

    private static long TreeSize(string directory)
    {
        StoragePaths.CheckTree(directory);
        return Directory.EnumerateFiles(directory, "*", SearchOption.AllDirectories).Sum(x => new FileInfo(x).Length);
    }

    private static async Task CopyTreeContentsAsync(string source, string destination, CancellationToken cancellationToken)
    {
        StoragePaths.CheckTree(source);
        foreach (var directory in Directory.EnumerateDirectories(source, "*", SearchOption.AllDirectories)) Directory.CreateDirectory(Path.Combine(destination, Path.GetRelativePath(source, directory)));
        foreach (var file in Directory.EnumerateFiles(source, "*", SearchOption.AllDirectories))
        {
            cancellationToken.ThrowIfCancellationRequested();
            var target = Path.Combine(destination, Path.GetRelativePath(source, file));
            await using var input = new FileStream(file, FileMode.Open, FileAccess.Read, FileShare.Read, 128 * 1024, FileOptions.Asynchronous);
            await using var output = new FileStream(target, FileMode.CreateNew, FileAccess.Write, FileShare.None, 128 * 1024, FileOptions.Asynchronous);
            await input.CopyToAsync(output, cancellationToken);
            output.Flush(true);
        }
    }

    private static async Task OverlayBackupAsync(string destination, BackupManifest manifest, string stage, CancellationToken cancellationToken)
    {
        if (manifest.Format == BackupFormat.Directory)
        {
            foreach (var entry in manifest.Entries)
            {
                cancellationToken.ThrowIfCancellationRequested();
                if (!ControlProtocol.IsRelativePath(entry.RelativePath)) throw new InvalidDataException("Invalid manifest path.");
                var target = Path.Combine(stage, entry.RelativePath.Replace('/', Path.DirectorySeparatorChar));
                if (entry.IsDirectory) { Directory.CreateDirectory(target); continue; }
                Directory.CreateDirectory(Path.GetDirectoryName(target)!);
                File.Copy(Path.Combine(destination, "files", entry.RelativePath.Replace('/', Path.DirectorySeparatorChar)), target, true);
            }
        }
        else
        {
            using var zip = ZipFile.OpenRead(Path.Combine(destination, "snapshot.zip"));
            foreach (var entry in manifest.Entries)
            {
                cancellationToken.ThrowIfCancellationRequested();
                if (!ControlProtocol.IsRelativePath(entry.RelativePath)) throw new InvalidDataException("Invalid manifest path.");
                var target = Path.Combine(stage, entry.RelativePath.Replace('/', Path.DirectorySeparatorChar));
                if (entry.IsDirectory) { Directory.CreateDirectory(target); continue; }
                Directory.CreateDirectory(Path.GetDirectoryName(target)!);
                await using var input = (zip.GetEntry(entry.RelativePath) ?? throw new InvalidDataException("Missing backup entry.")).Open();
                await using var output = new FileStream(target, FileMode.Create, FileAccess.Write, FileShare.None, 128 * 1024, FileOptions.Asynchronous);
                await input.CopyToAsync(output, cancellationToken);
                output.Flush(true);
            }
        }
    }

    private static async Task VerifyStageAsync(string stage, BackupManifest manifest, CancellationToken cancellationToken)
    {
        StoragePaths.CheckTree(stage);
        foreach (var entry in manifest.Entries)
        {
            var file = Path.Combine(stage, entry.RelativePath.Replace('/', Path.DirectorySeparatorChar));
            if (entry.IsDirectory) { if (!Directory.Exists(file)) throw new InvalidDataException("Restore directory missing."); }
            else if (new FileInfo(file).Length != entry.SizeBytes || await Sha256Async(file, cancellationToken) != entry.Sha256) throw new InvalidDataException("Restore stage hash mismatch.");
        }
    }
}
