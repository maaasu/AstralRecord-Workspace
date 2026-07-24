using System.Collections.Concurrent;

namespace SkillTreeEditor.Server.Services;

internal static class ExclusiveFileLock
{
    private static readonly ConcurrentDictionary<string, SemaphoreSlim> ProcessLocks =
        new(StringComparer.OrdinalIgnoreCase);

    public static async ValueTask<IAsyncDisposable> AcquireAsync(
        string path,
        CancellationToken cancellationToken)
    {
        if (OperatingSystem.IsMacOS())
            throw new PlatformNotSupportedException(
                "SkillTree Editor file locking is supported on Windows and Linux.");

        var fullPath = Path.GetFullPath(path);
        var processLock = ProcessLocks.GetOrAdd(fullPath, _ => new SemaphoreSlim(1, 1));
        await processLock.WaitAsync(cancellationToken);
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(fullPath)!);
            while (true)
            {
                cancellationToken.ThrowIfCancellationRequested();
                var stream = new FileStream(
                    fullPath,
                    FileMode.OpenOrCreate,
                    FileAccess.ReadWrite,
                    FileShare.ReadWrite,
                    bufferSize: 1,
                    FileOptions.Asynchronous);
                try
                {
                    stream.Lock(0, 1);
                    return new Lease(stream, processLock);
                }
                catch (IOException)
                {
                    await stream.DisposeAsync();
                    await Task.Delay(TimeSpan.FromMilliseconds(25), cancellationToken);
                }
                catch
                {
                    await stream.DisposeAsync();
                    throw;
                }
            }
        }
        catch
        {
            processLock.Release();
            throw;
        }
    }

    private sealed class Lease(FileStream stream, SemaphoreSlim processLock) : IAsyncDisposable
    {
        private int _disposed;

        public async ValueTask DisposeAsync()
        {
            if (Interlocked.Exchange(ref _disposed, 1) != 0)
                return;

            try
            {
                await stream.DisposeAsync();
            }
            finally
            {
                processLock.Release();
            }
        }
    }
}
