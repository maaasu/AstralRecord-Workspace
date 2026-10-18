using System.Text.Json;
using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Inventory;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;

namespace AstralRecordControl.Web.Hubs;

[Authorize(Policy = "AgentOnly")]
public sealed class AgentHub(AgentProtocolService protocol, InventoryNotifier notifier) : Hub
{
    private AgentIdentity Agent => AgentIdentity.From(Context.User!);
    private static T Read<T>(JsonElement message) => ControlJson.Deserialize<T>(message.GetRawText());
    private static async Task<T> Invoke<T>(Func<Task<T>> action)
    {
        try { return await action(); }
        catch (InventoryFault fault) { throw new HubException(fault.Code); }
        catch (Exception exception) when (exception is JsonException or ArgumentException or NullReferenceException) { throw new HubException("InvalidInput"); }
    }
    public Task<HelloAck> Hello(JsonElement message) => Invoke(async () => await protocol.HelloAsync(Agent, Read<AgentHello>(message), Context.ConnectionId, Context.Abort));
    public Task<ReconcileAck> Reconcile(JsonElement message) => Invoke(async () =>
    {
        var result = await protocol.ReconcileAsync(Agent, Read<ReconcileRequest>(message), Context.ConnectionId);
        await notifier.StateChangedAsync(); return result;
    });
    public Task<bool> Heartbeat(JsonElement message) => Invoke(async () =>
    {
        await protocol.HeartbeatAsync(Agent, Read<AgentHeartbeat>(message), Context.ConnectionId); await notifier.StateChangedAsync(); return true;
    });
    public Task<bool> InventoryApplied(JsonElement message) => Invoke(async () =>
    {
        await protocol.ApplyAckAsync(Agent, Read<Contracts.InventoryApplied>(message), Context.ConnectionId); await notifier.StateChangedAsync(); return true;
    });
    public Task<EventAck> PublishEvents(JsonElement message) => Invoke(async () =>
    {
        var batch = Read<EventBatch>(message);
        var result = await protocol.PublishEventsAsync(Agent, batch, Context.ConnectionId);
        foreach (var id in batch.Events.Where(x => x.EventType == "OperationChanged" && x.OperationId.HasValue).Select(x => x.OperationId!.Value).Distinct())
            await notifier.OperationChangedAsync(id);
        return result;
    });
    public override async Task OnDisconnectedAsync(Exception? exception)
    {
        await protocol.DisconnectedAsync(Agent, Context.ConnectionId); await notifier.StateChangedAsync(); await base.OnDisconnectedAsync(exception);
    }
}

public sealed class InventoryNotifier(IHubContext<AgentHub> agents, IHubContext<UiHub> ui, AgentConnectionRegistry connections, InventoryStore store)
{
    public Task StateChangedAsync() => ui.Clients.All.SendAsync("StateChanged", new { observedAtUtc = DateTimeOffset.UtcNow });
    public Task OperationChangedAsync(Guid operationId) => ui.Clients.All.SendAsync("OperationChanged", new { operationId, observedAtUtc = DateTimeOffset.UtcNow });
    public async Task InventoryChangedAsync(Guid hostId)
    {
        var connection = connections.Current(hostId);
        if (connection != null)
        {
            var inventory = await store.InventoryAsync(hostId);
            await agents.Clients.Client(connection.ConnectionId).SendAsync("InventoryChanged", new InventoryChanged(connection.Epoch, inventory.Revision, inventory.Sha256));
        }
        await StateChangedAsync();
    }
}
