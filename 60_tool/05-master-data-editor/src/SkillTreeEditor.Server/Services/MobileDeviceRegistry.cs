using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace SkillTreeEditor.Server.Services;

public sealed record MobilePairingCode(string Code, DateTimeOffset ExpiresAt);

public sealed record MobileDevice(string DeviceId, string Name, DateTimeOffset PairedAt);

/// <summary>PC ローカルの操作とモバイルホストで、同じワークスペースの端末登録を共有します。</summary>
public sealed class MobileDeviceRegistry
{
    private const int MaximumDevices = 20;
    private const int MaximumPairingAttempts = 12;

    private readonly string _statePath;
    private readonly string _lockPath;
    private readonly TimeProvider _clock;

    public MobileDeviceRegistry(string workspaceRoot)
        : this(workspaceRoot, Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "AstralRecord", "SkillTreeEditor", "mobile"), TimeProvider.System)
    {
    }

    internal MobileDeviceRegistry(string workspaceRoot, string stateDirectory, TimeProvider clock)
    {
        var workspace = Path.TrimEndingDirectorySeparator(Path.GetFullPath(workspaceRoot));
        if (OperatingSystem.IsWindows())
            workspace = workspace.ToUpperInvariant();
        var directory = Path.Combine(stateDirectory, Hash(workspace));
        _statePath = Path.Combine(directory, "devices.json");
        _lockPath = Path.Combine(directory, "devices.lock");
        _clock = clock;
    }

    public MobilePairingCode CreatePairingCode() => RunLocalMutation(state =>
    {
        if (state.Devices.Count >= MaximumDevices)
            throw new InvalidOperationException("登録端末の上限に達しました。端末を解除してから新しいコードを発行してください。");

        var code = RandomNumberGenerator.GetInt32(100_000_000).ToString("D8", CultureInfo.InvariantCulture);
        var expires = _clock.GetUtcNow().AddMinutes(5);
        Write(state with { Pending = new PendingPairing(Hash(code), expires, MaximumPairingAttempts) });
        return new MobilePairingCode(code, expires);
    });

    public IReadOnlyList<MobileDevice> ListDevices() => Read().Devices.Select(entry => entry.Device).ToArray();

    public bool Revoke(string deviceId) => RunLocalMutation(state =>
    {
        var removed = state.Devices.RemoveAll(entry => entry.Device.DeviceId == deviceId) != 0;
        if (removed) Write(state);
        return removed;
    });

    internal string? FindPublicKey(string deviceId) =>
        Read().Devices.Find(entry => entry.Device.DeviceId == deviceId)?.PublicKey;

    internal Task<MobileDevice?> PairAsync(string code, string publicKey, string name, CancellationToken cancellationToken) =>
        WithWriteLockAsync<MobileDevice?>(state =>
        {
            var now = _clock.GetUtcNow();
            var pending = state.Pending;
            if (pending is null || pending.ExpiresAt <= now || state.Devices.Count >= MaximumDevices)
                return null;

            var candidateHash = SHA256.HashData(Encoding.UTF8.GetBytes(code));
            if (!CryptographicOperations.FixedTimeEquals(candidateHash, Convert.FromHexString(pending.CodeHash)))
            {
                var remaining = pending.AttemptsRemaining - 1;
                Write(state with { Pending = remaining > 0 ? pending with { AttemptsRemaining = remaining } : null });
                return null;
            }

            // Re-pairing the same key replaces its registration and invalidates its previous sessions.
            state.Devices.RemoveAll(entry => entry.PublicKey == publicKey);
            var device = new MobileDevice(Guid.NewGuid().ToString("N"), name, now);
            state.Devices.Add(new RegisteredDevice(device, publicKey));
            Write(state with { Pending = null });
            return device;
        }, cancellationToken);

    // Local WPF callers may hold a synchronization context while the cross-process lock is busy.
    private T RunLocalMutation<T>(Func<RegistryState, T> action) =>
        Task.Run(() => WithWriteLockAsync(action, CancellationToken.None)).GetAwaiter().GetResult();

    private async Task<T> WithWriteLockAsync<T>(Func<RegistryState, T> action, CancellationToken cancellationToken)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(3));
        try
        {
            await using var gate = await ExclusiveFileLock.AcquireAsync(_lockPath, timeout.Token);
            return action(Read());
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            throw new IOException("端末登録の操作が競合しました。もう一度お試しください。");
        }
    }

    private RegistryState Read()
    {
        try
        {
            // Writers replace the file atomically. Opening it on every check observes desktop revocation
            // immediately, even when the mobile host runs as a separate process.
            using var stream = File.Open(_statePath, FileMode.Open, FileAccess.Read, FileShare.Read | FileShare.Delete);
            return JsonSerializer.Deserialize<RegistryState>(stream)
                ?? throw new IOException("モバイル端末の登録データを読み込めません。");
        }
        catch (FileNotFoundException) { return new RegistryState(null, []); }
        catch (DirectoryNotFoundException) { return new RegistryState(null, []); }
        catch (JsonException exception) { throw new IOException("モバイル端末の登録データを読み込めません。", exception); }
    }

    private void Write(RegistryState state)
    {
        var temporary = _statePath + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            using (var stream = File.Open(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                JsonSerializer.Serialize(stream, state);
                stream.Flush(flushToDisk: true);
            }
            File.Move(temporary, _statePath, overwrite: true);
        }
        finally
        {
            File.Delete(temporary);
        }
    }

    private static string Hash(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value)));

    private sealed record RegisteredDevice(MobileDevice Device, string PublicKey);
    private sealed record PendingPairing(string CodeHash, DateTimeOffset ExpiresAt, int AttemptsRemaining);
    private sealed record RegistryState(PendingPairing? Pending, List<RegisteredDevice> Devices);
}
