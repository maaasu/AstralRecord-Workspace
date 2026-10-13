using System.Text.Json;
using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Models;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Endpoints;

public static class EditorEndpoints
{
    public static IEndpointRouteBuilder MapEditorEndpoints(this IEndpointRouteBuilder endpoints)
    {
        var api = endpoints.MapGroup("/api");

        api.MapGet("/metadata", (WorkspacePaths paths) => Results.Ok(paths.ToMetadata()));
        api.MapGet("/classes", async (WorkspacePaths paths, CancellationToken token) =>
            Results.Ok(await new ClassMasterCatalog(paths).ReadAllAsync(token)));
        api.MapGet("/skills", async (SkillMasterCatalog skills, CancellationToken token) =>
            Results.Ok(await skills.ReadAllAsync(token)));

        if (endpoints.ServiceProvider.GetService<MinecraftIconService>() is not null)
        {
            api.MapGet("/minecraft-icons/{material}", async (
                string material,
                bool? refresh,
                MinecraftIconService icons,
                CancellationToken token) =>
            {
                try
                {
                    var path = await icons.GetIconPathAsync(material, refresh ?? false, token);
                    return path is null
                        ? Results.NotFound(new { message = $"Minecraftアイコン '{material}' が見つかりません。" })
                        : Results.File(path, "image/png", enableRangeProcessing: false);
                }
                catch (HttpRequestException exception)
                {
                    return Results.Problem(
                        statusCode: StatusCodes.Status503ServiceUnavailable,
                        title: "MC Iconsに接続できません。",
                        detail: exception.Message);
                }
            });
        }

        api.MapGet("/nodes", async (FilebaseRepository repository, CancellationToken token) =>
        {
            var documents = await repository.ReadNodesAsync(token);
            return Results.Ok(documents.Select(document => new
            {
                document.FileName,
                document.Revision,
                content = document.Content
            }));
        });
        api.MapGet("/nodes/{nodeId}", async (string nodeId, FilebaseRepository repository, HttpContext context, CancellationToken token) =>
        {
            var document = await repository.GetNodeAsync(nodeId, token);
            if (document is null) return Results.NotFound();
            context.Response.Headers.ETag = EditorRevision.ETag(document.Revision);
            return Results.Ok(document.Content);
        });
        api.MapPost("/nodes", CreateNodeAsync);
        api.MapPut("/nodes/{nodeId}", SaveNodeAsync);
        api.MapDelete("/nodes/{nodeId}", DeleteNodeAsync);

        api.MapGet("/structures", async (FilebaseRepository repository, CancellationToken token) =>
        {
            var documents = await repository.ReadStructuresAsync(token);
            return Results.Ok(documents.Select(document => new
            {
                document.FileName,
                document.Revision,
                content = document.Content
            }));
        });
        api.MapGet("/structures/{structureId}", async (
            string structureId,
            FilebaseRepository repository,
            HttpContext context,
            CancellationToken token) =>
        {
            var document = await repository.GetStructureAsync(structureId, token);
            if (document is null) return Results.NotFound();
            context.Response.Headers.ETag = EditorRevision.ETag(document.Revision);
            return Results.Ok(document.Content);
        });
        api.MapPost("/structures", CreateStructureAsync);
        api.MapPut("/structures/{structureId}", SaveStructureAsync);

        api.MapGet("/schemas", (SchemaCatalog schemas, CancellationToken token) => schemas.ListAsync(token));
        api.MapGet("/schemas/{fileName}", async (string fileName, SchemaCatalog schemas, CancellationToken token) =>
        {
            var schema = await schemas.GetAsync(fileName, token);
            return schema is null ? Results.NotFound() : Results.Ok(schema);
        });

        api.MapGet("/validation", (ValidationService validation, CancellationToken token) => validation.ValidateAllAsync(token));
        api.MapPost("/validation/structure", async (
            JsonElement payload,
            string? existingStructureId,
            FilebaseRepository repository,
            ValidationService validation,
            CancellationToken token) =>
        {
            var document = RequireObject(payload);
            string? existingFileName = null;
            if (!string.IsNullOrWhiteSpace(existingStructureId))
            {
                var existing = await repository.GetStructureAsync(existingStructureId, token);
                if (existing is null)
                    return Results.NotFound();
                existingFileName = existing.FileName;
            }
            return Results.Ok(await validation.ValidateStructureDocumentAsync(document, existingFileName, token));
        });
        api.MapPost("/validation/node", async (
            JsonElement payload,
            ValidationService validation,
            CancellationToken token) =>
        {
            var document = RequireObject(payload);
            return Results.Ok(await validation.ValidateNodeDocumentAsync(document, null, token));
        });

        api.MapGet("/settings", async (PluginConfigService config, HttpContext context, CancellationToken token) =>
        {
            var settings = await config.ReadVersionedAsync(token);
            context.Response.Headers.ETag = EditorRevision.ETag(settings.Revision);
            return Results.Ok(settings.Content);
        });
        api.MapPut("/settings", async (
            PluginSkillTreeSettings settings,
            PluginConfigService config,
            FilebaseRepository repository,
            ValidationService validation,
            WorkspaceMutationGate mutationGate,
            HttpContext context,
            CancellationToken token) =>
        {
            await using var mutation = await mutationGate.EnterAsync(token);
            var current = await config.ReadVersionedAsync(token);
            if (!EditorRevision.Matches(context.Request, current.Revision)) return EditorRevision.Conflict();
            var structure = await repository.GetStructureAsync(settings.StructureId, token);
            if (structure is null)
            {
                return Results.BadRequest(new
                {
                    message = $"構造 '{settings.StructureId}' が見つかりません。"
                });
            }

            var report = await validation.ValidateStructureDocumentAsync(
                structure.Content,
                structure.FileName,
                token,
                settings);
            if (!report.IsValid)
                return Results.Json(report, statusCode: StatusCodes.Status422UnprocessableEntity);

            await config.SaveAsync(settings, token);
            var saved = await config.ReadVersionedAsync(token);
            context.Response.Headers.ETag = EditorRevision.ETag(saved.Revision);
            return Results.Ok(saved.Content);
        });

        return endpoints;
    }

    private static async Task<IResult> CreateNodeAsync(
        JsonElement payload,
        FilebaseRepository repository,
        ValidationService validation,
        SchemaCatalog schemas,
        WorkspaceMutationGate mutationGate,
        HttpContext context,
        CancellationToken token)
    {
        var document = RequireObject(payload);
        await using var mutation = await mutationGate.EnterAsync(token);
        document["nodeId"] = await repository.GetNextNodeIdAsync(token);
        await EnsureSchemaReferenceAsync(document, "node", schemas, token);
        var fileName = $"{JsonValueReader.String(document["nodeId"])}.json";
        var report = await validation.ValidateNodeDocumentAsync(document, fileName, token);
        if (!report.IsValid)
            return Results.Json(report, statusCode: StatusCodes.Status422UnprocessableEntity);

        var created = await repository.CreateNodeAsync(document, token);
        context.Response.Headers.ETag = EditorRevision.ETag(created.Revision);
        return Results.Created($"/api/nodes/{JsonValueReader.String(created.Content["nodeId"])}", created.Content);
    }

    private static async Task<IResult> SaveNodeAsync(
        string nodeId,
        JsonElement payload,
        FilebaseRepository repository,
        ValidationService validation,
        WorkspaceMutationGate mutationGate,
        HttpContext context,
        CancellationToken token)
    {
        var document = RequireObject(payload);
        await using var mutation = await mutationGate.EnterAsync(token);
        var existing = await repository.GetNodeAsync(nodeId, token);
        if (existing is null)
            return Results.NotFound();
        if (!EditorRevision.Matches(context.Request, existing.Revision)) return EditorRevision.Conflict();

        var report = await validation.ValidateNodeDocumentAsync(document, existing.FileName, token);
        if (!report.IsValid)
            return Results.Json(report, statusCode: StatusCodes.Status422UnprocessableEntity);

        var saved = await repository.SaveNodeAsync(nodeId, document, token);
        context.Response.Headers.ETag = EditorRevision.ETag(saved.Revision);
        return Results.Ok(saved.Content);
    }

    private static async Task<IResult> DeleteNodeAsync(
        string nodeId,
        FilebaseRepository repository,
        WorkspaceMutationGate mutationGate,
        HttpContext context,
        CancellationToken token)
    {
        await using var mutation = await mutationGate.EnterAsync(token);
        var existing = await repository.GetNodeAsync(nodeId, token);
        if (existing is null) return Results.NotFound();
        if (!EditorRevision.Matches(context.Request, existing.Revision)) return EditorRevision.Conflict();
        var structures = await repository.ReadStructuresAsync(token);
        var referencedBy = structures
            .Where(structure => ReferencesNode(structure.Content, nodeId))
            .Select(structure => JsonValueReader.String(structure.Content["structureId"]) ?? structure.FileName)
            .ToArray();
        if (referencedBy.Length > 0)
        {
            return Results.Conflict(new
            {
                message = $"ノード '{nodeId}' は構造から参照されているため削除できません: {string.Join(", ", referencedBy)}"
            });
        }

        await repository.DeleteNodeAsync(nodeId, token);
        return Results.NoContent();
    }

    private static async Task<IResult> CreateStructureAsync(
        JsonElement payload,
        FilebaseRepository repository,
        ValidationService validation,
        SchemaCatalog schemas,
        WorkspaceMutationGate mutationGate,
        HttpContext context,
        CancellationToken token)
    {
        var document = RequireObject(payload);
        await using var mutation = await mutationGate.EnterAsync(token);
        await EnsureSchemaReferenceAsync(document, "structure", schemas, token);
        var structureId = JsonValueReader.String(document["structureId"]);
        var fileName = string.IsNullOrWhiteSpace(structureId) ? null : $"{structureId}.json";
        var report = await validation.ValidateStructureDocumentAsync(document, fileName, token);
        if (!report.IsValid)
            return Results.Json(report, statusCode: StatusCodes.Status422UnprocessableEntity);

        var created = await repository.CreateStructureAsync(document, token);
        context.Response.Headers.ETag = EditorRevision.ETag(created.Revision);
        return Results.Created($"/api/structures/{structureId}", created.Content);
    }

    private static async Task<IResult> SaveStructureAsync(
        string structureId,
        JsonElement payload,
        FilebaseRepository repository,
        ValidationService validation,
        WorkspaceMutationGate mutationGate,
        HttpContext context,
        CancellationToken token)
    {
        var document = RequireObject(payload);
        await using var mutation = await mutationGate.EnterAsync(token);
        var existing = await repository.GetStructureAsync(structureId, token);
        if (existing is null)
            return Results.NotFound();
        if (!EditorRevision.Matches(context.Request, existing.Revision)) return EditorRevision.Conflict();
        var report = await validation.ValidateStructureDocumentAsync(document, existing.FileName, token);
        if (!report.IsValid)
            return Results.Json(report, statusCode: StatusCodes.Status422UnprocessableEntity);

        var saved = await repository.SaveStructureAsync(structureId, document, token);
        context.Response.Headers.ETag = EditorRevision.ETag(saved.Revision);
        return Results.Ok(saved.Content);
    }

    private static JsonObject RequireObject(JsonElement payload)
        => JsonNode.Parse(payload.GetRawText()) as JsonObject
           ?? throw new ArgumentException("リクエストJSONのルートはオブジェクトで記載してください。");

    private static async Task EnsureSchemaReferenceAsync(
        JsonObject document,
        string entityKind,
        SchemaCatalog schemas,
        CancellationToken token)
    {
        if (JsonValueReader.String(document["$schema"]) is not null)
            return;

        var schema = await schemas.GetDefaultAsync(entityKind, token);
        if (schema is not null)
            document["$schema"] = $"../schemas/{schema.FileName}";
    }

    private static bool ReferencesNode(JsonObject structure, string nodeId)
    {
        if (string.Equals(JsonValueReader.String(structure["rootNodeId"]), nodeId, StringComparison.Ordinal))
            return true;
        if (structure["nodes"] is JsonArray nodes && nodes.OfType<JsonObject>()
                .Any(node => string.Equals(JsonValueReader.String(node["nodeId"]), nodeId, StringComparison.Ordinal)))
            return true;
        return structure["edges"] is JsonArray edges && edges.OfType<JsonObject>().Any(edge =>
            string.Equals(JsonValueReader.String(edge["sourceNodeId"]), nodeId, StringComparison.Ordinal)
            || string.Equals(JsonValueReader.String(edge["targetNodeId"]), nodeId, StringComparison.Ordinal));
    }
}
