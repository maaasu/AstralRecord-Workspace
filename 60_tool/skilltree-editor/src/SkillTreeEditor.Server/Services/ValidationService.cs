using System.Globalization;
using System.Numerics;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using SkillTreeEditor.Server.Models;

namespace SkillTreeEditor.Server.Services;

public sealed class ValidationService(
    WorkspacePaths paths,
    SchemaCatalog schemaCatalog,
    PluginConfigService pluginConfig)
{
    public async Task<ValidationReport> ValidateAllAsync(CancellationToken cancellationToken)
    {
        var report = new ValidationReport();
        var schemaCatalogIssues = await schemaCatalog.ValidateCatalogAsync(cancellationToken);
        report.Merge(schemaCatalogIssues);
        var invalidSchemaFiles = schemaCatalogIssues
            .Where(issue => string.Equals(issue.Code, "SCHEMA_INVALID", StringComparison.Ordinal))
            .Select(issue => issue.File)
            .Where(file => !string.IsNullOrWhiteSpace(file))
            .Select(file => file!)
            .ToHashSet(StringComparer.OrdinalIgnoreCase);
        var nodes = await LoadDirectoryAsync(paths.Nodes, "NODE_JSON_INVALID", report, cancellationToken);
        var structures = await LoadDirectoryAsync(paths.Structures, "STRUCTURE_JSON_INVALID", report, cancellationToken);
        var sequence = await LoadNodeIdSequenceAsync(report, cancellationToken);

        foreach (var duplicate in nodes
                     .Select(document => (Document: document, Id: JsonValueReader.String(document.Content["nodeId"])))
                     .Where(value => !string.IsNullOrWhiteSpace(value.Id))
                     .GroupBy(value => value.Id!, StringComparer.Ordinal)
                     .Where(group => group.Count() > 1))
        {
            report.AddError(
                "DUPLICATE_NODE_ID",
                $"nodeId '{duplicate.Key}' is defined by multiple files: {string.Join(", ", duplicate.Select(value => value.Document.FileName))}");
        }

        foreach (var duplicate in structures
                     .Select(document => (Document: document, Id: JsonValueReader.String(document.Content["structureId"])))
                     .Where(value => !string.IsNullOrWhiteSpace(value.Id))
                     .GroupBy(value => value.Id!, StringComparer.Ordinal)
                     .Where(group => group.Count() > 1))
        {
            report.AddError(
                "DUPLICATE_STRUCTURE_ID",
                $"structureId '{duplicate.Key}' is defined by multiple files: {string.Join(", ", duplicate.Select(value => value.Document.FileName))}");
        }

        foreach (var node in nodes)
        {
            MergeSchemaIssues(
                report,
                await schemaCatalog.ValidateAsync("node", node.Content, node.FileName, cancellationToken),
                invalidSchemaFiles);
            ValidateNodeShape(node.Content, node.FileName, report);
            ValidateDocumentFileName(node.Content, "nodeId", node.FileName, "NODE_FILE_NAME_MISMATCH", report);
        }

        if (sequence is not null)
        {
            MergeSchemaIssues(
                report,
                await schemaCatalog.ValidateAsync(
                    "generic",
                    sequence,
                    Path.GetFileName(paths.NodeIdSequence),
                    cancellationToken),
                invalidSchemaFiles);
            ValidateNodeIdSequence(sequence, nodes, report);
        }

        var masterNodeIds = nodes
            .Select(document => JsonValueReader.String(document.Content["nodeId"]))
            .Where(value => !string.IsNullOrWhiteSpace(value))
            .Select(value => value!)
            .ToHashSet(StringComparer.Ordinal);
        var displaySettings = await TryReadDisplaySettingsAsync(report, cancellationToken);
        foreach (var structure in structures)
        {
            MergeSchemaIssues(
                report,
                await schemaCatalog.ValidateAsync("structure", structure.Content, structure.FileName, cancellationToken),
                invalidSchemaFiles);
            ValidateStructureShape(structure.Content, structure.FileName, masterNodeIds, report, displaySettings);
            ValidateDocumentFileName(
                structure.Content,
                "structureId",
                structure.FileName,
                "STRUCTURE_FILE_NAME_MISMATCH",
                report);
        }

        return report;
    }

    private static void MergeSchemaIssues(
        ValidationReport report,
        IEnumerable<ValidationIssue> issues,
        IReadOnlySet<string> catalogInvalidSchemaFiles)
    {
        report.Merge(issues.Where(issue =>
            !string.Equals(issue.Code, "SCHEMA_INVALID", StringComparison.Ordinal)
            || string.IsNullOrWhiteSpace(issue.File)
            || !catalogInvalidSchemaFiles.Contains(issue.File)));
    }

    public async Task<ValidationReport> ValidateNodeDocumentAsync(
        JsonObject candidate,
        string? existingFileName,
        CancellationToken cancellationToken)
    {
        var report = new ValidationReport();
        report.Merge(await schemaCatalog.ValidateAsync("node", candidate, existingFileName, cancellationToken));
        ValidateNodeShape(candidate, existingFileName, report);

        var candidateId = JsonValueReader.String(candidate["nodeId"]);
        if (!string.IsNullOrWhiteSpace(candidateId))
        {
            ValidateDocumentFileName(candidate, "nodeId", existingFileName, "NODE_FILE_NAME_MISMATCH", report);
            var stored = await LoadDirectoryAsync(paths.Nodes, "NODE_JSON_INVALID", report, cancellationToken);
            var conflicts = stored.Where(document =>
                    !string.Equals(document.FileName, existingFileName, StringComparison.OrdinalIgnoreCase)
                    && string.Equals(JsonValueReader.String(document.Content["nodeId"]), candidateId, StringComparison.Ordinal))
                .Select(document => document.FileName)
                .ToArray();
            if (conflicts.Length > 0)
            {
                report.AddError(
                    "DUPLICATE_NODE_ID",
                    $"nodeId '{candidateId}' already exists in {string.Join(", ", conflicts)}.",
                    existingFileName,
                    "/nodeId");
            }
        }

        return report;
    }

    public async Task<ValidationReport> ValidateStructureDocumentAsync(
        JsonObject candidate,
        string? existingFileName,
        CancellationToken cancellationToken,
        PluginSkillTreeSettings? displaySettingsOverride = null)
    {
        var report = new ValidationReport();
        report.Merge(await schemaCatalog.ValidateAsync("structure", candidate, existingFileName, cancellationToken));

        var nodes = await LoadDirectoryAsync(paths.Nodes, "NODE_JSON_INVALID", report, cancellationToken);
        var masterNodeIds = nodes
            .Select(document => JsonValueReader.String(document.Content["nodeId"]))
            .Where(value => !string.IsNullOrWhiteSpace(value))
            .Select(value => value!)
            .ToHashSet(StringComparer.Ordinal);
        var displaySettings = displaySettingsOverride ?? await TryReadDisplaySettingsAsync(report, cancellationToken);
        ValidateStructureShape(candidate, existingFileName, masterNodeIds, report, displaySettings);

        var structureId = JsonValueReader.String(candidate["structureId"]);
        if (!string.IsNullOrWhiteSpace(structureId))
        {
            ValidateDocumentFileName(
                candidate,
                "structureId",
                existingFileName,
                "STRUCTURE_FILE_NAME_MISMATCH",
                report);
            var structures = await LoadDirectoryAsync(paths.Structures, "STRUCTURE_JSON_INVALID", report, cancellationToken);
            if (structures.Any(document =>
                    !string.Equals(document.FileName, existingFileName, StringComparison.OrdinalIgnoreCase)
                    && string.Equals(JsonValueReader.String(document.Content["structureId"]), structureId, StringComparison.Ordinal)))
            {
                report.AddError(
                    "DUPLICATE_STRUCTURE_ID",
                    $"structureId '{structureId}' already exists.",
                    existingFileName,
                    "/structureId");
            }
        }

        return report;
    }

    public static void ValidateNodeShape(JsonObject node, string? fileName, ValidationReport report)
    {
        RequireString(node, "$schema", fileName, report);
        RequireSchemaVersion(node, fileName, report);
        var nodeId = RequireString(node, "nodeId", fileName, report);
        if (nodeId is not null)
        {
            try
            {
                SafePath.RequireIdentifier(nodeId, "nodeId");
            }
            catch (ArgumentException exception)
            {
                report.AddError("NODE_ID_INVALID", exception.Message, fileName, "/nodeId");
            }
        }

        RequireString(node, "name", fileName, report);
        if (!node.ContainsKey("icon"))
            report.AddError("NODE_FIELD_REQUIRED", "icon is required.", fileName, "/icon");
        RequireArray(node, "lore", fileName, report);
        RequireArray(node, "tags", fileName, report);
        RequireString(node, "pointType", fileName, report);
        var pointCost = RequireInt32(node, "pointCost", fileName, report);
        if (pointCost < 0)
            report.AddError("POINT_COST_INVALID", "pointCost must not be negative.", fileName, "/pointCost");
        RequireArray(node, "effects", fileName, report);
    }

    public static void ValidateStructureShape(
        JsonObject structure,
        string? fileName,
        IReadOnlySet<string> masterNodeIds,
        ValidationReport report,
        PluginSkillTreeSettings? displaySettings = null)
    {
        RequireString(structure, "$schema", fileName, report);
        RequireSchemaVersion(structure, fileName, report);
        var structureId = RequireString(structure, "structureId", fileName, report);
        if (structureId is not null)
        {
            if (!Regex.IsMatch(structureId, "^[a-z0-9][a-z0-9_-]*$", RegexOptions.CultureInvariant))
                report.AddError(
                    "STRUCTURE_ID_INVALID",
                    "structureId may contain only lowercase letters, digits, '_' and '-'.",
                    fileName,
                    "/structureId");
        }

        RequireString(structure, "name", fileName, report);
        var rootNodeId = RequireString(structure, "rootNodeId", fileName, report);
        if (structure["nodes"] is not JsonArray placements)
        {
            report.AddError("STRUCTURE_FIELD_REQUIRED", "nodes must be an array.", fileName, "/nodes");
            placements = [];
        }
        if (structure["edges"] is not JsonArray edges)
        {
            report.AddError("STRUCTURE_FIELD_REQUIRED", "edges must be an array.", fileName, "/edges");
            edges = [];
        }

        var placementIds = new HashSet<string>(StringComparer.Ordinal);
        var coordinateKeys = new HashSet<string>(StringComparer.Ordinal);
        for (var index = 0; index < placements.Count; index++)
        {
            if (placements[index] is not JsonObject placement)
            {
                report.AddError("PLACEMENT_INVALID", "Each placement must be an object.", fileName, $"/nodes/{index}");
                continue;
            }

            var nodeId = JsonValueReader.String(placement["nodeId"]);
            if (string.IsNullOrWhiteSpace(nodeId))
            {
                report.AddError("PLACEMENT_NODE_REQUIRED", "Placement nodeId is required.", fileName, $"/nodes/{index}/nodeId");
                continue;
            }

            if (!placementIds.Add(nodeId))
                report.AddError("DUPLICATE_PLACEMENT", $"nodeId '{nodeId}' is placed more than once.", fileName, $"/nodes/{index}");
            if (!masterNodeIds.Contains(nodeId))
                report.AddError("UNKNOWN_NODE_ID", $"Placement references unknown nodeId '{nodeId}'.", fileName, $"/nodes/{index}/nodeId");

            var x = ReadInt32(placement["x"]);
            var y = ReadInt32(placement["y"]);
            var z = ReadInt32(placement["z"]);
            if (x is null || y is null || z is null)
            {
                report.AddError("COORDINATE_INVALID", "x, y and z must be 32-bit integers.", fileName, $"/nodes/{index}");
                continue;
            }

            var coordinateKey = string.Join("|", x.Value, y.Value, z.Value);
            if (!coordinateKeys.Add(coordinateKey))
                report.AddError("DUPLICATE_COORDINATE", $"Coordinate ({x}, {y}, {z}) is used more than once.", fileName, $"/nodes/{index}");

            if (displaySettings is not null
                && string.Equals(displaySettings.StructureId, structureId, StringComparison.Ordinal)
                && (!FitsInt32((long)displaySettings.CenterX + x.Value)
                    || !FitsInt32((long)displaySettings.CenterY + y.Value)
                    || !FitsInt32((long)displaySettings.CenterZ + z.Value)))
            {
                report.AddError(
                    "ABSOLUTE_COORDINATE_OVERFLOW",
                    $"Center + relative coordinate for nodeId '{nodeId}' exceeds the 32-bit block-coordinate range.",
                    fileName,
                    $"/nodes/{index}");
            }
        }

        if (!string.IsNullOrWhiteSpace(rootNodeId))
        {
            if (!masterNodeIds.Contains(rootNodeId))
                report.AddError("ROOT_NODE_UNKNOWN", $"rootNodeId '{rootNodeId}' does not exist in the node master.", fileName, "/rootNodeId");
            if (!placementIds.Contains(rootNodeId))
                report.AddError("ROOT_NODE_NOT_PLACED", $"rootNodeId '{rootNodeId}' is not placed in the structure.", fileName, "/rootNodeId");
        }

        var edgeKeys = new HashSet<string>(StringComparer.Ordinal);
        var adjacency = placementIds.ToDictionary(nodeId => nodeId, _ => new HashSet<string>(StringComparer.Ordinal), StringComparer.Ordinal);
        for (var index = 0; index < edges.Count; index++)
        {
            if (edges[index] is not JsonObject edge)
            {
                report.AddError("EDGE_INVALID", "Each edge must be an object.", fileName, $"/edges/{index}");
                continue;
            }

            var source = JsonValueReader.String(edge["sourceNodeId"]);
            var target = JsonValueReader.String(edge["targetNodeId"]);
            if (string.IsNullOrWhiteSpace(source) || string.IsNullOrWhiteSpace(target))
            {
                report.AddError("EDGE_ENDPOINT_REQUIRED", "sourceNodeId and targetNodeId are required.", fileName, $"/edges/{index}");
                continue;
            }

            if (string.Equals(source, target, StringComparison.Ordinal))
                report.AddError("SELF_EDGE", $"nodeId '{source}' cannot connect to itself.", fileName, $"/edges/{index}");

            var first = string.CompareOrdinal(source, target) <= 0 ? source : target;
            var second = string.CompareOrdinal(source, target) <= 0 ? target : source;
            if (!edgeKeys.Add(first + "\0" + second))
                report.AddError("DUPLICATE_EDGE", $"Edge '{first}' - '{second}' is duplicated.", fileName, $"/edges/{index}");

            if (!placementIds.Contains(source))
                report.AddError("EDGE_NODE_NOT_PLACED", $"Edge source '{source}' is not placed.", fileName, $"/edges/{index}/sourceNodeId");
            if (!placementIds.Contains(target))
                report.AddError("EDGE_NODE_NOT_PLACED", $"Edge target '{target}' is not placed.", fileName, $"/edges/{index}/targetNodeId");
            if (!masterNodeIds.Contains(source))
                report.AddError("UNKNOWN_NODE_ID", $"Edge source references unknown nodeId '{source}'.", fileName, $"/edges/{index}/sourceNodeId");
            if (!masterNodeIds.Contains(target))
                report.AddError("UNKNOWN_NODE_ID", $"Edge target references unknown nodeId '{target}'.", fileName, $"/edges/{index}/targetNodeId");

            if (!string.Equals(source, target, StringComparison.Ordinal)
                && adjacency.TryGetValue(source, out var sourceLinks)
                && adjacency.TryGetValue(target, out var targetLinks))
            {
                sourceLinks.Add(target);
                targetLinks.Add(source);
            }
        }

        if (!string.IsNullOrWhiteSpace(rootNodeId) && adjacency.ContainsKey(rootNodeId))
        {
            var visited = new HashSet<string>(StringComparer.Ordinal) { rootNodeId };
            var queue = new Queue<string>();
            queue.Enqueue(rootNodeId);
            while (queue.TryDequeue(out var current))
            {
                foreach (var neighbor in adjacency[current])
                {
                    if (visited.Add(neighbor))
                        queue.Enqueue(neighbor);
                }
            }

            foreach (var unreachable in placementIds.Except(visited, StringComparer.Ordinal).OrderBy(value => value, StringComparer.Ordinal))
            {
                report.AddError(
                    "UNREACHABLE_NODE",
                    $"nodeId '{unreachable}' is unreachable from rootNodeId '{rootNodeId}'.",
                    fileName,
                    "/nodes");
            }
        }
    }

    private static string? RequireString(JsonObject value, string property, string? fileName, ValidationReport report)
    {
        var result = JsonValueReader.String(value[property]);
        if (string.IsNullOrWhiteSpace(result))
            report.AddError("FIELD_REQUIRED", $"{property} must be a non-empty string.", fileName, $"/{property}");
        return result;
    }

    private static void RequireArray(JsonObject value, string property, string? fileName, ValidationReport report)
    {
        if (value[property] is not JsonArray)
            report.AddError("FIELD_REQUIRED", $"{property} must be an array.", fileName, $"/{property}");
    }

    private static int? RequireInt32(JsonObject value, string property, string? fileName, ValidationReport report)
    {
        var number = ReadInt32(value[property]);
        if (number is null)
            report.AddError("FIELD_REQUIRED", $"{property} must be a 32-bit integer.", fileName, $"/{property}");
        return number;
    }

    private static void RequireSchemaVersion(JsonObject value, string? fileName, ValidationReport report)
    {
        var version = ReadInt32(value["schemaVersion"]);
        if (version is null || version <= 0)
            report.AddError("SCHEMA_VERSION_INVALID", "schemaVersion must be a positive integer.", fileName, "/schemaVersion");
    }

    private async Task<PluginSkillTreeSettings?> TryReadDisplaySettingsAsync(
        ValidationReport report,
        CancellationToken cancellationToken)
    {
        try
        {
            return await pluginConfig.ReadAsync(cancellationToken);
        }
        catch (Exception exception) when (exception is FileNotFoundException or IOException or UnauthorizedAccessException)
        {
            report.AddWarning("PLUGIN_CONFIG_UNAVAILABLE", exception.Message, Path.GetFileName(paths.PluginConfig));
            return null;
        }
    }

    private static void ValidateDocumentFileName(
        JsonObject document,
        string idProperty,
        string? fileName,
        string code,
        ValidationReport report)
    {
        var id = JsonValueReader.String(document[idProperty]);
        if (string.IsNullOrWhiteSpace(id) || string.IsNullOrWhiteSpace(fileName))
            return;

        var expected = $"{id}.json";
        if (!string.Equals(expected, fileName, StringComparison.Ordinal))
        {
            report.AddError(
                code,
                $"File name must be '{expected}' for {idProperty} '{id}', but was '{fileName}'.",
                fileName,
                $"/{idProperty}");
        }
    }

    private static int? ReadInt32(JsonNode? value)
    {
        var number = JsonValueReader.Number(value);
        if (number is null
            || !double.IsFinite(number.Value)
            || number.Value != Math.Truncate(number.Value)
            || number.Value < int.MinValue
            || number.Value > int.MaxValue)
        {
            return null;
        }

        return (int)number.Value;
    }

    private static bool FitsInt32(long value) => value is >= int.MinValue and <= int.MaxValue;

    private async Task<JsonObject?> LoadNodeIdSequenceAsync(
        ValidationReport report,
        CancellationToken cancellationToken)
    {
        if (!File.Exists(paths.NodeIdSequence))
        {
            report.AddError(
                "NODE_ID_SEQUENCE_NOT_FOUND",
                $"Node ID sequence file does not exist: {paths.NodeIdSequence}",
                Path.GetFileName(paths.NodeIdSequence));
            return null;
        }

        try
        {
            var text = await File.ReadAllTextAsync(paths.NodeIdSequence, Encoding.UTF8, cancellationToken);
            return JsonNode.Parse(text) as JsonObject
                   ?? throw new JsonException("JSON root must be an object.");
        }
        catch (Exception exception) when (exception is JsonException or IOException or UnauthorizedAccessException)
        {
            report.AddError("NODE_ID_SEQUENCE_INVALID", exception.Message, Path.GetFileName(paths.NodeIdSequence));
            return null;
        }
    }

    private static void ValidateNodeIdSequence(
        JsonObject sequence,
        IEnumerable<StoredDocument> nodes,
        ValidationReport report)
    {
        var issuedText = JsonValueReader.String(sequence["lastIssuedNodeId"]);
        if (!TryParseDecimalId(issuedText, out var lastIssued))
            return;

        var maximumExisting = nodes
            .Select(node => JsonValueReader.String(node.Content["nodeId"]))
            .Select(value => TryParseDecimalId(value, out var parsed) ? parsed : (BigInteger?)null)
            .Where(value => value.HasValue)
            .Select(value => value!.Value)
            .DefaultIfEmpty(BigInteger.Zero)
            .Max();
        var minimumIssued = BigInteger.Max(new BigInteger(999), maximumExisting);
        if (lastIssued < minimumIssued)
        {
            report.AddError(
                "NODE_ID_SEQUENCE_BEHIND",
                $"lastIssuedNodeId '{lastIssued}' is lower than the required high-water '{minimumIssued}'.",
                "node-id-sequence.json",
                "/lastIssuedNodeId");
        }
    }

    private static bool TryParseDecimalId(string? value, out BigInteger parsed)
    {
        parsed = BigInteger.Zero;
        return !string.IsNullOrEmpty(value)
               && (value.Length == 1 || value[0] != '0')
               && value.All(character => character is >= '0' and <= '9')
               && BigInteger.TryParse(value, NumberStyles.None, CultureInfo.InvariantCulture, out parsed);
    }

    private static async Task<IReadOnlyList<StoredDocument>> LoadDirectoryAsync(
        string directory,
        string invalidCode,
        ValidationReport report,
        CancellationToken cancellationToken)
    {
        if (!Directory.Exists(directory))
        {
            report.AddWarning("DIRECTORY_NOT_FOUND", $"Directory does not exist yet: {directory}");
            return [];
        }

        var documents = new List<StoredDocument>();
        foreach (var path in Directory.EnumerateFiles(directory, "*.json", SearchOption.TopDirectoryOnly)
                     .OrderBy(value => value, StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                var text = await File.ReadAllTextAsync(path, Encoding.UTF8, cancellationToken);
                var content = JsonNode.Parse(text, documentOptions: new JsonDocumentOptions
                {
                    AllowTrailingCommas = false,
                    CommentHandling = JsonCommentHandling.Disallow
                }) as JsonObject;
                if (content is null)
                    throw new JsonException("JSON root must be an object.");
                documents.Add(new StoredDocument(Path.GetFileName(path), path, content));
            }
            catch (Exception exception) when (exception is JsonException or IOException or UnauthorizedAccessException)
            {
                report.AddError(invalidCode, exception.Message, Path.GetFileName(path));
            }
        }

        return documents;
    }
}
