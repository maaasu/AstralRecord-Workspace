using System.Diagnostics;
using AstralRecordControl.Contracts;
using AstralRecordControl.Runner;
using AstralRecordControl.TestProcess;
using Microsoft.Data.Sqlite;
using Xunit;

namespace AstralRecordControl.Runner.Tests;

public sealed class ProcessTests
{
    [Fact]
    public async Task ChildRetainsWindowsNetworkingAndPrivateWritableTempWithoutParentEnvironment()
    {
        var previous = Environment.GetEnvironmentVariable("ASTRAL_CONTROL_PARENT_ONLY_FIXTURE");
        Environment.SetEnvironmentVariable("ASTRAL_CONTROL_PARENT_ONLY_FIXTURE", "fixture-only");
        try
        {
            using var fixture = new Fixture();
            using var runner = fixture.Open();
            runner.ApplyInventory(fixture.Envelope());
            var hold = fixture.Hold(runner);
            var start = fixture.Request(runner, hold, OperationKind.Start);
            Assert.Equal(OperationStatus.Succeeded, (await WaitTerminal(runner, runner.Execute(start).OperationId)).Status);
            fixture.Track(runner.InspectRecovery(new(fixture.Server.ServerId)));
            var session = runner.GetSnapshot().Servers.Single().CurrentSessionId;
            var probe = fixture.Request(runner, hold, OperationKind.ConsoleCommand, new { command = "environment-probe" });
            Assert.Equal(OperationStatus.Succeeded, (await WaitTerminal(runner, runner.Execute(probe).OperationId)).Status);
            ReadLogsRequest logs = new() { ServerId = fixture.Server.ServerId, SessionId = session, Limit = 200 };
            await WaitUntil(() => runner.ReadLogs(logs).Rows.Any(row => row.Text == "environment-io-ready"));
            var lines = runner.ReadLogs(logs).Rows.Select(row => row.Text).ToArray();
            Assert.Contains("environment-system-root:" + Environment.GetFolderPath(Environment.SpecialFolder.Windows), lines);
            Assert.Contains("environment-temp:" + Path.TrimEndingDirectorySeparator(fixture.TemporaryDirectory) + Path.DirectorySeparatorChar, lines);
            Assert.Contains("environment-parent-only:absent", lines);
            var stop = fixture.Request(runner, hold, OperationKind.Stop);
            Assert.Equal(OperationStatus.Succeeded, (await WaitTerminal(runner, runner.Execute(stop).OperationId)).Status);
            runner.ReleaseHold(new(hold.ReservationId, hold.OwnerWorkflowId, hold.Fence));
        }
        finally { Environment.SetEnvironmentVariable("ASTRAL_CONTROL_PARENT_ONLY_FIXTURE", previous); }
    }

    [Fact]
    public async Task InvalidTemporaryDirectoryRefusesLaunchAndLeavesNoOwnedProcess()
    {
        using var fixture = new Fixture();
        using var runner = fixture.Open();
        runner.ApplyInventory(fixture.Envelope());
        Directory.CreateDirectory(Path.GetDirectoryName(fixture.TemporaryDirectory)!);
        File.WriteAllText(fixture.TemporaryDirectory, "not a directory");
        var hold = fixture.Hold(runner);
        var start = fixture.Request(runner, hold, OperationKind.Start);
        Assert.Equal(OperationStatus.NeedsAttention, (await WaitTerminal(runner, runner.Execute(start).OperationId)).Status);
        var state = Assert.Single(runner.GetSnapshot().Servers);
        Assert.Equal(ProcessState.Stopped, state.ProcessState);
        Assert.Null(state.CurrentSessionId);
        Assert.Empty(runner.ReadMetrics().Processes);
        Assert.Equal(ErrorCode.RecoveryRequired, Assert.Throws<RunnerFault>(() => runner.ReleaseHold(new(hold.ReservationId, hold.OwnerWorkflowId, hold.Fence))).Code);
    }

    [Fact]
    public async Task OwnedFixtureProcessReportsMetricsAndExpectedExit()
    {
        using var fixture = new Fixture();
        using var runner = fixture.Open();
        runner.ApplyInventory(fixture.Envelope());
        var hold = fixture.Hold(runner);
        var start = fixture.Request(runner, hold, OperationKind.Start);
        Assert.Equal(OperationStatus.Succeeded, (await WaitTerminal(runner, runner.Execute(start).OperationId)).Status);
        fixture.Track(runner.InspectRecovery(new(fixture.Server.ServerId)));
        var process = Assert.Single(runner.ReadMetrics().Processes);
        Assert.Equal(fixture.Server.ServerId, process.ServerId);
        Assert.NotNull(process.TotalProcessorTicks);
        Assert.True(process.WorkingSetBytes > 0);
        Assert.True(process.UptimeSeconds >= 0);
        var stop = fixture.Request(runner, hold, OperationKind.Stop);
        Assert.Equal(OperationStatus.Succeeded, (await WaitTerminal(runner, runner.Execute(stop).OperationId)).Status);
        await WaitUntil(() => runner.GetSnapshot().Servers.Single().LastExitedAtUtc is not null);
        Assert.True(Assert.Single(runner.GetSnapshot().Servers).LastExitWasExpected);
        Assert.Empty(runner.ReadMetrics().Processes);
        runner.ReleaseHold(new(hold.ReservationId, hold.OwnerWorkflowId, hold.Fence));
    }

    [Fact]
    public async Task StartConsoleLogsStopAndIdempotentReplayUseOneSession()
    {
        using var fixture = new Fixture();
        using var runner = fixture.Open();
        runner.ApplyInventory(fixture.Envelope());
        var hold = fixture.Hold(runner);
        var start = fixture.Request(runner, hold, OperationKind.Start);
        var accepted = runner.Execute(start);
        Assert.Equal(OperationStatus.Accepted, accepted.Status);
        var result = await WaitTerminal(runner, start.OperationId);
        Assert.Equal(OperationStatus.Succeeded, result.Status);
        Assert.Equal(result.OperationId, ControlJson.Deserialize<ExecutionSnapshot>(ControlJson.Serialize(result)).OperationId);
        fixture.Track(runner.InspectRecovery(new(fixture.Server.ServerId)));
        Assert.Equal(result.ServerSessionId, runner.Execute(start).ServerSessionId);
        Assert.Equal(OperationStatus.Succeeded, runner.Execute(start).Status);
        var changedJson = start.CommandJson.Replace("\"executionTimeoutSeconds\":10", "\"executionTimeoutSeconds\":11", StringComparison.Ordinal);
        Assert.Equal(ErrorCode.OperationConflict, Assert.Throws<RunnerFault>(() => runner.Execute(start with { CommandJson = changedJson, CommandSha256 = ControlProtocol.Hash(changedJson) })).Code);
        var session = Assert.IsType<Guid>(result.ServerSessionId);
        var console = fixture.Request(runner, hold, OperationKind.ConsoleCommand, new { command = "long-line" });
        Assert.Equal(OperationStatus.Succeeded, (await WaitTerminal(runner, runner.Execute(console).OperationId)).Status);
        await WaitUntil(() => runner.ReadLogs(new ReadLogsRequest { ServerId = fixture.Server.ServerId, SessionId = session, Limit = 200 }).Rows.Any(row => row.Truncated));
        var logs = runner.ReadLogs(new ReadLogsRequest { ServerId = fixture.Server.ServerId, SessionId = session, Limit = 200 });
        Assert.Contains(logs.Rows, row => row.Text == "fixture-ready");
        Assert.Contains(logs.Rows, row => row.Stream == "stderr");
        Assert.Contains(logs.Rows, row => row.Truncated);
        Assert.All(logs.Rows, row => Assert.True(System.Text.Encoding.UTF8.GetByteCount(row.Text) <= 64 * 1024));
        var stop = fixture.Request(runner, hold, OperationKind.Stop);
        Assert.Equal(OperationStatus.Succeeded, (await WaitTerminal(runner, runner.Execute(stop).OperationId)).Status);
        Assert.Equal(ProcessState.Stopped, Assert.Single(runner.GetSnapshot().Servers).ProcessState);
        runner.ReleaseHold(new(hold.ReservationId, hold.OwnerWorkflowId, hold.Fence));
    }

    [Fact]
    public async Task Utf8ChildOutputAndConsoleInputRoundTripWithoutLosingAnsiSequences()
    {
        using var fixture = new Fixture();
        using var runner = fixture.Open();
        runner.ApplyInventory(fixture.Envelope());
        var hold = fixture.Hold(runner);
        var start = fixture.Request(runner, hold, OperationKind.Start);
        var session = Assert.IsType<Guid>((await WaitTerminal(runner, runner.Execute(start).OperationId)).ServerSessionId);
        fixture.Track(runner.InspectRecovery(new(fixture.Server.ServerId)));

        const string commandText = "say 日本語😀";
        var command = fixture.Request(runner, hold, OperationKind.ConsoleCommand, new { command = commandText });
        Assert.Equal(OperationStatus.Succeeded, (await WaitTerminal(runner, runner.Execute(command).OperationId)).Status);

        var request = new ReadLogsRequest { ServerId = fixture.Server.ServerId, SessionId = session, Limit = 200 };
        await WaitUntil(() =>
        {
            var rows = runner.ReadLogs(request).Rows;
            return rows.Any(row => row.Stream == "stdout" && row.Text == "fixture-utf8-stdout:日本語😀\u001b[90m灰色\u001b[0m")
                && rows.Any(row => row.Stream == "stderr" && row.Text == "fixture-utf8-stderr:警告⚠️\u001b[31m赤色\u001b[0m")
                && rows.Any(row => row.Stream == "stdout" && row.Text == "echo:" + commandText);
        });

        var stop = fixture.Request(runner, hold, OperationKind.Stop);
        Assert.Equal(OperationStatus.Succeeded, (await WaitTerminal(runner, runner.Execute(stop).OperationId)).Status);
        runner.ReleaseHold(new(hold.ReservationId, hold.OwnerWorkflowId, hold.Fence));
    }

    [Fact]
    public async Task StopTimeoutPreservesHoldAndNeverEscalatesToKill()
    {
        using var fixture = new Fixture(ignoreStop: true);
        using var runner = fixture.Open();
        runner.ApplyInventory(fixture.Envelope());
        var hold = fixture.Hold(runner);
        var start = fixture.Request(runner, hold, OperationKind.Start);
        Assert.Equal(OperationStatus.Succeeded, (await WaitTerminal(runner, runner.Execute(start).OperationId)).Status);
        fixture.Track(runner.InspectRecovery(new(fixture.Server.ServerId)));
        var stop = fixture.Request(runner, hold, OperationKind.Stop, timeout: 1);
        Assert.Equal(OperationStatus.NeedsAttention, (await WaitTerminal(runner, runner.Execute(stop).OperationId)).Status);
        Assert.Equal(ErrorCode.RecoveryRequired, Assert.Throws<RunnerFault>(() => runner.ReleaseHold(new(hold.ReservationId, hold.OwnerWorkflowId, hold.Fence))).Code);
        var inspection = runner.InspectRecovery(new(fixture.Server.ServerId));
        Assert.True(inspection.ProcessAlive);
        Assert.True(inspection.IdentityMatches);
        Assert.Equal(ProcessState.Stopping, inspection.Runtime.ProcessState);
        Assert.Equal(ErrorCode.AgentDisabled, Assert.Throws<RunnerFault>(() => runner.Recover(new(fixture.Server.ServerId, inspection.Runtime.CurrentSessionId, WorkflowRecoveryAction.KillAfterInspection, "test cleanup"), false)).Code);
        var kill = fixture.Request(runner, hold, OperationKind.Kill, new { reason = "stop timeout inspection" });
        Assert.Equal(OperationStatus.Succeeded, (await WaitTerminal(runner, runner.Execute(kill).OperationId)).Status);
        Assert.Equal(OperationStatus.Failed, runner.GetExecution(stop.OperationId).Status);
        runner.ReleaseHold(new(hold.ReservationId, hold.OwnerWorkflowId, hold.Fence));
    }

    [Theory]
    [InlineData(OperationKind.Stop)]
    [InlineData(OperationKind.Restart)]
    public async Task TimedOutPowerOperationCanBeRecoveredByLocalAdministrator(OperationKind kind)
    {
        using var fixture = new Fixture(ignoreStop: true);
        using var runner = fixture.Open();
        runner.ApplyInventory(fixture.Envelope());
        var hold = fixture.Hold(runner);
        await WaitTerminal(runner, runner.Execute(fixture.Request(runner, hold, OperationKind.Start)).OperationId);
        fixture.Track(runner.InspectRecovery(new(fixture.Server.ServerId)));
        var pending = runner.Execute(fixture.Request(runner, hold, kind, timeout: 1));
        Assert.Equal(OperationStatus.NeedsAttention, (await WaitTerminal(runner, pending.OperationId)).Status);
        var inspection = runner.InspectRecovery(new(fixture.Server.ServerId));
        Assert.True(inspection.RecoveryRequired);
        var recovered = runner.Recover(new(fixture.Server.ServerId, inspection.Runtime.CurrentSessionId, WorkflowRecoveryAction.KillAfterInspection, "fixture inspected"), true);
        Assert.Equal(ProcessState.Stopped, recovered.Runtime.ProcessState);
        Assert.False(recovered.RecoveryRequired);
        Assert.Equal(OperationStatus.Failed, runner.GetExecution(pending.OperationId).Status);
        runner.ReleaseHold(new(hold.ReservationId, hold.OwnerWorkflowId, hold.Fence));
    }

    [Fact]
    public async Task StopDeadlineIncludesBlockedStdinWrite()
    {
        using var fixture = new Fixture(noRead: true);
        using var runner = fixture.Open();
        runner.ApplyInventory(fixture.Envelope());
        var hold = fixture.Hold(runner);
        await WaitTerminal(runner, runner.Execute(fixture.Request(runner, hold, OperationKind.Start)).OperationId);
        fixture.Track(runner.InspectRecovery(new(fixture.Server.ServerId)));
        var watch = Stopwatch.StartNew();
        var stop = await WaitTerminal(runner, runner.Execute(fixture.Request(runner, hold, OperationKind.Stop, timeout: 1)).OperationId);
        Assert.Equal(OperationStatus.NeedsAttention, stop.Status);
        Assert.True(watch.Elapsed < TimeSpan.FromSeconds(4));
        var inspected = runner.InspectRecovery(new(fixture.Server.ServerId));
        runner.Recover(new(fixture.Server.ServerId, inspected.Runtime.CurrentSessionId, WorkflowRecoveryAction.KillAfterInspection, "fixture stdin blocked"), true);
        runner.ReleaseHold(new(hold.ReservationId, hold.OwnerWorkflowId, hold.Fence));
    }

    [Fact]
    public async Task RunnerRestartLeavesLiveChildUnmanagedAndCannotReplayStdin()
    {
        using var fixture = new Fixture();
        Guid session;
        using (var runner = fixture.Open())
        {
            runner.ApplyInventory(fixture.Envelope());
            var hold = fixture.Hold(runner);
            var start = fixture.Request(runner, hold, OperationKind.Start);
            session = Assert.IsType<Guid>((await WaitTerminal(runner, runner.Execute(start).OperationId)).ServerSessionId);
            fixture.Track(runner.InspectRecovery(new(fixture.Server.ServerId)));
        }
        using var restarted = fixture.Open();
        var inspection = restarted.InspectRecovery(new(fixture.Server.ServerId));
        Assert.Equal(ProcessState.Unmanaged, inspection.Runtime.ProcessState);
        Assert.Equal(session, inspection.Runtime.CurrentSessionId);
        Assert.True(inspection.ProcessAlive);
        Assert.Equal(ErrorCode.RecoveryRequired, Assert.Throws<RunnerFault>(() => restarted.Execute(fixture.Request(restarted, Assert.Single(restarted.GetSnapshot().Holds), OperationKind.ConsoleCommand, new { command = "hello" }))).Code);
        fixture.KillFixtureProcess(inspection);
    }

    [Fact]
    public async Task RestartCreatesNewSessionAndCrashRestartUsesLocalHold()
    {
        using var fixture = new Fixture(restartOnCrash: true);
        using var runner = fixture.Open();
        runner.ApplyInventory(fixture.Envelope());
        var hold = fixture.Hold(runner);
        var start = fixture.Request(runner, hold, OperationKind.Start);
        var first = Assert.IsType<Guid>((await WaitTerminal(runner, runner.Execute(start).OperationId)).ServerSessionId);
        fixture.Track(runner.InspectRecovery(new(fixture.Server.ServerId)));
        var restart = fixture.Request(runner, hold, OperationKind.Restart);
        var secondResult = await WaitTerminal(runner, runner.Execute(restart).OperationId);
        Assert.Equal(OperationStatus.Succeeded, secondResult.Status);
        var second = Assert.IsType<Guid>(secondResult.ServerSessionId);
        Assert.NotEqual(first, second);
        fixture.Track(runner.InspectRecovery(new(fixture.Server.ServerId)));
        runner.ReleaseHold(new(hold.ReservationId, hold.OwnerWorkflowId, hold.Fence));
        var crashHold = fixture.Hold(runner);
        var crash = fixture.Request(runner, crashHold, OperationKind.ConsoleCommand, new { command = "crash" });
        await WaitTerminal(runner, runner.Execute(crash).OperationId);
        runner.ReleaseHold(new(crashHold.ReservationId, crashHold.OwnerWorkflowId, crashHold.Fence));
        await WaitUntil(() => Assert.Single(runner.GetSnapshot().Servers).ProcessState == ProcessState.Stopped);
        await Task.Delay(1100);
        await runner.RunAutomaticStartsAsync();
        await WaitUntil(() => Assert.Single(runner.GetSnapshot().Servers).ProcessState == ProcessState.Running);
        var third = Assert.IsType<Guid>(Assert.Single(runner.GetSnapshot().Servers).CurrentSessionId);
        Assert.NotEqual(second, third);
        fixture.Track(runner.InspectRecovery(new(fixture.Server.ServerId)));
        await WaitUntil(() => runner.GetSnapshot().Holds.Length == 0);
        var finalHold = fixture.Hold(runner);
        var stop = fixture.Request(runner, finalHold, OperationKind.Stop);
        Assert.Equal(OperationStatus.Succeeded, (await WaitTerminal(runner, runner.Execute(stop).OperationId)).Status);
        runner.ReleaseHold(new(finalHold.ReservationId, finalHold.OwnerWorkflowId, finalHold.Fence));
    }

    [Fact]
    public async Task AutoStartIsSuppressedByMaintenanceHold()
    {
        using var fixture = new Fixture(autoStart: true);
        using var runner = fixture.Open();
        runner.ApplyInventory(fixture.Envelope());
        var maintenance = fixture.Hold(runner);
        await runner.RunAutomaticStartsAsync();
        Assert.Equal(ProcessState.Stopped, Assert.Single(runner.GetSnapshot().Servers).ProcessState);
        runner.ReleaseHold(new(maintenance.ReservationId, maintenance.OwnerWorkflowId, maintenance.Fence));
        await runner.RunAutomaticStartsAsync();
        await WaitUntil(() => Assert.Single(runner.GetSnapshot().Servers).ProcessState == ProcessState.Running);
        fixture.Track(runner.InspectRecovery(new(fixture.Server.ServerId)));
        await WaitUntil(() => runner.GetSnapshot().Holds.Length == 0);
        var stopHold = fixture.Hold(runner);
        var stop = fixture.Request(runner, stopHold, OperationKind.Stop);
        Assert.Equal(OperationStatus.Succeeded, (await WaitTerminal(runner, runner.Execute(stop).OperationId)).Status);
        runner.ReleaseHold(new(stopHold.ReservationId, stopHold.OwnerWorkflowId, stopHold.Fence));
        await runner.RunAutomaticStartsAsync();
        Assert.Equal(ProcessState.Stopped, Assert.Single(runner.GetSnapshot().Servers).ProcessState);
    }

    [Fact]
    public async Task FailedSentJournalNeverReplaysConsoleInput()
    {
        using var fixture = new Fixture();
        using var runner = fixture.Open();
        runner.ApplyInventory(fixture.Envelope());
        var hold = fixture.Hold(runner);
        var start = fixture.Request(runner, hold, OperationKind.Start);
        var session = Assert.IsType<Guid>((await WaitTerminal(runner, runner.Execute(start).OperationId)).ServerSessionId);
        fixture.Track(runner.InspectRecovery(new(fixture.Server.ServerId)));
        using (var database = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = fixture.DatabasePath, Pooling = false }.ToString()))
        {
            database.Open();
            using var trigger = database.CreateCommand();
            trigger.CommandText = "CREATE TRIGGER FailSent BEFORE UPDATE OF phase ON ExecutionLedger WHEN NEW.phase='Sent' BEGIN SELECT RAISE(ABORT,'injected'); END;";
            trigger.ExecuteNonQuery();
        }
        var console = fixture.Request(runner, hold, OperationKind.ConsoleCommand, new { command = "once-only" });
        var first = await WaitTerminal(runner, runner.Execute(console).OperationId);
        Assert.Equal(OperationStatus.NeedsAttention, first.Status);
        Assert.Equal(OperationStatus.NeedsAttention, runner.Execute(console).Status);
        await WaitUntil(() => runner.ReadLogs(new ReadLogsRequest { ServerId = fixture.Server.ServerId, SessionId = session }).Rows.Any(r => r.Text == "echo:once-only"));
        Assert.Single(runner.ReadLogs(new ReadLogsRequest { ServerId = fixture.Server.ServerId, SessionId = session }).Rows, r => r.Text == "echo:once-only");
        Assert.Equal(ErrorCode.RecoveryRequired, Assert.Throws<RunnerFault>(() => runner.ReleaseHold(new(hold.ReservationId, hold.OwnerWorkflowId, hold.Fence))).Code);
        var inspection = runner.InspectRecovery(new(fixture.Server.ServerId));
        Assert.True(inspection.RecoveryRequired);
        var acknowledged = runner.Recover(new(fixture.Server.ServerId, session, WorkflowRecoveryAction.AbortAfterInspection, "checked console result; do not resend"), true);
        Assert.Equal(ProcessState.Running, acknowledged.Runtime.ProcessState);
        Assert.False(acknowledged.RecoveryRequired);
        Assert.Equal(OperationStatus.Failed, runner.GetExecution(console.OperationId).Status);
        runner.ReleaseHold(new(hold.ReservationId, hold.OwnerWorkflowId, hold.Fence));
        fixture.KillFixtureProcess(acknowledged);
    }

    [Fact]
    public async Task ReusedPidNeverAuthorizesRecoveryKill()
    {
        using var fixture = new Fixture();
        using var runner = fixture.Open();
        runner.ApplyInventory(fixture.Envelope());
        var hold = fixture.Hold(runner);
        var start = fixture.Request(runner, hold, OperationKind.Start);
        await WaitTerminal(runner, runner.Execute(start).OperationId);
        var original = runner.InspectRecovery(new(fixture.Server.ServerId));
        fixture.Track(original);
        runner.Dispose();
        fixture.KillFixtureProcess(original);
        using (var database = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = fixture.DatabasePath, Pooling = false }.ToString()))
        {
            database.Open();
            using var tamper = database.CreateCommand();
            tamper.CommandText = "UPDATE ServerSessions SET pid=$pid WHERE sessionId=$session;";
            tamper.Parameters.AddWithValue("$pid", Environment.ProcessId);
            tamper.Parameters.AddWithValue("$session", original.Session!.ServerSessionId.ToString());
            tamper.ExecuteNonQuery();
        }
        using var restarted = fixture.Open();
        var inspected = restarted.InspectRecovery(new(fixture.Server.ServerId));
        Assert.True(inspected.ProcessAlive);
        Assert.False(inspected.IdentityMatches);
        Assert.Equal(ErrorCode.RecoveryRequired, Assert.Throws<RunnerFault>(() => restarted.Recover(new(fixture.Server.ServerId, inspected.Runtime.CurrentSessionId, WorkflowRecoveryAction.KillAfterInspection, "fixture PID reuse"), true)).Code);
        var recovered = restarted.Recover(new(fixture.Server.ServerId, inspected.Runtime.CurrentSessionId, WorkflowRecoveryAction.AbortAfterInspection, "old process exited; PID now belongs to test host"), true);
        Assert.Equal(ProcessState.Stopped, recovered.Runtime.ProcessState);
        Assert.False(recovered.RecoveryRequired);
        restarted.ReleaseHold(new(hold.ReservationId, hold.OwnerWorkflowId, hold.Fence));
    }

    [Fact]
    public async Task TwoServersRunIndependentlyOnOneRunner()
    {
        using var fixture = new Fixture(twoServers: true);
        using var runner = fixture.Open();
        runner.ApplyInventory(fixture.Envelope());
        var second = Assert.IsType<ServerConfig>(fixture.SecondServer);
        var firstHold = fixture.Hold(runner);
        var secondHold = fixture.Hold(runner, second);
        var first = fixture.Request(runner, firstHold, OperationKind.Start);
        var other = fixture.RequestFor(runner, second, secondHold, OperationKind.Start);
        Assert.Equal(OperationStatus.Succeeded, (await WaitTerminal(runner, runner.Execute(first).OperationId)).Status);
        Assert.Equal(OperationStatus.Succeeded, (await WaitTerminal(runner, runner.Execute(other).OperationId)).Status);
        fixture.Track(runner.InspectRecovery(new(fixture.Server.ServerId)));
        fixture.Track(runner.InspectRecovery(new(second.ServerId)));
        Assert.All(runner.GetSnapshot().Servers, server => Assert.Equal(ProcessState.Running, server.ProcessState));
        var stopFirst = fixture.Request(runner, firstHold, OperationKind.Stop);
        Assert.Equal(OperationStatus.Succeeded, (await WaitTerminal(runner, runner.Execute(stopFirst).OperationId)).Status);
        Assert.Equal(ProcessState.Running, runner.GetSnapshot().Servers.Single(server => server.ServerId == second.ServerId).ProcessState);
        var stopSecond = fixture.RequestFor(runner, second, secondHold, OperationKind.Stop);
        Assert.Equal(OperationStatus.Succeeded, (await WaitTerminal(runner, runner.Execute(stopSecond).OperationId)).Status);
        runner.ReleaseHold(new(firstHold.ReservationId, firstHold.OwnerWorkflowId, firstHold.Fence));
        runner.ReleaseHold(new(secondHold.ReservationId, secondHold.OwnerWorkflowId, secondHold.Fence));
    }

    [Fact]
    public async Task LogRetentionKeepsActiveSegmentThenReportsGapAfterClosedLogsExpire()
    {
        using var fixture = new Fixture();
        var clock = new FixtureClock();
        using var runner = fixture.Open(clock);
        runner.ApplyInventory(fixture.Envelope());
        var hold = fixture.Hold(runner);
        var session = (await WaitTerminal(runner, runner.Execute(fixture.Request(runner, hold, OperationKind.Start)).OperationId)).ServerSessionId;
        fixture.Track(runner.InspectRecovery(new(fixture.Server.ServerId)));
        var query = new ReadLogsRequest { ServerId = fixture.Server.ServerId, SessionId = session };
        await WaitUntil(() => runner.ReadLogs(query).Rows.Length >= 2);
        var saved = runner.ReadLogs(query);
        clock.Now = clock.Now.AddDays(31);
        runner.PruneLogs(force: true);
        Assert.NotEmpty(runner.ReadLogs(query).Rows);
        clock.Now = DateTimeOffset.UtcNow;
        await WaitTerminal(runner, runner.Execute(fixture.Request(runner, hold, OperationKind.Stop)).OperationId);
        runner.ReleaseHold(new(hold.ReservationId, hold.OwnerWorkflowId, hold.Fence));
        var emptyJson = ControlJson.Serialize(new InventorySnapshot { HostId = fixture.Configuration.HostId, Revision = 2 });
        runner.ApplyInventory(new(1, fixture.Configuration.HostId, 2, emptyJson, ControlProtocol.Hash(emptyJson)));
        clock.Now = clock.Now.AddDays(31);
        runner.PruneLogs(force: true);
        var expired = runner.ReadLogs(query);
        Assert.Empty(expired.Rows);
        Assert.True(expired.Gap);
        Assert.Equal(saved.NextCursor, expired.NextCursor);
        Assert.Equal(saved.NextCursor + 1, expired.EarliestAvailableCursor);
    }

    [Fact]
    public async Task MissingLogFileAfterInterruptedPruneReturnsGapInsteadOfThrowing()
    {
        using var fixture = new Fixture();
        using var runner = fixture.Open();
        runner.ApplyInventory(fixture.Envelope());
        var hold = fixture.Hold(runner);
        var session = (await WaitTerminal(runner, runner.Execute(fixture.Request(runner, hold, OperationKind.Start)).OperationId)).ServerSessionId;
        fixture.Track(runner.InspectRecovery(new(fixture.Server.ServerId)));
        var query = new ReadLogsRequest { ServerId = fixture.Server.ServerId, SessionId = session };
        await WaitUntil(() => runner.ReadLogs(query).Rows.Length >= 2);
        await WaitTerminal(runner, runner.Execute(fixture.Request(runner, hold, OperationKind.Stop)).OperationId);
        var previous = runner.ReadLogs(query);
        using (var db = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = fixture.DatabasePath, Pooling = false }.ToString()))
        {
            db.Open(); using var command = db.CreateCommand(); command.CommandText = "SELECT path FROM LogSegments;";
            using var rows = command.ExecuteReader(); while (rows.Read()) File.Delete(rows.GetString(0));
        }
        var current = runner.ReadLogs(query);
        Assert.True(current.Gap);
        Assert.Empty(current.Rows);
        Assert.Equal(previous.NextCursor, current.NextCursor);
        runner.ReleaseHold(new(hold.ReservationId, hold.OwnerWorkflowId, hold.Fence));
    }

    [Fact]
    public void UnsupportedDependencyChainCannotBeSilentlyStarted()
    {
        using var fixture = new Fixture(twoServers: true);
        using var runner = fixture.Open();
        var second = fixture.SecondServer!;
        var json = ControlJson.Serialize(new InventorySnapshot { HostId = fixture.Configuration.HostId, Revision = 1, Servers = [fixture.Server with { DependsOnServerIds = [second.ServerId] }, second] });
        runner.ApplyInventory(new(1, fixture.Configuration.HostId, 1, json, ControlProtocol.Hash(json)));
        var hold = fixture.Hold(runner);
        Assert.Equal(ErrorCode.UnsupportedCapability, Assert.Throws<RunnerFault>(() => runner.Execute(fixture.Request(runner, hold, OperationKind.Start))).Code);
        Assert.All(runner.GetSnapshot().Servers, state => Assert.Equal(ProcessState.Stopped, state.ProcessState));
        runner.ReleaseHold(new(hold.ReservationId, hold.OwnerWorkflowId, hold.Fence));
    }

    private sealed class FixtureClock : TimeProvider
    {
        public DateTimeOffset Now { get; set; } = DateTimeOffset.UtcNow;
        public override DateTimeOffset GetUtcNow() => Now;
    }

    private static async Task<ExecutionSnapshot> WaitTerminal(RunnerStore runner, Guid operationId)
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(15));
        while (!timeout.IsCancellationRequested)
        {
            var execution = runner.GetExecution(operationId);
            if (execution.IsTerminal || execution.Status == OperationStatus.NeedsAttention) return execution;
            await Task.Delay(50);
        }
        throw new TimeoutException("Fixture operation did not finish.");
    }
    private static async Task WaitUntil(Func<bool> predicate)
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        while (!timeout.IsCancellationRequested)
        {
            if (predicate()) return;
            await Task.Delay(50);
        }
        throw new TimeoutException("Fixture output did not arrive.");
    }

    private sealed class Fixture : IDisposable
    {
        private readonly string root = Path.Combine(Path.GetTempPath(), "control-process-test-" + Guid.NewGuid().ToString("N"));
        private readonly Dictionary<int, DateTimeOffset> startedPids = [];
        public RunnerConfiguration Configuration { get; }
        public ServerConfig Server { get; }
        public ServerConfig? SecondServer { get; }
        public string DatabasePath => Path.Combine(root, "runner", "runner.db");
        public string TemporaryDirectory => Path.Combine(root, "runner", "process-temp", Server.ServerId.ToString("N"));
        public Fixture(bool ignoreStop = false, bool autoStart = false, bool restartOnCrash = false, bool twoServers = false, bool noRead = false)
        {
            var managed = Path.Combine(root, "managed");
            Directory.CreateDirectory(Path.Combine(managed, "server"));
            var executable = Path.GetFullPath(Path.Combine(System.Runtime.InteropServices.RuntimeEnvironment.GetRuntimeDirectory(), "../../..", "dotnet.exe"));
            Assert.True(File.Exists(executable));
            var host = Guid.NewGuid(); var managedId = Guid.NewGuid(); var executableId = Guid.NewGuid();
            Configuration = new RunnerConfiguration { HostId = host, EnableProcessControl = true, LocalResources = new HostResources { Roots = [new ManagedRoot { Id = managedId, Path = managed }], Executables = [new ManagedExecutable { Id = executableId, Path = executable, Kind = ServerKind.Java }] } };
            Server = new ServerConfig { ServerId = Guid.NewGuid(), HostId = host, Revision = 1, Kind = ServerKind.Java, ManagedRootId = managedId, ExecutableId = executableId, ServerDirectory = "server", ArgumentList = ignoreStop ? [typeof(Marker).Assembly.Location, "ignore-stop"] : [typeof(Marker).Assembly.Location], AutoStart = autoStart, RestartOnCrash = restartOnCrash, RestartDelaySeconds = 1 };
            if (noRead) Server = Server with { ArgumentList = [typeof(Marker).Assembly.Location, "no-read"], StopCommand = new string('x', 4096) };
            if (twoServers)
            {
                Directory.CreateDirectory(Path.Combine(managed, "server2"));
                SecondServer = Server with { ServerId = Guid.NewGuid(), ServerDirectory = "server2" };
            }
        }
        public RunnerStore Open(TimeProvider? clock = null) => new(Path.Combine(root, "runner"), Configuration, clock);
        public InventoryEnvelope Envelope()
        {
            var inventory = new InventorySnapshot { HostId = Configuration.HostId, Revision = 1, Servers = SecondServer is null ? [Server] : [Server, SecondServer] };
            var json = ControlJson.Serialize(inventory);
            return new(1, inventory.HostId, 1, json, ControlProtocol.Hash(json));
        }
        public ResourceHold Hold(RunnerStore runner, ServerConfig? server = null) => runner.AcquireHold(new(Guid.NewGuid(), Guid.NewGuid(), ["server:" + (server ?? Server).ServerId], HoldMode.Exclusive));
        public ExecuteRequest Request(RunnerStore runner, ResourceHold hold, OperationKind kind, object? payload = null, int timeout = 10)
            => RequestFor(runner, Server, hold, kind, payload, timeout);
        public ExecuteRequest RequestFor(RunnerStore runner, ServerConfig server, ResourceHold hold, OperationKind kind, object? payload = null, int timeout = 10)
        {
            var snapshot = runner.GetSnapshot().Servers.Single(row => row.ServerId == server.ServerId);
            var command = new OperationCommand { OperationId = Guid.NewGuid(), HostId = Configuration.HostId, ServerId = server.ServerId, WorkflowId = hold.OwnerWorkflowId, ReservationId = hold.ReservationId, ReservationFence = hold.Fence, Kind = kind, ConfigRevision = server.Revision, ExpectedStateRevision = snapshot.StateRevision, ExpectedServerSessionId = snapshot.CurrentSessionId, StartBeforeUtc = DateTimeOffset.UtcNow.AddMinutes(1), ExecutionTimeoutSeconds = timeout, Payload = ControlJson.Element(payload ?? new { }) };
            var json = ControlJson.Serialize(command);
            return new(command.OperationId, json, ControlProtocol.Hash(json));
        }
        public void KillFixtureProcess(RecoveryInspection inspection)
        {
            Track(inspection);
            if (!inspection.IdentityMatches || inspection.Session?.ProcessId is not int pid) return;
            using var process = Process.GetProcessById(pid);
            if (!process.HasExited) { process.Kill(); process.WaitForExit(5000); }
        }
        public void Track(RecoveryInspection inspection)
        {
            if (inspection.IdentityMatches && inspection.Session?.ProcessId is int pid && inspection.Session.ProcessCreatedAtUtc is DateTimeOffset created)
                startedPids[pid] = created;
        }
        public void Dispose()
        {
            // All processes killed here were spawned from this fixture and inspected by Runner.
            foreach (var (pid, created) in startedPids)
                try
                {
                    using var process = Process.GetProcessById(pid);
                    if (!process.HasExited && Math.Abs((process.StartTime.ToUniversalTime() - created.UtcDateTime).TotalMilliseconds) < 1)
                    { process.Kill(); process.WaitForExit(5000); }
                }
                catch (ArgumentException) { }
            for (var attempt = 0; attempt < 20 && Directory.Exists(root); attempt++)
            {
                try { Directory.Delete(root, true); }
                catch (IOException) when (attempt < 19) { Thread.Sleep(100); }
            }
        }
    }
}
