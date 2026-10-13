using AstralRecordApi.Models;
using AstralRecordApi.Services;
using Xunit;

namespace AstralRecordApi.Tests.Services;

public sealed class ServerRecordsCacheTests
{
    [Fact]
    public async Task ConcurrentRequestsWithDifferentLimitsShareOneCalculation()
    {
        var clock = new Clock();
        var cache = new ServerRecordsCache(clock);
        var started = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var calls = 0;
        async Task<ServerRecordsResponse> Calculate(CancellationToken token)
        {
            Interlocked.Increment(ref calls);
            started.SetResult();
            await release.Task.WaitAsync(token);
            return Result(clock, 100);
        }
        var first = cache.GetAsync([], 1, Calculate);
        await started.Task;
        var others = Enumerable.Range(2, 20).Select(limit => cache.GetAsync([], limit, Calculate)).ToArray();
        release.SetResult();
        var responses = await Task.WhenAll(others.Prepend(first));
        Assert.Equal(1, calls);
        Assert.Equal(Enumerable.Range(1, 21), responses.Select(response => response.Rankings[0].Entries.Count));
        Assert.All(responses, response => Assert.Equal(clock.GetUtcNow(), response.GeneratedAt));
    }

    [Fact]
    public async Task ExpiryAndAuthorityChangesRecalculateWithoutGrowingTheCache()
    {
        var clock = new Clock();
        var cache = new ServerRecordsCache(clock);
        var calls = 0;
        Task<ServerRecordsResponse> Calculate(CancellationToken _) { calls++; return Task.FromResult(Result(clock, 100)); }
        var a = Guid.NewGuid();
        var b = Guid.NewGuid();
        var first = await cache.GetAsync([a, b], 10, Calculate);
        clock.Advance(TimeSpan.FromSeconds(9));
        var reordered = await cache.GetAsync([b, a, a], 100, Calculate);
        Assert.Equal(first.GeneratedAt, reordered.GeneratedAt);
        Assert.Equal(1, calls);
        clock.Advance(TimeSpan.FromSeconds(1));
        var fresh = await cache.GetAsync([a, b], 10, Calculate);
        Assert.NotEqual(first.GeneratedAt, fresh.GeneratedAt);
        Assert.Equal(2, calls);
        await cache.GetAsync([a], 10, Calculate);
        Assert.Equal(3, calls);
        await cache.GetAsync([a, b], 10, Calculate);
        Assert.Equal(4, calls);
    }

    [Fact]
    public async Task FailureAndCancellationDoNotPoisonTheNextCalculation()
    {
        var clock = new Clock();
        var cache = new ServerRecordsCache(clock);
        await Assert.ThrowsAsync<InvalidOperationException>(() => cache.GetAsync([], 10,
            _ => throw new InvalidOperationException("calculation failed")));
        using var canceled = new CancellationTokenSource();
        canceled.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => cache.GetAsync([], 10,
            _ => Task.FromResult(Result(clock, 100)), canceled.Token));
        var result = await cache.GetAsync([], 10, _ => Task.FromResult(Result(clock, 100)));
        Assert.Equal(10, result.Rankings[0].Entries.Count);
    }

    private static ServerRecordsResponse Result(TimeProvider clock, int count) =>
        new(clock.GetUtcNow(), [new("level", "level", true, Enumerable.Range(1, count)
            .Select(rank => new ServerRankingEntryResponse(rank, Guid.NewGuid(), "Player" + rank, count - rank + 1)).ToArray())]);

    private sealed class Clock : TimeProvider
    {
        private DateTimeOffset now = new(2026, 10, 13, 0, 0, 0, TimeSpan.Zero);
        public override DateTimeOffset GetUtcNow() => now;
        public void Advance(TimeSpan duration) => now += duration;
    }
}
