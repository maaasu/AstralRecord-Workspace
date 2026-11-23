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
    public void ArchitectModeIsScopedToTool09()
    {
        Assert.Equal("BuildOnly", ToolJobService.Normalize(new ToolJobPlanRequest("09")).ArchitectMode);
        var deploy = ToolJobService.Normalize(new ToolJobPlanRequest("09", ArchitectMode: "Deploy"));
        Assert.Equal("Deploy", deploy.ArchitectMode);
        Assert.Null(deploy.Recovery);
        Assert.Throws<ToolJobException>(() => ToolJobService.Normalize(new ToolJobPlanRequest("09", ArchitectMode: "Invalid")));
        Assert.Throws<ToolJobException>(() => ToolJobService.Normalize(new ToolJobPlanRequest("09", Recovery: "Auto")));
        Assert.Throws<ToolJobException>(() => ToolJobService.Normalize(new ToolJobPlanRequest("12", ArchitectMode: "Deploy")));
    }

    [Fact]
    public async Task BrowserHostDoesNotExposeOperationHistoryOrMaintenanceOptions()
    {
        var workspace = new WorkspacePaths(Path.GetTempPath());
        var service = new ToolJobService(workspace, new ConfigurationBuilder().Build());
        Assert.False(service.GetCapabilities().Enabled);
        Assert.Null(service.GetCapabilities().ActiveRunId);
        var denied = await Assert.ThrowsAsync<ToolJobException>(async () => await service.GetMaintenanceOptionsAsync(default));
        Assert.Equal(404, denied.StatusCode);
    }

    [Fact]
    public void DetailedMaintenanceRequiresUniqueIdsAndTheConfigurationDigest()
    {
        var digest = new string('a', 64);
        var request = new ToolJobPlanRequest("16", DistributionSelection: ["n2", "d1-t2"], DistributionConfigurationDigest: digest);
        var settings = ToolJobService.Normalize(request);
        Assert.Equal(new[] { "d1-t2", "n2" }, settings.DistributionSelection);
        Assert.Equal(digest.ToUpperInvariant(), settings.DistributionConfigurationDigest);
        Assert.Null(settings.WorldCopy);
        Assert.Null(settings.NetworkPlugins);
        Assert.Equal("Skip", ToolJobService.Normalize(new ToolJobPlanRequest("16")).WorldCopy);

        Assert.Throws<ToolJobException>(() => ToolJobService.Normalize(request with { ToolId = "01" }));
        Assert.Throws<ToolJobException>(() => ToolJobService.Normalize(request with { WorldCopy = "Skip" }));
        Assert.Throws<ToolJobException>(() => ToolJobService.Normalize(request with { DistributionConfigurationDigest = null }));
        Assert.Throws<ToolJobException>(() => ToolJobService.Normalize(request with { DistributionSelection = null }));
        Assert.Throws<ToolJobException>(() => ToolJobService.Normalize(request with { DistributionSelection = [] }));
        Assert.Throws<ToolJobException>(() => ToolJobService.Normalize(request with { DistributionSelection = ["n1", "n1"] }));
        Assert.Throws<ToolJobException>(() => ToolJobService.Normalize(request with { DistributionSelection = ["d0-t1"] }));
    }

    [Fact]
    public void RelocatedCompletedHistoryUsesCurrentLogPathWithoutRewritingJournal()
    {
        if (!OperatingSystem.IsWindows()) return;
        var root = Directory.CreateTempSubdirectory("editor-tool-relocation-");
        try
        {
            WriteBootstrap(root);
            var jobs = Path.Combine(root.FullName, "60_tool", "05-master-data-editor", ".cache", "tool-jobs");
            var now = DateTimeOffset.UtcNow;
            var plan = new ToolJobPlan(Guid.NewGuid(), "12", root.FullName, null, null, false, true, [], [], [], [], now);
            var run = new ToolJobRun(Guid.NewGuid(), "12", plan, "succeeded", now, LogPath: "C:/old-editor/log.jsonl");
            WriteRun(jobs, run, 0, 0);
            var directory = Path.Combine(jobs, run.Id.ToString("D"));
            var statePath = Path.Combine(directory, "state.json");
            var original = File.ReadAllBytes(statePath);
            var service = CreateService(root);
            var current = service.GetRun(run.Id);
            Assert.Equal(Path.Combine(directory, "log.jsonl"), current.LogPath);
            Assert.True(File.Exists(current.LogPath));
            Assert.Equal("日本語の作業ログ", Assert.Single(service.GetLogs(run.Id, 0).Lines).Text);
            Assert.Equal(original, File.ReadAllBytes(statePath));
        }
        finally { root.Delete(recursive: true); }
    }

    [Fact]
    public void AReopenedDesktopFindsLiveRunsAndReconcilesDeadWorkers()
    {
        if (!OperatingSystem.IsWindows()) return;
        var root = Directory.CreateTempSubdirectory("editor-tool-state-");
        try
        {
            var jobs = Path.Combine(root.FullName, "60_tool", "05-master-data-editor", ".cache", "tool-jobs");
            Directory.CreateDirectory(jobs);
            File.WriteAllText(Path.Combine(root.FullName, "60_tool", "invoke-powershell7.ps1"), "");
            File.WriteAllText(Path.Combine(root.FullName, "60_tool", "05-master-data-editor", "Invoke-EditorToolJob.ps1"), "");
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
        var gate = Path.Combine(root.FullName, "finish");
        var config = Path.Combine(root.FullName, "fixture.json");
        var startedRun = (ToolJobRun?)null;
        var service = CreateService(root);
        using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(50));
        var cancellation = deadline.Token;
        try
        {
            File.WriteAllText(config, "{}");
            WriteBootstrap(root);

            var options = new ToolJobPlanRequest("16");
            var stale = await service.CreatePlanAsync(options, cancellation);
            var unconfirmed = await Assert.ThrowsAsync<ToolJobException>(() => service.StartAsync(
                new ToolJobStartRequest(stale.Id, []), cancellation));
            Assert.Equal(409, unconfirmed.StatusCode);
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
                await WaitForLauncherExitAsync(startedRun, cleanup.Token);
            }
            root.Delete(recursive: true);
        }
    }

    [Fact]
    public async Task DifferentToolsCanPrepareAndRunTogetherWhileDuplicatesArePrevented()
    {
        if (!OperatingSystem.IsWindows()) return;
        var root = Directory.CreateTempSubdirectory("editor-tool-direct-");
        var config = Path.Combine(root.FullName, "fixture.json");
        var planGate = Path.Combine(root.FullName, "pause-plan-01");
        var runGate = Path.Combine(root.FullName, "finish");
        var service = CreateService(root);
        Task<ToolJobRun>? starting = null;
        using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(50));
        var token = deadline.Token;
        try
        {
            WriteBootstrap(root);
            var request = new ToolJobDirectStartRequest(Guid.NewGuid(),
                new ToolJobPlanRequest("01", PluginTestMode: "Selected", PluginTests: "LatestTest"));

            var maintenance = await Assert.ThrowsAsync<ToolJobException>(() => service.StartDirectAsync(
                new ToolJobDirectStartRequest(Guid.NewGuid(), new ToolJobPlanRequest("16")), token));
            Assert.Equal(400, maintenance.StatusCode);
            var architect = await Assert.ThrowsAsync<ToolJobException>(() => service.StartDirectAsync(
                new ToolJobDirectStartRequest(Guid.NewGuid(), new ToolJobPlanRequest("09", ArchitectMode: "Deploy")), token));
            Assert.Equal(400, architect.StatusCode);

            File.WriteAllText(config, "{}");
            var stale = await service.CreatePlanAsync(new ToolJobPlanRequest("01", PluginTestMode: "All"), token);
            File.WriteAllText(config, "{\"blocked\":true}");
            var blocked = await Assert.ThrowsAsync<ToolJobException>(() => service.StartDirectAsync(request, token));
            Assert.Equal(409, blocked.StatusCode);
            Assert.Contains("Fixture deployment is blocked.", blocked.Message);
            Assert.Empty(service.GetRuns());

            File.WriteAllText(config, "{\"fresh\":true}");
            File.WriteAllText(planGate, "");
            starting = service.StartDirectAsync(request, token);
            var repeatedDuringPreparation = await Assert.ThrowsAsync<ToolJobException>(() => service.StartDirectAsync(request, token));
            Assert.Equal(409, repeatedDuringPreparation.StatusCode);
            var otherService = CreateService(root);
            var competingRequest = request with { RequestId = Guid.NewGuid() };
            var competingDuringPreparation = await Assert.ThrowsAsync<ToolJobException>(() => otherService.StartDirectAsync(competingRequest, token));
            Assert.Equal(409, competingDuringPreparation.StatusCode);
            Assert.Contains("01", competingDuringPreparation.Message);
            var buildRequest = new ToolJobDirectStartRequest(Guid.NewGuid(), new ToolJobPlanRequest("12"));
            var build = await otherService.StartDirectAsync(buildRequest, token);
            Assert.Equal(buildRequest.RequestId, build.Id);
            Assert.False(starting.IsCompleted);

            File.Delete(planGate);
            var run = await starting;

            Assert.Equal(request.RequestId, run.Id);
            Assert.NotEqual(stale.Id, run.Plan.Id);
            Assert.True(run.Plan.WorkspaceMismatch);
            Assert.Single(run.Plan.Confirmations);
            Assert.Equal(run.Id, otherService.GetCapabilities().ActiveRunId);
            Assert.Equal(run.Id, (await otherService.StartDirectAsync(request, token)).Id);
            var competing = await Assert.ThrowsAsync<ToolJobException>(() => otherService.StartDirectAsync(competingRequest, token));
            Assert.Equal(409, competing.StatusCode);
            Assert.Contains("実行中", competing.Message);
            Assert.Equal(new[] { run.Id, build.Id }, service.GetRuns().Select(entry => entry.Id));
            Assert.All(service.GetRuns(), entry => Assert.Null(entry.FinishedAtUtc));

            var reusedId = buildRequest with { Settings = request.Settings };
            var reused = await Assert.ThrowsAsync<ToolJobException>(() => service.StartDirectAsync(reusedId, token));
            Assert.Equal(409, reused.StatusCode);
            Assert.Contains("異なる実行設定", reused.Message);

            File.WriteAllText(runGate, "");
            while (service.GetRuns().Any(entry => entry.FinishedAtUtc is null))
                await Task.Delay(100, token);
            var failed = await otherService.StartDirectAsync(request, token);
            Assert.Equal("failed", failed.Status);
            Assert.Equal(7, failed.ExitCode);
            Assert.Equal("Synthetic deployment failure.", Assert.Single(service.GetLogs(run.Id, 0).Lines).Text);
            Assert.Equal("Completed.", Assert.Single(service.GetLogs(build.Id, 0).Lines).Text);
            Assert.Equal(2, service.GetRuns().Length);

            var otherSettings = request with { Settings = request.Settings with { PluginTests = "DifferentTest" } };
            var changed = await Assert.ThrowsAsync<ToolJobException>(() => service.StartDirectAsync(otherSettings, token));
            Assert.Equal(409, changed.StatusCode);
            Assert.Equal(2, service.GetRuns().Length);
        }
        finally
        {
            File.Delete(planGate);
            File.WriteAllText(runGate, "");
            if (starting is not null)
            {
                await Record.ExceptionAsync(() => starting);
                using var cleanup = new CancellationTokenSource(TimeSpan.FromSeconds(22));
                while (service.GetCapabilities().ActiveRunId is not null)
                    await Task.Delay(100, cleanup.Token);
                foreach (var run in service.GetRuns())
                    await WaitForLauncherExitAsync(run, cleanup.Token);
            }
            root.Delete(recursive: true);
        }
    }

    private static async Task WaitForLauncherExitAsync(ToolJobRun run, CancellationToken token)
    {
        var launcherPath = Path.Combine(Path.GetDirectoryName(run.LogPath)!, "launcher.json");
        if (!File.Exists(launcherPath)) return;
        using var identity = JsonDocument.Parse(File.ReadAllText(launcherPath));
        var record = identity.RootElement;
        try
        {
            using var process = Process.GetProcessById(record.GetProperty("pid").GetInt32());
            if (process.StartTime.ToUniversalTime().Ticks == record.GetProperty("startedAtTicks").GetInt64())
                await process.WaitForExitAsync(token);
        }
        catch (ArgumentException) { } // The fixture process already exited.
        catch (InvalidOperationException) { }
    }

    private static ToolJobService CreateService(DirectoryInfo root)
        => new(new WorkspacePaths(root.FullName), new ConfigurationBuilder()
            .AddInMemoryCollection(new Dictionary<string, string?>
            {
                ["SkillTreeEditor:DesktopSessionToken"] = "fixture-token"
            })
            .Build());

    private static void WriteBootstrap(DirectoryInfo root)
    {
        var tools = Directory.CreateDirectory(Path.Combine(root.FullName, "60_tool"));
        var adapter = Directory.CreateDirectory(Path.Combine(tools.FullName, "05-master-data-editor"));
        File.WriteAllText(Path.Combine(adapter.FullName, "Invoke-EditorToolJob.ps1"), "");
        File.WriteAllText(Path.Combine(tools.FullName, "invoke-powershell7.ps1"), """
            param($Adapter, $Mode, $Job, $Workspace)
            $ErrorActionPreference = 'Stop'
            $utf8 = [Text.UTF8Encoding]::new($false)
            function Save-Json($path, $value) {
                [IO.File]::WriteAllText($path, ($value | ConvertTo-Json -Depth 20 -Compress), $utf8)
            }
            if ($Mode -eq 'Plan') {
                $request = Get-Content -Raw -LiteralPath (Join-Path $Job 'request.json') | ConvertFrom-Json
                $preparing = Join-Path $Workspace ('pause-plan-' + $request.options.toolId)
                for ($i = 0; $i -lt 200 -and (Test-Path -LiteralPath $preparing); $i++) {
                    Start-Sleep -Milliseconds 100
                }
                $configPath = Join-Path $Workspace 'fixture.json'
                $settings = Get-Content -Raw -LiteralPath $configPath | ConvertFrom-Json
                $warnings = @()
                if ($settings.blocked) { $warnings = @('Fixture deployment is blocked.') }
                Save-Json (Join-Path $Job 'plan.json') @{ canRun=!$settings.blocked; workspaceMismatch=$true;
                    warnings=$warnings; summary=@('Fixture plan'); paths=@(); confirmations=@(@{id='build';label='Build'});
                    fingerprint=(Get-FileHash -LiteralPath $configPath).Hash }
                exit 0
            }
            for ($i = 0; $i -lt 200 -and !(Test-Path -LiteralPath (Join-Path $Workspace 'finish')); $i++) {
                Start-Sleep -Milliseconds 100
            }
            $statePath = Join-Path $Job 'state.json'
            $state = Get-Content -Raw -LiteralPath $statePath | ConvertFrom-Json
            $request = Get-Content -Raw -LiteralPath (Join-Path $Job 'request.json') | ConvertFrom-Json
            $failed = $request.options.pluginTestMode -eq 'Selected'
            $state.status = if ($failed) { 'failed' } else { 'succeeded' }
            $state.exitCode = if ($failed) { 7 } else { 0 }
            $message = if ($failed) { 'Synthetic deployment failure.' } else { 'Completed.' }
            $log = @{ sequence=1; stream=if ($failed) { 'error' } else { 'output' }; text=$message }
            [IO.File]::WriteAllText((Join-Path $Job 'log.jsonl'), (($log | ConvertTo-Json -Compress) + "`n"), $utf8)
            $state.finishedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
            Save-Json "$statePath.swap" $state
            Move-Item -LiteralPath "$statePath.swap" -Destination $statePath -Force
            exit $state.exitCode
            """);
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
