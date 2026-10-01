using System.Text;
using SkillTreeEditor.Server.Models;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Endpoints;

public static class MasterDataEndpoints
{
    public static IEndpointRouteBuilder MapMasterDataEndpoints(this IEndpointRouteBuilder endpoints)
    {
        var api = endpoints.MapGroup("/api/master-data");
        api.AddEndpointFilter(async (context, next) =>
        {
            try { return await next(context); }
            catch (MasterDataValidationException exception)
            { return Results.Json(exception.Report, statusCode: StatusCodes.Status422UnprocessableEntity); }
        });
        api.MapGet("/catalog", (MasterDataCatalog catalog, WorkspacePaths paths, CancellationToken token) =>
            catalog.ReadAsync(paths.Backups, token));
        api.MapGet("/files", (string? category, string? query, MasterDataService service, CancellationToken token) =>
            service.ListAsync(category, query, token));
        api.MapGet("/file", (string path, MasterDataService service, CancellationToken token) => service.ReadAsync(path, token));
        api.MapPut("/file", (MasterDataSaveRequest request, MasterDataService service, CancellationToken token) => service.SaveAsync(request, token));
        api.MapPost("/file", async (MasterDataCreateRequest request, MasterDataService service, CancellationToken token) =>
        {
            var document = await service.CreateAsync(request, token);
            return Results.Created("/api/master-data/file?path=" + Uri.EscapeDataString(document.Path), document);
        });
        api.MapPost("/copy", async (MasterDataCopyRequest request, MasterDataService service, CancellationToken token) =>
        {
            var document = await service.CopyAsync(request, token);
            return Results.Created("/api/master-data/file?path=" + Uri.EscapeDataString(document.Path), document);
        });
        api.MapDelete("/file", async (string path, string revision, MasterDataService service, CancellationToken token) =>
        {
            await service.DeleteAsync(path, revision, token);
            return Results.NoContent();
        });
        api.MapPost("/parse", (MasterDataParseRequest request, MasterDataCodec codec, MasterDataPaths paths) =>
        {
            paths.Resolve(request.Path);
            return codec.Parse(request.Path, request.Raw);
        });
        api.MapPost("/render", (MasterDataRenderRequest request, MasterDataCodec codec, MasterDataPaths paths) =>
        {
            paths.Resolve(request.Path);
            return codec.Render(request);
        });
        api.MapPost("/validate", (MasterDataParseRequest request, MasterDataValidation validation, CancellationToken token) =>
            validation.ValidateAsync(request.Path, request.Raw, token));
        api.MapGet("/references", (string path, MasterDataService service, CancellationToken token) => service.ReferencesAsync(path, token));
        api.MapGet("/candidates", (MasterDataService service, CancellationToken token) => service.CandidatesAsync(token));
        api.MapGet("/export", async (string path, MasterDataService service, CancellationToken token) =>
        {
            var document = await service.ReadAsync(path, token);
            return Results.File(Encoding.UTF8.GetBytes(document.Raw), document.Format == "json" ? "application/json" : "application/yaml", Path.GetFileName(path));
        });
        api.MapGet("/documentation", async (string path, MasterDataPaths paths, CancellationToken token) =>
        {
            if (!path.EndsWith("YAMLスキーマ定義.md", StringComparison.Ordinal))
                throw new ArgumentException("スキーマ定義書のパスを指定してください。");
            var full = paths.Resolve(path, directory: true);
            return Results.Ok(new { path, raw = await File.ReadAllTextAsync(full, Encoding.UTF8, token) });
        });
        return endpoints;
    }
}
