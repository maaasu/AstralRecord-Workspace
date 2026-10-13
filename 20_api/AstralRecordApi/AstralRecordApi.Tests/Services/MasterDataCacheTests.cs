using AstralRecordApi.Services;
using Xunit;

namespace AstralRecordApi.Tests.Services;

public class MasterDataCacheTests
{
    [Fact]
    public async Task ConcurrentColdReaders_RunOneLoaderAndShareConvertedValue()
    {
        var cache = new MasterDataCache();
        var started = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var loads = 0;
        async Task<object> Load()
        {
            Interlocked.Increment(ref loads);
            started.TrySetResult();
            await release.Task;
            return new object();
        }
        var first = cache.GetAsync("item", Load);
        await started.Task;
        var readers = Enumerable.Range(0, 32).Select(_ => cache.GetAsync("item", Load)).ToArray();
        release.SetResult();
        var values = await Task.WhenAll(readers.Prepend(first));
        Assert.Equal(1, loads);
        Assert.All(values, value => Assert.Same(values[0], value));
    }

    [Fact]
    public async Task InvalidateDuringFill_DiscardsOldValueAndReloadsCommittedGeneration()
    {
        var cache = new MasterDataCache();
        var started = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var loads = 0;
        var value = 1;
        async Task<int> Load()
        {
            Interlocked.Increment(ref loads);
            var observed = value;
            if (observed == 1) { started.SetResult(); await release.Task; }
            return observed;
        }
        var pending = cache.GetAsync("item", Load);
        await started.Task;
        value = 2;
        cache.Invalidate();
        release.SetResult();
        var result = await pending;
        Assert.Equal(2, result.Value);
        Assert.Equal(2, loads);
        Assert.Same(result, cache.Get("item", () => 3));
    }

    [Fact]
    public async Task FailedFill_DoesNotPublishAndNextReaderRetries()
    {
        var cache = new MasterDataCache();
        await Assert.ThrowsAsync<InvalidOperationException>(() => cache.GetAsync<int>("skill", () => throw new InvalidOperationException()));
        Assert.Equal(7, (await cache.GetAsync("skill", () => Task.FromResult(7))).Value);
    }

    [Fact]
    public void RetentionExpiresAfterFiveMinutes_RefreshesExternalChangesAndGeneration()
    {
        var clock = new Clock();
        var cache = new MasterDataCache(clock);
        var initial = cache.Get("item", () => 1);
        clock.UtcNow += TimeSpan.FromMinutes(5);
        var refreshed = cache.Get("item", () => 2);
        Assert.Equal(2, refreshed.Value);
        Assert.NotEqual(initial.Generation, refreshed.Generation);
    }
    private sealed class Clock : TimeProvider
    {
        public DateTimeOffset UtcNow { get; set; } = DateTimeOffset.UtcNow;
        public override DateTimeOffset GetUtcNow() => UtcNow;
    }
}
