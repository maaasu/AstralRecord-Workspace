using SkillTreeEditor.Server.Models;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Endpoints;

public static class ToolJobEndpoints
{
    public static IEndpointRouteBuilder MapToolJobEndpoints(this IEndpointRouteBuilder endpoints)
    {
        var api = endpoints.MapGroup("/api/tool-jobs");
        api.AddEndpointFilter(async (context, next) =>
        {
            context.HttpContext.Response.Headers.CacheControl = "no-store";
            try
            {
                return await next(context);
            }
            catch (ToolJobException error)
            {
                return Results.Problem(statusCode: error.StatusCode, detail: error.Message);
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException
                or System.Text.Json.JsonException or System.ComponentModel.Win32Exception)
            {
                return Results.Problem(statusCode: StatusCodes.Status503ServiceUnavailable,
                    detail: "ツール実行の記録にアクセスできないか、PowerShell を起動できません。");
            }
        });

        api.MapGet("/capabilities", (ToolJobService jobs) => Results.Ok(jobs.GetCapabilities()));
        api.MapGet("/maintenance-options", async (ToolJobService jobs, CancellationToken token)
            => Results.Ok(await jobs.GetMaintenanceOptionsAsync(token)));
        api.MapPost("/plans", async (ToolJobPlanRequest request, ToolJobService jobs, CancellationToken token)
            => Results.Ok(await jobs.CreatePlanAsync(request, token)));
        api.MapGet("/runs", (ToolJobService jobs) => Results.Ok(jobs.GetRuns()));
        api.MapGet("/runs/{id:guid}", (Guid id, ToolJobService jobs) => Results.Ok(jobs.GetRun(id)));
        api.MapPost("/runs", async (ToolJobStartRequest request, ToolJobService jobs, CancellationToken token) =>
        {
            var run = await jobs.StartAsync(request, token);
            return Results.Accepted($"/api/tool-jobs/runs/{run.Id}", run);
        });
        api.MapPost("/runs/direct", async (ToolJobDirectStartRequest request, ToolJobService jobs, CancellationToken token) =>
        {
            var run = await jobs.StartDirectAsync(request, token);
            return Results.Accepted($"/api/tool-jobs/runs/{run.Id}", run);
        });
        api.MapGet("/runs/{id:guid}/logs", (Guid id, long? after, ToolJobService jobs)
            => Results.Ok(jobs.GetLogs(id, after ?? 0)));
        api.MapPost("/runs/{id:guid}/input", (Guid id, ToolJobInputRequest request, ToolJobService jobs) =>
        {
            jobs.SubmitInput(id, request);
            return Results.NoContent();
        });
        return endpoints;
    }
}
