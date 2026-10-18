using System.Text.Json;
using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Inventory;
using AstralRecordControl.Web.Hubs;
using AstralRecordControl.Web.Operations;
using Microsoft.AspNetCore.Routing;

namespace AstralRecordControl.Web;

public static partial class ControlApplication
{
    private static void MapSchedules(RouteGroupBuilder owner)
    {
        owner.MapGet("/schedules", async (Guid? serverId, InventoryStore store) => Results.Ok(await store.SchedulesAsync(serverId)));
        owner.MapGet("/schedules/{scheduleId:guid}", async (Guid scheduleId, HttpContext http, InventoryStore store) =>
        {
            var row = (await store.SchedulesAsync()).SingleOrDefault(s => s.Definition.ScheduleId == scheduleId) ?? throw new InventoryFault(404, "NotFound");
            http.Response.Headers.ETag = Quote(row.Definition.Revision);
            return Results.Ok(new { row.Definition, row.AppliedRevision, occurrences = await store.ScheduleHistoryAsync(scheduleId) });
        });
        owner.MapPost("/schedules", async (JsonElement body, HttpContext http, InventoryStore store, InventoryNotifier notifier) =>
        {
            RequireCreate(http);
            var row = await store.SaveScheduleAsync(Read<ScheduleDefinition>(body), null, Idempotency(http));
            await notifier.InventoryChangedAsync(row.HostId);
            http.Response.Headers.ETag = Quote(row.Revision);
            return Results.Created($"/api/control/v1/schedules/{row.ScheduleId}", row);
        });
        owner.MapPut("/schedules/{scheduleId:guid}", async (Guid scheduleId, JsonElement body, HttpContext http, InventoryStore store, InventoryNotifier notifier) =>
        {
            var input = Read<ScheduleDefinition>(body);
            if (input.ScheduleId != scheduleId) throw new InventoryFault(400, "InvalidInput");
            var requestKey = http.Request.Headers.ContainsKey("Idempotency-Key") ? Idempotency(http) : Guid.NewGuid();
            var row = await store.SaveScheduleAsync(input, Match(http), requestKey);
            await notifier.InventoryChangedAsync(row.HostId);
            http.Response.Headers.ETag = Quote(row.Revision);
            return Results.Ok(row);
        });
        owner.MapPost("/schedules/{scheduleId:guid}/run-now", async (Guid scheduleId, HttpContext http, InventoryStore store, IAgentOperationTransport agents) =>
        {
            RequireCreate(http);
            var operationId = Idempotency(http);
            var definition = await store.PrepareRunNowAsync(scheduleId, operationId);
            var location = $"/api/control/v1/schedules/{scheduleId}/requests/{operationId}";
            var receipt = await store.ManualRequestAsync(scheduleId, operationId);
            if (receipt.RejectionCode != null) return Results.Accepted(location, new { operationId, status = "Rejected", errorCode = receipt.RejectionCode });
            var result = await store.ManualReportAsync(scheduleId, operationId);
            if (result != null) return Results.Accepted(location, new { result.OperationId, status = result.Status.ToString() });
            try { result = await agents.RunScheduleNowAsync(definition.HostId, scheduleId, definition.Revision, operationId, http.RequestAborted); }
            catch (InventoryFault fault) when (fault.Code == "HostOffline")
            { return Results.Accepted(location, new { operationId, status = "AcceptanceUnknown" }); }
            catch (InventoryFault fault) when (fault.Code is "StaleRevision" or "UnsupportedCapability" or "StateConflict" or "OperationConflict")
            {
                await store.RejectManualRequestAsync(scheduleId, operationId, fault.Code);
                return Results.Accepted(location, new { operationId, status = "Rejected", errorCode = fault.Code });
            }
            catch (Exception error) when (error is IOException or OperationCanceledException or Microsoft.AspNetCore.SignalR.HubException)
            { return Results.Accepted(location, new { operationId, status = "AcceptanceUnknown" }); }
            await store.WriteAsync(async () => { await store.StageScheduleReportAsync(definition.HostId, result); return true; });
            return Results.Accepted(location, new { result.OperationId, status = result.Status.ToString() });
        });
        owner.MapGet("/schedules/{scheduleId:guid}/requests/{operationId:guid}", async (Guid scheduleId, Guid operationId, InventoryStore store) =>
        {
            var report = await store.ManualReportAsync(scheduleId, operationId);
            var receipt = await store.ManualRequestAsync(scheduleId, operationId);
            if (report == null && receipt.RejectionCode != null) return Results.Ok(new { operationId, status = "Rejected", errorCode = receipt.RejectionCode });
            return report == null ? Results.Ok(new { operationId, status = "AcceptanceUnknown" }) : Results.Ok((object)report);
        });
    }
}
