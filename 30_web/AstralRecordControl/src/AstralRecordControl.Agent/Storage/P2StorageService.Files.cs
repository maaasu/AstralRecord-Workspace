using System.IO.Compression;
using System.Text;
using AstralRecordControl.Contracts;

namespace AstralRecordControl.Agent.Storage;

public sealed partial class P2StorageService
{
    private static readonly UTF8Encoding StrictUtf8 = new(false, true);
    private const long MaxZipExtractBytes = 20L * 1024 * 1024 * 1024;
    private const int MaxZipEntries = 100_000;

    public async Task<FileListResult> ListFilesAsync(ServerConfig server, ListFilesPayload payload, CancellationToken cancellationToken = default)
    {
        FileOperationValidation.ValidateTarget(payload.Target);
        if (payload.Limit is < 1 or > 1000 || payload.Cursor is not null && !ControlProtocol.IsRelativePath(payload.Cursor)) throw new ArgumentException("Invalid list request.");
        var directory = paths.Target(server, payload.Target);
        if (!Directory.Exists(directory)) throw new DirectoryNotFoundException(directory);
        StoragePaths.CheckNoReparse(directory);
        var names = Directory.EnumerateFileSystemEntries(directory).Select(x => Path.GetFileName(x)!).Order(StringComparer.OrdinalIgnoreCase).ToArray();
        var selected = names.Where(n => payload.Cursor is null || StringComparer.OrdinalIgnoreCase.Compare(n, payload.Cursor) > 0).Take(payload.Limit + 1).ToArray();
        var entries = new List<FileEntry>();
        foreach (var name in selected.Take(payload.Limit))
        {
            cancellationToken.ThrowIfCancellationRequested();
            var path = Path.Combine(directory, name);
            StoragePaths.CheckNoReparse(path);
            if (Directory.Exists(path)) entries.Add(new FileEntry(name, true, 0, null));
            else
            {
                var info = new FileInfo(path);
                entries.Add(new FileEntry(name, false, info.Length, await Sha256Async(path, cancellationToken)));
            }
        }
        return new FileListResult([.. entries], selected.Length > payload.Limit ? entries[^1].Name : null);
    }

    public async Task<FileReadResult> ReadFileAsync(ServerConfig server, ReadFilePayload payload, CancellationToken cancellationToken = default)
    {
        var path = paths.Target(server, payload.Target);
        await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 4096, FileOptions.Asynchronous);
        if (stream.Length > 2L * 1024 * 1024) throw new InvalidOperationException("Text file exceeds 2 MiB.");
        var bytes = new byte[(int)stream.Length];
        await stream.ReadExactlyAsync(bytes, cancellationToken);
        var bom = bytes.AsSpan().StartsWith(Encoding.UTF8.Preamble);
        var text = StrictUtf8.GetString(bytes, bom ? 3 : 0, bytes.Length - (bom ? 3 : 0));
        if (text.IndexOf('\0') >= 0) throw new InvalidOperationException("Binary file requires an artifact.");
        var newline = text.Contains("\r\n", StringComparison.Ordinal) ? "crlf" : text.Contains('\r') ? "cr" : "lf";
        return new FileReadResult(text, bom ? "utf-8-bom" : "utf-8", newline, Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(bytes)));
    }

    public async Task<FileWriteResult> WriteFileAsync(ServerConfig server, WriteFilePayload payload, Guid operationId, Func<CancellationToken, Task> verifyGuard, CancellationToken cancellationToken = default)
    {
        RequireId(operationId);
        FileOperationValidation.ValidateTarget(payload.Target);
        FileOperationValidation.ValidateEtag(payload.ExpectedEtag);
        if (payload.ContentUtf8 is null || Encoding.UTF8.GetByteCount(payload.ContentUtf8) > 64 * 1024) throw new ArgumentException("Text exceeds 64 KiB.");
        await gate.WaitAsync(cancellationToken);
        try
        {
            EnsureNoRecovery(server);
            var path = paths.Target(server, payload.Target);
            if (Directory.Exists(path)) throw new IOException("Target is a directory.");
            var current = File.Exists(path) ? await ReadFileAsync(server, new ReadFilePayload(payload.Target), cancellationToken) : null;
            if ((current?.Etag ?? "absent") != payload.ExpectedEtag) throw new FileConflictException("File etag changed.");
            var text = current?.Newline switch
            {
                "crlf" => payload.ContentUtf8.Replace("\r\n", "\n").Replace("\r", "\n").Replace("\n", "\r\n"),
                "cr" => payload.ContentUtf8.Replace("\r\n", "\n").Replace("\r", "\n").Replace("\n", "\r"),
                _ => payload.ContentUtf8
            };
            var content = StrictUtf8.GetBytes(text);
            if (current?.Encoding == "utf-8-bom") content = [.. Encoding.UTF8.Preamble, .. content];
            EnsureFreeSpace(path, content.Length);
            var parent = Path.GetDirectoryName(path)!;
            if (!Directory.Exists(parent)) throw new DirectoryNotFoundException(parent);
            var temporary = Path.Combine(parent, ".control-" + operationId.ToString("N") + ".tmp");
            if (File.Exists(temporary)) throw new IOException("Temporary path already exists.");
            await using (var output = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None, 4096, FileOptions.WriteThrough))
            {
                await output.WriteAsync(content, cancellationToken);
                output.Flush(true);
            }
            var trash = current is null ? null : TrashPath(server, operationId, Path.GetFileName(path));
            try
            {
                await Guard(verifyGuard, cancellationToken);
                paths.Target(server, payload.Target);
                if ((File.Exists(path) ? await Sha256Async(path, cancellationToken) : "absent") != payload.ExpectedEtag) throw new FileConflictException("File etag changed during edit.");
                if (trash is null) File.Move(temporary, path);
                else File.Replace(temporary, path, trash);
            }
            finally { if (File.Exists(temporary)) File.Delete(temporary); }
            return new FileWriteResult(Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(content)), trash);
        }
        finally { gate.Release(); }
    }

    public async Task<FileMutationResult> MutateAsync(ServerConfig server, FileMutationPayload payload, Guid operationId, Func<CancellationToken, Task> verifyGuard, CancellationToken cancellationToken = default)
    {
        RequireId(operationId);
        FileOperationValidation.ValidateTarget(payload.Source);
        FileOperationValidation.ValidateEtag(payload.ExpectedEtag);
        if (payload.Destination is not null) FileOperationValidation.ValidateTarget(payload.Destination);
        await gate.WaitAsync(cancellationToken);
        try
        {
            EnsureNoRecovery(server);
            var source = paths.Target(server, payload.Source);
            var destination = payload.Destination is null ? null : paths.Target(server, payload.Destination);
            if (destination == source) throw new ArgumentException("Source and destination match.");
            if (destination is not null && Directory.Exists(source) && destination.StartsWith(source + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) throw new ArgumentException("Cannot place a directory inside itself.");
            if (payload.Action == FileMutationAction.CreateDirectory)
            {
                if (payload.ExpectedEtag != "absent" || File.Exists(source) || Directory.Exists(source)) throw new InvalidOperationException("Directory already exists.");
                await Guard(verifyGuard, cancellationToken);
                paths.Target(server, payload.Source);
                Directory.CreateDirectory(source);
                return new FileMutationResult(null, null);
            }
            if (!File.Exists(source) && !Directory.Exists(source)) throw new FileNotFoundException("Mutation source missing.");
            var isDirectory = Directory.Exists(source);
            if (isDirectory && payload.ExpectedEtag != "absent") throw new ArgumentException("Directory etag must be absent.");
            if (!isDirectory && await Sha256Async(source, cancellationToken) != payload.ExpectedEtag) throw new FileConflictException("Source etag changed.");
            if (payload.Action is FileMutationAction.Copy or FileMutationAction.Move)
            {
                if (destination is null || File.Exists(destination) || Directory.Exists(destination) || !Directory.Exists(Path.GetDirectoryName(destination))) throw new IOException("Invalid or occupied destination.");
            }
            await Guard(verifyGuard, cancellationToken);
            paths.Target(server, payload.Source);
            if (payload.Destination is not null) paths.Target(server, payload.Destination);
            if (!isDirectory && await Sha256Async(source, cancellationToken) != payload.ExpectedEtag) throw new FileConflictException("Source etag changed during mutation.");
            if (payload.Action == FileMutationAction.Delete)
            {
                var trash = TrashPath(server, operationId, Path.GetFileName(source));
                if (isDirectory) Directory.Move(source, trash); else File.Move(source, trash);
                return new FileMutationResult(null, trash);
            }
            if (payload.Action == FileMutationAction.Move)
            {
                if (isDirectory) Directory.Move(source, destination!); else File.Move(source, destination!);
                return new FileMutationResult(isDirectory ? null : payload.ExpectedEtag, null);
            }
            if (payload.Action == FileMutationAction.Copy)
            {
                var stage = destination! + ".control-copy-" + operationId.ToString("N");
                if (File.Exists(stage) || Directory.Exists(stage)) throw new IOException("Copy stage already exists.");
                try
                {
                    EnsureFreeSpace(stage, isDirectory ? TreeSize(source) : new FileInfo(source).Length);
                    if (isDirectory) await CopyTreeAsync(source, stage, cancellationToken);
                    else File.Copy(source, stage);
                    await Guard(verifyGuard, cancellationToken);
                    paths.Target(server, payload.Source);
                    paths.Target(server, payload.Destination!);
                    if (File.Exists(destination) || Directory.Exists(destination) || !isDirectory && await Sha256Async(source, cancellationToken) != payload.ExpectedEtag) throw new InvalidOperationException("Copy source or destination changed.");
                    if (isDirectory) Directory.Move(stage, destination!); else File.Move(stage, destination!);
                }
                finally
                {
                    if (File.Exists(stage)) File.Delete(stage);
                    if (Directory.Exists(stage)) Directory.Delete(stage, true);
                }
                return new FileMutationResult(isDirectory ? null : payload.ExpectedEtag, null);
            }
            throw new ArgumentException("Unsupported file mutation.");
        }
        finally { gate.Release(); }
    }

    private string TrashPath(ServerConfig server, Guid operationId, string leaf)
    {
        var serverPath = paths.Server(server);
        var trashRoot = Path.Combine(Path.GetDirectoryName(serverPath)!, ".control-trash", server.ServerId.ToString("N"));
        StoragePaths.CheckNoReparse(trashRoot);
        Directory.CreateDirectory(trashRoot);
        var path = Path.Combine(trashRoot, operationId.ToString("N") + "-" + leaf);
        if (File.Exists(path) || Directory.Exists(path)) throw new IOException("Trash path already exists.");
        return path;
    }

    private static async Task CopyTreeAsync(string source, string destination, CancellationToken cancellationToken)
    {
        StoragePaths.CheckTree(source);
        Directory.CreateDirectory(destination);
        foreach (var directory in Directory.EnumerateDirectories(source, "*", SearchOption.AllDirectories))
        {
            cancellationToken.ThrowIfCancellationRequested();
            StoragePaths.CheckNoReparse(directory);
            Directory.CreateDirectory(Path.Combine(destination, Path.GetRelativePath(source, directory)));
        }
        foreach (var file in Directory.EnumerateFiles(source, "*", SearchOption.AllDirectories))
        {
            cancellationToken.ThrowIfCancellationRequested();
            StoragePaths.CheckNoReparse(file);
            var target = Path.Combine(destination, Path.GetRelativePath(source, file));
            await using var input = new FileStream(file, FileMode.Open, FileAccess.Read, FileShare.Read, 128 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
            await using var output = new FileStream(target, FileMode.CreateNew, FileAccess.Write, FileShare.None, 128 * 1024, FileOptions.Asynchronous);
            await input.CopyToAsync(output, cancellationToken);
            output.Flush(true);
        }
    }
}
