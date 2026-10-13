using AstralRecordApi.Models;

namespace AstralRecordApi.Services;

/// <summary>全チャンネルで上位100件を10秒間共有し、同時要求による重複集計を抑制します。</summary>
public sealed class ServerRecordsCache(TimeProvider clock)
{
    public const int MaximumLimit = 100;
    private static readonly TimeSpan Lifetime = TimeSpan.FromSeconds(10);
    private readonly SemaphoreSlim fillGate = new(1, 1);
    private CacheEntry? current;

    public async Task<ServerRecordsResponse> GetAsync(
        IReadOnlyCollection<Guid> authorities, int limit,
        Func<CancellationToken, Task<ServerRecordsResponse>> create,
        CancellationToken cancellationToken = default)
    {
        if (limit is < 1 or > MaximumLimit) throw new ArgumentOutOfRangeException(nameof(limit));
        var key = string.Join(',', authorities.Distinct().Order());
        var cached = Read(key);
        if (cached is not null) return Slice(cached, limit);

        await fillGate.WaitAsync(cancellationToken);
        try
        {
            cached = Read(key);
            if (cached is null)
            {
                var result = await create(cancellationToken);
                // DTOの共有コレクションを読み取り専用にして、要求間の変更混入を防ぐ。
                cached = result with
                {
                    Rankings = Array.AsReadOnly(result.Rankings.Select(ranking => ranking with
                    {
                        Entries = Array.AsReadOnly(ranking.Entries.Take(MaximumLimit).ToArray()),
                    }).ToArray()),
                };
                Volatile.Write(ref current, new CacheEntry(key, clock.GetUtcNow(), cached));
            }
            return Slice(cached, limit);
        }
        finally
        {
            fillGate.Release();
        }
    }

    private ServerRecordsResponse? Read(string key)
    {
        var entry = Volatile.Read(ref current);
        return entry is not null && entry.Authorities == key && clock.GetUtcNow() - entry.CachedAt < Lifetime
            ? entry.Result : null;
    }

    private static ServerRecordsResponse Slice(ServerRecordsResponse result, int limit) =>
        limit == MaximumLimit ? result : result with
        {
            Rankings = Array.AsReadOnly(result.Rankings.Select(ranking => ranking with
            {
                Entries = Array.AsReadOnly(ranking.Entries.Take(limit).ToArray()),
            }).ToArray()),
        };

    private sealed record CacheEntry(string Authorities, DateTimeOffset CachedAt, ServerRecordsResponse Result);
}
