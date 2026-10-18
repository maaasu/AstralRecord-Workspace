using System.Security.Cryptography;
using System.Text.Json;
using System.Runtime.InteropServices;
using System.Text;
using AstralRecordControl.Contracts;

namespace AstralRecordControl.Agent.Storage;

public sealed partial class P2StorageService
{
    private const int MaxMetadataBytes = 64 * 1024 * 1024;
    private readonly string dataDirectory;
    private readonly StoragePaths paths;
    private readonly TimeProvider clock;
    private readonly Func<string, long?> availableBytesProbe;
    private readonly SemaphoreSlim gate = new(1, 1);
    public Action<RestoreJournal>? JournalBoundary { get; set; }

    public P2StorageService(string dataDirectory, HostResources resources, TimeProvider? clock = null, Func<string, long?>? availableBytesProbe = null)
    {
        if (string.IsNullOrWhiteSpace(dataDirectory) || !Path.IsPathFullyQualified(dataDirectory)) throw new ArgumentException("Absolute local data directory required.", nameof(dataDirectory));
        this.dataDirectory = Path.GetFullPath(dataDirectory);
        if (StoragePaths.IsNetwork(this.dataDirectory)) throw new ArgumentException("Local data directory required.", nameof(dataDirectory));
        this.paths = new StoragePaths(resources);
        this.clock = clock ?? TimeProvider.System;
        this.availableBytesProbe = availableBytesProbe ?? AvailableBytes;
        StoragePaths.CheckNoReparse(this.dataDirectory);
        Directory.CreateDirectory(this.dataDirectory);
        CleanupFailedBackups();
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
        if (bytes.Length > MaxMetadataBytes) throw new InvalidDataException("Stored metadata exceeds 64 MiB.");
        await using (var stream = new FileStream(temp, FileMode.CreateNew, FileAccess.Write, FileShare.None, 4096, FileOptions.WriteThrough))
        {
            await stream.WriteAsync(bytes, cancellationToken);
            stream.Flush(true);
        }
        File.Move(temp, path, true);
    }

    private static T ReadJson<T>(string path)
    {
        StoragePaths.CheckNoReparse(path);
        if (new FileInfo(path).Length > MaxMetadataBytes) throw new InvalidDataException("Stored metadata exceeds 64 MiB.");
        var bytes = File.ReadAllBytes(path);
        if (bytes.Length > MaxMetadataBytes) throw new InvalidDataException("Stored metadata exceeds 64 MiB.");
        var json = new UTF8Encoding(false, true).GetString(bytes);
        ControlJson.ValidateJson(json, MaxMetadataBytes);
        return JsonSerializer.Deserialize<T>(json, ControlJson.Options) ?? throw new InvalidDataException("Stored metadata is null.");
    }

    private void EnsureNoRecovery(ServerConfig server)
    {
        if (InspectRecovery(server) is not null) throw new InvalidOperationException("Restore recovery is required before another mutation.");
    }

    private void EnsureFreeSpace(string target, long neededBytes)
    {
        if (neededBytes < 0) throw new ArgumentOutOfRangeException(nameof(neededBytes));
        var available = availableBytesProbe(target);
        if (available is null || available < 0) throw new IOException("Disk capacity cannot be verified.");
        if (available.Value < checked(neededBytes + Math.Max(64L * 1024 * 1024, neededBytes / 20))) throw new IOException("Insufficient free disk space.");
    }

    private static long? AvailableBytes(string target)
    {
        var root = Path.GetPathRoot(Path.GetFullPath(target));
        if (string.IsNullOrEmpty(root)) return null;
        if (StoragePaths.IsNetwork(root) && !Path.EndsInDirectorySeparator(root)) root += Path.DirectorySeparatorChar;
        if (!OperatingSystem.IsWindows())
        {
            try { return new DriveInfo(root).AvailableFreeSpace; }
            catch (IOException) { return null; }
        }
        if (!GetDiskFreeSpaceEx(root, out var available, out _, out _)) return null;
        return available > long.MaxValue ? long.MaxValue : (long)available;
    }

    [DllImport("kernel32.dll", EntryPoint = "GetDiskFreeSpaceExW", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetDiskFreeSpaceEx(string directoryName, out ulong freeBytesAvailable, out ulong totalNumberOfBytes, out ulong totalNumberOfFreeBytes);
}
