using System.Diagnostics;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Configuration;
using SkillTreeEditor.Server.Models;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class ToolJobServiceTests
{
    [Fact]
    public void BrowserHostDoesNotExposeOperationHistory()
    {
        var workspace = new WorkspacePaths(Path.GetTempPath());
        var service = new ToolJobService(workspace, new ConfigurationBuilder().Build());
        Assert.False(service.GetCapabilities().Enabled);
        Assert.Null(service.GetCapabilities().ActiveRunId);
    }

    [Fact]
    public void AReopenedDesktopFindsLiveRunsAndReconcilesDeadWorkers()
    {
        if (!OperatingSystem.IsWindows()) return;
        var root = Directory.CreateTempSubdirectory("editor-tool-state-");
        try
        {
            var jobs = Path.Combine(root.FullName, "60_tool", "skilltree-editor", ".cache", "tool-jobs");
            Directory.CreateDirectory(jobs);
            File.WriteAllText(Path.Combine(root.FullName, "60_tool", "invoke-powershell7.ps1"), "");
            File.WriteAllText(Path.Combine(root.FullName, "60_tool", "skilltree-editor", "Invoke-EditorToolJob.ps1"), "");
            var configuration = new ConfigurationBuilder()
                .AddInMemoryCollection(new Dictionary<string, string?>
                {
                    ["SkillTreeEditor:DesktopSessionToken"] = "test-desktop-token"
                })
                .Build();
            var workspace = new WorkspacePaths(root.FullName);
            var now = DateTimeOffset.UtcNow;
            var plan = new ToolJobPlan(Guid.NewGuid(), "12", root.FullName, null, null,
                false, true, [], [], [], [], now);
            var live = new ToolJobRun(Guid.NewGuid(), "12", plan, "running", now);
            var orphan = new ToolJobRun(Guid.NewGuid(), "12", plan, "waitingForInput", now.AddMinutes(-1),
                Prompt: new ToolJobPrompt(Guid.NewGuid(), "test", []));
            using var process = Process.GetCurrentProcess();
            var startedAtTicks = process.StartTime.ToUniversalTime().Ticks;
            WriteRun(jobs, live, process.Id, startedAtTicks);

            // A recycled PID must not give old work an active lease in the editor.
            WriteRun(jobs, orphan, process.Id, startedAtTicks - 1);
            var service = new ToolJobService(workspace, configuration);
            Assert.Equal(live.Id, service.GetCapabilities().ActiveRunId);
            var reconciled = service.GetRun(orphan.Id);
            Assert.Equal("interrupted", reconciled.Status);
            Assert.NotNull(reconciled.FinishedAtUtc);
            Assert.Null(reconciled.Prompt);

            var reopened = new ToolJobService(workspace, configuration);
            Assert.Equal(reconciled.FinishedAtUtc, reopened.GetRun(orphan.Id).FinishedAtUtc);
            var first = reopened.GetLogs(live.Id, 0);
            var item = Assert.Single(first.Lines);
            Assert.Equal("日本語の作業ログ", item.Text);
            Assert.Empty(reopened.GetLogs(live.Id, first.Next).Lines);
        }
        finally
        {
            root.Delete(recursive: true);
        }
    }

    [Fact]
    public async Task StartingRequiresCurrentPlanAndPreventsConcurrentOrRepeatExecution()
    {
        if (!OperatingSystem.IsWindows()) return;
        var root = Directory.CreateTempSubdirectory("editor-tool-start-");
        var tools = Directory.CreateDirectory(Path.Combine(root.FullName, "60_tool"));
        var adapter = Directory.CreateDirectory(Path.Combine(tools.FullName, "skilltree-editor"));
        var gate = Path.Combine(root.FullName, "finish");
        var config = Path.Combine(root.FullName, "fixture.json");
        var startedRun = (ToolJobRun?)null;
        var service = new ToolJobService(new WorkspacePaths(root.FullName), new ConfigurationBuilder()
            .AddInMemoryCollection(new Dictionary<string, string?>
            {
                ["SkillTreeEditor:DesktopSessionToken"] = "fixture-token"
            })
            .Build());
        using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(50));
        var cancellation = deadline.Token;
        try
        {
            File.WriteAllText(config, "{}");
            File.WriteAllText(Path.Combine(adapter.FullName, "Invoke-EditorToolJob.ps1"), "");
            File.WriteAllText(Path.Combine(tools.FullName, "invoke-powershell7.ps1"), """
                param($Adapter, $Mode, $Job, $Workspace)
                $ErrorActionPreference = 'Stop'
                $utf8 = [Text.UTF8Encoding]::new($false)
                function Save-Json($path, $value) {
                    [IO.File]::WriteAllText($path, ($value | ConvertTo-Json -Depth 20 -Compress), $utf8)
                }
                if ($Mode -eq 'Plan') {
                    Save-Json (Join-Path $Job 'plan.json') @{ canRun=$true; workspaceMismatch=$false;
                        warnings=@(); summary=@(); paths=@(); confirmations=@(@{id='build';label='Build'});
                        fingerprint=(Get-FileHash -LiteralPath (Join-Path $Workspace 'fixture.json')).Hash }
                    exit 0
                }
                for ($i = 0; $i -lt 200 -and !(Test-Path -LiteralPath (Join-Path $Workspace 'finish')); $i++) {
                    Start-Sleep -Milliseconds 100
                }
                $statePath = Join-Path $Job 'state.json'
                $state = Get-Content -Raw -LiteralPath $statePath | ConvertFrom-Json
                $state.status = 'succeeded'
                $state.exitCode = 0
                $state.finishedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
                Save-Json "$statePath.swap" $state
                Move-Item -LiteralPath "$statePath.swap" -Destination $statePath -Force
                exit 0
                """);

            var options = new ToolJobPlanRequest("12");
            var stale = await service.CreatePlanAsync(options, cancellation);
            Assert.NotNull(await Record.ExceptionAsync(() => service.StartAsync(
                new ToolJobStartRequest(stale.Id, []), cancellation)));
            File.WriteAllText(config, "{\"changed\":true}");
            var changed = await Record.ExceptionAsync(() => service.StartAsync(
                new ToolJobStartRequest(stale.Id, ["build"]), cancellation));
            Assert.Contains("変わりました", changed?.Message);

            var selected = await service.CreatePlanAsync(options, cancellation);
            var competingPlan = await service.CreatePlanAsync(options, cancellation);
            var first = service.StartAsync(new ToolJobStartRequest(selected.Id, ["build"]), cancellation);
            startedRun = await first;
            Assert.Equal(startedRun.Id, service.GetCapabilities().ActiveRunId);
            var competing = await Record.ExceptionAsync(() => service.StartAsync(
                new ToolJobStartRequest(competingPlan.Id, ["build"]), cancellation));
            Assert.Contains("実行中", competing?.Message);

            File.WriteAllText(gate, "");
            while (service.GetRun(startedRun.Id).Status != "succeeded")
                await Task.Delay(100, cancellation);
            var repeated = await Record.ExceptionAsync(() => service.StartAsync(
                new ToolJobStartRequest(selected.Id, ["build"]), cancellation));
            Assert.Contains("計画を作り直してください", repeated?.Message);
            Assert.Single(service.GetRuns());
        }
        finally
        {
            if (startedRun is not null)
            {
                File.WriteAllText(gate, "");
                using var cleanup = new CancellationTokenSource(TimeSpan.FromSeconds(22));
                while (service.GetRun(startedRun.Id).Status is "starting" or "running")
                    await Task.Delay(100, cleanup.Token);
            }
            root.Delete(recursive: true);
        }
    }

    private static void WriteRun(string jobs, ToolJobRun run, int pid, long startedAtTicks)
    {
        var json = new JsonSerializerOptions(JsonSerializerDefaults.Web);
        var path = Directory.CreateDirectory(Path.Combine(jobs, run.Id.ToString("D"))).FullName;
        File.WriteAllText(Path.Combine(path, "state.json"), JsonSerializer.Serialize(run, json));
        File.WriteAllText(Path.Combine(path, "worker.json"), JsonSerializer.Serialize(new { pid, startedAtTicks }, json));
        var log = JsonSerializer.Serialize(new ToolJobLogLine(1, "output", "日本語の作業ログ"), json);
        File.WriteAllText(Path.Combine(path, "log.jsonl"), log + "\n", new UTF8Encoding(false));
    }
}
