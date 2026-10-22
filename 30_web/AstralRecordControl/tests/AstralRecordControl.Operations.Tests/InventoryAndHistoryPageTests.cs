using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Pages;
using AstralRecordControl.Web.Pages.Shared;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
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
            var page = new ServerIndex(db, null!, null!, new FixedClock(now));
            await page.OnGetAsync();
            Assert.Equal(state, page.Status(server).State);
        }
        host.LastSeenAtUtc = now.AddSeconds(-31);
        await db.SaveChangesAsync();
        var stale = new ServerIndex(db, null!, null!, new FixedClock(now));
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
