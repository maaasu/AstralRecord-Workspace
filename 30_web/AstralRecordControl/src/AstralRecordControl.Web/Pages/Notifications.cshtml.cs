using AstralRecordControl.Web.Authentication;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using AstralRecordControl.Web.Notifications;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Pages;

[Authorize(Policy = "OwnerOnly")]
[ValidateAntiForgeryToken]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class NotificationsModel(ControlDbContext db, NotificationService notifications, OwnerAuthService auth) : PageModel
{
    public sealed record DestinationView(Guid Id, string Name, long Revision, bool Enabled);
    public sealed record DeliveryView(Guid EventId, string EventType, DateTimeOffset OccurredAtUtc, string DestinationName, int Attempt, string State, string? ErrorCode);
    public DestinationView[] Destinations { get; private set; } = [];
    public DeliveryView[] Deliveries { get; private set; } = [];
    public Guid NewDestinationId { get; private set; } = Guid.NewGuid();
    public string? Error { get; private set; }
    public string? Message { get; private set; }

    public async Task OnGetAsync() => await LoadAsync();

    public async Task<IActionResult> OnPostSaveAsync(Guid? destinationId, long revision, string displayName, string? url, bool enabled)
    {
        if (!await auth.HasRecentAuthenticationAsync(User))
        {
            Error = "通知先の変更前にセキュリティ画面で再認証してください。";
            await LoadAsync(); return Page();
        }
        try
        {
            var id = destinationId ?? Guid.NewGuid();
            await notifications.SaveDestinationAsync(id, revision, displayName, url, enabled);
            return RedirectToPage();
        }
        catch (Exception exception) when (exception is InventoryFault or ArgumentException or DbUpdateConcurrencyException)
        {
            Error = exception is InventoryFault fault ? fault.Code : "InvalidDestination";
            await LoadAsync(); return Page();
        }
    }

    private async Task LoadAsync()
    {
        var destinations = await notifications.ListDestinationsAsync();
        Destinations = destinations.Select(x => new DestinationView(x.DestinationProfileId, x.DisplayName, x.Revision, x.Enabled)).ToArray();
        var names = destinations.ToDictionary(x => x.DestinationProfileId, x => x.DisplayName);
        var recent = await db.NotificationOutbox.AsNoTracking().OrderByDescending(x => x.NextAttemptUnixMs).Take(50).ToArrayAsync();
        var ids = recent.Select(x => x.EventId).Distinct().ToArray();
        var events = (await db.NotificationEvents.AsNoTracking().Where(x => ids.Contains(x.EventId)).ToArrayAsync()).ToDictionary(x => x.EventId);
        Deliveries = recent.Where(x => events.ContainsKey(x.EventId)).Select(x => new DeliveryView(x.EventId,
            events[x.EventId].EventType, events[x.EventId].OccurredAtUtc,
            names.GetValueOrDefault(x.DestinationProfileId) ?? "削除済み通知先", x.Attempt,
            x.SentAtUtc.HasValue ? "送信済み" : x.DeadLetteredAtUtc.HasValue ? "送信停止" : "送信待ち",
            x.LastErrorCode)).ToArray();
    }
}
