using System.Text.Json;
using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Pages.Servers;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Xunit;

namespace AstralRecordControl.Operations.Tests;

public sealed class OperationsStatusPageTests
{
    [Fact]
    public async Task StatusReadsLatestTwentyOperationsAndCurrentSessionFromSqlite()
    {
        Assert.Contains(typeof(OperationsModel).GetCustomAttributes(typeof(AuthorizeAttribute), true).Cast<AuthorizeAttribute>(), x => x.Policy == "OwnerOnly");
        Assert.Contains(typeof(OperationsModel).GetCustomAttributes(typeof(ResponseCacheAttribute), true).Cast<ResponseCacheAttribute>(), x => x.NoStore);
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        await using var db = new ControlDbContext(new DbContextOptionsBuilder<ControlDbContext>().UseSqlite(connection).Options);
        await db.Database.EnsureCreatedAsync();
        var now = TimeProvider.System.GetUtcNow();
        var hostId = Guid.NewGuid();
        var serverId = Guid.NewGuid();
        var sessionId = Guid.NewGuid();
        var config = new ServerConfig { ServerId = serverId, HostId = hostId, Revision = 1, ManagedRootId = Guid.NewGuid(), ExecutableId = Guid.NewGuid(), ServerDirectory = "servers/test" };
        var host = new InventoryHost
        {
            HostId = hostId, HostKey = "fixture", DisplayName = "Fixture", Enabled = true,
            ConnectionState = HostConnectionState.Online, LastSeenAtUtc = now,
            CapabilitiesJson = ControlJson.Serialize(new[] { "P1.Operations", "P1.LegacyGate" }),
            RuntimeSnapshotJson = ControlJson.Serialize(new[] { new ServerRuntimeSnapshot { ServerId = serverId, ConfigRevision = 1, StateRevision = 8, ProcessState = ProcessState.Running, CurrentSessionId = sessionId } })
        };
        db.Hosts.Add(host);
        db.Servers.Add(new InventoryServer { ServerId = serverId, HostId = hostId, ServerKey = "fixture", DisplayName = "Fixture", DesiredRevision = 1, AppliedRevision = 1, DesiredConfigJson = ControlJson.Serialize(config) });
        var workflowId = Guid.NewGuid();
        db.Workflows.Add(new ControlWorkflow { WorkflowId = workflowId });
        for (var i = 0; i < 25; i++)
            db.Operations.Add(new ControlOperation { OperationId = Guid.NewGuid(), IdempotencyKey = Guid.NewGuid(), HostId = hostId, ServerId = serverId, WorkflowId = workflowId, ReservationId = Guid.NewGuid(), Kind = OperationKind.Start, CreatedAtUtc = now.AddMinutes(i - 25), Status = OperationStatus.Succeeded });
        await db.SaveChangesAsync();
        var configuration = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?> { ["Control:EnableProcessControl"] = "true" }).Build();
        var page = new OperationsModel(db, null!, null!, configuration, TimeProvider.System);

        using (var json = JsonDocument.Parse(JsonSerializer.Serialize(((JsonResult)await page.OnGetStatusAsync(serverId)).Value, new JsonSerializerOptions(JsonSerializerDefaults.Web))))
        {
            var root = json.RootElement;
            Assert.True(root.GetProperty("enabled").GetBoolean());
            Assert.Equal("Running", root.GetProperty("processState").GetString());
            Assert.Equal(8, root.GetProperty("stateRevision").GetInt64());
            Assert.Equal(sessionId, root.GetProperty("currentSessionId").GetGuid());
            Assert.Equal(1, root.GetProperty("configRevision").GetInt64());
            var rows = root.GetProperty("recent").EnumerateArray().ToArray();
            Assert.Equal(20, rows.Length);
            Assert.Equal(now.AddMinutes(-1), rows[0].GetProperty("createdAtUtc").GetDateTimeOffset());
            Assert.Equal(now.AddMinutes(-20), rows[^1].GetProperty("createdAtUtc").GetDateTimeOffset());
        }

        host.ConnectionState = HostConnectionState.Offline;
        await db.SaveChangesAsync();
        using var offline = JsonDocument.Parse(JsonSerializer.Serialize(((JsonResult)await page.OnGetStatusAsync(serverId)).Value, new JsonSerializerOptions(JsonSerializerDefaults.Web)));
        Assert.False(offline.RootElement.GetProperty("enabled").GetBoolean());
        Assert.Equal(JsonValueKind.Null, offline.RootElement.GetProperty("processState").ValueKind);
        host.ConnectionState = HostConnectionState.Online;
        host.LastSeenAtUtc = now.AddMinutes(-1);
        await db.SaveChangesAsync();
        using var stale = JsonDocument.Parse(JsonSerializer.Serialize(((JsonResult)await page.OnGetStatusAsync(serverId)).Value, new JsonSerializerOptions(JsonSerializerDefaults.Web)));
        Assert.False(stale.RootElement.GetProperty("enabled").GetBoolean());
        Assert.Equal(JsonValueKind.Null, stale.RootElement.GetProperty("processState").ValueKind);
        Assert.Equal(JsonValueKind.Null, stale.RootElement.GetProperty("currentSessionId").ValueKind);
    }
}
