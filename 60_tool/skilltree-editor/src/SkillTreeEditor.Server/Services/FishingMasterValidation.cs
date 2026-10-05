using System.Text.Json;
using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Models;

namespace SkillTreeEditor.Server.Services;

// Rules shared by the editor's Validate and Save endpoints. The API also validates imports.
internal static class FishingMasterValidation
{
    private const string FishDirectory = "44.features.fishing.fish";
    private const string AreaDirectory = "46.features.fishing.area";
    private const string RulesDirectory = "47.features.fishing.rules";
    private const int MaxLevel = int.MaxValue;

    public static async Task ValidateAsync(MasterDataPaths paths, MasterDataCodec codec, string path,
        JsonObject master, List<MasterDataIssue> issues, CancellationToken token)
    {
        var directory = path.Split('/')[0];
        if (directory is not (FishDirectory or AreaDirectory or RulesDirectory)) return;
        void Error(string code, string pointer, string message) => issues.Add(new("error", code, pointer, message));
        if (!Integer(master["schemaVersion"], 1, 1)) Error("FISHING_SCHEMA_VERSION", "/schemaVersion", "schemaVersion は 1 を指定してください。");
        if (string.IsNullOrWhiteSpace(Text(master["id"]))) Error("FISHING_ID", "/id", "id は必須です。");

        if (directory == FishDirectory)
        {
            if (Text(master["type"]) != "FISH") Error("FISHING_TYPE", "/type", "type は FISH を指定してください。");
            if (string.IsNullOrWhiteSpace(Text(master["name"]))) Error("FISHING_NAME", "/name", "name は必須です。");
            if (Text(master["entityType"]) is not ("COD" or "SALMON" or "TROPICAL_FISH" or "PUFFERFISH"))
                Error("FISHING_ENTITY_TYPE", "/entityType", "entityType は COD / SALMON / TROPICAL_FISH / PUFFERFISH から選んでください。");
            foreach (var key in new[] { "maxHealth", "swimSpeed", "fleeSpeed", "pullPerTick", "biteChancePercent", "landFleeSpeed", "hopSpeed" })
            {
                var max = key == "biteChancePercent" ? 100 : key == "maxHealth" ? 100000 : key == "pullPerTick" ? 100 : 1;
                var positive = key != "pullPerTick";
                if (!Number(master[key], 0, max, positive))
                    Error("FISHING_NUMBER", "/" + key, $"{key} は {(positive ? "0 より大きく" : "0 以上")} {max} 以下の有限数で指定してください。");
            }
            if (!Integer(master["caughtAmount"], 1, 64)) Error("FISHING_CAUGHT_AMOUNT", "/caughtAmount", "caughtAmount は 1～64 の整数で指定してください。");
            await ReferenceAsync(master["caughtItemId"], "item", "/caughtItemId", "10.features.item", paths, codec, issues, token, master["caughtAmount"]);
            if (master.ContainsKey("lootTableId"))
                await ReferenceAsync(master["lootTableId"], "loot_table", "/lootTableId", "80.shared.loot/table", paths, codec, issues, token);
        }
        else if (directory == RulesDirectory)
        {
            if (Text(master["type"]) != "FISHING_RULES") Error("FISHING_TYPE", "/type", "type は FISHING_RULES を指定してください。");
            foreach (var key in new[] { "searchRadius", "fishCountMin", "fishCountMax", "waitingTicks", "attackTicks", "turnTicks", "landingTicks", "hangingTicks", "escapeTicks", "nibbleTicks", "nibbleCycleTicks", "reelCooldownTicks" })
            {
                var min = key == "attackTicks" ? 2 : 1;
                var max = key == "searchRadius" ? 32 : key is "fishCountMin" or "fishCountMax" ? 32 : 72000;
                if (!Integer(master[key], min, max)) Error("FISHING_RULE_INTEGER", "/" + key, $"{key} は {min}～{max} の整数で指定してください。");
            }
            foreach (var key in new[] { "reelMultiplier", "slackDistance", "nibbleDistance" })
            {
                var max = key == "nibbleDistance" ? 1 : 100;
                var positive = key == "reelMultiplier";
                if (!Number(master[key], 0, max, positive)) Error("FISHING_RULE_NUMBER", "/" + key, $"{key} は {(positive ? "0 より大きく" : "0 以上")} {max} 以下の有限数で指定してください。");
            }
            if (ReadInteger(master["attackTicks"]) is { } attack && ReadInteger(master["turnTicks"]) is { } turn && attack > turn)
                Error("FISHING_ATTACK_TURN", "/turnTicks", "turnTicks は attackTicks 以上にしてください。");
            if (ReadInteger(master["nibbleCycleTicks"]) is { } cycle && ReadInteger(master["nibbleTicks"]) is { } nibble && cycle > nibble)
                Error("FISHING_NIBBLE_CYCLE", "/nibbleCycleTicks", "nibbleCycleTicks は nibbleTicks 以下にしてください。");
            if (ReadInteger(master["fishCountMin"]) is { } minCount && ReadInteger(master["fishCountMax"]) is { } maxCount && minCount > maxCount)
                Error("FISHING_FISH_COUNT_RANGE", "/fishCountMax", "fishCountMax は fishCountMin 以上にしてください。");
        }
        else
        {
            if (Text(master["type"]) != "FISHING_AREA") Error("FISHING_TYPE", "/type", "type は FISHING_AREA を指定してください。");
            if (string.IsNullOrWhiteSpace(Text(master["name"]))) Error("FISHING_NAME", "/name", "name は必須です。");
            if (master["enabled"]?.GetValueKind() is not (JsonValueKind.True or JsonValueKind.False))
                Error("FISHING_ENABLED", "/enabled", "enabled は真偽値で指定してください。");
            await ReferenceAsync(master["worldId"], "world", "/worldId", "60.features.world", paths, codec, issues, token);
            await ReferenceAsync(master["rulesId"], "fishing_rules", "/rulesId", RulesDirectory, paths, codec, issues, token);
            if (master["levelRanges"] is not JsonArray ranges || ranges.Count == 0)
                Error("FISHING_LEVEL_RANGES", "/levelRanges", "levelRanges には1件以上のレベル帯を指定してください。");
            else
            {
                var bounds = new List<(long Min, long Max, int Index)>();
                for (var i = 0; i < ranges.Count; i++)
                {
                    var pointer = $"/levelRanges/{i}";
                    if (ranges[i] is not JsonObject band) { Error("FISHING_LEVEL_RANGE", pointer, "レベル帯はオブジェクトで指定してください。"); continue; }
                    var min = ReadInteger(band["minLevel"]);
                    var max = ReadInteger(band["maxLevel"]);
                    if (min is null || min < 0) Error("FISHING_MIN_LEVEL", pointer + "/minLevel", "minLevel は 0 以上の整数で指定してください。");
                    if (max is null || max < 0 || max > MaxLevel || min is not null && max < min)
                        Error("FISHING_MAX_LEVEL", pointer + "/maxLevel", "maxLevel は minLevel 以上、2147483647 以下の整数で指定してください（両端を含みます）。");
                    if (min is >= 0 && max >= min && max <= MaxLevel) bounds.Add((min.Value, max.Value, i));
                    if (band["fish"] is not JsonArray fishes || fishes.Count == 0)
                    { Error("FISHING_BAND_FISH", pointer + "/fish", "各レベル帯には1件以上の魚を指定してください。"); continue; }
                    var ids = new HashSet<string>(StringComparer.Ordinal);
                    double total = 0;
                    for (var j = 0; j < fishes.Count; j++)
                    {
                        var entryPointer = pointer + "/fish/" + j;
                        if (fishes[j] is not JsonObject entry) { Error("FISHING_BAND_ENTRY", entryPointer, "魚の候補はオブジェクトで指定してください。"); continue; }
                        var id = await ReferenceAsync(entry["fishId"], "fishing_fish", entryPointer + "/fishId", FishDirectory, paths, codec, issues, token);
                        if (id is not null && !ids.Add(id)) Error("FISHING_DUPLICATE_FISH", entryPointer + "/fishId", "同じレベル帯に同じ魚を複数指定できません。");
                        if (!Number(entry["chancePercent"], 0, 100, positive: true))
                            Error("FISHING_CHANCE", entryPointer + "/chancePercent", "chancePercent は 0 より大きく 100 以下の有限数で指定してください。");
                        else total += ReadNumber(entry["chancePercent"])!.Value;
                    }
                    if (Math.Abs(total - 100) > 0.000001)
                        Error("FISHING_CHANCE_SUM", pointer + "/fish", $"出現確率の合計は 100% にしてください（現在 {total:G17}%）。");
                }
                var ordered = bounds.OrderBy(range => range.Min).ThenBy(range => range.Max).ToArray();
                for (var i = 1; i < ordered.Length; i++)
                    if (ordered[i].Min <= ordered[i - 1].Max)
                        Error("FISHING_LEVEL_OVERLAP", $"/levelRanges/{ordered[i].Index}/minLevel", "レベル帯が重複しています。maxLevel は両端を含みます。");
            }
            if (master["enabled"]?.GetValueKind() == JsonValueKind.True && ReferenceId(master["worldId"], "world") is { } worldId)
                foreach (var other in paths.Enumerate(AreaDirectory).Where(MasterDataPaths.IsData))
                {
                    if (paths.Relative(other).Equals(path, MasterDataPaths.Comparison) || other.EndsWith(".schema.json", StringComparison.OrdinalIgnoreCase)) continue;
                    var parsed = codec.Parse(other, await File.ReadAllTextAsync(other, token)).Content as JsonObject;
                    if (parsed?["enabled"]?.GetValueKind() == JsonValueKind.True && ReferenceId(parsed["worldId"], "world") == worldId)
                        Error("FISHING_DUPLICATE_ENABLED_WORLD", "/worldId", $"このワールドには有効な釣りエリアが既にあります: {paths.Relative(other)}");
                }
        }
    }

    private static async Task<string?> ReferenceAsync(JsonNode? value, string prefix, string pointer, string directory,
        MasterDataPaths paths, MasterDataCodec codec, List<MasterDataIssue> issues, CancellationToken token, JsonNode? caughtAmount = null)
    {
        var id = ReferenceId(value, prefix);
        if (id is null) { issues.Add(new("error", "FISHING_REFERENCE", pointer, $"{{ref: {prefix}:<id>}} 形式で指定してください。")); return null; }
        var found = false;
        foreach (var file in paths.Enumerate(directory).Where(MasterDataPaths.IsData))
        {
            if (file.EndsWith(".schema.json", StringComparison.OrdinalIgnoreCase)) continue;
            var other = codec.Parse(file, await File.ReadAllTextAsync(file, token)).Content as JsonObject;
            if (Text(other?["id"]) == id)
            {
                if (pointer == "/caughtItemId")
                {
                    if (Text(other?["category"]) is "equipment" or "pet" or "pet_egg")
                        issues.Add(new("error", "FISHING_CAUGHT_ITEM_CATEGORY", pointer, "装備・ペット・ペットの卵は釣果アイテムに指定できません。"));
                    var maxStack = ReadInteger(other?["maxStack"]) ?? 64;
                    if (ReadInteger(caughtAmount) is { } amount && amount > maxStack)
                        issues.Add(new("error", "FISHING_CAUGHT_STACK", "/caughtAmount", $"caughtAmount は釣果アイテムの maxStack ({maxStack}) 以下にしてください。"));
                }
                found = true;
                break;
            }
        }
        if (!found) issues.Add(new("error", "FISHING_REFERENCE_NOT_FOUND", pointer, $"参照先 {prefix}:{id} が見つかりません。"));
        return id;
    }

    private static string? ReferenceId(JsonNode? value, string prefix)
    {
        if (value is not JsonObject obj || obj.Count != 1 || Text(obj["ref"]) is not { } reference) return null;
        return reference.StartsWith(prefix + ":", StringComparison.Ordinal) && reference.Length > prefix.Length + 1 ? reference[(prefix.Length + 1)..] : null;
    }
    private static string? Text(JsonNode? value) => value is JsonValue scalar && scalar.GetValueKind() == JsonValueKind.String ? scalar.GetValue<string>() : null;
    private static double? ReadNumber(JsonNode? value) => value is JsonValue scalar && scalar.GetValueKind() == JsonValueKind.Number
        && double.TryParse(scalar.ToJsonString(), System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var number) && double.IsFinite(number) ? number : null;
    private static long? ReadInteger(JsonNode? value) => ReadNumber(value) is { } number && number == Math.Truncate(number) && number >= long.MinValue && number <= long.MaxValue ? (long)number : null;
    private static bool Integer(JsonNode? value, long min, long max) => ReadInteger(value) is { } number && number >= min && number <= max;
    private static bool Number(JsonNode? value, double min, double max, bool positive = false) => ReadNumber(value) is { } number && number >= min && number <= max && (!positive || number > 0);
}
