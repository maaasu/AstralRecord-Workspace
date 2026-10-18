using System.Text.Json;
using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Inventory;
using AstralRecordControl.Web.Hubs;
using AstralRecordControl.Web.Operations;
using Microsoft.AspNetCore.Routing;

namespace AstralRecordControl.Web;

public static partial class ControlApplication
{
    private static void MapBackups(RouteGroupBuilder owner)
    {
        owner.MapGet("/backup-profiles", async (Guid? serverId, InventoryStore store) => Results.Ok(await store.BackupProfilesAsync(serverId)));
        owner.MapGet("/backup-profiles/{profileId:guid}", async (Guid profileId, InventoryStore store, HttpContext http) =>
        {
            var profile = (await store.BackupProfilesAsync()).SingleOrDefault(p => p.BackupProfileId == profileId) ?? throw new InventoryFault(404, "NotFound");
            http.Response.Headers.ETag = Quote(profile.Revision);
            return Results.Ok(profile);
        });
        owner.MapPost("/backup-profiles", async (JsonElement body, HttpContext http, InventoryStore store, InventoryNotifier notifier) =>
        {
            RequireCreate(http);
            var profile = await store.SaveBackupProfileAsync(Read<BackupProfile>(body), null, Idempotency(http));
            await notifier.InventoryChangedAsync((await store.ServerAsync(profile.ServerId)).HostId);
            http.Response.Headers.ETag = Quote(profile.Revision);
            return Results.Created($"/api/control/v1/backup-profiles/{profile.BackupProfileId}", profile);
        });
        owner.MapPut("/backup-profiles/{profileId:guid}", async (Guid profileId, JsonElement body, HttpContext http, InventoryStore store, InventoryNotifier notifier) =>
        {
            var input = Read<BackupProfile>(body);
            if (input.BackupProfileId != profileId) throw new InventoryFault(400, "InvalidInput");
            var profile = await store.SaveBackupProfileAsync(input, Match(http), Idempotency(http));
            await notifier.InventoryChangedAsync((await store.ServerAsync(profile.ServerId)).HostId);
            http.Response.Headers.ETag = Quote(profile.Revision);
            return Results.Ok(profile);
        });
        owner.MapGet("/servers/{serverId:guid}/backups", async (Guid serverId, Guid? cursor, int? limit, InventoryStore store, IAgentOperationTransport agents, HttpContext http) =>
        {
            if (limit is < 1 or > 100) throw new InventoryFault(400, "InvalidInput");
            var server = await store.ServerAsync(serverId);
            var page = await agents.ListBackupsAsync(server.HostId, serverId, http.RequestAborted, cursor, limit ?? 100);
            return Results.Ok(new { page.Rows, page.NextCursor, scope = "ServerFiles" });
        });
        owner.MapPost("/backups/{backupId:guid}/protect", async (Guid backupId, Guid serverId, JsonElement body, HttpContext http, ControlOperationsService operations) =>
        {
            var request = Read<BackupActionRequest>(body);
            var result = await operations.CreateAsync(serverId, request.ToOperation(OperationKind.SetBackupProtection, ControlJson.Element(new { backupId, @protected = request.Protected })), Idempotency(http), http.RequestAborted);
            return Results.Accepted($"/api/control/v1/operations/{result.OperationId}", new { result.OperationId, result.Status });
        });
        owner.MapDelete("/backups/{backupId:guid}", async (Guid backupId, Guid serverId, HttpContext http, ControlOperationsService operations) =>
        {
            var body = await JsonDocument.ParseAsync(http.Request.Body, cancellationToken: http.RequestAborted);
            using (body)
            {
                var request = Read<BackupActionRequest>(body.RootElement);
                var result = await operations.CreateAsync(serverId, request.ToOperation(OperationKind.DeleteBackup, ControlJson.Element(new { backupId, reason = request.Reason })), Idempotency(http), http.RequestAborted);
                return Results.Accepted($"/api/control/v1/operations/{result.OperationId}", new { result.OperationId, result.Status });
            }
        });
    }
}

public sealed record BackupActionRequest(long ConfigRevision, long ExpectedStateRevision, Guid? ExpectedServerSessionId, bool Protected = false, string Reason = "")
{
    public ServerOperationRequest ToOperation(OperationKind kind, JsonElement payload) => new() { Kind = kind, Payload = payload, ConfigRevision = ConfigRevision, ExpectedStateRevision = ExpectedStateRevision, ExpectedServerSessionId = ExpectedServerSessionId };
}
