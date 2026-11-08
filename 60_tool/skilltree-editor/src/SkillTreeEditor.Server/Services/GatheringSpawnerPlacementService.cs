using System.Globalization;
using System.Text;
using System.Text.Json.Nodes;
using Microsoft.Extensions.Options;
using SkillTreeEditor.Server.Options;

namespace SkillTreeEditor.Server.Services;

public sealed record GatheringSpawnerMarker(string Id, int X, int Y, int Z);

public sealed class GatheringSpawnerPlacementService(IOptions<EditorOptions> options, WorkspacePaths workspace,
    MasterDataCodec codec, MasterDataService masters)
{
    private const string FileName = "gathering_spawners.yml";

    public async Task<IReadOnlyList<GatheringSpawnerMarker>> ListAsync(string worldId, CancellationToken token)
    {
        var configuredPath = options.Value.GatheringSpawnerLocationsPath;
        if (string.IsNullOrWhiteSpace(configuredPath))
            throw new FileNotFoundException("SkillTreeEditor:GatheringSpawnerLocationsPath に採集スポナーの配置ファイルを設定してください。");

        var aliases = await masters.ReadBukkitWorldAliasesAsync(worldId, token);
        if (aliases is null) return [];

        var raw = await ReadLocationsAsync(configuredPath, token);
        if (codec.Parse(FileName, raw).Content is not JsonObject data || data["spawners"] is not JsonArray rows)
            throw new InvalidOperationException("gathering_spawners.yml の spawners 配列を読み取れません。");

        var markers = new List<GatheringSpawnerMarker>();
        foreach (var row in rows)
        {
            token.ThrowIfCancellationRequested();
            if (row is not JsonObject location || JsonValueReader.String(location["world"]) is not { } world ||
                string.IsNullOrWhiteSpace(world))
                throw new InvalidOperationException("gathering_spawners.yml にワールドが不明な配置があります。");

            if (world != worldId && !aliases.Contains(MasterDataService.NormalizeBukkitWorldPath(world))) continue;

            var id = JsonValueReader.String(location["id"]);
            if (string.IsNullOrWhiteSpace(id) ||
                !TryReadCoordinate(location["x"], out var x) ||
                !TryReadCoordinate(location["y"], out var y) ||
                !TryReadCoordinate(location["z"], out var z))
                throw new InvalidOperationException("gathering_spawners.yml に ID または座標が不正な配置があります。");

            markers.Add(new(id, x, y, z));
        }
        return markers;
    }

    private async Task<string> ReadLocationsAsync(string configuredPath, CancellationToken token)
    {
        try
        {
            var path = Path.GetFullPath(configuredPath, workspace.WorkspaceRoot);
            // The plugin periodically rewrites this file. Allow its writer to open it during a read.
            await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read,
                FileShare.ReadWrite | FileShare.Delete, 4096, FileOptions.Asynchronous | FileOptions.SequentialScan);
            var size = stream.Length;
            var lastWrite = File.GetLastWriteTimeUtc(path);
            using var reader = new StreamReader(stream, new UTF8Encoding(false, true), leaveOpen: true);
            var raw = await reader.ReadToEndAsync(token);
            if (stream.Position != size || stream.Length != size || File.GetLastWriteTimeUtc(path) != lastWrite)
                throw new InvalidOperationException("採集スポナーの配置ファイルが読込中に更新されました。");
            return raw;
        }
        catch (Exception exception) when (exception is FileNotFoundException or DirectoryNotFoundException)
        {
            throw new FileNotFoundException("設定先の採集スポナー配置ファイルが見つかりません。", exception);
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException)
        {
            throw new InvalidOperationException("採集スポナーの配置ファイルを読み込めません。配置ファイルのパスと読み取り権限を確認してください。", exception);
        }
    }

    private static bool TryReadCoordinate(JsonNode? node, out int coordinate) =>
        int.TryParse(JsonValueReader.String(node) ?? node?.ToJsonString(),
            NumberStyles.Integer, CultureInfo.InvariantCulture, out coordinate);
}
