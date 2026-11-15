using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Services;

/// <summary>One CPU experiment at a time, with a wall deadline and bounded in-memory retention.</summary>
public sealed class BalanceLabJobs(BalanceLabCatalog catalog) : IDisposable
{
    private readonly object _gate = new();
    private readonly List<Job> _jobs = [];
    private bool _disposed;

    public JsonObject Start(JsonObject scenario)
    {
        BalanceLabRunner.Validate(scenario);
        var owned = scenario.DeepClone().AsObject();
        lock (_gate)
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            if (_jobs.Any(job => job.Status == "running"))
                throw new InvalidOperationException("ラボは実行中です。完了を待つか、現在の試行をキャンセルしてください。");
            while (_jobs.Count >= 8) { _jobs[0].Cancellation.Dispose(); _jobs.RemoveAt(0); }
            var job = new Job();
            _jobs.Add(job);
            job.Work = Task.Run(() => ExecuteAsync(job, owned));
            return Snapshot(job);
        }
    }

    public JsonObject Read(string id)
    {
        lock (_gate) return Snapshot(Find(id));
    }

    public JsonObject Cancel(string id)
    {
        lock (_gate)
        {
            var job = Find(id);
            if (job.Status == "running")
            {
                job.UserCancelled = true;
                job.Cancellation.Cancel();
            }
            return Snapshot(job);
        }
    }

    private async Task ExecuteAsync(Job job, JsonObject scenario)
    {
        try
        {
            job.Cancellation.CancelAfter(TimeSpan.FromSeconds(30));
            var snapshot = await catalog.ReadAsync(job.Cancellation.Token);
            await catalog.ValidateProposalAsync(snapshot, scenario, job.Cancellation.Token);
            var result = BalanceLabRunner.Run(snapshot, scenario, (done, total) =>
            {
                lock (_gate) { job.Completed = done; job.Total = total; }
            }, job.Cancellation.Token);
            job.Cancellation.Token.ThrowIfCancellationRequested();
            if (result.ToJsonString().Length > 16_000_000)
                throw new ArgumentException("結果が16MBを超えました。試行数またはビルド数を減らしてください。");
            lock (_gate) { job.Result = result; job.Status = "completed"; job.Completed = job.Total; }
        }
        catch (OperationCanceledException)
        {
            lock (_gate)
            {
                job.Status = "cancelled";
                job.Error = job.UserCancelled ? "キャンセルしました。部分結果は全体の成績として保存しません。" : "実時間の上限30秒に達しました。条件を絞って再実行してください。";
            }
        }
        catch (Exception exception)
        {
            lock (_gate) { job.Status = "failed"; job.Error = "計算を完了できませんでした: " + exception.Message; }
        }
    }

    private Job Find(string id) => _jobs.Find(job => job.Id == id)
        ?? throw new FileNotFoundException("ラボの実行結果がありません。再起動または保存上限で破棄された結果は、保存条件から再実行してください。");

    private static JsonObject Snapshot(Job job) => new()
    {
        ["id"] = job.Id, ["status"] = job.Status, ["completed"] = job.Completed, ["total"] = job.Total,
        ["error"] = job.Error, ["result"] = job.Result?.DeepClone()
    };

    public void Dispose()
    {
        lock (_gate)
        {
            _disposed = true;
            foreach (var job in _jobs.Where(job => job.Status == "running")) job.Cancellation.Cancel();
            // A running task still owns its token. Dispose its source after it has observed cancellation.
            foreach (var job in _jobs)
                _ = job.Work.ContinueWith(_ => job.Cancellation.Dispose(), CancellationToken.None,
                    TaskContinuationOptions.ExecuteSynchronously, TaskScheduler.Default);
        }
    }

    private sealed class Job
    {
        public string Id { get; } = Guid.NewGuid().ToString("N");
        public string Status { get; set; } = "running";
        public int Completed { get; set; }
        public int Total { get; set; }
        public bool UserCancelled { get; set; }
        public string? Error { get; set; }
        public JsonObject? Result { get; set; }
        public CancellationTokenSource Cancellation { get; } = new();
        public Task Work { get; set; } = Task.CompletedTask;
    }
}
