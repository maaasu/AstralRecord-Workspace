using System.Text.Json;
using Microsoft.Extensions.Options;
using SkillTreeEditor.Server.Options;

namespace SkillTreeEditor.Server.Services;

/// <summary>Publishes an already generated local preview. It never starts a planner or writes files.</summary>
public sealed class SpawnerPlanningService(IOptions<EditorOptions> options, WorkspacePaths workspace)
{
    private const int MaxManifestBytes = 1024 * 1024;
    private const int MaxMetadataPrefixBytes = 256 * 1024;
    private const int MaxDetailMetadataBytes = 4096;
    private const long MaxDetailImageBytes = 128L * 1024 * 1024;
    private const int MaxDetailDimension = 8192;
    private static readonly string[] Radii = ["32", "48", "64"];

    public async Task<IResult> ReadManifestAsync(HttpContext context, CancellationToken cancellationToken)
    {
        try
        {
            var snapshot = await ReadManifestSnapshotAsync(cancellationToken);
            context.Response.Headers.ETag = $"\"{snapshot.Revision}\"";
            context.Response.Headers.CacheControl = "no-store";
            return Results.Bytes(snapshot.Bytes, contentType: "application/json; charset=utf-8");
        }
        catch (FileNotFoundException)
        {
            return NotReady();
        }
        catch (DirectoryNotFoundException)
        {
            return NotReady();
        }
        catch (InvalidDataException)
        {
            return InvalidExport();
        }
    }

    public async Task<IResult> ReadDataAsync(HttpContext context, string kind, string? radius, string? revision, CancellationToken cancellationToken)
    {
        if (kind == "proposals" && !Radii.Contains(radius, StringComparer.Ordinal))
            return Results.Problem(statusCode: StatusCodes.Status400BadRequest,
                title: "半径は 32、48、64 のいずれかを指定してください。");
        if (string.IsNullOrWhiteSpace(revision))
            return Results.Problem(statusCode: StatusCodes.Status400BadRequest,
                title: "manifest の exportRevision を revision クエリに指定してください。");

        FileStream? stream = null;
        ManifestSnapshot? snapshot = null;
        try
        {
            snapshot = await ReadManifestSnapshotAsync(cancellationToken);
            if (!string.Equals(snapshot.Revision, revision, StringComparison.Ordinal)) return StaleExport();
            if (kind == "proposals" && !snapshot.ProposalFiles.ContainsKey(radius!))
                return Results.Problem(statusCode: StatusCodes.Status404NotFound,
                    title: "この計画には指定された半径の配置案がありません。");

            var fileName = kind switch
            {
                "cells" => snapshot.CellsFile,
                "regions" => snapshot.RegionsFile,
                "proposals" => snapshot.ProposalFiles[radius!],
                _ => throw new ArgumentException("未対応の出力種別です。", nameof(kind))
            };
            var fullPath = Path.Combine(snapshot.Directory, fileName);
            RequireNoLinks(fullPath);
            stream = new FileStream(fullPath, FileMode.Open, FileAccess.Read,
                FileShare.ReadWrite | FileShare.Delete, 64 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
            var metadata = await ReadRootMetadataAsync(stream, kind, cancellationToken);
            if (metadata.SchemaVersion != 2
                || !string.Equals(metadata.ExportRevision, snapshot.Revision, StringComparison.Ordinal)
                || !string.Equals(metadata.TerrainFingerprint, snapshot.TerrainFingerprint, StringComparison.Ordinal)
                || !string.Equals(metadata.WorldId, snapshot.WorldId, StringComparison.Ordinal)
                || kind == "proposals" && (metadata.PreviewOnly != true || metadata.RadiusMeters != int.Parse(radius!)))
                return InvalidExport();

            stream.Position = 0;
            context.Response.Headers.ETag = $"\"{snapshot.Revision}\"";
            context.Response.Headers.CacheControl = "no-store";
            var result = Results.Stream(stream, contentType: "application/json; charset=utf-8",
                fileDownloadName: null, enableRangeProcessing: false);
            stream = null; // ASP.NET Core owns and disposes the response stream.
            return result;
        }
        catch (FileNotFoundException)
        {
            return snapshot is null ? NotReady() : InvalidExport();
        }
        catch (DirectoryNotFoundException)
        {
            return snapshot is null ? NotReady() : InvalidExport();
        }
        catch (InvalidDataException)
        {
            return InvalidExport();
        }
        finally
        {
            if (stream is not null) await stream.DisposeAsync();
        }
    }

    public async Task<IResult> ReadTerrainDetailAsync(HttpContext context, string? revision, bool image, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(revision))
            return Results.Problem(statusCode: StatusCodes.Status400BadRequest,
                title: "manifest の exportRevision を revision クエリに指定してください。");
        FileStream? imageStream = null;
        try
        {
            var manifest = await ReadManifestSnapshotAsync(cancellationToken);
            if (!string.Equals(manifest.Revision, revision, StringComparison.Ordinal)) return StaleExport();
            var metadataPath = Path.Combine(manifest.Directory, "terrain-detail.json");
            RequireNoLinks(metadataPath);
            var metadataBytes = await ReadSmallFileAsync(metadataPath, MaxDetailMetadataBytes, cancellationToken);
            using var document = JsonDocument.Parse(metadataBytes);
            var detail = document.RootElement;
            if (detail.ValueKind != JsonValueKind.Object
                || GetInt(detail, "schemaVersion") != 1
                || GetString(detail, "terrainFingerprint") != manifest.TerrainFingerprint
                || GetInt(detail, "minX") != manifest.MinX
                || GetInt(detail, "minZ") != manifest.MinZ
                || GetInt(detail, "width") != manifest.Width
                || GetInt(detail, "height") != manifest.Height
                || GetInt(detail, "blocksPerPixel") != 1
                || GetString(detail, "image") != "terrain-detail.png"
                || manifest.Width > MaxDetailDimension || manifest.Height > MaxDetailDimension) return InvalidExport();
            var imagePath = Path.Combine(manifest.Directory, "terrain-detail.png");
            RequireNoLinks(imagePath);
            imageStream = new FileStream(imagePath, FileMode.Open, FileAccess.Read,
                FileShare.ReadWrite | FileShare.Delete, 64 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
            if (imageStream.Length is < 24 or > MaxDetailImageBytes) return InvalidExport();
            var header = new byte[24];
            await imageStream.ReadExactlyAsync(header, cancellationToken);
            if (!header.AsSpan(0, 8).SequenceEqual(new byte[] { 137, 80, 78, 71, 13, 10, 26, 10 })
                || !header.AsSpan(12, 4).SequenceEqual("IHDR"u8)
                || System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(header.AsSpan(16, 4)) != manifest.Width
                || System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(header.AsSpan(20, 4)) != manifest.Height)
                return InvalidExport();
            context.Response.Headers.ETag = $"\"{manifest.Revision}\"";
            context.Response.Headers.CacheControl = "no-store";
            if (!image) return Results.Bytes(metadataBytes, contentType: "application/json; charset=utf-8");
            imageStream.Position = 0;
            var result = Results.Stream(imageStream, contentType: "image/png", enableRangeProcessing: false);
            imageStream = null;
            return result;
        }
        catch (FileNotFoundException) { return DetailUnavailable(); }
        catch (DirectoryNotFoundException) { return DetailUnavailable(); }
        catch (InvalidDataException) { return InvalidExport(); }
        catch (JsonException) { return InvalidExport(); }
        catch (EndOfStreamException) { return InvalidExport(); }
        finally { if (imageStream is not null) await imageStream.DisposeAsync(); }
    }

    private static async Task<byte[]> ReadSmallFileAsync(string path, int limit, CancellationToken cancellationToken)
    {
        await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read,
            FileShare.ReadWrite | FileShare.Delete, 4096, FileOptions.Asynchronous | FileOptions.SequentialScan);
        if (stream.Length > limit) throw new InvalidDataException("詳細地図のメタデータが大きすぎます。");
        using var memory = new MemoryStream();
        await stream.CopyToAsync(memory, cancellationToken);
        if (memory.Length > limit) throw new InvalidDataException("詳細地図のメタデータが大きすぎます。");
        return memory.ToArray();
    }

    private async Task<ManifestSnapshot> ReadManifestSnapshotAsync(CancellationToken cancellationToken)
    {
        var directory = ResolveDataDirectory();
        var path = Path.Combine(directory, "manifest.json");
        RequireNoLinks(path);
        await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read,
            FileShare.ReadWrite | FileShare.Delete, 16 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
        if (stream.Length > MaxManifestBytes) throw new InvalidDataException("manifest が大きすぎます。");
        using var memory = new MemoryStream();
        await stream.CopyToAsync(memory, cancellationToken);
        if (memory.Length > MaxManifestBytes) throw new InvalidDataException("manifest が大きすぎます。");
        var bytes = memory.ToArray();
        try
        {
            using var document = JsonDocument.Parse(bytes);
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object
                || GetInt(root, "schemaVersion") != 2
                || GetString(root, "mode") != "continent-redesign"
                || GetBool(root, "previewOnly") != true)
                throw new InvalidDataException("manifest の形式が一致しません。");

            var revision = GetString(root, "exportRevision");
            var worldId = GetString(root, "worldId");
            var fingerprint = GetString(root, "terrainFingerprint");
            if (!root.TryGetProperty("bounds", out var bounds) || bounds.ValueKind != JsonValueKind.Object)
                throw new InvalidDataException("manifest の範囲が不正です。");
            var minX = GetInt(bounds, "minX"); var minZ = GetInt(bounds, "minZ");
            var width = GetInt(bounds, "width"); var height = GetInt(bounds, "height");
            if (minX is null || minZ is null || width is null or <= 0 || height is null or <= 0)
                throw new InvalidDataException("manifest の範囲が不正です。");
            if (!IsSha256(revision) || string.IsNullOrWhiteSpace(worldId) || !IsSha256(fingerprint))
                throw new InvalidDataException("manifest の識別情報が不足しています。");

            if (!root.TryGetProperty("radiusOptions", out var radiusOptions)
                || radiusOptions.ValueKind != JsonValueKind.Array
                || radiusOptions.GetArrayLength() == 0
                || radiusOptions.EnumerateArray().Any(value => value.ValueKind != JsonValueKind.Number
                    || !value.TryGetInt32(out var number) || !Radii.Contains(number.ToString(), StringComparer.Ordinal)))
                throw new InvalidDataException("manifest の半径候補が不足しています。");
            var declaredRadii = radiusOptions.EnumerateArray().Select(value => value.GetInt32().ToString()).ToArray();
            if (declaredRadii.Distinct(StringComparer.Ordinal).Count() != declaredRadii.Length)
                throw new InvalidDataException("manifest の半径候補が重複しています。");

            if (!root.TryGetProperty("files", out var files) || files.ValueKind != JsonValueKind.Object
                || GetString(files, "cells") != "cells.json"
                || GetString(files, "regions") != "regions.json"
                || !files.TryGetProperty("proposals", out var proposals) || proposals.ValueKind != JsonValueKind.Object)
                throw new InvalidDataException("manifest のファイル参照が不正です。");
            var proposalFiles = new Dictionary<string, string>(StringComparer.Ordinal);
            foreach (var radius in declaredRadii)
            {
                var expected = $"proposals-{radius}.json";
                if (GetString(proposals, radius) != expected)
                    throw new InvalidDataException("manifest のファイル参照が不正です。");
                proposalFiles.Add(radius, expected);
            }
            return new ManifestSnapshot(directory, bytes, revision!, worldId!, fingerprint!, minX.Value, minZ.Value, width.Value, height.Value,
                "cells.json", "regions.json", proposalFiles);
        }
        catch (JsonException ex)
        {
            throw new InvalidDataException("manifest の JSON が不正です。", ex);
        }
    }

    private string ResolveDataDirectory()
    {
        var configured = options.Value.SpawnerPlanningDataDirectory;
        if (!string.IsNullOrWhiteSpace(configured)
            && configured.Split(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar).Any(part => part == ".."))
            throw new ArgumentException("SpawnerPlanningDataDirectory に親ディレクトリ参照は使用できません。");
        var directory = string.IsNullOrWhiteSpace(configured)
            ? Path.Combine(workspace.WorkspaceRoot, "60_tool", "skilltree-editor", ".cache", "spawner-planning")
            : Path.IsPathFullyQualified(configured)
                ? configured
                : ResolveRelativeDirectory(configured);
        var full = Path.GetFullPath(directory);
        RequireNoLinks(full);
        return full;
    }

    private string ResolveRelativeDirectory(string relative)
    {
        if (relative.Split(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)
            .Any(part => part is "" or "." or "..") || relative.Contains(':'))
            throw new ArgumentException("SpawnerPlanningDataDirectory に親ディレクトリ参照は使用できません。");
        return SafePath.UnderRoot(workspace.WorkspaceRoot, relative);
    }

    private static void RequireNoLinks(string path)
    {
        var full = Path.GetFullPath(path);
        var current = Path.GetPathRoot(full)!;
        foreach (var part in full[current.Length..].Split(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar))
        {
            if (part.Length == 0) continue;
            current = Path.Combine(current, part);
            try
            {
                if ((File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0)
                    throw new UnauthorizedAccessException("リンクされた出力ディレクトリまたはファイルは読み込めません。");
            }
            catch (FileNotFoundException) { break; }
            catch (DirectoryNotFoundException) { break; }
        }
    }

    private static async Task<RootMetadata> ReadRootMetadataAsync(FileStream stream, string kind, CancellationToken cancellationToken)
    {
        var arrayName = kind;
        var buffer = new byte[MaxMetadataPrefixBytes];
        var count = 0;
        while (count < buffer.Length)
        {
            var read = await stream.ReadAsync(buffer.AsMemory(count), cancellationToken);
            count += read;
            try
            {
                var reader = new Utf8JsonReader(buffer.AsSpan(0, count), read == 0, default);
                var values = new Dictionary<string, string>(StringComparer.Ordinal);
                string? property = null;
                while (reader.Read())
                {
                    if (reader.TokenType == JsonTokenType.PropertyName && reader.CurrentDepth == 1)
                    {
                        property = reader.GetString();
                        continue;
                    }
                    if (property is null || reader.CurrentDepth != 1) continue;
                    if (property == arrayName)
                    {
                        if (reader.TokenType != JsonTokenType.StartArray)
                            throw new InvalidDataException("データ配列がありません。");
                        return new RootMetadata(ParseInt(values, "schemaVersion"), Get(values, "exportRevision"),
                            Get(values, "terrainFingerprint"), Get(values, "worldId"),
                            ParseBool(values, "previewOnly"), ParseInt(values, "radiusMeters"));
                    }
                    if (reader.TokenType is JsonTokenType.String or JsonTokenType.Number or JsonTokenType.True or JsonTokenType.False)
                    {
                        var value = reader.TokenType switch
                        {
                            JsonTokenType.String => reader.GetString() ?? "",
                            JsonTokenType.True => "true",
                            JsonTokenType.False => "false",
                            _ => System.Text.Encoding.UTF8.GetString(reader.ValueSpan)
                        };
                        if (!values.TryAdd(property, value))
                            throw new InvalidDataException("重複した識別情報があります。");
                    }
                    property = null;
                }
            }
            catch (JsonException ex)
            {
                throw new InvalidDataException("データファイルの JSON が不正です。", ex);
            }
            if (read == 0) break;
        }
        throw new InvalidDataException("データファイルの識別情報が見つかりません。");
    }

    private static string? Get(Dictionary<string, string> values, string name)
        => values.GetValueOrDefault(name);
    private static int? ParseInt(Dictionary<string, string> values, string name)
        => int.TryParse(Get(values, name), out var value) ? value : null;
    private static bool? ParseBool(Dictionary<string, string> values, string name)
        => bool.TryParse(Get(values, name), out var value) ? value : null;
    private static string? GetString(JsonElement element, string property)
        => element.TryGetProperty(property, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() : null;
    private static int? GetInt(JsonElement element, string property)
        => element.TryGetProperty(property, out var value) && value.ValueKind == JsonValueKind.Number && value.TryGetInt32(out var number) ? number : null;
    private static bool? GetBool(JsonElement element, string property)
        => element.TryGetProperty(property, out var value) ? value.ValueKind switch
        {
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            _ => null
        } : null;
    private static bool IsSha256(string? value)
        => value is { Length: 64 } && value.All(Uri.IsHexDigit);

    private static IResult NotReady()
        => Results.Problem(statusCode: StatusCodes.Status404NotFound,
            title: "スポナー配置案はまだありません。地形解析と配置案の生成を実行してください。");
    private static IResult StaleExport()
        => Results.Problem(statusCode: StatusCodes.Status409Conflict,
            title: "配置案が更新されました。manifest を再読み込みしてください。");
    private static IResult InvalidExport()
        => Results.Problem(statusCode: StatusCodes.Status409Conflict,
            title: "配置案のファイルが揃っていないか、版が一致しません。配置案を再生成してください。");
    private static IResult DetailUnavailable()
        => Results.Problem(statusCode: StatusCodes.Status404NotFound,
            title: "詳細地図はまだありません。16m区画の地図を表示してください。");

    private sealed record ManifestSnapshot(string Directory, byte[] Bytes, string Revision, string WorldId,
        string TerrainFingerprint, int MinX, int MinZ, int Width, int Height,
        string CellsFile, string RegionsFile, Dictionary<string, string> ProposalFiles);
    private sealed record RootMetadata(int? SchemaVersion, string? ExportRevision, string? TerrainFingerprint,
        string? WorldId, bool? PreviewOnly, int? RadiusMeters);
}
