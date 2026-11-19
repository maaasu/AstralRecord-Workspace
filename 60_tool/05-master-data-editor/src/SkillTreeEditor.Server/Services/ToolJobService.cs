using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text;
using System.Text.Json;
using SkillTreeEditor.Server.Models;

namespace SkillTreeEditor.Server.Services;

public sealed class ToolJobService(WorkspacePaths workspace, IConfiguration configuration)
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    private static readonly TimeSpan PlanLifetime = TimeSpan.FromMinutes(15);
    private static readonly TimeSpan BootstrapGrace = TimeSpan.FromSeconds(20);
    private readonly ConcurrentDictionary<Guid, PreparedPlan> plans = new();

    private string ToolRoot => Path.Combine(workspace.WorkspaceRoot, "60_tool");
    private string Bootstrap => Path.Combine(ToolRoot, "invoke-powershell7.ps1");
    private string Runner
    {
        get
        {
            var bundled = Path.Combine(AppContext.BaseDirectory, "tool-host", "Invoke-EditorToolJob.ps1");
            return File.Exists(bundled) ? bundled : Path.Combine(ToolRoot, "05-master-data-editor", "Invoke-EditorToolJob.ps1");
        }
    }
    private string StateRoot => Path.Combine(ToolRoot, "05-master-data-editor", ".cache", "tool-jobs");
    private bool Enabled => OperatingSystem.IsWindows()
        && !string.IsNullOrWhiteSpace(configuration["SkillTreeEditor:DesktopSessionToken"]);

    public ToolJobCapabilities GetCapabilities()
    {
        var reason = !Enabled ? "運用ツールは Windows デスクトップ版で使用できます。"
            : !File.Exists(Bootstrap) || !File.Exists(Runner) ? "選択中のワークスペースに運用ツール起動ファイルがありません。"
            : null;
        return new ToolJobCapabilities(reason is null, reason, workspace.WorkspaceRoot,
            reason is null ? GetRuns().FirstOrDefault(IsActive)?.Id : null,
            Enabled ? ReadWorkspaceBranch() : null);
    }

    public async Task<ToolJobPlan> CreatePlanAsync(ToolJobPlanRequest request, CancellationToken token)
    {
        RequireEnabled();
        var options = Normalize(request);
        var details = await ReadPlanAsync(options, token);
        var now = DateTimeOffset.UtcNow;
        foreach (var entry in plans.Where(entry => entry.Value.Plan.ExpiresAtUtc <= now))
            plans.TryRemove(entry.Key, out _);
        var prepared = PreparePlan(options, details, now);
        plans[prepared.Plan.Id] = prepared;
        return prepared.Plan;
    }

    public async Task<ToolJobRun> StartAsync(ToolJobStartRequest request, CancellationToken token)
    {
        RequireEnabled();
        token.ThrowIfCancellationRequested();
        var expiredMessage = "計画の有効期限が切れました。計画を作り直してください。";
        if (!plans.TryGetValue(request.PlanId, out var prepared))
            throw Conflict(expiredMessage);

        using var launch = AcquireToolLaunchLock(prepared.Plan.ToolId);
        if (!plans.ContainsKey(request.PlanId) || prepared.Plan.ExpiresAtUtc <= DateTimeOffset.UtcNow)
            throw Conflict(expiredMessage);
        RequireNoActiveRun(prepared.Plan.ToolId);
        if (!prepared.Plan.CanRun) throw Conflict("計画に表示された実行条件を解決し、新しい計画を確認してください。");
        var required = prepared.Plan.Confirmations.Select(item => item.Id).ToHashSet(StringComparer.Ordinal);
        if (request.Confirmations is null || request.Confirmations.Length != required.Count
            || !required.SetEquals(request.Confirmations))
            throw Conflict("現在の計画に表示されたすべての条件を確認してください。");

        var current = await ReadPlanAsync(prepared.Request, token);
        if (!current.CanRun || current.Fingerprint != prepared.Fingerprint || prepared.Plan.ExpiresAtUtc <= DateTimeOffset.UtcNow)
        {
            plans.TryRemove(request.PlanId, out _);
            throw Conflict("実行計画または設定が変わりました。新しい計画を確認してください。");
        }

        return Launch(prepared, token);
    }

    public async Task<ToolJobRun> StartDirectAsync(ToolJobDirectStartRequest request, CancellationToken token)
    {
        RequireEnabled();
        token.ThrowIfCancellationRequested();
        if (request.RequestId == Guid.Empty || request.Settings is null)
            throw new ToolJobException(400, "実行するツールと設定を指定してください。");
        if (request.Settings.ToolId is not ("01" or "03" or "12"))
            throw new ToolJobException(400, "直接実行できるツールを指定してください。AstralArchitect とメンテナンス配布は実行計画の確認が必要です。");

        var options = Normalize(request.Settings);
        using var launch = AcquireToolLaunchLock(options.ToolId);
        if (ReadDirectRun(request.RequestId, options) is { } existing)
            return existing;

        RequireNoActiveRun(options.ToolId);
        var details = await ReadPlanAsync(options, token);
        if (!details.CanRun)
            throw Conflict(string.Join(" ", details.Warnings.Prepend("実行条件を満たしていません。")));

        token.ThrowIfCancellationRequested();
        var directory = RunDirectory(request.RequestId);
        new MasterDataPaths(workspace).RequireNoLinks(directory);
        Directory.CreateDirectory(directory);
        // A request ID is shared across tool types. Only its owner can publish a run.
        using var requestLock = AcquireLock(Path.Combine(directory, "launch.lock"), "同じ実行 ID の処理を準備中です。");
        return ReadDirectRun(request.RequestId, options)
            ?? Launch(PreparePlan(options, details, DateTimeOffset.UtcNow), token, request.RequestId);
    }

    private ToolJobRun? ReadDirectRun(Guid id, ToolJobPlanRequest options)
    {
        if (ReadRun(id) is not { } existing) return null;
        var recordedRequest = ReadJson<ExecutionRequest>(Path.Combine(RunDirectory(id), "request.json"));
        if (recordedRequest is { Direct: true } && recordedRequest.Options == options)
            return existing;
        throw Conflict("同じ実行 ID に異なる実行設定が登録されています。");
    }

    private PreparedPlan PreparePlan(ToolJobPlanRequest options, ToolJobPlanDetails details, DateTimeOffset now)
    {
        var plan = new ToolJobPlan(Guid.NewGuid(), options.ToolId, workspace.WorkspaceRoot,
            details.ConfigurationPath, details.FilebaseSource, details.WorkspaceMismatch, details.CanRun, details.Warnings,
            details.Summary, details.Paths, details.Confirmations, now + PlanLifetime);
        return new PreparedPlan(plan, options, details.Fingerprint);
    }

    private FileStream AcquireToolLaunchLock(string toolId)
    {
        EnsureStorage();
        return AcquireLock(Path.Combine(StateRoot, $"launch-{toolId}.lock"), $"{toolId} の実行を準備中です。");
    }

    private void RequireNoActiveRun(string toolId)
    {
        if (GetRuns().Any(run => run.ToolId == toolId && IsActive(run)))
            throw Conflict($"{toolId} は実行中です。完了後に再実行してください。");
    }

    private ToolJobRun Launch(PreparedPlan prepared, CancellationToken token, Guid? directRequestId = null)
    {
        token.ThrowIfCancellationRequested();
        var run = new ToolJobRun(directRequestId ?? Guid.NewGuid(), prepared.Plan.ToolId, prepared.Plan,
            "starting", DateTimeOffset.UtcNow);
        var directory = RunDirectory(run.Id);
        run = run with { LogPath = Path.Combine(directory, "log.jsonl") };
        Directory.CreateDirectory(directory);
        WriteJson(Path.Combine(directory, "request.json"), new ExecutionRequest(prepared.Request, prepared.Fingerprint,
            Direct: directRequestId.HasValue));
        WriteState(run);
        if (directRequestId is null) plans.TryRemove(prepared.Plan.Id, out _);

        Process process;
        try
        {
            process = Process.Start(StartInfo("Run", directory, redirect: false))
                ?? throw new InvalidOperationException("PowerShell launch did not return a process.");
        }
        catch (Exception error) when (error is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            CompleteWithoutWorker(run, null);
            return GetRun(run.Id);
        }

        // PowerShell owns the durable status and log once it starts. This task only covers bootstrap failures.
        try
        {
            WriteJson(Path.Combine(directory, "launcher.json"), new ProcessIdentity(
                process.Id, process.StartTime.ToUniversalTime().Ticks));
        }
        catch (Exception error) when (error is InvalidOperationException or System.ComponentModel.Win32Exception or IOException)
        {
            // A fast exit can occur before StartTime is available. The observer still captures its exit code.
        }
        _ = ObserveBootstrapAsync(process, run);
        return GetRun(run.Id);
    }

    public ToolJobRun[] GetRuns()
    {
        RequireEnabled();
        if (!Directory.Exists(StateRoot)) return [];
        new MasterDataPaths(workspace).RequireNoLinks(StateRoot);
        return Directory.EnumerateDirectories(StateRoot)
            .Select(directory => Guid.TryParseExact(Path.GetFileName(directory), "D", out var id) ? ReadRun(id) : null)
            .OfType<ToolJobRun>()
            .OrderByDescending(run => run.StartedAtUtc)
            .ToArray();
    }

    public ToolJobRun GetRun(Guid id)
    {
        RequireEnabled();
        return ReadRun(id) ?? throw new ToolJobException(404, "実行記録が見つかりません。");
    }

    public ToolJobLogs GetLogs(Guid id, long after)
    {
        RequireEnabled();
        if (ReadState(id) is null) throw new ToolJobException(404, "実行記録が見つかりません。");
        if (after < 0) throw new ToolJobException(400, "ログの取得位置が無効です。");
        var path = Path.Combine(RunDirectory(id), "log.jsonl");
        new MasterDataPaths(workspace).RequireNoLinks(path);
        if (!File.Exists(path)) return new ToolJobLogs([], 0);

        using var stream = File.Open(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        var length = stream.Length;
        if (after > length) throw new ToolJobException(400, "ログの取得位置が無効です。");
        if (after > 0)
        {
            stream.Position = after - 1;
            if (stream.ReadByte() != '\n') throw new ToolJobException(400, "ログの取得位置が無効です。");
        }

        using var reader = new StreamReader(stream, new UTF8Encoding(false), detectEncodingFromByteOrderMarks: false);
        var lines = new List<ToolJobLogLine>();
        var next = after;
        while (lines.Count < 250 && next < length && reader.ReadLine() is { } line)
        {
            var end = next + Encoding.UTF8.GetByteCount(line) + 1;
            if (end > length) break; // The worker was still appending that line.
            var item = JsonSerializer.Deserialize<ToolJobLogLine>(line, Json)
                ?? throw new JsonException("Missing tool job log item.");
            lines.Add(item);
            next = end;
        }
        return new ToolJobLogs(lines.ToArray(), next);
    }

    public void SubmitInput(Guid id, ToolJobInputRequest request)
    {
        var run = GetRun(id);
        if (run.Prompt is null || run.Prompt.Id != request.PromptId
            || !run.Prompt.Choices.Any(choice => choice.Value == request.Value))
            throw Conflict("この実行に該当する入力待ちはありません。");
        var path = Path.Combine(RunDirectory(id), $"input-{request.PromptId:D}.json");
        new MasterDataPaths(workspace).RequireNoLinks(path);
        if (File.Exists(path)) throw Conflict("この確認への回答はすでに送信されています。");
        try
        {
            WriteJson(path, request, overwrite: false);
        }
        catch (IOException) when (File.Exists(path))
        {
            throw Conflict("この確認への回答はすでに送信されています。");
        }
    }

    internal static ToolJobPlanRequest Normalize(ToolJobPlanRequest request)
    {
        if (request.ToolId is not ("01" or "03" or "09" or "12" or "16"))
            throw new ToolJobException(400, "対応する運用ツールを選択してください。");
        if (request.ToolId != "12" && (request.NetworkTarget is not null || request.SkipTests)
            || request.ToolId != "16" && (request.WorldCopy is not null || request.NetworkPlugins is not null)
            || request.ToolId != "03" && request.ServerAlreadyRunning
            || request.ToolId != "01" && (request.PluginTestMode is not null || request.PluginTests is not null)
            || request.ToolId != "09" && request.ArchitectMode is not null
            || request.ToolId is ("09" or "12") && request.Recovery is not null)
            throw new ToolJobException(400, "選択したツールで使用できる項目を指定してください。");

        if (request.NetworkTarget is not (null or "All" or "Lobby" or "Proxy" or "Extension")
            || request.WorldCopy is not (null or "Include" or "Skip")
            || request.NetworkPlugins is not (null or "Include" or "Skip")
            || request.ArchitectMode is not (null or "BuildOnly" or "Deploy")
            || request.PluginTestMode is not (null or "All" or "Selected" or "Skip")
            || request.Recovery is not (null or "Auto" or "Restart" or "Restore")
            || request.Recovery == "Restore" && request.ToolId != "16"
            || request.ServerAlreadyRunning && request.Recovery is "Restart"
            || request.PluginTests?.Length > 1000
            || (request.PluginTestMode == "Selected") != (request.PluginTests is not null))
            throw new ToolJobException(400, "実行条件に無効な値があります。");

        return request with
        {
            NetworkTarget = request.ToolId == "12" ? request.NetworkTarget ?? "All" : null,
            WorldCopy = request.ToolId == "16" ? request.WorldCopy ?? "Skip" : null,
            NetworkPlugins = request.ToolId == "16" ? request.NetworkPlugins ?? "Skip" : null,
            Recovery = request.ToolId is ("09" or "12") ? null : request.Recovery ?? "Auto",
            ArchitectMode = request.ToolId == "09" ? request.ArchitectMode ?? "BuildOnly" : null
        };
    }

    private async Task<ToolJobPlanDetails> ReadPlanAsync(ToolJobPlanRequest request, CancellationToken token)
    {
        EnsureStorage();
        var directory = Path.Combine(StateRoot, "plan-" + Guid.NewGuid().ToString("D"));
        Directory.CreateDirectory(directory);
        try
        {
            WriteJson(Path.Combine(directory, "request.json"), new ExecutionRequest(request, null));
            using var process = Process.Start(StartInfo("Plan", directory, redirect: true))
                ?? throw new ToolJobException(503, "PowerShell を起動できません。");
            var output = process.StandardOutput.BaseStream.CopyToAsync(Stream.Null);
            var error = process.StandardError.BaseStream.CopyToAsync(Stream.Null);
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(token);
            timeout.CancelAfter(TimeSpan.FromMinutes(2));
            try
            {
                await process.WaitForExitAsync(timeout.Token);
                await Task.WhenAll(output, error).WaitAsync(timeout.Token);
            }
            catch (OperationCanceledException)
            {
                try { process.Kill(entireProcessTree: true); }
                catch (InvalidOperationException) { }
                token.ThrowIfCancellationRequested();
                throw new ToolJobException(503, "運用ツールの計画確認が時間内に終わりませんでした。");
            }

            var details = ReadJson<ToolJobPlanDetails>(Path.Combine(directory, "plan.json"));
            if (process.ExitCode == 0 && details is not null) return details;
            var failure = ReadJson<PlanningFailure>(Path.Combine(directory, "error.json"));
            throw new ToolJobException(422, failure?.Message ??
                "PowerShell 7 またはビルド環境の初期化に失敗しました。60_tool のローカル設定を確認してください。");
        }
        finally
        {
            // The directory contains only this invocation's fixed-name handoff files.
            foreach (var file in Directory.EnumerateFiles(directory))
                File.Delete(file);
            Directory.Delete(directory);
        }
    }

    private ProcessStartInfo StartInfo(string mode, string directory, bool redirect)
    {
        var start = new ProcessStartInfo
        {
            FileName = Path.Combine(Environment.SystemDirectory, "WindowsPowerShell", "v1.0", "powershell.exe"),
            WorkingDirectory = workspace.WorkspaceRoot,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = redirect,
            RedirectStandardError = redirect
        };
        foreach (var argument in new[] { "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                     "-File", Bootstrap, Runner, mode, directory, workspace.WorkspaceRoot })
            start.ArgumentList.Add(argument);
        start.Environment.Remove("SkillTreeEditor__DesktopSessionToken");
        start.Environment.Remove("SkillTreeEditor:DesktopSessionToken");
        start.Environment.Remove("ASTRALRECORD_CONTROL_GATE_LEASE");
        // A desktop launched from PowerShell 7 can inherit module paths incompatible with the 5.1 bootstrap.
        start.Environment.Remove("PSModulePath");
        return start;
    }

    private async Task ObserveBootstrapAsync(Process process, ToolJobRun run)
    {
        using (process)
        {
            try
            {
                await process.WaitForExitAsync();
                CompleteWithoutWorker(run, process.ExitCode);
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException
                or InvalidOperationException or System.ComponentModel.Win32Exception)
            {
                // History reconciliation can still identify the interrupted invocation after the process exits.
            }
        }
    }

    private void CompleteWithoutWorker(ToolJobRun initial, int? exitCode)
    {
        var current = ReadState(initial.Id);
        if (current is null || !IsActive(current) || IsProcessAlive(initial.Id, "worker.json")) return;
        var status = current.Status == "starting" ? "failed" : "interrupted";
        if (status == "failed")
        {
            var logFile = Path.Combine(RunDirectory(initial.Id), "log.jsonl");
            var startup = new ToolJobLogLine(1, "error",
                "PowerShell の運用ツールを起動できませんでした。PowerShell 7 と 60_tool のローカル設定を確認してください。");
            using var stream = File.Open(logFile, FileMode.OpenOrCreate, FileAccess.Write, FileShare.ReadWrite);
            if (stream.Length == 0)
            {
                JsonSerializer.Serialize(stream, startup, Json);
                stream.WriteByte((byte)'\n');
            }
        }
        WriteState(current with { Status = status, ExitCode = exitCode, FinishedAtUtc = DateTimeOffset.UtcNow, Prompt = null });
    }

    private ToolJobRun? ReadRun(Guid id)
    {
        var run = ReadState(id);
        if (run is null || !IsActive(run)) return run;
        if (IsProcessAlive(id, "worker.json") || IsProcessAlive(id, "launcher.json")
            || run.Status == "starting" && DateTimeOffset.UtcNow - run.StartedAtUtc < BootstrapGrace)
            return run;
        var latest = ReadState(id);
        if (latest is null || !IsActive(latest)) return latest;
        var interrupted = latest with { Status = "interrupted", FinishedAtUtc = DateTimeOffset.UtcNow, Prompt = null };
        WriteState(interrupted);
        return interrupted;
    }

    private bool IsProcessAlive(Guid id, string file)
    {
        var record = ReadJson<ProcessIdentity>(Path.Combine(RunDirectory(id), file));
        if (record is null) return false;
        try
        {
            using var process = Process.GetProcessById(record.Pid);
            return !process.HasExited && process.StartTime.ToUniversalTime().Ticks == record.StartedAtTicks;
        }
        catch (ArgumentException) { return false; }
        catch (InvalidOperationException) { return false; }
        catch (System.ComponentModel.Win32Exception) { return true; }
    }

    private string? ReadWorkspaceBranch()
    {
        try
        {
            var dotGit = Path.Combine(workspace.WorkspaceRoot, ".git");
            var gitDirectory = Directory.Exists(dotGit) ? dotGit : null;
            if (gitDirectory is null && File.Exists(dotGit))
            {
                var pointer = File.ReadLines(dotGit).FirstOrDefault();
                if (pointer?.StartsWith("gitdir: ", StringComparison.Ordinal) == true)
                    gitDirectory = Path.GetFullPath(pointer[8..], workspace.WorkspaceRoot);
            }
            var head = gitDirectory is null ? null : File.ReadLines(Path.Combine(gitDirectory, "HEAD")).FirstOrDefault();
            const string prefix = "ref: refs/heads/";
            return head?.StartsWith(prefix, StringComparison.Ordinal) == true ? head[prefix.Length..] : null;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or ArgumentException)
        {
            return null;
        }
    }

    private string RunDirectory(Guid id) => Path.Combine(StateRoot, id.ToString("D"));

    private ToolJobRun? ReadState(Guid id)
    {
        var directory = RunDirectory(id);
        var run = ReadJson<ToolJobRun>(Path.Combine(directory, "state.json"));
        // History can move with the workspace or numbered tool layout. Keep the
        // original journal untouched and expose the current physical log location.
        return run is null ? null : run with { LogPath = Path.Combine(directory, "log.jsonl") };
    }

    private void WriteState(ToolJobRun run) => WriteJson(Path.Combine(RunDirectory(run.Id), "state.json"), run);

    private T? ReadJson<T>(string path)
    {
        new MasterDataPaths(workspace).RequireNoLinks(path);
        try
        {
            using var stream = File.Open(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            return JsonSerializer.Deserialize<T>(stream, Json);
        }
        catch (FileNotFoundException) { return default; }
        catch (DirectoryNotFoundException) { return default; }
    }

    private static void WriteJson<T>(string path, T value, bool overwrite = true)
    {
        var temporary = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            using (var stream = File.Open(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                JsonSerializer.Serialize(stream, value, Json);
                stream.Flush(flushToDisk: true);
            }
            File.Move(temporary, path, overwrite);
        }
        finally
        {
            File.Delete(temporary);
        }
    }

    private FileStream AcquireLock(string lockPath, string conflictMessage)
    {
        new MasterDataPaths(workspace).RequireNoLinks(lockPath);
        try { return File.Open(lockPath, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None); }
        catch (IOException error) when ((error.HResult & 0xffff) is 32 or 33) { throw Conflict(conflictMessage); }
    }

    private void EnsureStorage()
    {
        var paths = new MasterDataPaths(workspace);
        paths.RequireNoLinks(StateRoot);
        paths.RequireNoLinks(Bootstrap);
        var workspaceRunner = Path.Combine(ToolRoot, "05-master-data-editor", "Invoke-EditorToolJob.ps1");
        if (Runner == workspaceRunner) paths.RequireNoLinks(workspaceRunner);
        Directory.CreateDirectory(StateRoot);
    }

    private void RequireEnabled()
    {
        if (!Enabled) throw new ToolJobException(404, "運用ツールは Windows デスクトップ版で使用できます。");
        if (!File.Exists(Bootstrap) || !File.Exists(Runner))
            throw new ToolJobException(503, "選択中のワークスペースに運用ツール起動ファイルがありません。");
    }

    private static bool IsActive(ToolJobRun run) => run.Status is "starting" or "running" or "waitingForInput";
    private static ToolJobException Conflict(string message) => new(409, message);
    private sealed record PreparedPlan(ToolJobPlan Plan, ToolJobPlanRequest Request, string Fingerprint);
    private sealed record ExecutionRequest(ToolJobPlanRequest Options, string? Fingerprint, bool Direct = false);
    private sealed record PlanningFailure(string Message);
    private sealed record ProcessIdentity(int Pid, long StartedAtTicks);
}

internal sealed class ToolJobException(int statusCode, string message) : Exception(message)
{
    public int StatusCode { get; } = statusCode;
}
