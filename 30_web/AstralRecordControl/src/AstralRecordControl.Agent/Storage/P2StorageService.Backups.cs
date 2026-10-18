using System.IO.Compression;
using System.IO.Enumeration;
using AstralRecordControl.Contracts;

namespace AstralRecordControl.Agent.Storage;

internal sealed record StoredBackup(BackupManifest Manifest, Guid DestinationRootId);

public sealed partial class P2StorageService
{
    private string BackupIndex(Guid backupId) => Data(Path.Combine("backups", backupId.ToString("N") + ".json"));
    private string BackupDestination(ServerConfig server, Guid rootId, Guid backupId)
    {
        if (rootId == server.ManagedRootId) throw new InvalidOperationException("Backup destination must be a separate managed root.");
        var root = paths.Root(rootId, true);
        var current = paths.Server(server);
        if (string.Equals(root, current, StringComparison.OrdinalIgnoreCase) || root.StartsWith(current + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase) || current.StartsWith(root + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("Backup destination overlaps server data.");
        var destination = Path.Combine(root, "control-backups", server.ServerId.ToString("N"), backupId.ToString("N"));
        StoragePaths.CheckNoReparse(destination);
        return destination;
    }

    private StoredBackup LoadBackup(ServerConfig server, Guid backupId)
    {
        RequireId(backupId);
        var stored = ReadJson<StoredBackup>(BackupIndex(backupId));
        if (stored.Manifest.ServerId != server.ServerId || stored.Manifest.BackupId != backupId || stored.Manifest.Scope != "ServerFiles") throw new InvalidOperationException("Backup belongs to another server or scope.");
        return stored;
    }

    public BackupSummary[] ListBackups(ServerConfig server)
    {
        var directory = Data("backups");
        if (!Directory.Exists(directory)) return [];
        var result = new List<BackupSummary>();
        foreach (var file in Directory.EnumerateFiles(directory, "*.json"))
        {
            StoragePaths.CheckNoReparse(file);
            var m = ReadJson<StoredBackup>(file).Manifest;
            if (m.ServerId == server.ServerId) result.Add(new BackupSummary(m.BackupId, m.ServerId, m.BackupProfileId, m.State, m.CreatedAtUtc, m.SizeBytes, m.Protected, m.Format, m.Scope));
        }
        return [.. result.OrderByDescending(x => x.CreatedAtUtc)];
    }

    public async Task<BackupManifest> CreateBackupAsync(ServerConfig server, BackupProfile profile, CreateBackupPayload payload, Guid operationId, Func<CancellationToken, Task> verifyGuard, CancellationToken cancellationToken = default)
    {
        RequireId(operationId);
        BackupValidation.Validate(profile);
        if (payload.BackupId == Guid.Empty || payload.BackupProfileId != profile.BackupProfileId || payload.ProfileRevision != profile.Revision || profile.ServerId != server.ServerId) throw new ArgumentException("Backup profile mismatch.");
        await gate.WaitAsync(cancellationToken);
        try
        {
            EnsureNoRecovery(server);
            var source = paths.Server(server);
            StoragePaths.CheckTree(source);
            var destination = BackupDestination(server, profile.DestinationRootId, payload.BackupId);
            if (File.Exists(BackupIndex(payload.BackupId)) || Directory.Exists(destination)) throw new IOException("Backup ID already used.");
            var stage = Data(Path.Combine("staging", "backup-" + payload.BackupId.ToString("N")));
            if (Directory.Exists(stage)) throw new IOException("Backup stage already exists.");
            Directory.CreateDirectory(Path.Combine(stage, "files"));
            var creating = new BackupManifest { BackupId = payload.BackupId, HostId = server.HostId, ServerId = server.ServerId, ConfigRevision = server.Revision, ServerKind = server.Kind, BackupProfileId = profile.BackupProfileId, ProfileRevision = profile.Revision, CreatedAtUtc = clock.GetUtcNow(), Format = profile.Format, State = BackupState.Creating };
            await AtomicJsonAsync(BackupIndex(payload.BackupId), new StoredBackup(creating, profile.DestinationRootId), cancellationToken);
            try
            {
                await Guard(verifyGuard, cancellationToken);
                var sourceFiles = Directory.EnumerateFiles(source, "*", SearchOption.AllDirectories)
                    .Select(path => (Path: path, Relative: Path.GetRelativePath(source, path).Replace('\\', '/')))
                    .Where(x => !IsExcluded(x.Relative, profile.ExcludePatterns)).ToArray();
                var size = sourceFiles.Sum(x => new FileInfo(x.Path).Length);
                EnsureFreeSpace(stage, size);
                var entries = new List<BackupEntry>(sourceFiles.Length);
                foreach (var directory in Directory.EnumerateDirectories(source, "*", SearchOption.AllDirectories))
                {
                    StoragePaths.CheckNoReparse(directory);
                    var relative = Path.GetRelativePath(source, directory).Replace('\\', '/');
                    if (!ControlProtocol.IsRelativePath(relative) || IsExcluded(relative, profile.ExcludePatterns)) continue;
                    Directory.CreateDirectory(Path.Combine(stage, "files", relative.Replace('/', Path.DirectorySeparatorChar)));
                    entries.Add(new BackupEntry(relative, 0, "", true));
                }
                foreach (var item in sourceFiles)
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    StoragePaths.CheckNoReparse(item.Path);
                    if (!ControlProtocol.IsRelativePath(item.Relative)) throw new InvalidOperationException("Invalid source path.");
                    await Guard(verifyGuard, cancellationToken);
                    var target = Path.Combine(stage, "files", item.Relative.Replace('/', Path.DirectorySeparatorChar));
                    Directory.CreateDirectory(Path.GetDirectoryName(target)!);
                    await using (var input = new FileStream(item.Path, FileMode.Open, FileAccess.Read, FileShare.Read, 128 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan))
                    await using (var output = new FileStream(target, FileMode.CreateNew, FileAccess.Write, FileShare.None, 128 * 1024, FileOptions.Asynchronous))
                    {
                        await input.CopyToAsync(output, cancellationToken);
                        output.Flush(true);
                    }
                    var copiedHash = await Sha256Async(target, cancellationToken);
                    if (await Sha256Async(item.Path, cancellationToken) != copiedHash) throw new IOException("Source changed during backup.");
                    entries.Add(new BackupEntry(item.Relative, new FileInfo(target).Length, copiedHash));
                }
                var manifest = creating with { Entries = [.. entries.OrderBy(x => x.RelativePath, StringComparer.OrdinalIgnoreCase)], SizeBytes = entries.Sum(e => e.SizeBytes), State = BackupState.Completed };
                manifest = manifest with { ManifestSha256 = ControlProtocol.Hash(ControlJson.Serialize(manifest)) };
                await AtomicJsonAsync(Path.Combine(stage, "manifest.json"), manifest, cancellationToken);
                if (profile.Format == BackupFormat.Zip)
                {
                    ZipFile.CreateFromDirectory(Path.Combine(stage, "files"), Path.Combine(stage, "snapshot.zip"), CompressionLevel.Optimal, false);
                    Directory.Delete(Path.Combine(stage, "files"), true);
                }
                await Guard(verifyGuard, cancellationToken);
                StoragePaths.CheckNoReparse(destination);
                var incomplete = destination + ".incomplete";
                if (Directory.Exists(incomplete)) throw new IOException("Incomplete destination already exists.");
                await CopyTreeAsync(stage, incomplete, cancellationToken);
                await VerifyStoredBackupAsync(incomplete, manifest, cancellationToken);
                Directory.Move(incomplete, destination);
                await AtomicJsonAsync(BackupIndex(payload.BackupId), new StoredBackup(manifest, profile.DestinationRootId), cancellationToken);
                Directory.Delete(stage, true);
                return manifest;
            }
            catch
            {
                await AtomicJsonAsync(BackupIndex(payload.BackupId), new StoredBackup(creating with { State = BackupState.Failed }, profile.DestinationRootId), CancellationToken.None);
                throw;
            }
        }
        finally { gate.Release(); }
    }

    private static bool IsExcluded(string relativePath, string[] patterns)
    {
        var current = relativePath;
        while (true)
        {
            if (patterns.Any(p => FileSystemName.MatchesSimpleExpression(p, current, true))) return true;
            var slash = current.LastIndexOf('/');
            if (slash < 0) return false;
            current = current[..slash];
        }
    }

    private static async Task VerifyStoredBackupAsync(string directory, BackupManifest manifest, CancellationToken cancellationToken)
    {
        var diskManifest = ReadJson<BackupManifest>(Path.Combine(directory, "manifest.json"));
        if (diskManifest.ManifestSha256 != manifest.ManifestSha256 || diskManifest.BackupId != manifest.BackupId || diskManifest.ServerId != manifest.ServerId) throw new InvalidDataException("Backup manifest changed.");
        if (diskManifest.ManifestSha256 != ControlProtocol.Hash(ControlJson.Serialize(diskManifest with { ManifestSha256 = "" }))) throw new InvalidDataException("Backup manifest hash mismatch.");
        manifest = diskManifest;
        if (manifest.Format == BackupFormat.Directory)
        {
            var expected = manifest.Entries.Select(x => x.RelativePath).ToHashSet(StringComparer.OrdinalIgnoreCase);
            var fileRoot = Path.Combine(directory, "files");
            StoragePaths.CheckTree(fileRoot);
            var actual = Directory.EnumerateFileSystemEntries(fileRoot, "*", SearchOption.AllDirectories).Select(x => Path.GetRelativePath(fileRoot, x).Replace('\\', '/')).ToHashSet(StringComparer.OrdinalIgnoreCase);
            if (!actual.SetEquals(expected)) throw new InvalidDataException("Backup manifest does not enumerate all entries.");
            foreach (var entry in manifest.Entries)
            {
                if (!ControlProtocol.IsRelativePath(entry.RelativePath)) throw new InvalidDataException("Unsafe manifest entry.");
                var path = Path.GetFullPath(Path.Combine(directory, "files", entry.RelativePath.Replace('/', Path.DirectorySeparatorChar)));
                if (!path.StartsWith(Path.Combine(directory, "files") + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("Backup path escapes snapshot.");
                if (entry.IsDirectory) { if (!Directory.Exists(path)) throw new InvalidDataException("Backup directory missing."); }
                else if (new FileInfo(path).Length != entry.SizeBytes || await Sha256Async(path, cancellationToken) != entry.Sha256) throw new InvalidDataException("Backup file hash mismatch.");
            }
        }
        else
        {
            var archive = Path.Combine(directory, "snapshot.zip");
            using var zip = ZipFile.OpenRead(archive);
            var expectedNames = manifest.Entries.Where(x => !x.IsDirectory).Select(x => x.RelativePath).ToHashSet(StringComparer.OrdinalIgnoreCase);
            if (zip.Entries.Any(x => !expectedNames.Contains(x.FullName) && !manifest.Entries.Any(e => e.IsDirectory && x.FullName == e.RelativePath + "/"))) throw new InvalidDataException("Unexpected backup ZIP entry.");
            foreach (var entry in manifest.Entries)
            {
                if (entry.IsDirectory)
                {
                    if (zip.GetEntry(entry.RelativePath + "/") is null && !zip.Entries.Any(x => x.FullName.StartsWith(entry.RelativePath + "/", StringComparison.Ordinal))) throw new InvalidDataException("Backup directory missing.");
                    continue;
                }
                var zipEntry = zip.GetEntry(entry.RelativePath) ?? throw new InvalidDataException("Backup ZIP entry missing.");
                if (zipEntry.Length != entry.SizeBytes) throw new InvalidDataException("Backup ZIP entry size mismatch.");
                await using var input = zipEntry.Open();
                if (Convert.ToHexStringLower(await System.Security.Cryptography.SHA256.HashDataAsync(input, cancellationToken)) != entry.Sha256) throw new InvalidDataException("Backup ZIP entry hash mismatch.");
            }
        }
    }

    public async Task<Guid[]> PruneBackupsAsync(ServerConfig server, BackupProfile profile, Func<CancellationToken, Task> verifyGuard, CancellationToken cancellationToken = default)
    {
        BackupValidation.Validate(profile);
        if (profile.ServerId != server.ServerId) throw new ArgumentException("Profile belongs to another server.");
        var completed = ListBackups(server).Where(x => x.BackupProfileId == profile.BackupProfileId && x.State == BackupState.Completed).OrderByDescending(x => x.CreatedAtUtc).ToArray();
        var cutoff = clock.GetUtcNow().AddDays(-profile.RetentionDays);
        var removed = new List<Guid>();
        foreach (var item in completed.Skip(profile.RetentionCount).Where(x => x.CreatedAtUtc < cutoff && !x.Protected))
        {
            await DeleteBackupAsync(server, new DeleteBackupPayload(item.BackupId, "Retention policy"), verifyGuard, cancellationToken);
            removed.Add(item.BackupId);
        }
        return [.. removed];
    }

    public async Task<BackupManifest> SetBackupProtectionAsync(ServerConfig server, SetBackupProtectionPayload payload, Func<CancellationToken, Task> verifyGuard, CancellationToken cancellationToken = default)
    {
        await gate.WaitAsync(cancellationToken);
        try
        {
            var stored = LoadBackup(server, payload.BackupId);
            if (stored.Manifest.State != BackupState.Completed) throw new InvalidOperationException("Only completed backups may change protection.");
            await Guard(verifyGuard, cancellationToken);
            var changed = stored.Manifest with { Protected = payload.Protected };
            await AtomicJsonAsync(BackupIndex(payload.BackupId), stored with { Manifest = changed }, cancellationToken);
            return changed;
        }
        finally { gate.Release(); }
    }

    public async Task DeleteBackupAsync(ServerConfig server, DeleteBackupPayload payload, Func<CancellationToken, Task> verifyGuard, CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(payload.Reason)) throw new ArgumentException("Reason required.");
        await gate.WaitAsync(cancellationToken);
        try
        {
            var stored = LoadBackup(server, payload.BackupId);
            if (stored.Manifest.Protected || stored.Manifest.State != BackupState.Completed || InspectRecovery(server)?.BackupId == payload.BackupId) throw new InvalidOperationException("Backup cannot be deleted.");
            var destination = BackupDestination(server, stored.DestinationRootId, payload.BackupId);
            await Guard(verifyGuard, cancellationToken);
            StoragePaths.CheckTree(destination);
            var deleted = destination + ".deleted";
            if (Directory.Exists(deleted)) throw new IOException("Deletion already pending.");
            Directory.Move(destination, deleted);
            await AtomicJsonAsync(BackupIndex(payload.BackupId), stored with { Manifest = stored.Manifest with { State = BackupState.Deleted } }, cancellationToken);
            Directory.Delete(deleted, true);
        }
        finally { gate.Release(); }
    }

    public async Task<ExportedArtifact> ExportBackupAsync(ServerConfig server, Guid backupId, Guid operationId, CancellationToken cancellationToken = default)
    {
        RequireId(operationId);
        var stored = LoadBackup(server, backupId);
        if (stored.Manifest.State != BackupState.Completed) throw new InvalidOperationException("Backup is incomplete.");
        var destination = BackupDestination(server, stored.DestinationRootId, backupId);
        await VerifyStoredBackupAsync(destination, stored.Manifest, cancellationToken);
        var output = Data(Path.Combine("exports", operationId.ToString("N") + ".zip"));
        Directory.CreateDirectory(Path.GetDirectoryName(output)!);
        if (File.Exists(output)) throw new IOException("Export already exists.");
        ZipFile.CreateFromDirectory(destination, output, CompressionLevel.Optimal, false);
        return new ExportedArtifact(output, new FileInfo(output).Length, await Sha256Async(output, cancellationToken));
    }
}
