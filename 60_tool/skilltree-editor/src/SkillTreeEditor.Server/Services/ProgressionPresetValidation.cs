using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Models;

namespace SkillTreeEditor.Server.Services;

internal static class ProgressionPresetValidation
{
    internal const string Category = "36.features.progression_preset";
    internal const string SchemaPath = Category + "/schemas/progression-preset.v1.schema.json";

    internal static bool IsPreset(string path) => path.StartsWith(Category + "/", MasterDataPaths.Comparison)
        && !path.EndsWith(".schema.json", StringComparison.OrdinalIgnoreCase);

    internal static async Task ValidateAsync(MasterDataPaths paths, MasterDataCodec codec, string path,
        JsonObject preset, List<MasterDataIssue> issues, CancellationToken token)
    {
        var id = MasterDataValidation.Text(preset["id"]);
        if (path != $"{Category}/v1.{id}.yml")
            issues.Add(new("error", "PROGRESSION_PRESET_FILENAME", "/id", "保存先はカテゴリ直下の v1.<id>.yml にしてください。"));

        var classId = MasterDataValidation.Text(preset["classId"]);
        var buildId = MasterDataValidation.Text(preset["buildId"]);
        if (!string.IsNullOrWhiteSpace(classId))
        {
            var classes = await ReadMastersAsync(paths, codec, "20.features.class", token);
            if (classId == "administrator" || !classes.Any(entry => MasterDataValidation.Text(entry.Content["id"]) == classId))
                issues.Add(new("error", "REFERENCE_NOT_FOUND", "/classId", $"クラス '{classId}' はプリセットに指定できません。"));
        }
        if (!string.IsNullOrWhiteSpace(classId) && !string.IsNullOrWhiteSpace(buildId))
        {
            var presets = await ReadMastersAsync(paths, codec, Category, token);
            foreach (var other in presets)
                if (!other.Path.Equals(path, MasterDataPaths.Comparison)
                    && MasterDataValidation.Text(other.Content["classId"]) == classId
                    && MasterDataValidation.Text(other.Content["buildId"]) == buildId)
                    issues.Add(new("error", "PROGRESSION_PRESET_DUPLICATE_BUILD", "/buildId",
                        $"クラスとビルドの組み合わせは {other.Path} で使用されています。"));
        }

        if (preset["levels"] is not JsonArray levels) return;
        var nodes = await ReadMastersAsync(paths, codec, "35.features.skilltree/nodes", token);
        var nodeIds = nodes.Select(entry => MasterDataValidation.Text(entry.Content["nodeId"])).ToHashSet(StringComparer.Ordinal);
        var equipment = await ReadReferenceIndexAsync(paths, codec, "10.features.item/20.equipment", "item:", token);
        var skills = await ReadReferenceIndexAsync(paths, codec, "30.features.skill", "skill:", token);
        var sigils = await ReadReferenceIndexAsync(paths, codec, "10.features.item/70.sigil", "item:", token);
        var previous = 0;
        for (var index = 0; index < levels.Count; index++)
        {
            if (levels[index] is not JsonObject snapshot) continue;
            var pointer = $"/levels/{index}";
            var level = ReadInteger(snapshot["level"]);
            if (level > 0)
            {
                if (index == 0 && level != 1 || index > 0 && level <= previous)
                    issues.Add(new("error", "PROGRESSION_PRESET_LEVEL_ORDER", pointer + "/level",
                        "最初のレベルは1にし、以降は重複せず昇順にしてください。"));
                previous = level;
            }
            if (snapshot["nodes"] is JsonArray selectedNodes)
                for (var nodeIndex = 0; nodeIndex < selectedNodes.Count; nodeIndex++)
                {
                    var nodeId = ReadInteger(selectedNodes[nodeIndex]);
                    if (nodeId > 0 && !nodeIds.Contains(nodeId.ToString(CultureInfo.InvariantCulture)))
                        issues.Add(new("error", "REFERENCE_NOT_FOUND", $"{pointer}/nodes/{nodeIndex}", $"ノード '{nodeId}' がありません。"));
                }

            foreach (var key in new[] { "equipment", "skills", "passives" })
            {
                if (snapshot[key] is not JsonArray assignments) continue;
                var slots = new HashSet<string>(StringComparer.Ordinal);
                var skillIds = new HashSet<string>(StringComparer.Ordinal);
                for (var slotIndex = 0; slotIndex < assignments.Count; slotIndex++)
                {
                    if (assignments[slotIndex] is not JsonObject assignment) continue;
                    var assignmentPath = $"{pointer}/{key}/{slotIndex}";
                    var slot = key == "equipment"
                        ? MasterDataValidation.Text(assignment["slot"])
                        : ReadPositiveIntegerText(assignment["slot"]);
                    if (slot is not null && !slots.Add(slot))
                        issues.Add(new("error", "PROGRESSION_PRESET_DUPLICATE_SLOT", assignmentPath + "/slot",
                            $"スロット '{slot}' が重複しています。"));
                    if (key == "equipment")
                    {
                        ValidateEquipment(assignment, equipment, assignmentPath, issues);
                        continue;
                    }
                    if (MasterDataValidation.Text(assignment["skill"]) is not { } skill) continue;
                    if (!skillIds.Add(skill))
                        issues.Add(new("error", "PROGRESSION_PRESET_DUPLICATE_SKILL", assignmentPath + "/skill",
                            $"スキル '{skill}' が重複しています。"));
                    ValidateSkill(assignment, skill, skills, sigils, key == "passives", level, assignmentPath, issues);
                }
            }
        }
    }

    private static void ValidateEquipment(JsonObject assignment, IReadOnlyDictionary<string, JsonObject> equipment,
        string pointer, List<MasterDataIssue> issues)
    {
        if (MasterDataValidation.Text(assignment["item"]) is not { } reference) return;
        if (!equipment.TryGetValue(reference, out var item))
        {
            issues.Add(new("error", "REFERENCE_NOT_FOUND", pointer + "/item", $"装備 '{reference}' がありません。"));
            return;
        }
        var expected = MasterDataValidation.Text(assignment["slot"]) switch
        {
            "MAIN_HAND" => "WEAPON",
            "OFF_HAND" => "SUBWEAPON",
            var slot => slot
        };
        if (item["equipment"] is not JsonObject definition || MasterDataValidation.Text(definition["slot"]) != expected)
        {
            issues.Add(new("error", "PROGRESSION_PRESET_EQUIPMENT_SLOT", pointer + "/item", "指定した装備枠に対応するアイテムを選んでください。"));
            return;
        }

        var enhanceLevel = ReadInteger(assignment["enhanceLevel"]);
        if (enhanceLevel == 0) return;
        var maxEnhanceLevel = ReachableEnhanceLevel(definition["enhance"] as JsonObject);
        if (enhanceLevel > maxEnhanceLevel)
            issues.Add(new("error", "PROGRESSION_PRESET_ENHANCE_LEVEL", pointer + "/enhanceLevel",
                $"状態変化前の装備の強化上限は +{maxEnhanceLevel} です。"));
    }

    private static int ReachableEnhanceLevel(JsonObject? enhance)
    {
        var cap = ReadInteger(enhance?["maxLevel"]);
        var levels = (enhance?["levels"] as JsonArray ?? []).OfType<JsonObject>()
            .Select(row => ReadInteger(row["level"])).Where(level => level > 0).Distinct().Order();
        var reachable = 0;
        foreach (var level in levels)
        {
            if (level > cap || level - reachable != 1) break;
            reachable = level;
        }
        return reachable;
    }

    private static void ValidateSkill(JsonObject assignment, string reference, IReadOnlyDictionary<string, JsonObject> skills,
        IReadOnlyDictionary<string, JsonObject> sigils, bool passive, int level, string pointer, List<MasterDataIssue> issues)
    {
        if (!skills.TryGetValue(reference, out var skill))
        {
            issues.Add(new("error", "REFERENCE_NOT_FOUND", pointer + "/skill", $"スキル '{reference}' がありません。"));
            return;
        }
        if ((skill["passive"] is JsonObject) != passive)
            issues.Add(new("error", "PROGRESSION_PRESET_SKILL_KIND", pointer + "/skill",
                passive ? "パッシブスキルを指定してください。" : "アクティブスキルを指定してください。"));
        if (level > 0 && ReadInteger(skill["requiredLevel"]) > level)
            issues.Add(new("error", "PROGRESSION_PRESET_SKILL_LEVEL", pointer + "/skill", "このスキルの必要プレイヤーレベルが適用開始レベルを超えています。"));

        var skillLevel = ReadInteger(assignment["skillLevel"], 1);
        var maxSkillLevel = ReadInteger(skill["maxLevel"], 1);
        if (skillLevel > maxSkillLevel)
            issues.Add(new("error", "PROGRESSION_PRESET_SKILL_MAX_LEVEL", pointer + "/skillLevel",
                $"このスキルの最大レベルは {maxSkillLevel} です。"));
        var attachments = assignment["sigils"] as JsonArray;
        if ((skill["passive"] as JsonObject)?["bindRequired"]?.GetValueKind() == JsonValueKind.False
            && (skillLevel > 1 || attachments?.Count > 0))
        {
            issues.Add(new("error", "PROGRESSION_PRESET_BIND_FREE_PASSIVE", pointer,
                "バインド不要のパッシブにはスキルレベルの変更やシジルの装着を指定できません。"));
        }
        if (attachments is { Count: > 0 })
            ValidateSigils(attachments, skill, skillLevel, sigils, pointer + "/sigils", issues);
    }

    private static void ValidateSigils(JsonArray attachments, JsonObject skill, int skillLevel,
        IReadOnlyDictionary<string, JsonObject> sigils, string pointer, List<MasterDataIssue> issues)
    {
        var maxSlots = (skill["sigilSlotsByLevel"] as JsonArray ?? []).OfType<JsonObject>()
            .Where(row => ReadInteger(row["level"], int.MaxValue) <= skillLevel)
            .Select(row => ReadInteger(row["slots"])).DefaultIfEmpty(0).Max();
        var allowedIds = (skill["allowedSigilIds"] as JsonArray ?? [])
            .Select(node => MasterDataValidation.Text(node)?.Trim()).ToHashSet(StringComparer.OrdinalIgnoreCase);
        var slots = new HashSet<int>();
        var groups = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        for (var index = 0; index < attachments.Count; index++)
        {
            if (attachments[index] is not JsonObject attachment) continue;
            var attachmentPath = $"{pointer}/{index}";
            var slot = ReadInteger(attachment["slot"]);
            if (slot > maxSlots)
                issues.Add(new("error", "PROGRESSION_PRESET_SIGIL_SLOT", attachmentPath + "/slot",
                    $"スキルレベル {skillLevel} で使用できるシジル枠は {maxSlots} 個です。"));
            if (slot > 0 && !slots.Add(slot))
                issues.Add(new("error", "PROGRESSION_PRESET_DUPLICATE_SIGIL_SLOT", attachmentPath + "/slot", "同じシジル枠が重複しています。"));

            if (MasterDataValidation.Text(attachment["item"]) is not { } reference) continue;
            if (!sigils.TryGetValue(reference, out var item) || MasterDataValidation.Text(item["category"]) != "sigil"
                || MasterDataValidation.Text((item["sigil"] as JsonObject)?["equipGroupId"])?.Trim() is not { Length: > 0 } group)
            {
                issues.Add(new("error", "PROGRESSION_PRESET_SIGIL_ITEM", attachmentPath + "/item", $"シジル '{reference}' がありません。"));
                continue;
            }
            if (!allowedIds.Contains(MasterDataValidation.Text(item["id"])))
                issues.Add(new("error", "PROGRESSION_PRESET_SIGIL_NOT_ALLOWED", attachmentPath + "/item", "このスキルに装着できるシジルを選んでください。"));
            if (!groups.Add(group))
                issues.Add(new("error", "PROGRESSION_PRESET_DUPLICATE_SIGIL_GROUP", attachmentPath + "/item",
                    $"同じシジルグループ '{group}' を複数装着できません。"));
        }
    }

    private static int ReadInteger(JsonNode? node, int fallback = 0)
        => node?.GetValueKind() == JsonValueKind.Number ? ParseInteger(node.ToJsonString(), fallback) : fallback;

    private static string? ReadPositiveIntegerText(JsonNode? node)
        => ReadInteger(node) is var value && value > 0 ? value.ToString(CultureInfo.InvariantCulture) : null;

    internal static bool MatchesNodeId(string text, string nodeId)
        => ParseInteger(text, -1).ToString(CultureInfo.InvariantCulture) == nodeId;

    private static int ParseInteger(string text, int fallback)
        => decimal.TryParse(text, NumberStyles.Float, CultureInfo.InvariantCulture, out var value)
            && value is >= 0 and <= int.MaxValue && value == decimal.Truncate(value)
                ? (int)value : fallback;

    private static async Task<Dictionary<string, JsonObject>> ReadReferenceIndexAsync(MasterDataPaths paths,
        MasterDataCodec codec, string category, string prefix, CancellationToken token)
    {
        var index = new Dictionary<string, JsonObject>(StringComparer.Ordinal);
        foreach (var entry in await ReadMastersAsync(paths, codec, category, token))
            if (MasterDataValidation.Text(entry.Content["id"]) is { } id)
                index.TryAdd(prefix + id, entry.Content);
        return index;
    }

    internal static async Task<IReadOnlyList<string>> FindNodeReferencesAsync(
        MasterDataPaths paths, MasterDataCodec codec, string nodeId, CancellationToken token)
    {
        var presets = await ReadMastersAsync(paths, codec, Category, token);
        return presets.Where(preset => preset.Content["levels"] is JsonArray levels
                && levels.OfType<JsonObject>().Any(snapshot => snapshot["nodes"] is JsonArray nodes
                    && nodes.Any(node => ReadPositiveIntegerText(node) == nodeId)))
            .Select(preset => preset.Path).ToArray();
    }

    private static async Task<List<(string Path, JsonObject Content)>> ReadMastersAsync(
        MasterDataPaths paths, MasterDataCodec codec, string category, CancellationToken token)
    {
        var masters = new List<(string, JsonObject)>();
        foreach (var file in paths.Enumerate(category).Where(MasterDataPaths.IsData))
        {
            if (file.EndsWith(".schema.json", StringComparison.OrdinalIgnoreCase)) continue;
            var relative = paths.Relative(file);
            if (codec.Parse(relative, await File.ReadAllTextAsync(file, token)).Content is JsonObject master)
                masters.Add((relative, master));
        }
        return masters;
    }
}
