using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Endpoints;

public static class MasterAnalyticsEndpoints
{
    public static IEndpointRouteBuilder MapMasterAnalyticsEndpoints(this IEndpointRouteBuilder endpoints)
    {
        var api = endpoints.MapGroup("/api/master-analytics");
        api.MapGet("/catalog", (MasterAnalyticsService service, CancellationToken token) => service.ReadAsync(token));
        api.MapGet("/growth", (string? uuid, int? maxPlayerLevel, int? startPlayerLevel, int? startClassLevel, string? classIds, MasterAnalyticsService service, CancellationToken token) =>
            service.SimulateAsync(uuid, maxPlayerLevel ?? 100, startPlayerLevel ?? 1, startClassLevel ?? 1, classIds, token));
        return endpoints;
    }
}
