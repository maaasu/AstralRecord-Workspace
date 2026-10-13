using System.Globalization;
using System.Numerics;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Models;

namespace SkillTreeEditor.Server.Services;

public sealed class FilebaseRepository(WorkspacePaths paths, BackupService backupService)
{
    private static readonly BigInteger MinimumIssuedNodeId = new(999);
    private static readonly BigInteger MaximumNodeId = BigInteger.Pow(10, 100) - BigInteger.One;
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private readonly MasterDataPaths _boundary = new(paths);

    public Task<IReadOnlyList<StoredDocument>> ReadNodesAsync(CancellationToken cancellationToken)
        => ReadDirectoryAsync(paths.Nodes, cancellationToken);

    public Task<IReadOnlyList<StoredDocument>> ReadStructuresAsync(CancellationToken cancellationToken)
        => ReadDirectoryAsync(paths.Structures, cancellationToken);

    public async Task<StoredDocument?> GetNodeAsync(string nodeId, CancellationToken cancellationToken)
        => await FindByPropertyAsync(paths.Nodes, "nodeId", nodeId, cancellationToken);

    public async Task<StoredDocument?> GetStructureAsync(string structureId, CancellationToken cancellationToken)
        => await FindByPropertyAsync(paths.Structures, "structureId", structureId, cancellationToken);

    public async Task<string> GetNextNodeIdAsync(CancellationToken cancellationToken)
    {
        _boundary.RequireNoLinks(paths.NodeIdSequenceLock);
        _boundary.RequireNoLinks(paths.NodeIdSequence);
        await using var allocationLock = await ExclusiveFileLock.AcquireAsync(
            paths.NodeIdSequenceLock,
            cancellationToken);
        return await GetNextNodeIdLockedAsync(cancellationToken);
    }

    private async Task<string> GetNextNodeIdLockedAsync(CancellationToken cancellationToken)
    {
        var documents = await ReadNodesAsync(cancellationToken);
        var maximum = MinimumIssuedNodeId;
        foreach (var document in documents)
        {
            var id = JsonValueReader.String(document.Content["nodeId"]);
            if (TryParseNodeId(id, out var numericId) && numericId > maximum)
                maximum = numericId;
        }

        var sequence = await ReadNodeIdSequenceAsync(cancellationToken);
        if (sequence is null)
            throw new InvalidDataException($"nodeId採番の管理ファイルがありません: {paths.NodeIdSequence}");
        if (sequence.Value < maximum)
            throw new InvalidDataException(
                $"node-id-sequence.json の lastIssuedNodeId '{sequence}' が既存の最大nodeId '{maximum}' より小さい値です。");
        maximum = sequence.Value;
        if (maximum >= MaximumNodeId)
            throw new InvalidOperationException("100桁以内のnodeIdを採番できる上限に達しました。");

        return (maximum + BigInteger.One).ToString(CultureInfo.InvariantCulture);
    }

    public async Task<StoredDocument> CreateNodeAsync(JsonObject content, CancellationToken cancellationToken)
    {
        await _writeLock.WaitAsync(cancellationToken);
        try
        {
            _boundary.RequireNoLinks(paths.NodeIdSequenceLock);
            _boundary.RequireNoLinks(paths.NodeIdSequence);
            await using var allocationLock = await ExclusiveFileLock.AcquireAsync(
                paths.NodeIdSequenceLock,
                cancellationToken);
            // Re-read both the node directory and high-water only after the inter-process
            // lock has been acquired. This prevents a delayed writer from moving the
            // sequence backwards after another editor process has already advanced it.
            var nodeId = await GetNextNodeIdLockedAsync(cancellationToken);
            var normalized = NormalizeNode(content);
            normalized["nodeId"] = nodeId;
            var path = SafePath.UnderRoot(paths.Nodes, $"{nodeId}.json");
            if (File.Exists(path))
                throw new IOException($"採番されたnodeIdのファイルは既に存在します: {path}");

            // Reserve the ID first. A later node write failure intentionally leaves a gap,
            // which is preferable to ever issuing the same ID twice.
            await WriteNodeIdSequenceLockedAsync(nodeId, cancellationToken);
            await WriteJsonLockedAsync(path, normalized, "nodes", createOnly: true, cancellationToken);
            return new StoredDocument(Path.GetFileName(path), path, normalized, await ReadRevisionAsync(path, cancellationToken));
        }
        finally
        {
            _writeLock.Release();
        }
    }

    public async Task<StoredDocument> SaveNodeAsync(string nodeId, JsonObject content, CancellationToken cancellationToken)
    {
        SafePath.RequireIdentifier(nodeId, nameof(nodeId));
        var documentNodeId = JsonValueReader.String(content["nodeId"]);
        if (!string.Equals(nodeId, documentNodeId, StringComparison.Ordinal))
            throw new InvalidOperationException("nodeIdは変更できません。URLのIDと一致させてください。");

        var existing = await GetNodeAsync(nodeId, cancellationToken)
            ?? throw new FileNotFoundException($"ノード '{nodeId}' が見つかりません。");
        var normalized = NormalizeNode(content);
        await WriteJsonAsync(existing.FullPath, normalized, "nodes", createOnly: false, cancellationToken);
        return existing with { Content = normalized, Revision = await ReadRevisionAsync(existing.FullPath, cancellationToken) };
    }

    public async Task DeleteNodeAsync(string nodeId, CancellationToken cancellationToken)
    {
        SafePath.RequireIdentifier(nodeId, nameof(nodeId));
        var existing = await GetNodeAsync(nodeId, cancellationToken)
            ?? throw new FileNotFoundException($"ノード '{nodeId}' が見つかりません。");
        await DeleteAsync(existing.FullPath, "nodes", cancellationToken);
    }

    public async Task<StoredDocument> CreateStructureAsync(JsonObject content, CancellationToken cancellationToken)
    {
        var structureId = SafePath.RequireIdentifier(
            JsonValueReader.String(content["structureId"])
                ?? throw new ArgumentException("structureIdは必須です。"),
            "structureId");
        if (await GetStructureAsync(structureId, cancellationToken) is not null)
            throw new InvalidOperationException($"structureId '{structureId}' は既に存在します。");

        var path = SafePath.UnderRoot(paths.Structures, $"{structureId}.json");
        var normalized = NormalizeStructure(content);
        await WriteJsonAsync(path, normalized, "structures", createOnly: true, cancellationToken);
        return new StoredDocument(Path.GetFileName(path), path, normalized, await ReadRevisionAsync(path, cancellationToken));
    }

    public async Task<StoredDocument> SaveStructureAsync(
        string structureId,
        JsonObject content,
        CancellationToken cancellationToken)
    {
        SafePath.RequireIdentifier(structureId, nameof(structureId));
        var documentStructureId = JsonValueReader.String(content["structureId"]);
        if (!string.Equals(structureId, documentStructureId, StringComparison.Ordinal))
            throw new InvalidOperationException("structureIdは変更できません。URLのIDと一致させてください。");

        var existing = await GetStructureAsync(structureId, cancellationToken)
            ?? throw new FileNotFoundException($"構造 '{structureId}' が見つかりません。");
        var normalized = NormalizeStructure(content);
        await WriteJsonAsync(existing.FullPath, normalized, "structures", createOnly: false, cancellationToken);
        return existing with { Content = normalized, Revision = await ReadRevisionAsync(existing.FullPath, cancellationToken) };
    }

    public static JsonObject NormalizeNode(JsonObject content) => (JsonObject)content.DeepClone();

    public static JsonObject NormalizeStructure(JsonObject content)
    {
        var clone = (JsonObject)content.DeepClone();

        if (clone["nodes"] is JsonArray nodes)
        {
            clone["nodes"] = new JsonArray(nodes
                .OfType<JsonObject>()
                .OrderBy(node => JsonValueReader.String(node["nodeId"]), StringComparer.Ordinal)
                .Select(node => node.DeepClone())
                .ToArray());
        }

        if (clone["edges"] is JsonArray edges)
        {
            var normalizedEdges = new List<JsonObject>();
            foreach (var edge in edges.OfType<JsonObject>())
            {
                var source = JsonValueReader.String(edge["sourceNodeId"]);
                var target = JsonValueReader.String(edge["targetNodeId"]);
                var normalized = (JsonObject)edge.DeepClone();
                if (source is not null && target is not null && string.CompareOrdinal(source, target) > 0)
                {
                    normalized["sourceNodeId"] = target;
                    normalized["targetNodeId"] = source;
                }

                normalizedEdges.Add(normalized);
            }

            clone["edges"] = new JsonArray(normalizedEdges
                .OrderBy(edge => JsonValueReader.String(edge["sourceNodeId"]), StringComparer.Ordinal)
                .ThenBy(edge => JsonValueReader.String(edge["targetNodeId"]), StringComparer.Ordinal)
                .Select(edge => edge.DeepClone())
                .ToArray());
        }

        return clone;
    }

    private async Task<IReadOnlyList<StoredDocument>> ReadDirectoryAsync(
        string directory,
        CancellationToken cancellationToken)
    {
        _boundary.RequireNoLinks(directory);
        if (!Directory.Exists(directory))
            return [];

        var documents = new List<StoredDocument>();
        foreach (var file in Directory.EnumerateFiles(directory, "*.json", SearchOption.TopDirectoryOnly)
                     .OrderBy(value => value, StringComparer.OrdinalIgnoreCase))
        {
            _boundary.RequireNoLinks(file);
            var bytes = await File.ReadAllBytesAsync(file, cancellationToken);
            var text = Encoding.UTF8.GetString(bytes);
            if (text.StartsWith('\uFEFF')) text = text[1..];
            var content = JsonNode.Parse(
                text,
                documentOptions: new JsonDocumentOptions
                {
                    CommentHandling = JsonCommentHandling.Disallow,
                    AllowTrailingCommas = false
                }) as JsonObject
                ?? throw new InvalidDataException($"JSONのルートはオブジェクトで記載してください: {file}");
            documents.Add(new StoredDocument(Path.GetFileName(file), Path.GetFullPath(file), content, EditorRevision.Hash(bytes)));
        }

        return documents;
    }

    private async Task<string> ReadRevisionAsync(string path, CancellationToken token)
    {
        _boundary.RequireNoLinks(path);
        return EditorRevision.Hash(await File.ReadAllBytesAsync(path, token));
    }

    private async Task<StoredDocument?> FindByPropertyAsync(
        string directory,
        string propertyName,
        string propertyValue,
        CancellationToken cancellationToken)
    {
        SafePath.RequireIdentifier(propertyValue, propertyName);
        var matches = (await ReadDirectoryAsync(directory, cancellationToken))
            .Where(document => string.Equals(
                JsonValueReader.String(document.Content[propertyName]),
                propertyValue,
                StringComparison.Ordinal))
            .ToArray();

        return matches.Length switch
        {
            0 => null,
            1 => matches[0],
            _ => throw new InvalidDataException(
                $"複数のファイルに同じ {propertyName} '{propertyValue}' が定義されています: {string.Join(", ", matches.Select(match => match.FileName))}")
        };
    }

    private async Task<BigInteger?> ReadNodeIdSequenceAsync(CancellationToken cancellationToken)
    {
        _boundary.RequireNoLinks(paths.NodeIdSequence);
        if (!File.Exists(paths.NodeIdSequence))
            return null;

        var text = await File.ReadAllTextAsync(paths.NodeIdSequence, Encoding.UTF8, cancellationToken);
        var document = JsonNode.Parse(text) as JsonObject
            ?? throw new InvalidDataException($"nodeId採番管理ファイルのルートはオブジェクトで記載してください: {paths.NodeIdSequence}");
        if (document.Count != 3
            || !string.Equals(
                JsonValueReader.String(document["$schema"]),
                "./schemas/node-id-sequence.v1.schema.json",
                StringComparison.Ordinal)
            || JsonValueReader.Number(document["schemaVersion"]) != 1)
        {
            throw new InvalidDataException(
                "node-id-sequence.json には正規の $schema、schemaVersion、lastIssuedNodeId だけを記載してください。");
        }
        var value = JsonValueReader.String(document["lastIssuedNodeId"]);
        if (!TryParseNodeId(value, out var parsed))
            throw new InvalidDataException("node-id-sequence.json の lastIssuedNodeId は0以上の10進数を文字列で指定してください。");
        return parsed;
    }

    private async Task WriteNodeIdSequenceLockedAsync(string nodeId, CancellationToken cancellationToken)
    {
        var sequence = new JsonObject
        {
            ["$schema"] = "./schemas/node-id-sequence.v1.schema.json",
            ["schemaVersion"] = 1,
            ["lastIssuedNodeId"] = nodeId
        };
        await WriteJsonLockedAsync(
            paths.NodeIdSequence,
            sequence,
            "node-id-sequence",
            createOnly: !File.Exists(paths.NodeIdSequence),
            cancellationToken);
    }

    private static bool TryParseNodeId(string? value, out BigInteger parsed)
    {
        parsed = BigInteger.Zero;
        if (string.IsNullOrEmpty(value)
            || value.Length > 100
            || (value.Length > 1 && value[0] == '0')
            || value.Any(character => character is < '0' or > '9'))
        {
            return false;
        }

        return BigInteger.TryParse(value, NumberStyles.None, CultureInfo.InvariantCulture, out parsed);
    }

    private async Task WriteJsonAsync(
        string path,
        JsonObject content,
        string backupCategory,
        bool createOnly,
        CancellationToken cancellationToken)
    {
        await _writeLock.WaitAsync(cancellationToken);
        try
        {
            await WriteJsonLockedAsync(path, content, backupCategory, createOnly, cancellationToken);
        }
        finally
        {
            _writeLock.Release();
        }
    }

    private async Task WriteJsonLockedAsync(
        string path,
        JsonObject content,
        string backupCategory,
        bool createOnly,
        CancellationToken cancellationToken)
    {
        _boundary.RequireNoLinks(path);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        if (createOnly && File.Exists(path))
            throw new IOException($"保存先のファイルは既に存在します: {path}");

        var serialized = StableJson.Serialize(content);
        if (!createOnly
            && File.Exists(path)
            && string.Equals(
                await File.ReadAllTextAsync(path, Encoding.UTF8, cancellationToken),
                serialized,
                StringComparison.Ordinal))
        {
            return;
        }

        if (!createOnly)
            await backupService.BackupAsync(path, backupCategory, cancellationToken);

        var temporaryPath = SafePath.UnderRoot(
            Path.GetDirectoryName(path)!,
            $".{Path.GetFileName(path)}.{Guid.NewGuid():N}.tmp");
        try
        {
            _boundary.RequireNoLinks(temporaryPath);
            await File.WriteAllTextAsync(
                temporaryPath,
                serialized,
                new UTF8Encoding(encoderShouldEmitUTF8Identifier: false),
                cancellationToken);
            _boundary.RequireNoLinks(path);
            File.Move(temporaryPath, path, overwrite: !createOnly);
        }
        finally
        {
            if (File.Exists(temporaryPath))
                File.Delete(temporaryPath);
        }
    }

    private async Task DeleteAsync(string path, string backupCategory, CancellationToken cancellationToken)
    {
        await _writeLock.WaitAsync(cancellationToken);
        try
        {
            _boundary.RequireNoLinks(path);
            await backupService.BackupAsync(path, backupCategory, cancellationToken);
            _boundary.RequireNoLinks(path);
            File.Delete(path);
        }
        finally
        {
            _writeLock.Release();
        }
    }
}
