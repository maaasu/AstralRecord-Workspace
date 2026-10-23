using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Http.Features;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Endpoints;

public static class BalanceLabEndpoints
{
    public static IEndpointRouteBuilder MapBalanceLabEndpoints(this IEndpointRouteBuilder endpoints)
    {
        var api = endpoints.MapGroup("/api/balance-lab");
        api.MapGet("/catalog", (BalanceLabCatalog catalog, CancellationToken token) => catalog.ReadAsync(token));
        api.MapPost("/jobs", async (HttpRequest request, BalanceLabJobs jobs, CancellationToken token) =>
        {
            var feature = request.HttpContext.Features.Get<IHttpMaxRequestBodySizeFeature>();
            if (feature is { IsReadOnly: false }) feature.MaxRequestBodySize = 1_000_000;
            if (request.ContentLength > 1_000_000) return Results.Problem("条件JSONは1MB以下にしてください。", statusCode: 413);
            try
            {
                var scenario = await JsonNode.ParseAsync(request.Body, documentOptions: new JsonDocumentOptions { MaxDepth = 40 }, cancellationToken: token) as JsonObject
                    ?? throw new ArgumentException("条件JSONをオブジェクトで指定してください。");
                return Results.Json(jobs.Start(scenario), statusCode: StatusCodes.Status202Accepted);
            }
            catch (JsonException) { return Results.Problem("条件JSONの形式が正しくありません。", statusCode: 400); }
        });
        api.MapGet("/jobs/{id}", (string id, BalanceLabJobs jobs) => jobs.Read(id));
        api.MapDelete("/jobs/{id}", (string id, BalanceLabJobs jobs) => jobs.Cancel(id));
        return endpoints;
    }
}
