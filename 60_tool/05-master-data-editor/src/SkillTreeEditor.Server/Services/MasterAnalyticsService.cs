using System.Buffers.Binary;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using SkillTreeEditor.Server.Models;
using YamlDotNet.Core;
using YamlDotNet.RepresentationModel;

namespace SkillTreeEditor.Server.Services;

/// <summary>Reads workspace masters. The simulation mirrors the Plugin's normal (non-rebirth) experience formulas.</summary>
public sealed partial class MasterAnalyticsService(WorkspacePaths paths)
{
    public const string AccountSource = "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/account/service/AccountService.java";
    public const string ClassSource = "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/playerclass/PlayerClassService.kt";

    public async Task<MasterAnalyticsCatalog> ReadAsync(CancellationToken token)
    {
        var diagnostics = new List<AnalyticsDiagnostic>();
        var equipment = await ReadDirectoryAsync(Path.Combine(paths.WorkspaceRoot, "40_filebase", "10.features.item", "20.equipment"), token, diagnostics);
        var classes = await ReadDirectoryAsync(paths.Classes, token, diagnostics);
        return new MasterAnalyticsCatalog(equipment.Where(value => value.Content["equipment"] is not null).ToArray(), classes, diagnostics);
    }

    public async Task<GrowthSimulation> SimulateAsync(string? uuid, int maximum, int startPlayer, int startClass, string? ids, CancellationToken token)
    {
        if (!Guid.TryParse(uuid ?? "00000000-0000-0000-0000-000000000001", out var account))
            throw new ArgumentException("アカウントUUIDの形式が正しくありません。");
        if (maximum is < 1 or > 200 || startPlayer < 1 || startPlayer > maximum || startClass is < 1 or > 1000)
            throw new ArgumentException("プレイヤーLvは1〜200、開始クラスLvは1〜1000で指定してください。");
        var diagnostics = new List<AnalyticsDiagnostic>();
        var classMasters = await ReadDirectoryAsync(paths.Classes, token, diagnostics);
        var requested = (ids ?? "adventurer,swordsman,hunter,mage").Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).ToHashSet(StringComparer.OrdinalIgnoreCase);
        var selected = classMasters.Where(value => requested.Contains(Text(value.Content["id"]))).Select(value => new ClassGrowthDefinition(
            Text(value.Content["id"]), Text(value.Content["name"]), Math.Max(1, Integer(value.Content["maxLevel"], 100)), Math.Max(10, Integer(value.Content["expRate"], 100)))).ToArray();
        if (selected.Length != requested.Count || selected.Length is < 1 or > 12)
            throw new ArgumentException("既存のクラスIDを1〜12件指定してください。クラスの読込エラーがある場合は先に修正してください。");
        // Bound the editor simulation separately from the Plugin's allowed master maxLevel.
        if (selected.Any(value => value.MaxLevel > 10000))
            throw new ArgumentException("シミュレーションはクラス上限Lv10000まで対応します。");
        var thresholds = selected.ToDictionary(value => value.Id, value => ClassThresholds(value.MaxLevel, value.ExpRate), StringComparer.OrdinalIgnoreCase);
        var initialPlayerExperience = TotalPlayerExperience(account, startPlayer);
        var points = new List<GrowthPoint>();
        var totalPlayerExperience = initialPlayerExperience;
        for (var playerLevel = startPlayer; playerLevel <= maximum; playerLevel++)
        {
            token.ThrowIfCancellationRequested();
            if (playerLevel > startPlayer)
                totalPlayerExperience += PlayerExperienceForNextLevel(account, playerLevel - 1);
            var earned = totalPlayerExperience - initialPlayerExperience;
            var values = selected.Select(definition =>
            {
                var levels = thresholds[definition.Id];
                var initialClass = Math.Min(startClass, definition.MaxLevel);
                var total = levels[initialClass - 1] + earned;
                var index = Array.BinarySearch(levels, total);
                var level = index >= 0 ? index + 1 : ~index;
                level = Math.Clamp(level, 1, definition.MaxLevel);
                var next = level == definition.MaxLevel ? 0 : levels[level] - total;
                return new ClassGrowthValue(definition.Id, level, total, Math.Max(0, next));
            }).ToArray();
            points.Add(new GrowthPoint(playerLevel, totalPlayerExperience, values));
        }
        return new GrowthSimulation(account.ToString(), startPlayer, startClass, selected, points,
            [AccountSource + " :: requiredExperienceForNextLevel / stableHash", ClassSource + " :: requiredClassExperienceForNextLevel"],
            "通常育成・転生なし。開始プレイヤーLv到達直後から、開始クラスLv到達直後の1職だけを継続して育て、両方へ同じEXPを加算する仮定。職業ごとの線は独立した比較で、転職条件・コマンド加算・過去の育成履歴は含みません。");
    }

    public static long PlayerExperienceForNextLevel(Guid uuid, int level)
    {
        if (level is < 1 or > 200) throw new ArgumentOutOfRangeException(nameof(level));
        int[] wave = [0, 90, 35, 140, 60, 185, 95, 230];
        var bytes = SHA256.HashData(Encoding.UTF8.GetBytes(uuid.ToString() + ":" + level.ToString(CultureInfo.InvariantCulture)));
        var signed = BinaryPrimitives.ReadInt32BigEndian(bytes);
        var stableHash = ((long)signed % int.MaxValue + int.MaxValue) % int.MaxValue;
        return 500L + (long)level * level * 120 + level / 10 * 850 + wave[(level - 1) % wave.Length]
               + (level % 5 == 0 ? 600L + level * 80 : 0) + stableHash % (90 + level * 4);
    }

    public static long ClassExperienceForNextLevel(int level, int expRate)
    {
        var normalized = Math.Max(1, level);
        var basis = 45L + (long)normalized * normalized * 8;
        var milestone = normalized % 10 == 0 ? 150L + normalized * 12 : 0;
        return Math.Max(1, (long)Math.Round((basis + milestone) * (double)Math.Max(10, expRate) / 100, MidpointRounding.AwayFromZero));
    }

    private static long[] ClassThresholds(int maximum, int rate)
    {
        var result = new long[maximum];
        for (var level = 1; level < maximum; level++) result[level] = result[level - 1] + ClassExperienceForNextLevel(level, rate);
        return result;
    }

    private static long TotalPlayerExperience(Guid uuid, int target)
    {
        long total = 0;
        for (var level = 1; level < target; level++) total += PlayerExperienceForNextLevel(uuid, level);
        return total;
    }

    private async Task<IReadOnlyList<AnalyticsMaster>> ReadDirectoryAsync(string directory, CancellationToken token, List<AnalyticsDiagnostic> diagnostics)
    {
        var result = new List<AnalyticsMaster>();
        if (!Directory.Exists(directory)) return result;
        var files = Directory.EnumerateFiles(directory, "*", new EnumerationOptions { RecurseSubdirectories = true, AttributesToSkip = FileAttributes.ReparsePoint }).Where(path => Path.GetExtension(path) is ".yml" or ".yaml").Order(StringComparer.OrdinalIgnoreCase);
        foreach (var file in files)
        {
            token.ThrowIfCancellationRequested();
            var relative = Path.GetRelativePath(Path.Combine(paths.WorkspaceRoot, "40_filebase"), file).Replace('\\', '/');
            try
            {
                var raw = await File.ReadAllTextAsync(file, token);
                var stream = new YamlStream();
                stream.Load(new StringReader(raw));
                if (stream.Documents.Count != 1 || ConvertNode(stream.Documents[0].RootNode) is not JsonObject document)
                    throw new FormatException("単一のYAMLオブジェクトである必要があります。");
                var match = ProgressionPattern().Match(raw);
                int? progression = match.Success && int.TryParse(match.Groups[1].Value, out var value) ? value : null;
                result.Add(new AnalyticsMaster(relative, document, progression));
            }
            catch (Exception exception) when (exception is YamlException or FormatException or ArgumentException or IOException)
            {
                diagnostics.Add(new AnalyticsDiagnostic(relative, exception.Message));
            }
        }
        return result;
    }

    private static JsonNode? ConvertNode(YamlNode node, int depth = 0)
    {
        if (depth > 50) throw new FormatException("YAMLのネストが深すぎます。");
        if (node is YamlMappingNode map)
        {
            var result = new JsonObject();
            foreach (var entry in map.Children)
            {
                if (entry.Key is not YamlScalarNode key || key.Value is null) throw new FormatException("マップのキーは文字列で指定してください。");
                result.Add(key.Value, ConvertNode(entry.Value, depth + 1));
            }
            return result;
        }
        if (node is YamlSequenceNode sequence) return new JsonArray(sequence.Children.Select(value => ConvertNode(value, depth + 1)).ToArray());
        if (node is not YamlScalarNode scalar) throw new FormatException("未対応のYAML要素です。");
        var text = scalar.Value ?? "";
        if (scalar.Style is not ScalarStyle.Plain) return JsonValue.Create(text);
        if (text is "null" or "Null" or "NULL" or "~" or "") return null;
        if (bool.TryParse(text, out var boolean)) return JsonValue.Create(boolean);
        if (long.TryParse(text, NumberStyles.Integer, CultureInfo.InvariantCulture, out var integer)) return JsonValue.Create(integer);
        if (double.TryParse(text, NumberStyles.Float, CultureInfo.InvariantCulture, out var number) && double.IsFinite(number)) return JsonValue.Create(number);
        return JsonValue.Create(text);
    }

    private static string Text(JsonNode? node) => node?.ToString() ?? "";
    private static int Integer(JsonNode? node, int fallback) => int.TryParse(Text(node), out var value) ? value : fallback;

    [GeneratedRegex(@"^#\s+progression:\s*(\d+)\s*$", RegexOptions.Multiline)]
    private static partial Regex ProgressionPattern();
}
