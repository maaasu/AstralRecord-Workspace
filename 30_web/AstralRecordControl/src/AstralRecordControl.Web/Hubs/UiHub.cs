using AstralRecordControl.Web.Authentication;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;

namespace AstralRecordControl.Web.Hubs;

// Browser connections receive notifications only. All mutations remain HTTP+CSRF.
[Authorize(Policy = "OwnerOnly")]
public sealed class UiHub(OwnerAuthService owners, OwnerSessionConnections connections) : Hub
{
    public override async Task OnConnectedAsync()
    {
        var session = await owners.ValidateSessionAsync(Context.User!);
        if (session == null || !Guid.TryParse(Context.User!.FindFirst(OwnerAuthentication.SessionClaim)?.Value, out var id)) { Context.Abort(); return; }
        connections.Register(id, Context.ConnectionId, Context.Abort);
        await base.OnConnectedAsync();
    }
    public override Task OnDisconnectedAsync(Exception? exception)
    {
        connections.Unregister(Context.ConnectionId); return base.OnDisconnectedAsync(exception);
    }
}
