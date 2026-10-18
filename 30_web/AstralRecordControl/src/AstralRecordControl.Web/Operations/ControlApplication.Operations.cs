using System.Text.Json;
using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Authentication;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using Microsoft.AspNetCore.Antiforgery;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web;

public static partial class ControlApplication
{
    private static void MapOperations(WebApplication app, RouteGroupBuilder owner)
    {
        owner.MapPost("/servers/{serverId:guid}/operations", async (Guid serverId, JsonElement body, HttpContext http, Operations.ControlOperationsService operations) =>
        {
            var row = await operations.CreateAsync(serverId, Read<ServerOperationRequest>(body), Idempotency(http), http.RequestAborted);
            return Results.Accepted($"/api/control/v1/operations/{row.OperationId}", new { row.OperationId, row.Status });
        });
        owner.MapGet("/operations/{operationId:guid}", async (Guid operationId, Operations.ControlOperationsService operations) =>
        {
            var row = await operations.RefreshAsync(operationId);
            return Results.Ok(new { row.OperationId, row.HostId, row.ServerId, row.WorkflowId, row.Kind, row.Status, row.StatusVersion, row.CreatedAtUtc, row.AcceptedAtUtc, row.StartedAtUtc, row.FinishedAtUtc, row.ErrorCode });
        });
        owner.MapGet("/operations/{operationId:guid}/result", async (Guid operationId, Operations.ControlOperationsService operations, Operations.OperationResultStore results, HttpContext http) =>
        {
            var row = await operations.GetAsync(operationId);
            if (!OperationValidation.IsTerminal(row.Status)) throw new InventoryFault(409, "StateConflict");
            var stored = row.Status == OperationStatus.Succeeded ? await results.ReadAsync(operationId, http.RequestAborted) : null;
            if (stored == null && row.ResultJson?.Contains("\"resultStored\":true", StringComparison.Ordinal) == true) throw new InventoryFault(409, "RecoveryRequired");
            return Results.Content(stored ?? row.ResultJson ?? "{}", "application/json");
        });
        owner.MapGet("/servers/{serverId:guid}/logs", async (Guid serverId, Guid? sessionId, string? segmentId, long? afterSequence, int? limit, Operations.ControlOperationsService operations, HttpContext http) =>
            Results.Ok(await operations.ReadLogsAsync(serverId, new ReadLogsRequest { ServerId = serverId, SessionId = sessionId, SegmentId = segmentId, AfterSequence = afterSequence ?? 0, Limit = limit ?? 200 }, http.RequestAborted)));
        owner.MapGet("/servers/{serverId:guid}/runtime", async (Guid serverId, InventoryStore store, Operations.IAgentOperationTransport agents, HttpContext http) =>
        {
            var server = await store.ServerAsync(serverId);
            return Results.Ok((await agents.SnapshotAsync(server.HostId, http.RequestAborted)).Servers.SingleOrDefault(s => s.ServerId == serverId) ?? throw new InventoryFault(409, "StateConflict"));
        });
        owner.MapGet("/workflows", async (ControlDbContext db) => (await db.Workflows.AsNoTracking().ToArrayAsync()).OrderByDescending(x => x.CreatedAtUtc).Take(100).ToArray());
        owner.MapGet("/workflows/{workflowId:guid}", async (Guid workflowId, ControlDbContext db, Operations.ReservationService reservations) =>
        {
            var workflow = await db.Workflows.AsNoTracking().SingleOrDefaultAsync(x => x.WorkflowId == workflowId) ?? throw new InventoryFault(404, "NotFound");
            var lease = await reservations.GetAsync(workflowId);
            var operations = await db.Operations.AsNoTracking().Where(x => x.WorkflowId == workflowId).Select(x => new { x.OperationId, x.Status, x.ErrorCode }).ToArrayAsync();
            return Results.Ok(new { workflow.WorkflowId, workflow.Kind, workflow.Status, workflow.RecoveryRequired, lease, operations });
        });
        owner.MapPost("/workflows/{workflowId:guid}/recover", async (Guid workflowId, JsonElement body, Operations.ControlOperationsService operations, Operations.ReservationService reservations, ControlDbContext db, HttpContext http) =>
        {
            var request = Read<WorkflowRecoveryRequest>(body);
            var workflow = await db.Workflows.AsNoTracking().SingleOrDefaultAsync(x => x.WorkflowId == workflowId) ?? throw new InventoryFault(404, "NotFound");
            if (workflow.Kind == "Maintenance")
            {
                await reservations.RecoverMaintenancePreparationAsync(workflowId, request.Action, http.RequestAborted);
                return Results.Accepted($"/api/control/v1/workflows/{workflowId}", new { workflowId, status = request.Action == WorkflowRecoveryAction.Resume ? "Resumed" : "Resolved" });
            }
            var child = await operations.RecoverAsync(workflowId, request, http.RequestAborted);
            return Results.Accepted($"/api/control/v1/workflows/{workflowId}", new { workflowId, operationId = child?.OperationId, status = child?.Status.ToString() ?? "Resolved" });
        });

        var gate = app.MapGroup("/api/control/v1").RequireAuthorization(CliAuthentication.OperatorPolicy);
        gate.AddEndpointFilter(async (context, next) =>
        {
            var http = context.HttpContext; http.Response.Headers.CacheControl = "no-store";
            try
            {
                if (!HttpMethods.IsGet(http.Request.Method) && !HttpMethods.IsHead(http.Request.Method)) await CliAuthentication.ValidateCookieCsrfAsync(http);
                return await next(context);
            }
            catch (AntiforgeryValidationException) { return Fault(http, 400, "AntiforgeryFailed"); }
            catch (InventoryFault fault) { return Fault(http, fault.Status, fault.Code); }
            catch (Exception exception) when (exception is JsonException or ArgumentException or NullReferenceException or OverflowException) { return Fault(http, 400, "InvalidInput"); }
        });
        gate.MapGet("/gate/catalog", async (ControlDbContext db) =>
        {
            var servers = await db.Servers.AsNoTracking().ToArrayAsync();
            return Results.Ok(new
            {
                servers = servers.Select(s => new { s.ServerId, s.ServerKey, s.HostId, s.RuntimeServerId, s.EnvironmentId, resourceKeys = new[] { Operations.ReservationService.ServerKey(s.ServerId) }.Concat(s.EnvironmentId.HasValue ? [Operations.ReservationService.EnvironmentKey(s.EnvironmentId.Value)] : Array.Empty<string>()).ToArray() }),
                environments = await db.Environments.AsNoTracking().Select(x => new { x.EnvironmentId, x.EnvironmentKey }).ToArrayAsync()
            });
        });
        gate.MapPost("/reservations", async (JsonElement body, Operations.ReservationService reservations, HttpContext http) =>
        {
            var lease = await reservations.AcquireAsync(Read<ReservationAcquireRequest>(body), http.RequestAborted);
            return Results.Created($"/api/control/v1/reservations/{lease.ReservationId}", lease);
        });
        gate.MapGet("/reservations/{reservationId:guid}", async (Guid reservationId, Operations.ReservationService reservations) => await reservations.GetByReservationAsync(reservationId));
        gate.MapPost("/reservations/{reservationId:guid}/validate", async (Guid reservationId, JsonElement body, Operations.ReservationService reservations, HttpContext http) =>
        {
            await reservations.ValidateAsync(reservationId, Read<GateValidateRequest>(body), http.RequestAborted);
            return Results.Ok(new { valid = true });
        });
        gate.MapPost("/reservations/{reservationId:guid}/release", async (Guid reservationId, JsonElement body, Operations.ReservationService reservations, HttpContext http) =>
        {
            await reservations.ReleaseAsync(reservationId, Read<GateReleaseRequest>(body), http.RequestAborted);
            return Results.NoContent();
        });
    }
}
