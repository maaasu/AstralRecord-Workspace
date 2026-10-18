using System.IO.Compression;
using AstralRecordControl.Contracts;

namespace AstralRecordControl.Agent.Storage;

public sealed partial class P2StorageService
{
    public async Task<ExportedArtifact> ExportFilesAsync(ServerConfig server, ExportFilesPayload payload, Guid operationId, CancellationToken cancellationToken = default)
    {
        RequireId(operationId);
        if (payload.TransferId == Guid.Empty || (payload.Target is null) == (payload.BackupId is null)) throw new ArgumentException("Exactly one export target required.");
        if (payload.BackupId is Guid backupId) return await ExportBackupAsync(server, backupId, operationId, cancellationToken);
        var source = paths.Target(server, payload.Target!);
        var output = Data(Path.Combine("exports", operationId.ToString("N") + (Directory.Exists(source) ? ".zip" : ".bin")));
        Directory.CreateDirectory(Path.GetDirectoryName(output)!);
        if (File.Exists(output)) throw new IOException("Export already exists.");
        if (Directory.Exists(source))
        {
            StoragePaths.CheckTree(source);
            EnsureFreeSpace(output, TreeSize(source));
            ZipFile.CreateFromDirectory(source, output, CompressionLevel.Optimal, false);
        }
        else
        {
            StoragePaths.CheckNoReparse(source);
            EnsureFreeSpace(output, new FileInfo(source).Length);
            File.Copy(source, output);
        }
        return new ExportedArtifact(output, new FileInfo(output).Length, await Sha256Async(output, cancellationToken));
    }

    public async Task<FileMutationResult> ImportArtifactAsync(ServerConfig server, ImportArtifactPayload payload, string verifiedArtifactPath, Guid operationId, Func<CancellationToken, Task> verifyGuard, CancellationToken cancellationToken = default)
    {
        RequireId(operationId);
        FileOperationValidation.ValidateTarget(payload.Target);
        FileOperationValidation.ValidateArtifact(payload.Artifact);
        FileOperationValidation.ValidateEtag(payload.ExpectedEtag);
        if (!Enum.IsDefined(payload.Mode)) throw new ArgumentException("Invalid import mode.");
        await gate.WaitAsync(cancellationToken);
        try
        {
            EnsureNoRecovery(server);
            var target = paths.Target(server, payload.Target);
            if (new FileInfo(verifiedArtifactPath).Length != payload.Artifact.SizeBytes || await Sha256Async(verifiedArtifactPath, cancellationToken) != payload.Artifact.Sha256) throw new InvalidOperationException("Artifact hash mismatch.");
            if (payload.Mode == ArtifactImportMode.File)
            {
                if (Directory.Exists(target)) throw new IOException("File target is a directory.");
                var etag = File.Exists(target) ? await Sha256Async(target, cancellationToken) : "absent";
                if (etag != payload.ExpectedEtag) throw new InvalidOperationException("Target etag changed.");
                EnsureFreeSpace(target, payload.Artifact.SizeBytes);
                var temp = Path.Combine(Path.GetDirectoryName(target)!, ".control-import-" + operationId.ToString("N") + ".tmp");
                File.Copy(verifiedArtifactPath, temp, false);
                try
                {
                    if (await Sha256Async(temp, cancellationToken) != payload.Artifact.Sha256) throw new InvalidDataException("Artifact changed during local staging.");
                    var trash = etag == "absent" ? null : TrashPath(server, operationId, Path.GetFileName(target));
                    await Guard(verifyGuard, cancellationToken);
                    paths.Target(server, payload.Target);
                    if ((File.Exists(target) ? await Sha256Async(target, cancellationToken) : "absent") != payload.ExpectedEtag) throw new InvalidOperationException("Target changed during import.");
                    if (trash is null) File.Move(temp, target); else File.Replace(temp, target, trash);
                    return new FileMutationResult(payload.Artifact.Sha256, trash);
                }
                finally { if (File.Exists(temp)) File.Delete(temp); }
            }
            if (payload.ExpectedEtag != "absent" || File.Exists(target) || Directory.Exists(target)) throw new IOException("ZIP target must be absent.");
            var stage = Path.Combine(Path.GetDirectoryName(target)!, ".control-extract-" + operationId.ToString("N"));
            if (Directory.Exists(stage)) throw new IOException("Import stage already exists.");
            ValidateZip(verifiedArtifactPath);
            EnsureFreeSpace(stage, ZipExpandedLength(verifiedArtifactPath));
            Directory.CreateDirectory(stage);
            try
            {
                await ExtractValidatedZipAsync(verifiedArtifactPath, stage, cancellationToken);
                if (await Sha256Async(verifiedArtifactPath, cancellationToken) != payload.Artifact.Sha256) throw new InvalidDataException("Artifact changed during ZIP extraction.");
                await Guard(verifyGuard, cancellationToken);
                paths.Target(server, payload.Target);
                if (File.Exists(target) || Directory.Exists(target)) throw new IOException("ZIP target appeared during import.");
                Directory.Move(stage, target);
                return new FileMutationResult(null, null);
            }
            finally { if (Directory.Exists(stage)) Directory.Delete(stage, true); }
        }
        finally { gate.Release(); }
    }

    public async Task<FileMutationResult> ProvisionServerAsync(ServerConfig server, ProvisionServerPayload payload, string verifiedArtifactPath, Guid operationId, Func<CancellationToken, Task> verifyGuard, CancellationToken cancellationToken = default)
    {
        RequireId(operationId);
        FileOperationValidation.ValidateArtifact(payload.Artifact);
        if (!payload.EulaAccepted || !string.Equals(payload.TemplateId, server.Kind.ToString(), StringComparison.OrdinalIgnoreCase)) throw new ArgumentException("Template/EULA mismatch.");
        await gate.WaitAsync(cancellationToken);
        try
        {
            EnsureNoRecovery(server);
            var serverPath = paths.Server(server);
            if (!Directory.Exists(serverPath) || Directory.EnumerateFileSystemEntries(serverPath).Any()) throw new IOException("Provisioning requires an empty registered server directory.");
            if (new FileInfo(verifiedArtifactPath).Length != payload.Artifact.SizeBytes || await Sha256Async(verifiedArtifactPath, cancellationToken) != payload.Artifact.Sha256) throw new InvalidOperationException("Artifact hash mismatch.");
            var stage = Path.Combine(Path.GetDirectoryName(serverPath)!, ".control-provision-" + operationId.ToString("N"));
            ValidateZip(verifiedArtifactPath);
            EnsureFreeSpace(stage, ZipExpandedLength(verifiedArtifactPath));
            Directory.CreateDirectory(stage);
            try
            {
                await ExtractValidatedZipAsync(verifiedArtifactPath, stage, cancellationToken);
                if (await Sha256Async(verifiedArtifactPath, cancellationToken) != payload.Artifact.Sha256) throw new InvalidDataException("Artifact changed during provisioning.");
                if (server.Kind == ServerKind.Java) await File.WriteAllTextAsync(Path.Combine(stage, "eula.txt"), "eula=true\n", cancellationToken);
                await Guard(verifyGuard, cancellationToken);
                paths.Server(server);
                if (Directory.EnumerateFileSystemEntries(serverPath).Any()) throw new IOException("Server directory changed during provision.");
                var emptyTrash = TrashPath(server, operationId, Path.GetFileName(serverPath));
                Directory.Move(serverPath, emptyTrash);
                try { Directory.Move(stage, serverPath); }
                catch
                {
                    if (!Directory.Exists(serverPath) && Directory.Exists(emptyTrash)) Directory.Move(emptyTrash, serverPath);
                    throw;
                }
                return new FileMutationResult(null, null);
            }
            finally { if (Directory.Exists(stage)) Directory.Delete(stage, true); }
        }
        finally { gate.Release(); }
    }

    private static void ValidateZip(string archivePath)
    {
        StoragePaths.CheckNoReparse(archivePath);
        using var zip = ZipFile.OpenRead(archivePath);
        if (zip.Entries.Count > MaxZipEntries) throw new InvalidDataException("ZIP entry limit exceeded.");
        var names = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        long total = 0;
        foreach (var entry in zip.Entries)
        {
            var name = entry.FullName.TrimEnd('/');
            if (!ControlProtocol.IsRelativePath(name) || entry.FullName.Contains('\\') || !names.Add(name)) throw new InvalidDataException("Unsafe or duplicate ZIP entry.");
            var unixMode = (entry.ExternalAttributes >> 16) & 0xF000;
            if (unixMode is 0xA000 or 0x6000 or 0x2000 || (entry.ExternalAttributes & (int)FileAttributes.ReparsePoint) != 0) throw new InvalidDataException("ZIP link or special entry rejected.");
            if (entry.Length < 0 || entry.CompressedLength < 0 || entry.Length > MaxZipExtractBytes - total || entry.Length > Math.Max(1, entry.CompressedLength) * 100L) throw new InvalidDataException("ZIP expansion limit exceeded.");
            total += entry.Length;
        }
    }

    private static long ZipExpandedLength(string archivePath)
    {
        using var zip = ZipFile.OpenRead(archivePath);
        return zip.Entries.Sum(e => e.Length);
    }

    private static async Task ExtractValidatedZipAsync(string archivePath, string stage, CancellationToken cancellationToken)
    {
        ValidateZip(archivePath);
        using var zip = ZipFile.OpenRead(archivePath);
        long written = 0;
        foreach (var entry in zip.Entries)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var relative = entry.FullName.TrimEnd('/');
            var path = Path.GetFullPath(Path.Combine(stage, relative.Replace('/', Path.DirectorySeparatorChar)));
            if (!path.StartsWith(Path.TrimEndingDirectorySeparator(stage) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("ZIP path escapes stage.");
            if (entry.FullName.EndsWith('/')) { Directory.CreateDirectory(path); continue; }
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            await using var input = entry.Open();
            await using var output = new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None, 128 * 1024, FileOptions.Asynchronous);
            var buffer = new byte[128 * 1024];
            long entryWritten = 0;
            int read;
            while ((read = await input.ReadAsync(buffer, cancellationToken)) != 0)
            {
                entryWritten += read; written += read;
                if (entryWritten > entry.Length || written > MaxZipExtractBytes) throw new InvalidDataException("ZIP data exceeds declared limits.");
                await output.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
            }
            if (entryWritten != entry.Length) throw new InvalidDataException("ZIP entry length mismatch.");
            output.Flush(true);
        }
    }
}
