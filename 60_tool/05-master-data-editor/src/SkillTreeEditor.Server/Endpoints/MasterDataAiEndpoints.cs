using Microsoft.AspNetCore.Mvc;
using SkillTreeEditor.Server.Models;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Endpoints;

public static class MasterDataAiEndpoints
{
    public static IEndpointRouteBuilder MapMasterDataAiEndpoints(this IEndpointRouteBuilder endpoints)
    {
        var api = endpoints.MapGroup("/api/master-data/ai");
        api.AddEndpointFilter(async (context, next) =>
        {
            context.HttpContext.Response.Headers.CacheControl = "no-store";
            try { return await next(context); }
            catch (MasterDataAiException exception)
            { return Results.Problem(title: exception.Message, statusCode: exception.StatusCode); }
            catch (Exception exception) when (exception is ArgumentException or FileNotFoundException
                or DirectoryNotFoundException or UnauthorizedAccessException or IOException)
            { return Results.Problem(title: "AI に渡す設定、対象ファイル、原稿を確認してください。", statusCode: 400); }
        });
        api.MapGet("/settings", (MasterDataAiSettingsService service) => service.Read());
        api.MapPut("/settings", (MasterDataAiSettingsRequest request, MasterDataAiSettingsService service) => service.Update(request))
            .WithMetadata(new RequestSizeLimitAttribute(4096));
        api.MapPost("/proposals", (MasterDataAiRequest request, MasterDataAiService service, CancellationToken token) => service.GenerateAsync(request, token))
            .WithMetadata(new RequestSizeLimitAttribute(300_000));
        return endpoints;
    }
}
