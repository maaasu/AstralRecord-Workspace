using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Models;

namespace SkillTreeEditor.Server.Services;

/// <summary>JSON Schemaで表せないペットの相互制約と実マスターの対応を保存前に確認する。</summary>
internal static class PetMasterValidation
{
    internal const string MasterPath = "55.features.pet/v1.pets.yml";

    internal static async Task ValidateAsync(MasterDataPaths paths, MasterDataCodec codec, string path, JsonObject proposed,
        List<MasterDataIssue> issues, CancellationToken token)
    {
        var editingPet = path.StartsWith("55.features.pet/", MasterDataPaths.Comparison);
        if (!editingPet && !path.StartsWith("10.features.item/", MasterDataPaths.Comparison)
            && !path.StartsWith("40.features.mob/npc/", MasterDataPaths.Comparison)) return;
        JsonObject? master = proposed;
        if (!editingPet)
        {
            var full = paths.Resolve(MasterPath);
            if (!File.Exists(full)) return;
            master = codec.Parse(MasterPath, await File.ReadAllTextAsync(full, token)).Content as JsonObject;
        }
        if (master is null) return;
        if (editingPet)
        {
            Check(path == MasterPath, "PET_SINGLETON", "/id", "ペットマスターは55.features.pet/v1.pets.ymlの単一id: petsに保存してください。", issues);
            ValidateRules(master, issues);
        }
        var proposedId = JsonValueReader.String(proposed["id"]);
        var wantedItems = ItemBindings(master).Where(binding => editingPet || binding.Id == proposedId).ToArray();
        var facilityId = JsonValueReader.String((master["rules"] as JsonObject)?["facilityId"]);
        var validateFacility = editingPet || path.StartsWith("40.features.mob/npc/", MasterDataPaths.Comparison) && facilityId == proposedId;
        if (wantedItems.Length == 0 && !validateFacility) return;
        Master[] items = wantedItems.Length == 0 ? [] : await ReadMastersAsync(paths, codec, "10.features.item", path, proposed, token);
        foreach (var binding in wantedItems)
        {
            var item = items.FirstOrDefault(candidate => JsonValueReader.String(candidate.Content["id"]) == binding.Id);
            var valid = item is not null && JsonValueReader.String(item.Content["category"]) == binding.Category
                && item.Path.StartsWith("10.features.item/" + binding.Folder + "/", MasterDataPaths.Comparison);
            if (valid && binding.Category is "pet" or "pet_egg")
                valid = Number(item!.Content["maxStack"]) == 1 && item.Content["unTradeable"]?.ToJsonString() != "true";
            if (valid && binding.Category == "orb")
                valid = JsonValueReader.String(((item!.Content["orb"] as JsonObject)?["effect"] as JsonObject)?["type"]) == "PET_REVIVE";
            Check(valid, "PET_ITEM_BINDING", binding.Pointer,
                $"item:{binding.Id}は{binding.Folder}の{binding.Category}定義が必要です。ペット/卵はmaxStack: 1と譲渡可能、復活オーブはPET_REVIVEにしてください。", issues);
        }
        if (validateFacility)
        {
            var npcs = await ReadMastersAsync(paths, codec, "40.features.mob/npc", path, proposed, token);
            var npc = npcs.FirstOrDefault(candidate => JsonValueReader.String(candidate.Content["id"]) == facilityId);
            var interactions = npc?.Content["interactions"] as JsonObject;
            var actions = new[] { "leftClick", "rightClick" }
                .SelectMany(key => (interactions?[key] as JsonArray)?.OfType<JsonObject>() ?? []);
            Check(npc is not null && JsonValueReader.String(npc.Content["category"]) == "NPC"
                && actions.Any(action => JsonValueReader.String(action["id"]) == "gui"
                    && JsonValueReader.String((action["params"] as JsonObject)?["type"]) == "PET_CENTER"),
                "PET_FACILITY_BINDING", "/rules/facilityId", "施設NPCはinteractionsのguiでtype: PET_CENTERを持つNPCマスターにしてください。", issues);
        }
    }

    private static void ValidateRules(JsonObject master, List<MasterDataIssue> issues)
    {
        Check(JsonValueReader.String(master["id"]) == "pets", "PET_SINGLETON", "/id", "ペットマスターIDはpets固定です。", issues);
        if (master["rules"] is not JsonObject rules || master["species"] is not JsonArray species) return;
        var max = Number(rules["maxLevel"]); var wild = Number(rules["wildMaxTier"]); var breed = Number(rules["breedMaxTier"]);
        Check(max >= 50 && breed > wild, "PET_TIER_BOUNDS", "/rules", "最大成長度は50以上、配合の最大ティアは野生の最大ティアより高くしてください。", issues);
        var slots = (rules["skillSlotLevels"] as JsonArray)?.Select(Number).ToArray() ?? [];
        Check(slots.Length == 3 && slots[0] == 1 && slots[0] < slots[1] && slots[1] < slots[2] && slots[2] <= max
            && Number(rules["breedMinLevel"]) >= slots[1] && Number(rules["breedMinLevel"]) <= max,
            "PET_GROWTH_LEVELS", "/rules/skillSlotLevels", "3枠の成長度は1から昇順、配合成長度は2枠目以上かつ最大成長度以下にしてください。", issues);
        Check(IsInteger(rules["breedCooldownHours"]) && Number(rules["breedCooldownHours"]) > 0,
            "PET_BREED_COOLDOWN", "/rules/breedCooldownHours", "配合待機時間はAPIと同じ正の整数時間で指定してください。", issues);
        var weights = rules["potentialCountWeights"] as JsonArray;
        Check(Number(rules["potentialGrowthMultiplier"]) == 2 && weights?.Count == 3 && weights.All(IsInteger)
            && weights.All(value => Number(value) >= 0) && weights.Sum(Number) > 0,
            "PET_POTENTIAL", "/rules/potentialCountWeights", "潜在成長倍率は2固定、継承重みは非負整数3個で合計を正にしてください。", issues);
        if (master["experience"] is JsonObject curve)
            Check(Number(curve["activityRate"]) > 0, "PET_EXPERIENCE", "/experience/activityRate", "活動経験値比率はAPIと同じ正数で指定してください。", issues);
        var definitions = species.OfType<JsonObject>().ToArray();
        Check(definitions.Select(s => JsonValueReader.String(s["id"])).Order().SequenceEqual(new[] { "cat", "chicken", "wolf" }),
            "PET_SPECIES", "/species", "wolf、cat、chickenを各1種類ずつ定義してください。", issues);
        for (var index = 0; index < definitions.Length; index++)
        {
            var entry = definitions[index]; var pointer = "/species/" + index;
            var expectedEntity = JsonValueReader.String(entry["id"]) switch { "wolf" => "WOLF", "cat" => "CAT", "chicken" => "CHICKEN", _ => null };
            Check(JsonValueReader.String(entry["entityType"]) == expectedEntity && Number(entry["sizeMin"]) <= Number(entry["sizeMax"]),
                "PET_SPECIES_VALUES", pointer, "種類とエンティティ種別を対応させ、サイズ下限を上限以下にしてください。", issues);
            if (entry["skills"] is JsonArray skills)
            {
                var skillRows = skills.OfType<JsonObject>().ToArray();
                Check(skillRows.Length == 8 && skillRows.Select(s => JsonValueReader.String(s["id"])).Distinct().Count() == 8
                    && Enumerable.Range(0, 4).All(tier => skillRows.Count(s => Number(s["tier"]) == tier) == 2),
                    "PET_SKILL_TIERS", pointer + "/skills", "スキルIDは重複させず、T0〜T3を各2個、合計8個にしてください。", issues);
            }
            if (entry["stats"] is not JsonObject stats) continue;
            foreach (var (key, definition) in stats)
            {
                if (definition is not JsonObject stat || stat["tiers"] is not JsonArray tierValues) continue;
                var tiers = tierValues.OfType<JsonObject>().OrderBy(t => Number(t["tier"])).ToArray();
                var ordered = tiers.Select(t => Number(t["tier"])).SequenceEqual(Enumerable.Range(0, (int)Math.Clamp(breed + 1, 0, 101)).Select(t => (double)t));
                Check(ordered && tiers.All(t => Number(t["min"]) <= Number(t["max"]) && Number(t["max"]) <= 100000)
                    && tiers.Zip(tiers.Skip(1)).All(pair => Number(pair.First["max"]) < Number(pair.Second["min"]))
                    && tiers.Where(t => Number(t["tier"]) <= wild).Sum(t => Number(t["weight"])) > 0,
                    "PET_STAT_TIERS", pointer + "/stats/" + key + "/tiers", "T0から配合上限まで連続させ、上位の範囲を高くし、野生ティアの抽選重み合計を正にしてください。", issues);
                Check(Number(stat["valueWeightPower"]) <= 16 && Number(stat["inheritanceMin"]) <= Number(stat["inheritanceMax"]),
                    "PET_STAT_SCALING", pointer + "/stats/" + key, "個体値の重み指数は16以下、継承係数は下限を上限以下にしてください。", issues);
            }
        }
    }

    private sealed record Binding(string Id, string Category, string Folder, string Pointer);
    private sealed record Master(string Path, JsonObject Content);
    private static IEnumerable<Binding> ItemBindings(JsonObject master)
    {
        if (master["rules"] is JsonObject rules)
        {
            yield return new(ItemId(rules["reviveOrbItemId"]), "orb", "40.orb", "/rules/reviveOrbItemId");
            foreach (var group in new[] { "breedMaterials", "reviveMaterials" })
                foreach (var binding in Materials(rules[group], "/rules/" + group)) yield return binding;
        }
        if (master["species"] is JsonArray species)
            for (var index = 0; index < species.Count; index++)
            {
                if (species[index] is not JsonObject entry) continue;
                var pointer = "/species/" + index;
                yield return new(ItemId(entry["eggItemId"]), "pet_egg", "81.pet_egg", pointer + "/eggItemId");
                yield return new(ItemId(entry["petItemId"]), "pet", "80.pet", pointer + "/petItemId");
                foreach (var binding in Materials(entry["hatchMaterials"], pointer + "/hatchMaterials")) yield return binding;
            }
    }
    private static IEnumerable<Binding> Materials(JsonNode? node, string pointer)
    {
        if (node is not JsonArray entries) yield break;
        for (var index = 0; index < entries.Count; index++)
            if (entries[index] is JsonObject material) yield return new(ItemId(material["itemId"]), "material", "10.material", pointer + "/" + index + "/itemId");
    }
    private static async Task<Master[]> ReadMastersAsync(MasterDataPaths paths, MasterDataCodec codec, string directory,
        string proposedPath, JsonObject proposed, CancellationToken token)
    {
        var result = new List<Master>();
        foreach (var full in paths.Enumerate(directory).Where(MasterDataPaths.IsData))
        {
            var relative = paths.Relative(full);
            if (full.EndsWith(".schema.json", StringComparison.OrdinalIgnoreCase) || relative == proposedPath) continue;
            if (codec.Parse(relative, await File.ReadAllTextAsync(full, token)).Content is JsonObject content) result.Add(new(relative, content));
        }
        if (proposedPath.StartsWith(directory + "/", MasterDataPaths.Comparison)) result.Insert(0, new(proposedPath, proposed));
        return result.ToArray();
    }
    private static string ItemId(JsonNode? node)
    { var value = JsonValueReader.String(node) ?? ""; return value.StartsWith("item:", StringComparison.Ordinal) ? value[5..] : value; }
    private static double Number(JsonNode? node) => JsonValueReader.Number(node) is { } value && double.IsFinite(value) ? value : -1;
    private static bool IsInteger(JsonNode? node) => JsonValueReader.Number(node) is { } value && double.IsFinite(value) && value == Math.Truncate(value);
    private static void Check(bool valid, string code, string pointer, string message, List<MasterDataIssue> issues)
    { if (!valid) issues.Add(new("error", code, pointer, message)); }
}
