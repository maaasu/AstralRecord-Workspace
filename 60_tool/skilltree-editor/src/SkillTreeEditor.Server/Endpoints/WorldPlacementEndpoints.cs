using SkillTreeEditor.Server.Services;
using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Endpoints;

public static class WorldPlacementEndpoints
{
    public static IEndpointRouteBuilder MapWorldPlacementEndpoints(this IEndpointRouteBuilder endpoints)
    {
        var group = endpoints.MapGroup("/api/world-placements");
        group.MapGet("/dev", (HttpContext context, WorldPlacementProxyService proxy, CancellationToken token) => proxy.ForwardAsync(context, false, token));
        group.MapPut("/dev", (HttpContext context, WorldPlacementProxyService proxy, CancellationToken token) => proxy.ForwardAsync(context, true, token));
        group.MapGet("/catalog", async (MasterDataService masters, CancellationToken token) =>
        {
            var spawners = await masters.ListAsync("41.features.mob.spawner", null, token);
            var mobs = await masters.ListAsync("40.features.mob", null, token);
            var spawnerEntries = new List<object>();
            foreach (var item in spawners.Where(item => item.Id is not null))
            {
                var document = await masters.ReadAsync(item.Path, token);
                var radius = document.Content is JsonObject content && int.TryParse(content["radiusMeters"]?.ToString(), out var value) ? value : 32;
                spawnerEntries.Add(new { id = item.Id, name = item.Name ?? item.Id, radiusMeters = radius });
            }
            var npcEntries = new List<object>();
            foreach (var item in mobs.Where(item => item.Id is not null))
            {
                var document = await masters.ReadAsync(item.Path, token);
                if (document.Content is JsonObject content && content["category"]?.ToString() == "NPC")
                    npcEntries.Add(new { id = item.Id, name = item.Name ?? item.Id });
            }
            return Results.Ok(new { spawners = spawnerEntries, npcs = npcEntries });
        });
        return endpoints;
    }
}
