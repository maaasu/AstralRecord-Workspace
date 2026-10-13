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
    PluginConfigService pluginConfig,
    MasterTagCatalog masterTagCatalog)
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
        var masterTags = await TryReadMasterTagsAsync(report, cancellationToken);

        foreach (var duplicate in nodes
                     .Select(document => (Document: document, Id: JsonValueReader.String(document.Content["nodeId"])))
                     .Where(value => !string.IsNullOrWhiteSpace(value.Id))
                     .GroupBy(value => value.Id!, StringComparer.Ordinal)
                     .Where(group => group.Count() > 1))
        {
            report.AddError(
                "DUPLICATE_NODE_ID",
                $"nodeId '{duplicate.Key}' が複数のファイルに定義されています: {string.Join(", ", duplicate.Select(value => value.Document.FileName))}");
        }
        ValidateUniqueSkillPermissions(nodes, report);

        foreach (var duplicate in structures
                     .Select(document => (Document: document, Id: JsonValueReader.String(document.Content["structureId"])))
                     .Where(value => !string.IsNullOrWhiteSpace(value.Id))
                     .GroupBy(value => value.Id!, StringComparer.Ordinal)
                     .Where(group => group.Count() > 1))
        {
            report.AddError(
                "DUPLICATE_STRUCTURE_ID",
                $"structureId '{duplicate.Key}' が複数のファイルに定義されています: {string.Join(", ", duplicate.Select(value => value.Document.FileName))}");
        }

        foreach (var node in nodes)
        {
            MergeSchemaIssues(
                report,
                await schemaCatalog.ValidateAsync("node", node.Content, node.FileName, cancellationToken),
                invalidSchemaFiles);
            ValidateNodeShape(node.Content, node.FileName, report);
            if (masterTags is not null)
                ValidateNodeTags(node.Content, node.FileName, masterTags, report);
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
        var masterTags = await TryReadMasterTagsAsync(report, cancellationToken);
        if (masterTags is not null)
            ValidateNodeTags(candidate, existingFileName, masterTags, report);

        var candidateId = JsonValueReader.String(candidate["nodeId"]);
        var stored = await LoadDirectoryAsync(paths.Nodes, "NODE_JSON_INVALID", report, cancellationToken);
        if (!string.IsNullOrWhiteSpace(candidateId))
        {
            ValidateDocumentFileName(candidate, "nodeId", existingFileName, "NODE_FILE_NAME_MISMATCH", report);
            var conflicts = stored.Where(document =>
                    !string.Equals(document.FileName, existingFileName, StringComparison.OrdinalIgnoreCase)
                    && string.Equals(JsonValueReader.String(document.Content["nodeId"]), candidateId, StringComparison.Ordinal))
                .Select(document => document.FileName)
                .ToArray();
            if (conflicts.Length > 0)
            {
                report.AddError(
                    "DUPLICATE_NODE_ID",
                    $"nodeId '{candidateId}' は {string.Join(", ", conflicts)} に既に存在します。",
                    existingFileName,
                    "/nodeId");
            }
        }

        var candidateFileName = existingFileName ?? "<new-node>";
        var skillPermissionDocuments = stored
            .Where(document => !string.Equals(document.FileName, existingFileName, StringComparison.OrdinalIgnoreCase))
            .Append(new StoredDocument(candidateFileName, string.Empty, candidate))
            .ToArray();
        ValidateUniqueSkillPermissions(skillPermissionDocuments, report);

        return report;
    }

    private static void ValidateUniqueSkillPermissions(
        IEnumerable<StoredDocument> nodes,
        ValidationReport report)
    {
        var definitions = new Dictionary<string, (string NodeId, string FileName)>(StringComparer.Ordinal);
        foreach (var document in nodes)
        {
            var nodeId = JsonValueReader.String(document.Content["nodeId"]) ?? "(unknown)";
            var classId = document.Content["unlockCondition"] is JsonObject unlockCondition
                ? JsonValueReader.String(unlockCondition["classId"])?.Trim().ToLowerInvariant() ?? string.Empty
                : string.Empty;
            if (document.Content["effects"] is not JsonArray effects)
                continue;

            for (var index = 0; index < effects.Count; index++)
            {
                if (effects[index] is not JsonObject effect
                    || !string.Equals(JsonValueReader.String(effect["type"]), "skill", StringComparison.Ordinal))
                {
                    continue;
                }

                var skillId = JsonValueReader.String(effect["skillId"])?.Trim().ToLowerInvariant();
                if (string.IsNullOrWhiteSpace(skillId))
                    continue;

                var key = classId + "\0" + skillId;
                if (definitions.TryAdd(key, (nodeId, document.FileName)))
                    continue;

                var previous = definitions[key];
                var condition = string.IsNullOrEmpty(classId)
                    ? "クラス条件なし"
                    : $"クラス '{classId}' の条件";
                report.AddError(
                    "DUPLICATE_SKILL_PERMISSION",
                    $"スキル使用権限 '{skillId}'（{condition}）がノード '{previous.NodeId}' ({previous.FileName}) と '{nodeId}' ({document.FileName}) で重複しています。",
                    document.FileName,
                    $"/effects/{index}/skillId");
            }
        }
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
                    $"structureId '{structureId}' は既に存在します。",
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
                report.AddError("NODE_ID_INVALID", $"nodeId の形式が不正です。詳細: {exception.Message}", fileName, "/nodeId");
            }
        }

        RequireString(node, "name", fileName, report);
        if (!node.ContainsKey("icon"))
            report.AddError("NODE_FIELD_REQUIRED", "icon は必須です。", fileName, "/icon");
        RequireArray(node, "tags", fileName, report);
        RequireString(node, "pointType", fileName, report);
        var pointCost = RequireInt32(node, "pointCost", fileName, report);
        if (pointCost < 0)
            report.AddError("POINT_COST_INVALID", "pointCost に負の値は指定できません。", fileName, "/pointCost");
        RequireArray(node, "effects", fileName, report);
    }

    private static void ValidateNodeTags(
        JsonObject node,
        string? fileName,
        IReadOnlyDictionary<string, MasterTagSummary> masterTags,
        ValidationReport report)
    {
        if (node["tags"] is not JsonArray tags)
            return;

        for (var index = 0; index < tags.Count; index++)
        {
            var tagId = JsonValueReader.String(tags[index]);
            if (string.IsNullOrWhiteSpace(tagId))
                continue;
            if (!masterTags.TryGetValue(tagId, out var definition))
            {
                report.AddError(
                    "UNKNOWN_MASTER_TAG",
                    $"タグ '{tagId}' は共有マスタータグカタログに定義されていません。",
                    fileName,
                    $"/tags/{index}");
                continue;
            }

            if (!definition.AppliesTo.Contains("SKILLTREE_NODE", StringComparer.Ordinal))
            {
                report.AddError(
                    "MASTER_TAG_TARGET_INVALID",
                    $"タグ '{tagId}' はスキルツリーのノードには使用できません。",
                    fileName,
                    $"/tags/{index}");
            }
        }
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
                    "structureId は英小文字・数字で始め、英小文字・数字・'_'・'-' のみ使用できます。",
                    fileName,
                    "/structureId");
        }

        RequireString(structure, "name", fileName, report);
        var rootNodeId = RequireString(structure, "rootNodeId", fileName, report);
        if (structure["nodes"] is not JsonArray placements)
        {
            report.AddError("STRUCTURE_FIELD_REQUIRED", "nodes は配列で指定してください。", fileName, "/nodes");
            placements = [];
        }
        if (structure["edges"] is not JsonArray edges)
        {
            report.AddError("STRUCTURE_FIELD_REQUIRED", "edges は配列で指定してください。", fileName, "/edges");
            edges = [];
        }

        var placementIds = new HashSet<string>(StringComparer.Ordinal);
        var coordinateKeys = new HashSet<string>(StringComparer.Ordinal);
        for (var index = 0; index < placements.Count; index++)
        {
            if (placements[index] is not JsonObject placement)
            {
                report.AddError("PLACEMENT_INVALID", "各ノード配置はオブジェクトで指定してください。", fileName, $"/nodes/{index}");
                continue;
            }

            var nodeId = JsonValueReader.String(placement["nodeId"]);
            if (string.IsNullOrWhiteSpace(nodeId))
            {
                report.AddError("PLACEMENT_NODE_REQUIRED", "ノード配置の nodeId は必須です。", fileName, $"/nodes/{index}/nodeId");
                continue;
            }

            if (!placementIds.Add(nodeId))
                report.AddError("DUPLICATE_PLACEMENT", $"nodeId '{nodeId}' が複数回配置されています。", fileName, $"/nodes/{index}");
            if (!masterNodeIds.Contains(nodeId))
                report.AddError("UNKNOWN_NODE_ID", $"ノード配置が未定義の nodeId '{nodeId}' を参照しています。", fileName, $"/nodes/{index}/nodeId");

            var x = ReadCoordinate(placement["x"]);
            var y = ReadCoordinate(placement["y"]);
            var z = ReadCoordinate(placement["z"]);
            if (x is null || y is null || z is null)
            {
                report.AddError("COORDINATE_INVALID", "x・y・z は32ビットの座標範囲内で、0.1ブロック刻みの数値を指定してください。", fileName, $"/nodes/{index}");
                continue;
            }

            var coordinateKey = string.Join("|", x.Value.ToString("R", CultureInfo.InvariantCulture), y.Value.ToString("R", CultureInfo.InvariantCulture), z.Value.ToString("R", CultureInfo.InvariantCulture));
            if (!coordinateKeys.Add(coordinateKey))
                report.AddError("DUPLICATE_COORDINATE", $"座標 ({x}, {y}, {z}) が重複しています。", fileName, $"/nodes/{index}");

            if (displaySettings is not null
                && string.Equals(displaySettings.StructureId, structureId, StringComparison.Ordinal)
                && (!FitsInt32(displaySettings.CenterX + x.Value)
                    || !FitsInt32(displaySettings.CenterY + y.Value)
                    || !FitsInt32(displaySettings.CenterZ + z.Value)))
            {
                report.AddError(
                    "ABSOLUTE_COORDINATE_OVERFLOW",
                    $"nodeId '{nodeId}' の中心座標と相対座標の合計が32ビットのブロック座標範囲を超えています。",
                    fileName,
                    $"/nodes/{index}");
            }
        }

        if (!string.IsNullOrWhiteSpace(rootNodeId))
        {
            if (!masterNodeIds.Contains(rootNodeId))
                report.AddError("ROOT_NODE_UNKNOWN", $"rootNodeId '{rootNodeId}' はノードマスターに存在しません。", fileName, "/rootNodeId");
            if (!placementIds.Contains(rootNodeId))
                report.AddError("ROOT_NODE_NOT_PLACED", $"rootNodeId '{rootNodeId}' は構造に配置されていません。", fileName, "/rootNodeId");
        }

        var edgeKeys = new HashSet<string>(StringComparer.Ordinal);
        var adjacency = placementIds.ToDictionary(nodeId => nodeId, _ => new HashSet<string>(StringComparer.Ordinal), StringComparer.Ordinal);
        for (var index = 0; index < edges.Count; index++)
        {
            if (edges[index] is not JsonObject edge)
            {
                report.AddError("EDGE_INVALID", "各接続はオブジェクトで指定してください。", fileName, $"/edges/{index}");
                continue;
            }

            var source = JsonValueReader.String(edge["sourceNodeId"]);
            var target = JsonValueReader.String(edge["targetNodeId"]);
            if (string.IsNullOrWhiteSpace(source) || string.IsNullOrWhiteSpace(target))
            {
                report.AddError("EDGE_ENDPOINT_REQUIRED", "sourceNodeId と targetNodeId は必須です。", fileName, $"/edges/{index}");
                continue;
            }

            if (string.Equals(source, target, StringComparison.Ordinal))
                report.AddError("SELF_EDGE", $"nodeId '{source}' を自身に接続することはできません。", fileName, $"/edges/{index}");

            var first = string.CompareOrdinal(source, target) <= 0 ? source : target;
            var second = string.CompareOrdinal(source, target) <= 0 ? target : source;
            if (!edgeKeys.Add(first + "\0" + second))
                report.AddError("DUPLICATE_EDGE", $"接続 '{first}' - '{second}' が重複しています。", fileName, $"/edges/{index}");

            if (!placementIds.Contains(source))
                report.AddError("EDGE_NODE_NOT_PLACED", $"接続元 '{source}' が配置されていません。", fileName, $"/edges/{index}/sourceNodeId");
            if (!placementIds.Contains(target))
                report.AddError("EDGE_NODE_NOT_PLACED", $"接続先 '{target}' が配置されていません。", fileName, $"/edges/{index}/targetNodeId");
            if (!masterNodeIds.Contains(source))
                report.AddError("UNKNOWN_NODE_ID", $"接続元が未定義の nodeId '{source}' を参照しています。", fileName, $"/edges/{index}/sourceNodeId");
            if (!masterNodeIds.Contains(target))
                report.AddError("UNKNOWN_NODE_ID", $"接続先が未定義の nodeId '{target}' を参照しています。", fileName, $"/edges/{index}/targetNodeId");

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
                    $"nodeId '{unreachable}' は rootNodeId '{rootNodeId}' から到達できません。",
                    fileName,
                    "/nodes");
            }
        }
    }

    private static string? RequireString(JsonObject value, string property, string? fileName, ValidationReport report)
    {
        var result = JsonValueReader.String(value[property]);
        if (string.IsNullOrWhiteSpace(result))
            report.AddError("FIELD_REQUIRED", $"{property} は空でない文字列で指定してください。", fileName, $"/{property}");
        return result;
    }

    private static void RequireArray(JsonObject value, string property, string? fileName, ValidationReport report)
    {
        if (value[property] is not JsonArray)
            report.AddError("FIELD_REQUIRED", $"{property} は配列で指定してください。", fileName, $"/{property}");
    }

    private static int? RequireInt32(JsonObject value, string property, string? fileName, ValidationReport report)
    {
        var number = ReadInt32(value[property]);
        if (number is null)
            report.AddError("FIELD_REQUIRED", $"{property} は32ビット整数で指定してください。", fileName, $"/{property}");
        return number;
    }

    private static void RequireSchemaVersion(JsonObject value, string? fileName, ValidationReport report)
    {
        var version = ReadInt32(value["schemaVersion"]);
        if (version is null || version <= 0)
            report.AddError("SCHEMA_VERSION_INVALID", "schemaVersion は正の整数で指定してください。", fileName, "/schemaVersion");
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
            report.AddWarning("PLUGIN_CONFIG_UNAVAILABLE", $"プラグイン設定を読み込めません。詳細: {exception.Message}", Path.GetFileName(paths.PluginConfig));
            return null;
        }
    }

    private async Task<IReadOnlyDictionary<string, MasterTagSummary>?> TryReadMasterTagsAsync(
        ValidationReport report,
        CancellationToken cancellationToken)
    {
        try
        {
            return (await masterTagCatalog.ReadAllAsync(cancellationToken))
                .ToDictionary(value => value.Id, StringComparer.Ordinal);
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception exception)
        {
            report.AddError("TAG_CATALOG_INVALID", $"マスタータグカタログを読み込めません。詳細: {exception.Message}", Path.GetFileName(paths.TagCatalog));
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
                $"{idProperty} '{id}' のファイル名は '{expected}' にしてください。現在のファイル名: '{fileName}'。",
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

    private static double? ReadCoordinate(JsonNode? value)
    {
        var number = JsonValueReader.Number(value);
        if (number is null
            || !double.IsFinite(number.Value)
            || number.Value < int.MinValue
            || number.Value > int.MaxValue)
        {
            return null;
        }

        var tenth = Math.Round(number.Value * 10, MidpointRounding.AwayFromZero);
        return Math.Abs(number.Value * 10 - tenth) < 0.0000001 ? tenth / 10 : null;
    }

    private static bool FitsInt32(double value) => value is >= int.MinValue and <= int.MaxValue;

    private async Task<JsonObject?> LoadNodeIdSequenceAsync(
        ValidationReport report,
        CancellationToken cancellationToken)
    {
        if (!File.Exists(paths.NodeIdSequence))
        {
            report.AddError(
                "NODE_ID_SEQUENCE_NOT_FOUND",
                $"ノードID採番ファイルが存在しません: {paths.NodeIdSequence}",
                Path.GetFileName(paths.NodeIdSequence));
            return null;
        }

        try
        {
            var text = await File.ReadAllTextAsync(paths.NodeIdSequence, Encoding.UTF8, cancellationToken);
            return JsonNode.Parse(text) as JsonObject
                   ?? throw new JsonException("JSON のルートはオブジェクトで指定してください。");
        }
        catch (Exception exception) when (exception is JsonException or IOException or UnauthorizedAccessException)
        {
            report.AddError("NODE_ID_SEQUENCE_INVALID", $"ノードID採番ファイルを読み込めません。詳細: {exception.Message}", Path.GetFileName(paths.NodeIdSequence));
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
                $"lastIssuedNodeId '{lastIssued}' は必要な採番済み最大値 '{minimumIssued}' より小さい値です。",
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
            report.AddWarning("DIRECTORY_NOT_FOUND", $"ディレクトリがまだ存在しません: {directory}");
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
                    throw new JsonException("JSON のルートはオブジェクトで指定してください。");
                documents.Add(new StoredDocument(Path.GetFileName(path), path, content));
            }
            catch (Exception exception) when (exception is JsonException or IOException or UnauthorizedAccessException)
            {
                report.AddError(invalidCode, $"JSON ファイルを読み込めません。詳細: {exception.Message}", Path.GetFileName(path));
            }
        }

        return documents;
    }
}
