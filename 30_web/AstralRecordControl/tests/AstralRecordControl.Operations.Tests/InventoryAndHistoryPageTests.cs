using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Pages;
using AstralRecordControl.Web.Pages.Shared;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Xunit;
using ServerIndex = AstralRecordControl.Web.Pages.Servers.IndexModel;

namespace AstralRecordControl.Operations.Tests;

public sealed class InventoryAndHistoryPageTests
{
    [Fact]
    public async Task ServerRegisterShowsReportedProcessStatesAndNeverTreatsStaleOrMissingReportsAsStopped()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        await using var db = new ControlDbContext(new DbContextOptionsBuilder<ControlDbContext>().UseSqlite(connection).Options);
        await db.Database.EnsureCreatedAsync();
        var now = DateTimeOffset.Parse("2026-10-22T03:00:00Z");
        var host = new InventoryHost { HostId = Guid.NewGuid(), HostKey = "fixture", DisplayName = "Fixture", Enabled = true, ConnectionState = HostConnectionState.Online, LastSeenAtUtc = now };
        db.Hosts.Add(host);
        var server = new InventoryServer { ServerId = Guid.NewGuid(), HostId = host.HostId, ServerKey = "fixture", DisplayName = "Fixture" };
        db.Servers.Add(server);
        foreach (var state in new[] { ProcessState.Running, ProcessState.Stopped, ProcessState.Starting, ProcessState.Stopping, ProcessState.Unmanaged })
        {
            host.RuntimeSnapshotJson = ControlJson.Serialize(new[] { new ServerRuntimeSnapshot { ServerId = server.ServerId, ProcessState = state } });
            await db.SaveChangesAsync();
            var page = new ServerIndex(db, null!, null!, new FixedClock(now), new ConfigurationBuilder().Build(), null!);
            await page.OnGetAsync();
            Assert.Equal(state, page.Status(server).State);
        }
        host.LastSeenAtUtc = now.AddSeconds(-31);
        await db.SaveChangesAsync();
        var stale = new ServerIndex(db, null!, null!, new FixedClock(now), new ConfigurationBuilder().Build(), null!);
        await stale.OnGetAsync();
        Assert.Null(stale.Status(server).State);
        Assert.Equal("確認不能", stale.Status(server).Label);
        host.LastSeenAtUtc = now;
        var readinessLabels = new HashSet<string>();
        foreach (var readiness in new[] { GameReadiness.Ready, GameReadiness.Preparing, GameReadiness.Failed, GameReadiness.Unknown })
        {
            var status = ControlDisplay.ServerState(host, new ServerRuntimeSnapshot { ProcessState = ProcessState.Running, Readiness = readiness }, now);
            Assert.NotEqual(string.Empty, status.Detail);
            readinessLabels.Add(status.Detail);
            if (readiness == GameReadiness.Failed) Assert.Equal("is-warning", status.CssClass);
        }
        Assert.Equal(4, readinessLabels.Count);
        host.LastSeenAtUtc = now;
        host.ConnectionState = HostConnectionState.Offline;
        await db.SaveChangesAsync();
        await stale.OnGetAsync();
        Assert.Null(stale.Status(server).State);
        host.ConnectionState = HostConnectionState.Online;
        host.RuntimeSnapshotJson = "[]";
        await db.SaveChangesAsync();
        await stale.OnGetAsync();
        Assert.Null(stale.Status(server).State);
        Assert.Equal("確認不能", stale.Status(server).Label);
    }

    [Fact]
    public async Task RegisterActionsFollowLatestRuntimeAndBlockPendingOperationsAndSharedMaintenance()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        await using var db = new ControlDbContext(new DbContextOptionsBuilder<ControlDbContext>().UseSqlite(connection).Options);
        await db.Database.EnsureCreatedAsync();
        var now = DateTimeOffset.Parse("2026-10-24T03:00:00Z");
        var host = new InventoryHost { HostId = Guid.NewGuid(), HostKey = "fixture", DisplayName = "Fixture", Enabled = true, ConnectionState = HostConnectionState.Online, LastSeenAtUtc = now,
            CapabilitiesJson = ControlJson.Serialize(new[] { "P1.Operations", "P1.LegacyGate" }) };
        var config = new ServerConfig { ServerId = Guid.NewGuid(), HostId = host.HostId, Revision = 1, ManagedRootId = Guid.NewGuid(), ExecutableId = Guid.NewGuid(), ServerDirectory = "servers/test" };
        var server = new InventoryServer { ServerId = config.ServerId, HostId = host.HostId, ServerKey = "fixture", DisplayName = "Fixture", EnvironmentId = Guid.NewGuid(), DesiredRevision = 1, AppliedRevision = 1, DesiredConfigJson = ControlJson.Serialize(config) };
        var runtime = new ServerRuntimeSnapshot { ServerId = server.ServerId, ConfigRevision = 1, StateRevision = 9, ProcessState = ProcessState.Stopped };
        db.Hosts.Add(host);
        db.Environments.Add(new InventoryEnvironment { EnvironmentId = server.EnvironmentId!.Value, EnvironmentKey = "fixture", DisplayName = "Fixture" });
        db.Servers.Add(server);
        var configuration = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?> { ["Control:EnableProcessControl"] = "true" }).Build();
        var page = new ServerIndex(db, null!, null!, new FixedClock(now), configuration, null!);
        async Task Reload()
        {
            host.RuntimeSnapshotJson = ControlJson.Serialize(new[] { runtime });
            await db.SaveChangesAsync();
            await page.OnGetAsync();
        }
        await Reload();
        Assert.True(page.Actions(server).CanStart); Assert.False(page.Actions(server).CanStop);
        runtime = runtime with { ProcessState = ProcessState.Running, CurrentSessionId = Guid.NewGuid() };
        await Reload();
        Assert.False(page.Actions(server).CanStart); Assert.True(page.Actions(server).CanStop);
        foreach (var state in new[] { ProcessState.Starting, ProcessState.Stopping, ProcessState.Unmanaged })
        {
            runtime = runtime with { ProcessState = state };
            await Reload();
            Assert.False(page.Actions(server).CanStart); Assert.False(page.Actions(server).CanStop);
        }
        runtime = runtime with { ProcessState = ProcessState.Running };
        host.LastSeenAtUtc = now.AddSeconds(-31);
        await Reload();
        Assert.False(page.Actions(server).CanStop); Assert.Null(page.Actions(server).Runtime);
        host.LastSeenAtUtc = now;
        server.DesiredRevision = 2;
        await Reload();
        Assert.False(page.Actions(server).CanStop);
        server.DesiredRevision = 1;
        var operation = new ControlOperation { OperationId = Guid.NewGuid(), IdempotencyKey = Guid.NewGuid(), HostId = host.HostId, ServerId = server.ServerId, WorkflowId = Guid.NewGuid(), ReservationId = Guid.NewGuid(), Status = OperationStatus.NeedsAttention };
        db.Workflows.Add(new ControlWorkflow { WorkflowId = operation.WorkflowId });
        db.Operations.Add(operation);
        await Reload();
        Assert.True(page.Actions(server).Pending); Assert.False(page.Actions(server).CanStop);
        db.Operations.Remove(operation);
        var reservation = new ControlReservation { ResourceKey = "environment:" + server.EnvironmentId, ReservationId = Guid.NewGuid(), OwnerWorkflowId = Guid.NewGuid(), State = ReservationStatus.Prepared };
        db.Reservations.Add(reservation);
        await Reload();
        Assert.True(page.Actions(server).Pending); Assert.False(page.Actions(server).CanStop);
        reservation.State = ReservationStatus.Released;
        await Reload();
        Assert.True(page.Actions(server).CanStop);
        foreach (var directory in new[] { "servers", "servers/test/logs", "servers/testing" })
        {
            var directoryReservation = new ControlReservation
            {
                ResourceKey = AstralRecordControl.Web.Operations.ReservationService.DirectoryKey(host.HostId, config.ManagedRootId, directory),
                ReservationId = Guid.NewGuid(), State = ReservationStatus.Prepared
            };
            db.Reservations.Add(directoryReservation);
            await Reload();
            Assert.Equal(directory == "servers/testing", page.Actions(server).CanStop);
            db.Reservations.Remove(directoryReservation);
            await Reload();
        }
        host.RecoveryRequired = true;
        await Reload();
        Assert.False(page.Actions(server).CanStop);
        // Reject tampered actions before reaching the operation service.
        Assert.IsType<PageResult>(await page.OnPostOperateAsync(server.ServerId, Guid.NewGuid(), OperationKind.Restart, 1, 9, runtime.CurrentSessionId));
        Assert.NotNull(page.Error);
        Assert.IsType<PageResult>(await page.OnPostOperateAsync(server.ServerId, Guid.NewGuid(), OperationKind.Stop, 1, 9, runtime.CurrentSessionId));
        Assert.Empty(await db.Operations.ToArrayAsync());
    }

    [Fact]
    public async Task HistoryOpensOlderDetailsPagesTheListAndRejectsProtectedSelectionsWithoutDeletingAnything()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        await using var db = new ControlDbContext(new DbContextOptionsBuilder<ControlDbContext>().UseSqlite(connection).Options);
        await db.Database.EnsureCreatedAsync();
        var now = DateTimeOffset.Parse("2026-10-22T03:00:00Z");
        var rows = Enumerable.Range(0, 55).Select(i => new ControlWorkflow { WorkflowId = Guid.NewGuid(), CreatedAtUtc = now.AddMinutes(-i), Status = WorkflowStatus.Succeeded }).ToArray();
        db.Workflows.AddRange(rows);
        db.ReservationLeases.AddRange(rows.Select(row => new ControlReservationLease { ReservationId = Guid.NewGuid(), WorkflowId = row.WorkflowId, Status = ReservationStatus.Released }));
        rows[1].Status = WorkflowStatus.RecoveryRequired;
        rows[2].HiddenAtUtc = now;
        await db.SaveChangesAsync();
        var page = new WorkflowsModel(db, null!, null!, null!);
        Assert.IsType<PageResult>(await page.OnGetAsync(rows[^1].WorkflowId));
        Assert.Equal(rows[^1].WorkflowId, page.Selected?.Workflow.WorkflowId);
        Assert.True(page.HasNextPage);
        Assert.Equal(50, page.Recent.Length);
        Assert.DoesNotContain(page.Recent, row => row.Workflow.WorkflowId == rows[2].WorkflowId);
        Assert.IsType<NotFoundResult>(await page.OnGetAsync(rows[2].WorkflowId));
        Assert.IsType<NotFoundResult>(await page.OnGetAsync(Guid.NewGuid()));
        await page.OnGetAsync(null, 2);
        Assert.Equal(4, page.Recent.Length);
        Assert.False(page.HasNextPage);
        await page.OnPostConfirmDeleteAsync([rows[0].WorkflowId, rows[1].WorkflowId]);
        Assert.NotNull(page.Error);
        Assert.Empty(page.DeletionCandidates);
        Assert.Equal(55, await db.Workflows.CountAsync());
        Assert.Null((await db.Workflows.SingleAsync(x => x.WorkflowId == rows[0].WorkflowId)).HiddenAtUtc);
        var confirmation = new WorkflowsModel(db, null!, null!, null!);
        await confirmation.OnPostConfirmDeleteAsync([rows[0].WorkflowId]);
        Assert.Null(confirmation.Error);
        Assert.Single(confirmation.DeletionCandidates);
        Assert.False(string.IsNullOrWhiteSpace(confirmation.Target(confirmation.DeletionCandidates[0])));
    }

    private sealed class FixedClock(DateTimeOffset now) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => now;
    }
}
