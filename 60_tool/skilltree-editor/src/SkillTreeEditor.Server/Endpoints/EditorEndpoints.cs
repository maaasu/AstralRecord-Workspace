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

        api.MapGet("/nodes", async (FilebaseRepository repository, CancellationToken token) =>
        {
            var documents = await repository.ReadNodesAsync(token);
            return Results.Ok(documents.Select(document => new
            {
                document.FileName,
                content = document.Content
            }));
        });
        api.MapGet("/nodes/{nodeId}", async (string nodeId, FilebaseRepository repository, CancellationToken token) =>
        {
            var document = await repository.GetNodeAsync(nodeId, token);
            return document is null ? Results.NotFound() : Results.Ok(document.Content);
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
                content = document.Content
            }));
        });
        api.MapGet("/structures/{structureId}", async (
            string structureId,
            FilebaseRepository repository,
            CancellationToken token) =>
        {
            var document = await repository.GetStructureAsync(structureId, token);
            return document is null ? Results.NotFound() : Results.Ok(document.Content);
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

        api.MapGet("/settings", (PluginConfigService config, CancellationToken token) => config.ReadAsync(token));
        api.MapPut("/settings", async (
            PluginSkillTreeSettings settings,
            PluginConfigService config,
            FilebaseRepository repository,
            ValidationService validation,
            WorkspaceMutationGate mutationGate,
            CancellationToken token) =>
        {
            await using var mutation = await mutationGate.EnterAsync(token);
            var structure = await repository.GetStructureAsync(settings.StructureId, token);
            if (structure is null)
            {
                return Results.BadRequest(new
                {
                    message = $"Structure '{settings.StructureId}' was not found."
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
            return Results.Ok(settings);
        });

        return endpoints;
    }

    private static async Task<IResult> CreateNodeAsync(
        JsonElement payload,
        FilebaseRepository repository,
        ValidationService validation,
        SchemaCatalog schemas,
        WorkspaceMutationGate mutationGate,
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
        return Results.Created($"/api/nodes/{JsonValueReader.String(created.Content["nodeId"])}", created.Content);
    }

    private static async Task<IResult> SaveNodeAsync(
        string nodeId,
        JsonElement payload,
        FilebaseRepository repository,
        ValidationService validation,
        WorkspaceMutationGate mutationGate,
        CancellationToken token)
    {
        var document = RequireObject(payload);
        await using var mutation = await mutationGate.EnterAsync(token);
        var existing = await repository.GetNodeAsync(nodeId, token);
        if (existing is null)
            return Results.NotFound();

        var report = await validation.ValidateNodeDocumentAsync(document, existing.FileName, token);
        if (!report.IsValid)
            return Results.Json(report, statusCode: StatusCodes.Status422UnprocessableEntity);

        var saved = await repository.SaveNodeAsync(nodeId, document, token);
        return Results.Ok(saved.Content);
    }

    private static async Task<IResult> DeleteNodeAsync(
        string nodeId,
        FilebaseRepository repository,
        WorkspaceMutationGate mutationGate,
        CancellationToken token)
    {
        await using var mutation = await mutationGate.EnterAsync(token);
        var structures = await repository.ReadStructuresAsync(token);
        var referencedBy = structures
            .Where(structure => ReferencesNode(structure.Content, nodeId))
            .Select(structure => JsonValueReader.String(structure.Content["structureId"]) ?? structure.FileName)
            .ToArray();
        if (referencedBy.Length > 0)
        {
            return Results.Conflict(new
            {
                message = $"Node '{nodeId}' is referenced by structures: {string.Join(", ", referencedBy)}"
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
        return Results.Created($"/api/structures/{structureId}", created.Content);
    }

    private static async Task<IResult> SaveStructureAsync(
        string structureId,
        JsonElement payload,
        FilebaseRepository repository,
        ValidationService validation,
        WorkspaceMutationGate mutationGate,
        CancellationToken token)
    {
        var document = RequireObject(payload);
        await using var mutation = await mutationGate.EnterAsync(token);
        var existing = await repository.GetStructureAsync(structureId, token);
        if (existing is null)
            return Results.NotFound();
        var report = await validation.ValidateStructureDocumentAsync(document, existing.FileName, token);
        if (!report.IsValid)
            return Results.Json(report, statusCode: StatusCodes.Status422UnprocessableEntity);

        var saved = await repository.SaveStructureAsync(structureId, document, token);
        return Results.Ok(saved.Content);
    }

    private static JsonObject RequireObject(JsonElement payload)
        => JsonNode.Parse(payload.GetRawText()) as JsonObject
           ?? throw new ArgumentException("Request JSON root must be an object.");

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
