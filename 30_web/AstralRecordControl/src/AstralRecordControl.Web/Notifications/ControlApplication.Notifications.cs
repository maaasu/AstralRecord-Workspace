using System.Text.Json;
using AstralRecordControl.Web.Authentication;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web;

public static partial class ControlApplication
{
    private static void MapNotifications(RouteGroupBuilder owner)
    {
        owner.MapGet("/notification-destinations", async (ControlDbContext db) =>
            (await db.NotificationDestinations.AsNoTracking().OrderBy(x => x.DisplayName).ToArrayAsync())
            .Select(x => SafeDestination(x)).ToArray());
        owner.MapGet("/notification-destinations/{destinationId:guid}", async (Guid destinationId, ControlDbContext db, HttpContext http) =>
        {
            var row = await db.NotificationDestinations.AsNoTracking().SingleOrDefaultAsync(x => x.DestinationProfileId == destinationId)
                ?? throw new InventoryFault(404, "NotFound");
            http.Response.Headers.ETag = Quote(row.Revision);
            return Results.Ok(SafeDestination(row));
        });

        owner.MapPost("/notification-destinations", async (JsonElement body, HttpContext http,
            Notifications.NotificationService notifications, OwnerAuthService auth) =>
        {
            if (!await auth.HasRecentAuthenticationAsync(http.User)) throw new InventoryFault(403, "RecentAuthenticationRequired");
            RequireCreate(http);
            var request = Read<NotificationDestinationRequest>(body);
            var row = await notifications.SaveDestinationAsync(Idempotency(http), 0, request.DisplayName, request.Url, request.Enabled);
            http.Response.Headers.ETag = Quote(row.Revision);
            return Results.Created($"/api/control/v1/notification-destinations/{row.DestinationProfileId:D}", SafeDestination(row));
        });

        owner.MapPut("/notification-destinations/{destinationId:guid}", async (Guid destinationId, JsonElement body,
            HttpContext http, Notifications.NotificationService notifications, OwnerAuthService auth) =>
        {
            if (!await auth.HasRecentAuthenticationAsync(http.User)) throw new InventoryFault(403, "RecentAuthenticationRequired");
            var request = Read<NotificationDestinationRequest>(body);
            var row = await notifications.SaveDestinationAsync(destinationId, Match(http), request.DisplayName, request.Url, request.Enabled);
            http.Response.Headers.ETag = Quote(row.Revision);
            return Results.Ok(SafeDestination(row));
        });

        owner.MapGet("/notifications", async (ControlDbContext db) =>
        {
            var deliveries = await db.NotificationOutbox.AsNoTracking().OrderByDescending(x => x.NextAttemptUnixMs).Take(100).ToArrayAsync();
            var ids = deliveries.Select(x => x.EventId).Distinct().ToArray();
            var events = (await db.NotificationEvents.AsNoTracking().Where(x => ids.Contains(x.EventId)).ToArrayAsync()).ToDictionary(x => x.EventId);
            return deliveries.Where(x => events.ContainsKey(x.EventId)).Select(x => new
            {
                x.EventId, x.DestinationProfileId, events[x.EventId].EventType, events[x.EventId].OccurredAtUtc,
                x.Attempt, x.NextAttemptAtUtc, x.SentAtUtc, x.DeadLetteredAtUtc, x.LastErrorCode
            }).ToArray();
        });
    }

    private static object SafeDestination(NotificationDestination row) => new
    {
        row.DestinationProfileId, row.DisplayName, row.Enabled, row.Revision,
        hasUrl = !string.IsNullOrEmpty(row.ProtectedUrl), row.CreatedAtUtc, row.UpdatedAtUtc
    };

    private sealed record NotificationDestinationRequest(string DisplayName, string? Url, bool Enabled);
}
