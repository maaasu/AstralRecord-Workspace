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
        api.MapGet("/catalog", (bool? reload, MasterDataCatalog catalog, WorkspacePaths paths, CancellationToken token) =>
            catalog.ReadAsync(paths.Backups, token, reload == true)).AllowMobileEdit();
        api.MapGet("/files", (string? category, string? query, bool? reload, MasterDataService service, CancellationToken token) =>
            service.ListAsync(category, query, token, reload == true)).AllowMobileEdit();
        api.MapGet("/filter-index", (bool? reload, MasterDataFilterIndexService service, CancellationToken token) =>
            service.ReadAsync(token, reload == true)).AllowMobileEdit();
        api.MapGet("/prediction-index", async (FilebaseSnapshotCache snapshots, CancellationToken token) =>
        {
            var index = MasterDataPredictionIndex.Build(await snapshots.GetAsync(token, includeSchemas: false), token);
            return index is null
                ? Results.Problem(title: "既存マスターの候補が索引の上限を超えました。", statusCode: StatusCodes.Status503ServiceUnavailable)
                : Results.Ok(index);
        }).AllowMobileEdit();
        api.MapGet("/progression-design", (bool? reload, ProgressionDesignService service, CancellationToken token) =>
            service.ReadAsync(token, reload == true));
        api.MapPost("/progression-design/preview", (ProgressionDesignPreviewRequest request, ProgressionDesignService service, CancellationToken token) =>
            service.PreviewAsync(request, token));
        api.MapGet("/game-flow/context", (bool? reload, GameFlowService service, CancellationToken token) =>
            service.ReadContextAsync(token, reload == true));
        api.MapGet("/file", (string path, MasterDataService service, CancellationToken token) => service.ReadAsync(path, token)).AllowMobileEdit();
        api.MapPut("/file", (MasterDataSaveRequest request, MasterDataService service, CancellationToken token) => service.SaveAsync(request, token)).AllowMobileEdit();
        api.MapPost("/file", async (MasterDataCreateRequest request, MasterDataService service, CancellationToken token) =>
        {
            var document = await service.CreateAsync(request, token);
            return Results.Created("/api/master-data/file?path=" + Uri.EscapeDataString(document.Path), document);
        }).AllowMobileEdit();
        api.MapPost("/copy", async (MasterDataCopyRequest request, MasterDataService service, CancellationToken token) =>
        {
            var document = await service.CopyAsync(request, token);
            return Results.Created("/api/master-data/file?path=" + Uri.EscapeDataString(document.Path), document);
        }).AllowMobileEdit();
        api.MapDelete("/file", async (string path, string revision, MasterDataService service, CancellationToken token) =>
        {
            await service.DeleteAsync(path, revision, token);
            return Results.NoContent();
        }).AllowMobileEdit();
        api.MapPost("/parse", (MasterDataParseRequest request, MasterDataCodec codec, MasterDataPaths paths) =>
        {
            paths.Resolve(request.Path);
            return codec.Parse(request.Path, request.Raw);
        }).AllowMobileEdit();
        api.MapPost("/render", (MasterDataRenderRequest request, MasterDataCodec codec, MasterDataPaths paths) =>
        {
            paths.Resolve(request.Path);
            return codec.Render(request);
        }).AllowMobileEdit();
        api.MapPost("/validate", (MasterDataParseRequest request, MasterDataValidation validation, CancellationToken token) =>
            validation.ValidateAsync(request.Path, request.Raw, token)).AllowMobileEdit();
        api.MapGet("/references", (string path, MasterDataService service, CancellationToken token) => service.ReferencesAsync(path, token)).AllowMobileEdit();
        api.MapGet("/candidates", (MasterDataService service, CancellationToken token) => service.CandidatesAsync(token)).AllowMobileEdit();
        api.MapGet("/export", async (string path, MasterDataService service, CancellationToken token) =>
        {
            var document = await service.ReadAsync(path, token);
            return Results.File(Encoding.UTF8.GetBytes(document.Raw), document.Format == "json" ? "application/json" : "application/yaml", Path.GetFileName(path));
        }).AllowMobileEdit();
        api.MapGet("/documentation", async (string path, MasterDataPaths paths, CancellationToken token) =>
        {
            if (!path.EndsWith("YAMLスキーマ定義.md", StringComparison.Ordinal))
                throw new ArgumentException("スキーマ定義書のパスを指定してください。");
            var full = paths.Resolve(path, directory: true);
            return Results.Ok(new { path, raw = await File.ReadAllTextAsync(full, Encoding.UTF8, token) });
        }).AllowMobileEdit();
        return endpoints;
    }
}
