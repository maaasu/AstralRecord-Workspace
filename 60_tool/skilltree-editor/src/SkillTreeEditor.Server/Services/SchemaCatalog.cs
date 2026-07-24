using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Json.Schema;
using SkillTreeEditor.Server.Models;

namespace SkillTreeEditor.Server.Services;

public sealed class SchemaCatalog(WorkspacePaths paths)
{
    public async Task<IReadOnlyList<SchemaSummary>> ListAsync(CancellationToken cancellationToken)
    {
        if (!Directory.Exists(paths.Schemas))
            return [];

        var schemas = new List<SchemaSummary>();
        foreach (var path in Directory.EnumerateFiles(paths.Schemas, "*.schema.json", SearchOption.TopDirectoryOnly)
                     .OrderBy(value => value, StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                var schema = await ReadObjectAsync(path, cancellationToken);
                schemas.Add(CreateSummary(Path.GetFileName(path), schema));
            }
            catch (Exception exception) when (
                exception is JsonException or InvalidDataException or IOException or UnauthorizedAccessException)
            {
                // A schema that is not referenced by the current document must not make
                // the complete editor catalog unusable. Referenced schemas are read and
                // reported explicitly by ValidateAsync below.
            }
        }

        var defaults = schemas
            .Where(schema => !string.Equals(schema.EntityKind, "generic", StringComparison.OrdinalIgnoreCase))
            .GroupBy(schema => schema.EntityKind, StringComparer.OrdinalIgnoreCase)
            .ToDictionary(
                group => group.Key,
                group => group
                    .OrderByDescending(schema => schema.Version ?? -1)
                    .ThenByDescending(schema => schema.FileName, StringComparer.OrdinalIgnoreCase)
                    .First().FileName,
                StringComparer.OrdinalIgnoreCase);

        return schemas
            .Select(schema => schema with
            {
                IsDefault = defaults.TryGetValue(schema.EntityKind, out var defaultFileName)
                            && string.Equals(schema.FileName, defaultFileName, StringComparison.OrdinalIgnoreCase)
            })
            .ToArray();
    }

    public async Task<JsonObject?> GetAsync(string fileName, CancellationToken cancellationToken)
    {
        SafePath.RequireIdentifier(fileName, nameof(fileName));
        if (!fileName.EndsWith(".schema.json", StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("Schema file names must end with .schema.json.", nameof(fileName));

        var path = SafePath.UnderRoot(paths.Schemas, fileName);
        return File.Exists(path) ? await ReadObjectAsync(path, cancellationToken) : null;
    }

    public async Task<IReadOnlyList<ValidationIssue>> ValidateAsync(
        string entityKind,
        JsonObject instance,
        string? file,
        CancellationToken cancellationToken)
    {
        string? referencedFileName;
        try
        {
            referencedFileName = GetReferencedFileName(instance);
        }
        catch (ArgumentException exception)
        {
            return
            [
                new ValidationIssue("SCHEMA_REFERENCE_INVALID", exception.Message, "error", file, "/$schema")
            ];
        }

        if (referencedFileName is null)
        {
            return
            [
                new ValidationIssue(
                    "SCHEMA_REFERENCE_REQUIRED",
                    "$schema must reference a schema file in the skilltree schemas directory.",
                    "error",
                    file,
                    "/$schema")
            ];
        }

        var schemaPath = SafePath.UnderRoot(paths.Schemas, referencedFileName);
        if (!File.Exists(schemaPath))
        {
            return
            [
                new ValidationIssue(
                    "SCHEMA_NOT_FOUND",
                    $"Referenced JSON Schema '{referencedFileName}' was not found in the skilltree schemas directory.",
                    "error",
                    file,
                    "/$schema")
            ];
        }

        string schemaText;
        JsonObject schemaObject;
        try
        {
            schemaText = await File.ReadAllTextAsync(schemaPath, Encoding.UTF8, cancellationToken);
            schemaObject = JsonNode.Parse(schemaText) as JsonObject
                           ?? throw new JsonException("JSON Schema root must be an object.");
        }
        catch (Exception exception) when (
            exception is JsonException or InvalidDataException or IOException or UnauthorizedAccessException)
        {
            return
            [
                new ValidationIssue(
                    "SCHEMA_INVALID",
                    $"Schema '{referencedFileName}' could not be read: {exception.Message}",
                    "error",
                    referencedFileName)
            ];
        }

        var summary = CreateSummary(referencedFileName, schemaObject);

        if (!string.Equals(summary.EntityKind, entityKind, StringComparison.OrdinalIgnoreCase))
        {
            return
            [
                new ValidationIssue(
                    "SCHEMA_KIND_MISMATCH",
                    $"Schema '{summary.FileName}' describes '{summary.EntityKind}', not '{entityKind}'.",
                    "error",
                    file,
                    "/$schema")
            ];
        }

        try
        {
            using var schemaDocument = JsonDocument.Parse(schemaText);
            var schema = JsonSchema.Build(
                schemaDocument.RootElement,
                new BuildOptions { SchemaRegistry = new SchemaRegistry() });
            using var instanceDocument = JsonDocument.Parse(instance.ToJsonString());
            var results = schema.Evaluate(
                instanceDocument.RootElement,
                new EvaluationOptions { OutputFormat = OutputFormat.List });
            if (results.IsValid)
                return [];

            var issues = new List<ValidationIssue>();
            foreach (var detail in (results.Details ?? []).Where(detail => !detail.IsValid))
            {
                if (detail.Errors is null || detail.Errors.Count == 0)
                    continue;

                foreach (var error in detail.Errors)
                {
                    issues.Add(new ValidationIssue(
                        "JSON_SCHEMA",
                        error.Value,
                        "error",
                        file,
                        detail.InstanceLocation.ToString()));
                }
            }

            if (issues.Count == 0)
                issues.Add(new ValidationIssue("JSON_SCHEMA", "The document does not satisfy its JSON Schema.", "error", file));
            return issues;
        }
        catch (Exception exception) when (exception is JsonException or JsonSchemaException or InvalidOperationException)
        {
            return
            [
                new ValidationIssue(
                    "SCHEMA_INVALID",
                    $"Schema '{summary.FileName}' could not be evaluated: {exception.Message}",
                    "error",
                    summary.FileName)
            ];
        }
    }

    public async Task<IReadOnlyList<ValidationIssue>> ValidateCatalogAsync(CancellationToken cancellationToken)
    {
        if (!Directory.Exists(paths.Schemas))
            return [];

        var issues = new List<ValidationIssue>();
        foreach (var path in Directory.EnumerateFiles(paths.Schemas, "*.schema.json", SearchOption.TopDirectoryOnly)
                     .OrderBy(value => value, StringComparer.OrdinalIgnoreCase))
        {
            var fileName = Path.GetFileName(path);
            try
            {
                var schemaText = await File.ReadAllTextAsync(path, Encoding.UTF8, cancellationToken);
                using var schemaDocument = JsonDocument.Parse(schemaText);
                _ = JsonSchema.Build(
                    schemaDocument.RootElement,
                    new BuildOptions { SchemaRegistry = new SchemaRegistry() });
            }
            catch (Exception exception) when (
                exception is JsonException
                    or JsonSchemaException
                    or InvalidOperationException
                    or IOException
                    or UnauthorizedAccessException)
            {
                issues.Add(new ValidationIssue(
                    "SCHEMA_INVALID",
                    $"Schema '{fileName}' is invalid: {exception.Message}",
                    "error",
                    fileName));
            }
        }

        return issues;
    }

    public async Task<SchemaSummary?> GetDefaultAsync(string entityKind, CancellationToken cancellationToken)
        => (await ListAsync(cancellationToken)).FirstOrDefault(schema =>
            schema.IsDefault
            && string.Equals(schema.EntityKind, entityKind, StringComparison.OrdinalIgnoreCase));

    private static async Task<JsonObject> ReadObjectAsync(string path, CancellationToken cancellationToken)
    {
        var text = await File.ReadAllTextAsync(path, Encoding.UTF8, cancellationToken);
        return JsonNode.Parse(text) as JsonObject
            ?? throw new InvalidDataException($"Schema root must be an object: {path}");
    }

    private static SchemaSummary CreateSummary(string fileName, JsonObject schema)
        => new(
            fileName,
            JsonValueReader.String(schema["$id"]),
            JsonValueReader.String(schema["title"]),
            GetEntityKind(fileName, schema),
            GetVersion(fileName, schema));

    internal static string? GetReferencedFileName(JsonObject instance)
    {
        var reference = JsonValueReader.String(instance["$schema"]);
        if (reference is null)
            return null;

        string fileName;
        if (Uri.TryCreate(reference, UriKind.Absolute, out var absoluteUri))
        {
            fileName = Path.GetFileName(Uri.UnescapeDataString(absoluteUri.AbsolutePath));
        }
        else
        {
            var normalized = reference.Replace('\\', '/');
            var queryOrFragment = normalized.IndexOfAny(['?', '#']);
            if (queryOrFragment >= 0)
                normalized = normalized[..queryOrFragment];
            fileName = normalized[(normalized.LastIndexOf('/') + 1)..];
        }

        SafePath.RequireIdentifier(fileName, "$schema");
        if (!fileName.EndsWith(".schema.json", StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("$schema must reference a file ending in .schema.json.", "$schema");
        return fileName;
    }

    private static string GetEntityKind(string fileName, JsonObject schema)
    {
        var declaredKind = JsonValueReader.String(schema["x-astralrecord-entityKind"])
                           ?? JsonValueReader.String(schema["x-astralrecord-entity"]);
        if (!string.IsNullOrWhiteSpace(declaredKind))
            return declaredKind.ToLowerInvariant();

        if (schema["properties"] is JsonObject properties)
        {
            if (properties.ContainsKey("structureId") && properties.ContainsKey("nodes") && properties.ContainsKey("edges"))
                return "structure";
            if (properties.ContainsKey("nodeId") && properties.ContainsKey("effects"))
                return "node";
        }

        if (fileName.Contains("structure", StringComparison.OrdinalIgnoreCase))
            return "structure";
        if (fileName.Contains("node", StringComparison.OrdinalIgnoreCase))
            return "node";
        return "generic";
    }

    private static int? GetVersion(string fileName, JsonObject schema)
    {
        if (schema["properties"] is JsonObject properties
            && properties["schemaVersion"] is JsonObject versionSchema)
        {
            var numeric = JsonValueReader.Number(versionSchema["const"]);
            if (numeric is >= 1 and <= int.MaxValue && numeric == Math.Truncate(numeric.Value))
                return (int)numeric.Value;
        }

        var match = System.Text.RegularExpressions.Regex.Match(
            fileName,
            @"\.v(?<version>[1-9][0-9]*)\.schema\.json$",
            System.Text.RegularExpressions.RegexOptions.IgnoreCase | System.Text.RegularExpressions.RegexOptions.CultureInvariant);
        return match.Success && int.TryParse(match.Groups["version"].Value, out var version) ? version : null;
    }
}
