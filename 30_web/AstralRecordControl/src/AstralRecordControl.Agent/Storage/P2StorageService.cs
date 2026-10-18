using System.Security.Cryptography;
using System.Text.Json;
using AstralRecordControl.Contracts;

namespace AstralRecordControl.Agent.Storage;

public sealed partial class P2StorageService
{
    private readonly string dataDirectory;
    private readonly StoragePaths paths;
    private readonly TimeProvider clock;
    private readonly SemaphoreSlim gate = new(1, 1);
    public Action<RestoreJournal>? JournalBoundary { get; set; }

    public P2StorageService(string dataDirectory, HostResources resources, TimeProvider? clock = null)
    {
        if (string.IsNullOrWhiteSpace(dataDirectory) || !Path.IsPathFullyQualified(dataDirectory)) throw new ArgumentException("Absolute local data directory required.", nameof(dataDirectory));
        this.dataDirectory = Path.GetFullPath(dataDirectory);
        if (StoragePaths.IsNetwork(this.dataDirectory)) throw new ArgumentException("Local data directory required.", nameof(dataDirectory));
        this.paths = new StoragePaths(resources);
        this.clock = clock ?? TimeProvider.System;
        StoragePaths.CheckNoReparse(this.dataDirectory);
        Directory.CreateDirectory(this.dataDirectory);
    }

    private string Data(string relative)
    {
        var result = Path.GetFullPath(Path.Combine(dataDirectory, relative));
        if (!result.StartsWith(Path.TrimEndingDirectorySeparator(dataDirectory) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) throw new InvalidOperationException("Invalid data path.");
        StoragePaths.CheckNoReparse(result);
        return result;
    }

    private static void RequireId(Guid id)
    {
        if (id == Guid.Empty) throw new ArgumentException("Operation ID required.");
    }

    private static async Task Guard(Func<CancellationToken, Task> verifyGuard, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(verifyGuard);
        await verifyGuard(cancellationToken);
    }

    private static async Task<string> Sha256Async(string path, CancellationToken cancellationToken)
    {
        StoragePaths.CheckNoReparse(path);
        await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1024 * 128, FileOptions.Asynchronous | FileOptions.SequentialScan);
        return Convert.ToHexStringLower(await SHA256.HashDataAsync(stream, cancellationToken));
    }

    private static async Task AtomicJsonAsync<T>(string path, T value, CancellationToken cancellationToken)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        StoragePaths.CheckNoReparse(path);
        var temp = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        var bytes = JsonSerializer.SerializeToUtf8Bytes(value, ControlJson.Options);
        await using (var stream = new FileStream(temp, FileMode.CreateNew, FileAccess.Write, FileShare.None, 4096, FileOptions.WriteThrough))
        {
            await stream.WriteAsync(bytes, cancellationToken);
            stream.Flush(true);
        }
        File.Move(temp, path, true);
    }

    private static T ReadJson<T>(string path) => ControlJson.Deserialize<T>(File.ReadAllText(path));

    private void EnsureNoRecovery(ServerConfig server)
    {
        if (InspectRecovery(server) is not null) throw new InvalidOperationException("Restore recovery is required before another mutation.");
    }

    private static void EnsureFreeSpace(string target, long neededBytes)
    {
        var root = Path.GetPathRoot(Path.GetFullPath(target))!;
        if (StoragePaths.IsNetwork(root)) return;
        var drive = new DriveInfo(root);
        if (drive.AvailableFreeSpace < neededBytes + Math.Max(64L * 1024 * 1024, neededBytes / 20)) throw new IOException("Insufficient free disk space.");
    }
}
