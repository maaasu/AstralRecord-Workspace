using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Endpoints;

public static class SpawnerPlanningEndpoints
{
    public static IEndpointRouteBuilder MapSpawnerPlanningEndpoints(this IEndpointRouteBuilder endpoints)
    {
        var api = endpoints.MapGroup("/api/spawner-planning");
        api.MapGet("/manifest", (HttpContext context, SpawnerPlanningService service, CancellationToken token) => service.ReadManifestAsync(context, token));
        api.MapGet("/cells", (HttpContext context, string? revision, SpawnerPlanningService service, CancellationToken token) =>
            service.ReadDataAsync(context, "cells", null, revision, token));
        api.MapGet("/regions", (HttpContext context, string? revision, SpawnerPlanningService service, CancellationToken token) =>
            service.ReadDataAsync(context, "regions", null, revision, token));
        api.MapGet("/proposals/{radius}", (HttpContext context, string radius, string? revision, SpawnerPlanningService service, CancellationToken token) =>
            service.ReadDataAsync(context, "proposals", radius, revision, token));
        return endpoints;
    }
}
