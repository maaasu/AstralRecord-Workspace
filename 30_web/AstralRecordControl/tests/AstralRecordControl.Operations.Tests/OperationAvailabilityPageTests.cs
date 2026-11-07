using System.Text.Json;
using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using AstralRecordControl.Web.Operations;
using AstralRecordControl.Web.Pages.Servers;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Xunit;

namespace AstralRecordControl.Operations.Tests;

public sealed class OperationAvailabilityPageTests
{
    [Fact]
    public async Task InventoryAndConsoleAgreeAndOnlyCurrentMaintenanceLeaseCanStart()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        await using var db = new ControlDbContext(new DbContextOptionsBuilder<ControlDbContext>().UseSqlite(connection).Options);
        await db.Database.EnsureCreatedAsync();
        var configuration = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
            { ["Control:EnableProcessControl"] = "true" }).Build();
        var clock = new FixedClock(DateTimeOffset.Parse("2026-11-07T00:00:00Z"));
        var hostId = Guid.NewGuid();
        var serverId = Guid.NewGuid();
        var environmentId = Guid.NewGuid();
        var config = new ServerConfig { ServerId = serverId, HostId = hostId, Revision = 1, ManagedRootId = Guid.NewGuid(),
            ExecutableId = Guid.NewGuid(), ServerDirectory = "servers/build" };
        var runtime = new ServerRuntimeSnapshot { ServerId = serverId, ConfigRevision = 1, StateRevision = 1, ProcessState = ProcessState.Stopped };
        var host = new InventoryHost { HostId = hostId, HostKey = "fixture", Enabled = true, ConnectionState = HostConnectionState.Online,
            AppliedInventoryRevision = 1, LastSeenAtUtc = clock.GetUtcNow(), RuntimeSnapshotJson = ControlJson.Serialize(new[] { runtime }),
            CapabilitiesJson = ControlJson.Serialize(new[] { "P1.Operations", "P1.LegacyGate" }) };
        var server = new InventoryServer { ServerId = serverId, HostId = hostId, ServerKey = "build", EnvironmentId = environmentId,
            AppliedRevision = 1, DesiredRevision = 1, DesiredConfigJson = ControlJson.Serialize(config) };
        db.Hosts.Add(host);
        db.Environments.Add(new InventoryEnvironment { EnvironmentId = environmentId, EnvironmentKey = "fixture" });
        db.Servers.Add(server);
        var snapshot = ControlJson.Serialize(new InventorySnapshot { HostId = hostId, Revision = 1, Servers = [config] });
        db.InventoryVersions.Add(new InventoryVersion { HostId = hostId, Revision = 1, SnapshotJson = snapshot, Sha256 = ControlProtocol.Hash(snapshot) });
        await db.SaveChangesAsync();

        var reservations = new ReservationService(db, null!, null!, configuration, clock);
        var store = new InventoryStore(db, new InventoryWriteGate(), clock, configuration);
        var console = new OperationsModel(db, null!, reservations, configuration, clock, store);
        var inventory = new IndexModel(db, store, null!, clock, configuration, null!);
        var workflowId = Guid.NewGuid();
        var reservationId = Guid.NewGuid();
        var serverKey = ReservationService.ServerKey(serverId);
        var heldServer = new ControlReservation { ResourceKey = serverKey, ReservationId = reservationId, OwnerWorkflowId = workflowId, State = ReservationStatus.Prepared };
        var workflow = new ControlWorkflow { WorkflowId = workflowId, Status = WorkflowStatus.WaitingForStartup,
            RequestJson = ControlJson.Serialize(new ReservationAcquireRequest { WorkflowId = workflowId, ServerConditions = [new(serverId, ProcessRequirement.Stopped)] }) };
        var lease = new ControlReservationLease { WorkflowId = workflowId, ReservationId = reservationId, Status = ReservationStatus.Prepared,
            LastStepKey = "WaitForStartup", AdapterId = "01", ResourceKeysJson = ControlJson.Serialize(new[] { serverKey }),
            FencesJson = ControlJson.Serialize(new[] { new HostReservationFence(hostId, 1) }) };
        db.Reservations.Add(heldServer);
        db.Workflows.Add(workflow);
        db.ReservationLeases.Add(lease);
        await db.SaveChangesAsync();

        var maintenance = await ReadMatchingStatusAsync();
        Assert.False(maintenance.GetProperty("canStart").GetBoolean());
        Assert.Contains("予約", maintenance.GetProperty("reason").GetString());
        Assert.Equal(workflowId, maintenance.GetProperty("workflowId").GetGuid());
        Assert.Equal(reservationId, Assert.Single(maintenance.GetProperty("maintenanceStarts").EnumerateArray()).GetProperty("reservationId").GetGuid());

        var heldEnvironment = new ControlReservation { ResourceKey = ReservationService.EnvironmentKey(environmentId),
            ReservationId = Guid.NewGuid(), OwnerWorkflowId = Guid.NewGuid(), State = ReservationStatus.Prepared };
        db.Reservations.Add(heldEnvironment);
        await db.SaveChangesAsync();
        Assert.Empty((await ReadMatchingStatusAsync()).GetProperty("maintenanceStarts").EnumerateArray());
        heldEnvironment.State = ReservationStatus.Released;
        heldServer.State = ReservationStatus.Released;
        lease.Status = ReservationStatus.Released;
        await db.SaveChangesAsync();
        Assert.True((await ReadMatchingStatusAsync()).GetProperty("canStart").GetBoolean());

        host.RuntimeSnapshotJson = ControlJson.Serialize(new[] { runtime with { ProcessState = ProcessState.Starting } });
        await db.SaveChangesAsync();
        Assert.Contains("起動処理中", (await ReadMatchingStatusAsync()).GetProperty("reason").GetString());

        server.AppliedRevision = 0;
        await db.SaveChangesAsync();
        var pending = await ReadMatchingStatusAsync();
        Assert.Equal(0, pending.GetProperty("configRevision").GetInt64());
        Assert.Contains("設定の適用", pending.GetProperty("reason").GetString());

        server.AppliedRevision = 1;
        server.DesiredRevision = 2;
        server.DesiredConfigJson = ControlJson.Serialize(config with { Revision = 2, ServerDirectory = "servers/build-next" });
        host.RuntimeSnapshotJson = ControlJson.Serialize(new[] { runtime with { ProcessState = ProcessState.Running, CurrentSessionId = Guid.NewGuid() } });
        await db.SaveChangesAsync();
        var pendingStop = await ReadMatchingStatusAsync();
        Assert.True(pendingStop.GetProperty("canStop").GetBoolean());
        Assert.False(pendingStop.GetProperty("canStart").GetBoolean());
        Assert.False(pendingStop.GetProperty("canRun").GetBoolean());
        Assert.Contains("正常停止後", pendingStop.GetProperty("reason").GetString());

        var unrelatedEnvironment = new ControlReservation { ResourceKey = ReservationService.EnvironmentKey(Guid.NewGuid()),
            ReservationId = Guid.NewGuid(), OwnerWorkflowId = Guid.NewGuid(), State = ReservationStatus.Prepared };
        db.Reservations.Add(unrelatedEnvironment);
        await db.SaveChangesAsync();
        Assert.False((await ReadMatchingStatusAsync()).GetProperty("canStop").GetBoolean());
        unrelatedEnvironment.State = ReservationStatus.Released;

        host.RuntimeSnapshotJson = ControlJson.Serialize(new[] { runtime });
        heldServer.State = ReservationStatus.Prepared;
        lease.Status = ReservationStatus.Prepared;
        lease.AdapterId = "16";
        lease.ResourceKeysJson = ControlJson.Serialize(new[] { serverKey,
            ReservationService.DirectoryKey(hostId, config.ManagedRootId, config.ServerDirectory), ReservationService.EnvironmentKey(environmentId) });
        await db.SaveChangesAsync();
        var heldStart = Assert.Single((await ReadMatchingStatusAsync()).GetProperty("maintenanceStarts").EnumerateArray());
        Assert.True(heldStart.GetProperty("usesAppliedSettings").GetBoolean());
        lease.AdapterId = "01";
        await db.SaveChangesAsync();
        Assert.Empty((await ReadMatchingStatusAsync()).GetProperty("maintenanceStarts").EnumerateArray());

        host.LastSeenAtUtc = clock.GetUtcNow().AddMinutes(-1);
        await db.SaveChangesAsync();
        var stale = await ReadMatchingStatusAsync();
        Assert.Equal(JsonValueKind.Null, stale.GetProperty("processState").ValueKind);
        Assert.Contains("状態報告が途絶", stale.GetProperty("reason").GetString());

        async Task<JsonElement> ReadMatchingStatusAsync()
        {
            await inventory.OnGetAsync();
            var actions = inventory.Actions(Assert.Single(inventory.Servers));
            var status = JsonSerializer.SerializeToElement(Assert.IsType<JsonResult>(await console.OnGetStatusAsync(serverId)).Value);
            Assert.Equal(actions.CanStart, status.GetProperty("canStart").GetBoolean());
            Assert.Equal(actions.CanStop, status.GetProperty("canStop").GetBoolean());
            Assert.Equal(actions.CanRun, status.GetProperty("canRun").GetBoolean());
            Assert.Equal(actions.Reason, status.GetProperty("reason").GetString());
            return status;
        }
    }

    private sealed class FixedClock(DateTimeOffset now) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => now;
    }
}
