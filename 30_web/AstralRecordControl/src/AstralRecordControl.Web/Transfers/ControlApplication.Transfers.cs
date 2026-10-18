using System.Text.Json;
using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Authentication;
using AstralRecordControl.Web.Inventory;
using Microsoft.AspNetCore.Antiforgery;

namespace AstralRecordControl.Web;

public static partial class ControlApplication
{
    static partial void ConfigureTransfers(WebApplicationBuilder builder)
    {
        builder.Services.AddScoped<Transfers.TransferRelay>();
        builder.Services.AddHostedService<Transfers.TransferCleanupService>();
    }

    static partial void MapTransfers(WebApplication app)
    {
        var owner = app.MapGroup("/api/control/v1/transfers").RequireAuthorization("OwnerOnly");
        owner.AddEndpointFilter(async (context, next) =>
        {
            var http = context.HttpContext;
            try
            {
                if (!HttpMethods.IsGet(http.Request.Method))
                {
                    RequireOwnerOrigin(http, app.Configuration["Control:PublicOrigin"]);
                    await http.RequestServices.GetRequiredService<IAntiforgery>().ValidateRequestAsync(http);
                }
                return await next(context);
            }
            catch (AntiforgeryValidationException) { return Fault(http, 400, "AntiforgeryFailed"); }
            catch (InventoryFault error) { return Fault(http, error.Status, error.Code); }
            catch (Exception error) when (error is JsonException or ArgumentException or IOException) { return Fault(http, 400, "InvalidInput"); }
        });
        owner.MapGet("", async (Transfers.TransferRelay relay, HttpContext http) => Results.Ok(await relay.ListAsync(http.RequestAborted)));
        owner.MapPost("", async (JsonElement body, HttpContext http, Transfers.TransferRelay relay) =>
        {
            var transfer = await relay.CreateAsync(Read<TransferCreateRequest>(body), Idempotency(http), http.RequestAborted);
            return Results.Created($"/api/control/v1/transfers/{transfer.TransferId}", transfer);
        });
        owner.MapGet("/{id:guid}", async (Guid id, Transfers.TransferRelay relay, HttpContext http) => Results.Ok(await relay.GetAsync(id, null, http.RequestAborted)));
        owner.MapPut("/{id:guid}/chunks/{offset:long}", async (Guid id, long offset, HttpContext http, Transfers.TransferRelay relay) =>
            Results.Ok(await relay.PutChunkAsync(id, null, offset, RequireChunkHash(http), http.Request.Body, http.RequestAborted)));
        owner.MapPost("/{id:guid}/complete", async (Guid id, JsonElement body, HttpContext http, Transfers.TransferRelay relay) =>
            Results.Ok(await relay.CompleteAsync(id, null, Read<TransferCompleteRequest>(body), http.RequestAborted)));
        owner.MapGet("/{id:guid}/content", async (Guid id, HttpContext http, Transfers.TransferRelay relay) => await WriteRangeAsync(http, relay, id, null));

        var agent = app.MapGroup("/api/agent/v1/transfers").RequireAuthorization("AgentOnly");
        agent.AddEndpointFilter(async (context, next) =>
        {
            var http = context.HttpContext;
            try
            {
                if (!Guid.TryParse(http.Request.Headers["X-Control-Connection-Epoch"], out var epoch)) throw new InventoryFault(409, "StateConflict");
                await http.RequestServices.GetRequiredService<AgentProtocolService>().RequireAsync(AgentIdentity.From(http.User), epoch);
                return await next(context);
            }
            catch (InventoryFault error) { return Fault(http, error.Status, error.Code); }
            catch (Exception error) when (error is JsonException or ArgumentException or IOException) { return Fault(http, 400, "InvalidInput"); }
        });
        agent.MapGet("/{id:guid}", async (Guid id, HttpContext http, Transfers.TransferRelay relay) =>
            Results.Ok(await relay.GetAsync(id, AgentIdentity.From(http.User).HostId, http.RequestAborted)));
        agent.MapPost("/{id:guid}/prepare", async (Guid id, JsonElement body, HttpContext http, Transfers.TransferRelay relay) =>
            Results.Ok(await relay.PrepareAsync(id, AgentIdentity.From(http.User).HostId, Read<TransferPrepareRequest>(body), http.RequestAborted)));
        agent.MapPut("/{id:guid}/chunks/{offset:long}", async (Guid id, long offset, HttpContext http, Transfers.TransferRelay relay) =>
            Results.Ok(await relay.PutChunkAsync(id, AgentIdentity.From(http.User).HostId, offset, RequireChunkHash(http), http.Request.Body, http.RequestAborted)));
        agent.MapPost("/{id:guid}/complete", async (Guid id, JsonElement body, HttpContext http, Transfers.TransferRelay relay) =>
            Results.Ok(await relay.CompleteAsync(id, AgentIdentity.From(http.User).HostId, Read<TransferCompleteRequest>(body), http.RequestAborted)));
        agent.MapGet("/{id:guid}/content", async (Guid id, HttpContext http, Transfers.TransferRelay relay) =>
            await WriteRangeAsync(http, relay, id, AgentIdentity.From(http.User).HostId));
    }

    private static string RequireChunkHash(HttpContext http)
    {
        if (http.Request.ContentLength > Transfers.TransferRelay.ChunkSize)
            throw new InventoryFault(413, "TransferTooLarge");
        if (http.Request.ContentType != "application/octet-stream" || http.Request.ContentLength == 0)
            throw new InventoryFault(400, "InvalidInput");
        return http.Request.Headers["X-Chunk-Sha256"].ToString();
    }

    private static void RequireOwnerOrigin(HttpContext http, string? origin)
    {
        if (!string.Equals(http.Request.Headers.Origin, origin?.TrimEnd('/'), StringComparison.Ordinal))
            throw new InventoryFault(403, "OriginMismatch");
    }

    private static async Task WriteRangeAsync(HttpContext http, Transfers.TransferRelay relay, Guid id, Guid? hostId)
    {
        try
        {
            var raw = http.Request.Headers.Range.ToString();
            var parts = raw.StartsWith("bytes=", StringComparison.Ordinal) && !raw.Contains(',') ? raw[6..].Split('-', 2) : [];
            if (parts.Length != 2 ||
                !long.TryParse(parts[0], out var start) || !long.TryParse(parts[1], out var end))
                throw new InventoryFault(416, "InvalidRange");
            var range = await relay.OpenRangeAsync(id, hostId, start, end, http.RequestAborted);
            await using var file = range.Stream;
            http.Response.StatusCode = 206;
            http.Response.ContentType = "application/octet-stream";
            http.Response.Headers.ContentRange = $"bytes {start}-{end}/{range.Info.SizeBytes}";
            http.Response.Headers["X-Chunk-Sha256"] = range.ChunkSha256;
            http.Response.Headers.CacheControl = "no-store";
            http.Response.ContentLength = end - start + 1;
            var buffer = new byte[64 * 1024];
            var remaining = end - start + 1;
            while (remaining > 0)
            {
                var read = await file.ReadAsync(buffer.AsMemory(0, (int)Math.Min(buffer.Length, remaining)), http.RequestAborted);
                if (read == 0) throw new IOException("Transfer stage was truncated.");
                await http.Response.Body.WriteAsync(buffer.AsMemory(0, read), http.RequestAborted);
                remaining -= read;
            }
        }
        catch (InventoryFault error) { await Fault(http, error.Status, error.Code).ExecuteAsync(http); }
    }
}
