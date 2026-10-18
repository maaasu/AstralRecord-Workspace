using System.Text.Json;
using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Authentication;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Hubs;
using AstralRecordControl.Web.Inventory;
using Microsoft.AspNetCore.Antiforgery;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authorization;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web;

public static partial class ControlApplication
{
    static partial void ConfigureInventory(WebApplicationBuilder builder)
    {
        builder.Services.AddSingleton<InventoryWriteGate>();
        builder.Services.AddSingleton<AgentConnectionRegistry>();
        builder.Services.AddScoped<InventoryStore>();
        builder.Services.AddScoped<AgentCredentialService>();
        builder.Services.AddScoped<AgentProtocolService>();
        builder.Services.AddScoped<InventoryNotifier>();
        builder.Services.AddHostedService<AgentOfflineMonitor>();
        builder.Services.AddAuthentication().AddScheme<AuthenticationSchemeOptions, AgentBearerHandler>(AgentBearerHandler.SchemeName, _ => { });
        builder.Services.AddAuthorization(options => options.AddPolicy("AgentOnly", new AuthorizationPolicyBuilder(AgentBearerHandler.SchemeName).RequireAuthenticatedUser().RequireClaim("host_id").Build()));
        builder.Services.AddSignalR(options => { options.MaximumReceiveMessageSize = ControlProtocol.MaxMessageBytes; options.EnableDetailedErrors = false; })
            .AddJsonProtocol(options => options.PayloadSerializerOptions = ControlJson.CreateOptions());
    }

    static partial void MapInventory(WebApplication app)
    {
        var owner = app.MapGroup("/api/control/v1").RequireAuthorization("OwnerOnly");
        owner.AddEndpointFilter(async (context, next) =>
        {
            var http = context.HttpContext; http.Response.Headers.CacheControl = "no-store";
            try
            {
                if (!HttpMethods.IsGet(http.Request.Method) && !HttpMethods.IsHead(http.Request.Method)) await http.RequestServices.GetRequiredService<IAntiforgery>().ValidateRequestAsync(http);
                return await next(context);
            }
            catch (AntiforgeryValidationException) { return Fault(http, 400, "AntiforgeryFailed"); }
            catch (InventoryFault fault) { return Fault(http, fault.Status, fault.Code); }
            catch (Exception exception) when (exception is JsonException or ArgumentException or NullReferenceException or OverflowException) { return Fault(http, 400, "InvalidInput"); }
        });
        owner.MapGet("/overview", (InventoryStore store) => store.OverviewAsync());
        owner.MapGet("/hosts", async (ControlDbContext db) => await db.Hosts.AsNoTracking().OrderBy(x => x.HostKey).ToArrayAsync());
        owner.MapGet("/hosts/{hostId:guid}", async (Guid hostId, InventoryStore store) => await store.HostAsync(hostId));
        owner.MapPost("/hosts", async (JsonElement body, HttpContext http, InventoryStore store) =>
        {
            RequireCreate(http); var row = await store.CreateHostAsync(Read<HostCreateRequest>(body), Idempotency(http), body.GetRawText());
            http.Response.Headers.ETag = Quote(row.Revision); return Results.Created($"/api/control/v1/hosts/{row.HostId}", row);
        });
        owner.MapPut("/hosts/{hostId:guid}", async (Guid hostId, JsonElement body, HttpContext http, InventoryStore store, AgentConnectionRegistry connections, InventoryNotifier notifier) =>
        {
            var row = await store.UpdateHostAsync(hostId, Read<HostUpdateRequest>(body), Match(http));
            if (!row.Enabled) connections.RevokeHost(hostId);
            await notifier.StateChangedAsync(); http.Response.Headers.ETag = Quote(row.Revision); return Results.Ok(row);
        });
        owner.MapGet("/hosts/{hostId:guid}/resources", async (Guid hostId, InventoryStore store) =>
        {
            var host = await store.HostAsync(hostId);
            return Results.Ok(new { hostId, resources = ControlJson.Deserialize<HostResources>(host.ResourcesJson), capabilities = ControlJson.Deserialize<string[]>(host.CapabilitiesJson), host.LastSeenAtUtc });
        });
        owner.MapPost("/hosts/{hostId:guid}/credentials", async (Guid hostId, HttpContext http, AgentCredentialService credentials, OwnerAuthService auth) =>
        {
            if (!await auth.HasRecentAuthenticationAsync(http.User)) throw new InventoryFault(403, "RecentAuthenticationRequired");
            var issued = await credentials.IssueAsync(hostId); return Results.Created($"/api/control/v1/hosts/{hostId}/credentials/{issued.CredentialId}", issued);
        });
        owner.MapDelete("/hosts/{hostId:guid}/credentials/{credentialId:guid}", async (Guid hostId, Guid credentialId, HttpContext http, AgentCredentialService credentials, OwnerAuthService auth) =>
        {
            if (!await auth.HasRecentAuthenticationAsync(http.User)) throw new InventoryFault(403, "RecentAuthenticationRequired");
            await credentials.RevokeAsync(hostId, credentialId); return Results.NoContent();
        });
        owner.MapGet("/environments", async (ControlDbContext db) => await db.Environments.AsNoTracking().OrderBy(x => x.EnvironmentKey).ToArrayAsync());
        owner.MapPost("/environments", async (JsonElement body, HttpContext http, InventoryStore store) =>
        {
            RequireCreate(http); var row = await store.CreateEnvironmentAsync(Read<EnvironmentCreateRequest>(body), Idempotency(http), body.GetRawText());
            http.Response.Headers.ETag = Quote(row.Revision); return Results.Created($"/api/control/v1/environments/{row.EnvironmentId}", row);
        });
        owner.MapPut("/environments/{environmentId:guid}", async (Guid environmentId, JsonElement body, HttpContext http, InventoryStore store) =>
        {
            var row = await store.UpdateEnvironmentAsync(environmentId, Read<EnvironmentUpdateRequest>(body), Match(http)); http.Response.Headers.ETag = Quote(row.Revision); return Results.Ok(row);
        });
        owner.MapGet("/servers", async (ControlDbContext db) => await db.Servers.AsNoTracking().OrderBy(x => x.ServerKey).ToArrayAsync());
        owner.MapGet("/servers/{serverId:guid}", async (Guid serverId, InventoryStore store, HttpContext http) => { var row = await store.ServerAsync(serverId); http.Response.Headers.ETag = Quote(row.DesiredRevision); return row; });
        owner.MapPost("/servers", async (JsonElement body, HttpContext http, InventoryStore store, InventoryNotifier notifier) =>
        {
            RequireCreate(http); var row = await store.CreateServerAsync(Read<ServerCreateRequest>(body), Idempotency(http), body.GetRawText(), ConfigJson(body));
            await notifier.InventoryChangedAsync(row.HostId); http.Response.Headers.ETag = Quote(row.DesiredRevision); return Results.Created($"/api/control/v1/servers/{row.ServerId}", row);
        });
        owner.MapPut("/servers/{serverId:guid}", async (Guid serverId, JsonElement body, HttpContext http, InventoryStore store, InventoryNotifier notifier) =>
        {
            var row = await store.UpdateServerAsync(serverId, Read<ServerUpdateRequest>(body), Match(http), ConfigJson(body));
            await notifier.InventoryChangedAsync(row.HostId); http.Response.Headers.ETag = Quote(row.DesiredRevision); return Results.Ok(row);
        });
        owner.MapPost("/servers/{serverId:guid}/operations", async (Guid serverId, InventoryStore store) => { await store.ServerAsync(serverId); throw new InventoryFault(409, "UnsupportedCapability"); });
        owner.MapPost("/hosts/{hostId:guid}/operations", async (Guid hostId, InventoryStore store) => { await store.HostAsync(hostId); throw new InventoryFault(409, "UnsupportedCapability"); });
        owner.MapDelete("/servers/{serverId:guid}", async (Guid serverId, InventoryStore store) => { await store.ServerAsync(serverId); throw new InventoryFault(409, "UnsupportedCapability"); });
        foreach (var path in new[] { "/operations/{**rest}", "/workflows/{**rest}", "/reservations/{**rest}", "/schedules/{**rest}", "/backup-profiles/{**rest}", "/backups/{**rest}", "/transfers/{**rest}" })
            owner.MapMethods(path, ["GET", "POST", "PUT", "DELETE"], (HttpContext http) => Fault(http, 409, "UnsupportedCapability"));

        var agent = app.MapGroup("/api/agent/v1").RequireAuthorization("AgentOnly");
        agent.AddEndpointFilter(async (context, next) =>
        {
            var http = context.HttpContext; http.Response.Headers.CacheControl = "no-store";
            try
            {
                if (!Guid.TryParse(http.Request.Headers["X-Control-Connection-Epoch"], out var epoch)) throw new InventoryFault(409, "StateConflict");
                await http.RequestServices.GetRequiredService<AgentProtocolService>().RequireAsync(AgentIdentity.From(http.User), epoch);
                return await next(context);
            }
            catch (InventoryFault fault) { return Fault(http, fault.Status, fault.Code); }
            catch (Exception exception) when (exception is JsonException or ArgumentException or NullReferenceException) { return Fault(http, 400, "InvalidInput"); }
        });
        agent.MapGet("/inventory", async (HttpContext http, InventoryStore store) => await store.InventoryAsync(AgentIdentity.From(http.User).HostId));
        agent.MapPost("/events", async (JsonElement body, HttpContext http, AgentProtocolService protocol) =>
        {
            var request = Read<EventBatch>(body);
            if (request.ConnectionEpoch.ToString() != http.Request.Headers["X-Control-Connection-Epoch"].ToString()) throw new InventoryFault(409, "StateConflict");
            return await protocol.PublishEventsAsync(AgentIdentity.From(http.User), request);
        });
        agent.MapMethods("/transfers/{transferId:guid}/{**action}", ["GET", "POST", "PUT"], async (Guid transferId, string? action, HttpContext http, InventoryStore store) =>
        {
            await store.ValidateTransferScopeAsync(AgentIdentity.From(http.User).HostId, transferId, string.IsNullOrEmpty(action) ? "metadata" : action); return Results.Empty;
        });
        app.MapHub<AgentHub>(ControlProtocol.AgentHubPath);
        app.MapHub<UiHub>(ControlProtocol.UiHubPath);
    }

    private static T Read<T>(JsonElement body) => ControlJson.Deserialize<T>(body.GetRawText());
    private static string ConfigJson(JsonElement body) => body.ValueKind == JsonValueKind.Object && body.TryGetProperty("config", out var config) && config.ValueKind == JsonValueKind.Object ? config.GetRawText() : throw new JsonException("Missing config.");
    private static string Quote(long value) => $"\"{value}\"";
    private static long Match(HttpContext http)
    {
        var value = http.Request.Headers.IfMatch.ToString();
        if (value.Length < 3 || value[0] != '"' || value[^1] != '"' || !long.TryParse(value[1..^1], System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out var revision) || revision < 1) throw new InventoryFault(409, "StaleRevision");
        return revision;
    }
    private static void RequireCreate(HttpContext http)
    {
        if (http.Request.Headers.IfNoneMatch != "*") throw new InventoryFault(409, "StaleRevision");
    }
    private static Guid Idempotency(HttpContext http) => Guid.TryParse(http.Request.Headers["Idempotency-Key"], out var id) && id != Guid.Empty ? id : throw new InventoryFault(400, "InvalidIdempotencyKey");
    private static IResult Fault(HttpContext http, int status, string code) => Results.Problem(statusCode: status, title: code, extensions: new Dictionary<string, object?> { ["errorCode"] = code, ["traceId"] = http.TraceIdentifier, ["retryable"] = code == "HostOffline" });
}
