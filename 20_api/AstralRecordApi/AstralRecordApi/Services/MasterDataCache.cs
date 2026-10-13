using System.Collections.Concurrent;

namespace AstralRecordApi.Services;

/// <summary>Seeder が確定した世代ごとに変換済みマスタを共有し、初回取得を直列化する。</summary>
public sealed class MasterDataCache(TimeProvider? timeProvider = null)
{
    private readonly ConcurrentDictionary<string, SemaphoreSlim> gates = new();
    private readonly Dictionary<string, object> values = new();
    private readonly object sync = new();
    private readonly TimeProvider clock = timeProvider ?? TimeProvider.System;
    private DateTimeOffset expiresAt = (timeProvider ?? TimeProvider.System).GetUtcNow().AddMinutes(5);
    private string generation = Guid.NewGuid().ToString("N");

    public void Invalidate()
    {
        lock (sync)
        {
            generation = Guid.NewGuid().ToString("N");
            values.Clear();
            expiresAt = clock.GetUtcNow().AddMinutes(5);
        }
    }

    public MasterDataCacheValue<T> Get<T>(string key, Func<T> load)
        => GetAsync(key, () => Task.FromResult(load())).GetAwaiter().GetResult();

    public async Task<MasterDataCacheValue<T>> GetAsync<T>(string key, Func<Task<T>> load,
        CancellationToken cancellationToken = default)
    {
        lock (sync)
        {
            if (clock.GetUtcNow() >= expiresAt) Invalidate();
            if (values.TryGetValue(key, out var cached)) return (MasterDataCacheValue<T>)cached;
        }
        var gate = gates.GetOrAdd(key, _ => new SemaphoreSlim(1, 1));
        await gate.WaitAsync(cancellationToken);
        try
        {
            while (true)
            {
                string startedGeneration;
                lock (sync)
                {
                    if (values.TryGetValue(key, out var cached))
                        return (MasterDataCacheValue<T>)cached;
                    startedGeneration = generation;
                }
                var value = await load();
                lock (sync)
                {
                    // seed と並行して読んだ旧世代は公開せず、確定後の DB を再取得する。
                    if (startedGeneration != generation)
                        continue;
                    var result = new MasterDataCacheValue<T>(generation, value);
                    values.Add(key, result);
                    return result;
                }
            }
        }
        finally { gate.Release(); }
    }
}

public sealed record MasterDataCacheValue<T>(string Generation, T Value);
