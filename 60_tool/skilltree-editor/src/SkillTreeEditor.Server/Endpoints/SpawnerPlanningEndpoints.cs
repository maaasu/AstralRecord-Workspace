using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Endpoints;

public static class SpawnerPlanningEndpoints
{
    public static IEndpointRouteBuilder MapSpawnerPlanningEndpoints(this IEndpointRouteBuilder endpoints)
    {
        var api = endpoints.MapGroup("/api/spawner-planning");
        api.MapGet("/worlds", (HttpContext context, SpawnerPlanningService service, CancellationToken token) =>
            service.ListWorldsAsync(context, token));
        api.MapGet("/manifest", (HttpContext context, string? worldId, SpawnerPlanningService service, CancellationToken token) =>
            service.ReadManifestAsync(context, worldId, token));
        api.MapGet("/cells", (HttpContext context, string? worldId, string? revision, SpawnerPlanningService service, CancellationToken token) =>
            service.ReadDataAsync(context, "cells", null, worldId, revision, token));
        api.MapGet("/regions", (HttpContext context, string? worldId, string? revision, SpawnerPlanningService service, CancellationToken token) =>
            service.ReadDataAsync(context, "regions", null, worldId, revision, token));
        api.MapGet("/proposals/{radius}", (HttpContext context, string radius, string? worldId, string? revision, SpawnerPlanningService service, CancellationToken token) =>
            service.ReadDataAsync(context, "proposals", radius, worldId, revision, token));
        api.MapGet("/terrain-detail", (HttpContext context, string? worldId, string? revision, SpawnerPlanningService service, CancellationToken token) =>
            service.ReadTerrainDetailAsync(context, worldId, revision, false, token));
        api.MapGet("/terrain-detail/image", (HttpContext context, string? worldId, string? revision, SpawnerPlanningService service, CancellationToken token) =>
            service.ReadTerrainDetailAsync(context, worldId, revision, true, token));
        return endpoints;
    }
}
