using AstralRecordControl.Web.Metrics;
using Microsoft.AspNetCore.Routing;

namespace AstralRecordControl.Web;

public static partial class ControlApplication
{
    private static void MapMetrics(RouteGroupBuilder owner)
    {
        owner.MapGet("/servers/{serverId:guid}/metrics", async (Guid serverId, DateTimeOffset fromUtc, DateTimeOffset toUtc,
            string granularity, MetricsStore metrics) => Results.Ok(await metrics.QueryServerAsync(serverId, fromUtc, toUtc, granularity)));
    }
}
