using System.Text.Json;
using System.Text.Json.Nodes;
using System.Security.Cryptography;
using System.Text;
using System.Globalization;
using SkillTreeEditor.Server.Models;

namespace SkillTreeEditor.Server.Services;

/// <summary>Filebase の定義から、装備設計と入手経路の共通索引を構築します。</summary>
public sealed class ProgressionDesignService(FilebaseSnapshotCache snapshots, MasterDataPaths paths)
{
    private const string Configured = "configured";
    private const string DefinitionOnly = "definitionOnly";
    private volatile Latest? _latest;

    private sealed record Latest(FilebaseSnapshot Snapshot, ProgressionDesignCatalog Catalog);
    private sealed record Master(string Path, JsonObject Data, string Id, string Name, string Kind)
    {
        public ProgressionDesignContext Context(string pointer = "") => new(Kind, Id, Name, Path, pointer);
    }

    private sealed record LinkedMaster(ProgressionDesignContext Context, int? Level = null);
    private sealed record Drop(string ItemId, Master Master, string Pointer, string ItemPointer,
        ProgressionDesignField[] Quantity, ProgressionDesignField[] Rates, ProgressionDesignContext[] Context);

    public async Task<ProgressionDesignCatalog> ReadAsync(CancellationToken token, bool reload = false)
    {
        var snapshot = await snapshots.GetAsync(token, reload: reload, includeSchemas: false);
        if (_latest is { } latest && ReferenceEquals(latest.Snapshot, snapshot)) return latest.Catalog;
        var catalog = Build(snapshot, null, token);
        _latest = new(snapshot, catalog);
        return catalog;
    }

    public async Task<ProgressionDesignCatalog> PreviewAsync(ProgressionDesignPreviewRequest request, CancellationToken token)
    {
        ArgumentNullException.ThrowIfNull(request.Documents);
        var snapshot = await snapshots.GetAsync(token, includeSchemas: false);
        var documents = snapshot.Documents.ToDictionary(document => document.Path, StringComparer.Ordinal);
        var overrides = new Dictionary<string, JsonObject>(StringComparer.Ordinal);
        foreach (var draft in request.Documents)
        {
            token.ThrowIfCancellationRequested();
            if (draft is null || draft.Content is null || string.IsNullOrWhiteSpace(draft.Path)
                || !documents.TryGetValue(draft.Path, out var document) || document.IsReadOnly
                || document.PathError is not null || document.DecodeError is not null
                || Kind(document.Path) is null || !overrides.TryAdd(document.Path, draft.Content))
                throw new ArgumentException("同じ編集対象マスターを重複せずに指定してください。");
            paths.Resolve(document.Path);
            if (draft.Revision is not null && draft.Revision != document.Revision)
                throw new InvalidOperationException($"{document.Path} は別の編集で更新されました。下書きを保持して差分を確認してください。");
        }
        return Build(snapshot, overrides, token);
    }

    private static ProgressionDesignCatalog Build(FilebaseSnapshot snapshot,
        Dictionary<string, JsonObject>? overrides, CancellationToken token)
    {
        var masters = new List<Master>();
        foreach (var document in snapshot.Documents.OrderBy(document => document.Path, StringComparer.Ordinal))
        {
            token.ThrowIfCancellationRequested();
            if (document.PathError is not null || document.DecodeError is not null || Kind(document.Path) is not { } kind)
                continue;
            var data = overrides?.GetValueOrDefault(document.Path) ?? document.Content as JsonObject;
            var masterId = kind == "node" ? NodeId(data?["nodeId"])?.ToString(CultureInfo.InvariantCulture) : Id(data?["id"]);
            if (data is not null && masterId is { } id)
                masters.Add(new(document.Path, data, id, MasterDataService.DisplayName(data) ?? id, kind));
        }

        var classes = masters.Where(master => master.Kind == "class").Select(ReadClass).ToArray();
        var presets = masters.Where(master => master.Kind == "preset").Select(ReadPreset).OfType<ProgressionDesignPreset>().ToArray();
        var items = masters.Where(master => master.Kind == "item").Select(ReadItem).ToArray();
        var enchantChoices = ReadEnchantChoices(masters, snapshot);
        var skills = masters.Where(master => master.Kind == "skill").Select(ReadSkill).ToArray();
        var nodes = masters.Where(master => master.Kind == "node" && NodeId(master.Data["nodeId"]) is > 0)
            .Select(master => new ProgressionDesignNode(NodeId(master.Data["nodeId"])!.Value, master.Name, master.Path,
                Text(master.Data["icon"]), Text(master.Data["iconTexture"]), Integer(master.Data["customModelData"]),
                Rows(master.Data["effects"], "/effects").Where(effect => Text(effect.Data["type"]) == "skill")
                    .Select(effect => Id(effect.Data["skillId"])).OfType<string>().Distinct(StringComparer.Ordinal).ToArray()))
            .OrderBy(node => node.Id).ToArray();

        var pools = Index(masters, "lootPool");
        var tables = Index(masters, "lootTable");
        var recipes = Index(masters, "recipe");
        var fish = Index(masters, "fish");
        var contexts = ReadContexts(masters);
        var sources = new List<ProgressionDesignSource>();
        var usages = new List<ProgressionDesignUsage>();
        var targets = new List<ProgressionDesignAddTarget>();
        foreach (var master in masters)
        {
            token.ThrowIfCancellationRequested();
            var related = contexts[master.Kind + ":" + master.Id].ToArray();
            switch (master.Kind)
            {
                case "recipe":
                    ReadRecipe(master, sources);
                    break;
                case "shop":
                    ReadShop(master, recipes, related, sources);
                    break;
                case "mob":
                    ReadMob(master, pools, tables, related, sources, targets);
                    break;
                case "gathering":
                case "dungeon":
                    var pointer = master.Kind == "gathering" ? "/drops" : "/clearRewards";
                    var field = pointer[1..];
                    var level = Integer(master.Data["recommendedLevel"] ?? master.Data["requiredLevel"]);
                    AddDropTarget(master, pointer, level, targets);
                    AddSources(master, pointer, master.Data[field] as JsonObject, null, pools, tables, related,
                        level, sources);
                    break;
                case "lootTable":
                    foreach (var drop in TableDrops(master, pools))
                        sources.Add(DropSource(master, drop, "", [], null, DefinitionOnly, ["ルートテーブル定義"], null));
                    break;
                case "lootPool":
                    foreach (var drop in PoolDrops(master))
                        sources.Add(DropSource(master, drop, drop.Pointer, [], null, DefinitionOnly, ["ルートプール定義"], null));
                    break;
                case "fishing":
                    ReadFishing(master, fish, sources);
                    break;
                case "world":
                    if (Id(master.Data["requiredItemId"]) is { } requiredItem)
                        usages.Add(new(requiredItem, "world", master.Name + "（入場条件）", master.Path,
                            RefPointer(master.Data["requiredItemId"], "/requiredItemId"), null, []));
                    break;
            }
        }

        foreach (var source in sources)
            foreach (var ingredient in source.Ingredients)
                usages.Add(new(ingredient.ItemId, source.Kind, source.Name, ingredient.Path, ingredient.Pointer,
                    source.ItemId, ingredient.Quantity));
        foreach (var preset in presets)
            foreach (var checkpoint in preset.Checkpoints)
            {
                foreach (var assignment in checkpoint.Equipment)
                    usages.Add(new(assignment.ItemId, "preset", preset.Name, preset.Path, assignment.Pointer, null, []));
                foreach (var assignment in checkpoint.Skills.Concat(checkpoint.Passives))
                    foreach (var sigil in assignment.Sigils)
                        usages.Add(new(sigil.ItemId, "preset", preset.Name + "（装着シジル）", preset.Path, sigil.Pointer, null, []));
            }

        var uniqueSources = sources.Select(source => source with
        {
            Id = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new
            {
                source.Kind, source.Path, source.Pointer, source.ItemPath, source.ItemPointer, source.ItemId,
                source.Level, Context = source.Context.Select(context => new { context.Kind, context.Path, context.Pointer })
            }))))
        }).DistinctBy(source => source.Id).ToArray();
        return new(classes, presets, items, uniqueSources, usages.DistinctBy(usage =>
            (usage.ItemId, usage.Kind, usage.Path, usage.Pointer, usage.OutputItemId)).ToArray(), targets,
            snapshot.Documents.Where(document => Kind(document.Path) is not null).ToDictionary(document => document.Path, document => document.Revision), skills, nodes, enchantChoices);
    }

    private static ProgressionDesignEnchantChoice[] ReadEnchantChoices(List<Master> masters, FilebaseSnapshot snapshot)
    {
        var sources = masters.Where(master => master.Kind == "item")
            .Select(master => (Master: master, Orb: ProgressionEquipmentEnchants.OrbSource(master.Data)))
            .Where(entry => entry.Orb is not null).OrderBy(entry => entry.Orb!.Value.Priority)
            .GroupBy(entry => entry.Orb!.Value.MasterId, StringComparer.OrdinalIgnoreCase)
            .ToDictionary(group => group.Key, group => group.First().Master.Id, StringComparer.OrdinalIgnoreCase);
        var statusDocument = snapshot.Documents.FirstOrDefault(document =>
            document.Path == "75.shared.status/v1.status_types.yml" && document.DecodeError is null)?.Content as JsonObject;
        var statuses = Rows(statusDocument?["statuses"], "/statuses")
            .Where(entry => Text(entry.Data["id"]) is not null && Text(entry.Data["displayName"]) is not null)
            .GroupBy(entry => Text(entry.Data["id"])!, StringComparer.OrdinalIgnoreCase)
            .ToDictionary(group => group.Key, group => Text(group.First().Data["displayName"])!, StringComparer.OrdinalIgnoreCase);

        return masters.Where(master => master.Kind == "enchant").SelectMany(master => ProgressionEquipmentEnchants.Read(master.Data))
            .Where(effect => sources.ContainsKey(effect.MasterId))
            .GroupBy(effect => (effect.MasterId.ToUpperInvariant(), effect.EquipmentType, effect.EffectId.ToUpperInvariant()))
            .Where(group => group.Count() == 1).Select(group => group.Single())
            .Select(effect => new ProgressionDesignEnchantChoice(effect.MasterId, effect.EquipmentType, effect.EffectId,
                statuses.GetValueOrDefault(effect.Status) ?? effect.Status, effect.Type, effect.Min, effect.Max,
                sources[effect.MasterId])).ToArray();
    }

    private static Dictionary<string, Master> Index(IEnumerable<Master> masters, string kind) =>
        masters.Where(master => master.Kind == kind).GroupBy(master => master.Id, StringComparer.Ordinal)
            .ToDictionary(group => group.Key, group => group.First(), StringComparer.Ordinal);

    private static ProgressionDesignClass ReadClass(Master master) => new(master.Id, master.Name, master.Path,
        Rows(master.Data["unlockClassLevel"], "/unlockClassLevel")
            .Select(row => Id(row.Data["class"] ?? row.Data["classId"])).OfType<string>().Distinct(StringComparer.Ordinal).ToArray(),
        Integer(master.Data["maxLevel"]) ?? 0,
        (master.Data["usableSkills"] as JsonArray ?? []).Select(Id).OfType<string>().Distinct(StringComparer.Ordinal).ToArray());

    private static ProgressionDesignPreset? ReadPreset(Master master)
    {
        if (Id(master.Data["classId"]) is not { } classId || Text(master.Data["buildId"]) is not { } buildId)
            return null;
        var checkpoints = new List<ProgressionDesignCheckpoint>();
        foreach (var (checkpoint, pointer) in Rows(master.Data["levels"], "/levels"))
        {
            if (Integer(checkpoint["level"]) is not { } level || Integer(checkpoint["classLevel"]) is not { } classLevel)
                continue;
            var equipment = new List<ProgressionDesignEquipment>();
            foreach (var (row, rowPointer) in Rows(checkpoint["equipment"], pointer + "/equipment"))
                if (Text(row["slot"]) is { } slot && Id(row["item"]) is { } id)
                    equipment.Add(new(slot, id, rowPointer, Integer(row["enhanceLevel"]) ?? 0,
                        Rows(row["enchants"], rowPointer + "/enchants")
                            .Select(enchant => ReadEquipmentEnchant(enchant.Data)).OfType<ProgressionDesignEnchant>().ToArray()));
            var nodes = (checkpoint["nodes"] as JsonArray ?? []).Select(Integer).OfType<int>().ToArray();
            checkpoints.Add(new(level, classLevel, pointer, equipment, nodes,
                ReadSkillAssignments(checkpoint["skills"], pointer + "/skills"),
                ReadSkillAssignments(checkpoint["passives"], pointer + "/passives")));
        }
        return new(master.Id, master.Name, master.Path, classId, buildId, checkpoints);
    }

    private static ProgressionDesignEnchant? ReadEquipmentEnchant(JsonObject data) =>
        Text(data["enchantMasterId"]) is { } master && Text(data["effectId"]) is { } effect
        && Text(data["value"]) is { } value ? new(master, effect, value) : null;

    private static ProgressionDesignSkillAssignment[] ReadSkillAssignments(JsonNode? assignments, string pointer) =>
        Rows(assignments, pointer).Where(row => Integer(row.Data["slot"]) is not null && Id(row.Data["skill"]) is not null)
            .Select(row => new ProgressionDesignSkillAssignment(Integer(row.Data["slot"])!.Value, Id(row.Data["skill"])!,
                row.Pointer, Integer(row.Data["skillLevel"]) ?? 1,
                Rows(row.Data["sigils"], row.Pointer + "/sigils")
                    .Where(sigil => Integer(sigil.Data["slot"]) is not null && Id(sigil.Data["item"]) is not null)
                    .Select(sigil => new ProgressionDesignSigilAssignment(Integer(sigil.Data["slot"])!.Value,
                        Id(sigil.Data["item"])!, sigil.Pointer)).ToArray())).ToArray();

    private static ProgressionDesignSkill ReadSkill(Master master) => new(master.Id, master.Name, master.Path,
        Text(master.Data["icon"]), Text(master.Data["iconTexture"]), Integer(master.Data["customModelData"]),
        master.Data["passive"] is JsonObject,
        (master.Data["passive"] as JsonObject)?["bindRequired"]?.GetValueKind() != JsonValueKind.False,
        Integer(master.Data["requiredLevel"]) ?? 1, Integer(master.Data["maxLevel"]) ?? 1,
        Rows(master.Data["sigilSlotsByLevel"], "/sigilSlotsByLevel")
            .Where(row => Integer(row.Data["level"]) is not null && Integer(row.Data["slots"]) is not null)
            .Select(row => new ProgressionDesignSigilSlots(Integer(row.Data["level"])!.Value, Integer(row.Data["slots"])!.Value)).ToArray(),
        (master.Data["allowedSigilIds"] as JsonArray ?? []).Select(Id).OfType<string>().ToArray());

    private static ProgressionDesignItem ReadItem(Master master)
    {
        var data = master.Data;
        var equipment = data["equipment"] as JsonObject;
        var slot = Text(equipment?["slot"]);
        var slots = ProgressionEquipmentSlots.Compatible(slot, Text(equipment?["tag"]));
        var classes = equipment?["requiredClasses"] is JsonArray requirements
            ? requirements.Select(value => Id(value is JsonObject row ? row["classId"] ?? row["class"] ?? value : value))
                .OfType<string>().Distinct(StringComparer.Ordinal).ToArray()
            : [];
        return new(master.Id, master.Name, master.Path, Text(data["category"]), Text(data["rarity"]), Text(data["icon"]),
            Text(data["iconTexture"]), MasterDataService.ReadModelData(data["customModelData"]), slot,
            Text(equipment?["handType"]) ?? (slot is "WEAPON" or "TOOL" ? "ONE" : null),
            Integer(equipment?["requiredLevel"]) ?? 0, classes, slots,
            ProgressionPresetValidation.ReachableEnhanceLevel(equipment?["enhance"] as JsonObject),
            Text((data["sigil"] as JsonObject)?["equipGroupId"]),
            slot == "SKILLBOOK" ? (equipment?["usableSkills"] as JsonArray ?? []).Select(Id).OfType<string>()
                .Distinct(StringComparer.Ordinal).ToArray() : [],
            (equipment?["requiredClasses"] as JsonArray ?? []).OfType<JsonObject>()
                .Where(row => Id(row["classId"] ?? row["class"]) is not null)
                .Select(row => new ProgressionDesignClassRequirement(Id(row["classId"] ?? row["class"])!,
                    Math.Max(1, Integer(row["level"]) ?? 1))).ToArray(),
            ProgressionEquipmentEnchants.MaxSlots(equipment), ProgressionEquipmentEnchants.EquipmentType(slot));
    }

    private static void ReadRecipe(Master master, List<ProgressionDesignSource> sources)
    {
        if (master.Data["result"] is not JsonObject result || Id(result["itemId"]) is not { } itemId) return;
        var mode = Text(master.Data["category"]);
        sources.Add(new(master.Id, master.Kind, master.Name, itemId, master.Path, "/result", master.Path,
            RefPointer(result["itemId"], "/result/itemId"), Fields(master, result, "/result", "amount"),
            Fields(master, master.Data, "", "successRate"),
            Ingredients(master, master.Data["ingredients"], "/ingredients"), [], Integer(master.Data["requiredLevel"]), mode, DefinitionOnly,
            mode == "SHOP" ? ["入手にはショップの商品登録が必要です。"] : ["レシピ定義。利用先は確認していません。"],
            new(master.Path, "/ingredients"), Fields(master, master.Data, "", "requiredCurrency")));
    }

    private static void ReadShop(Master master, Dictionary<string, Master> recipes, LinkedMaster[] related,
        List<ProgressionDesignSource> sources)
    {
        var mode = Text(master.Data["mode"]);
        var access = Text(master.Data["access"]);
        foreach (var (row, pointer) in Rows(master.Data["items"], "/items"))
        {
            if (Id(row["itemId"]) is not { } itemId) continue;
            var context = related.Select(link => link.Context).ToList();
            var ingredients = Ingredients(master, row["requiredItems"], pointer + "/requiredItems").ToList();
            var costs = Fields(master, row, pointer, "priceGold").ToList();
            var warnings = new List<string>();
            if (Id(row["recipeId"]) is { } recipeId)
            {
                if (recipes.TryGetValue(recipeId, out var recipe)
                    && string.Equals(Text(recipe.Data["category"]), "SHOP", StringComparison.OrdinalIgnoreCase))
                {
                    context.Insert(0, recipe.Context("/result"));
                    ingredients.AddRange(Ingredients(recipe, recipe.Data["ingredients"], "/ingredients"));
                    costs.AddRange(Fields(recipe, recipe.Data, "", "requiredCurrency"));
                }
                else warnings.Add($"SHOP レシピ '{recipeId}' を解決できません。");
            }
            if (access == "NPC_ONLY" && related.Length == 0)
                warnings.Add("商品を開く NPC のマスター参照がありません。");
            sources.Add(new(master.Id, master.Kind, master.Name, itemId, master.Path, pointer, master.Path,
                RefPointer(row["itemId"], pointer + "/itemId"), Fields(master, row, pointer, "amount"), [], ingredients,
                context, null, mode, warnings.Count == 0 ? Configured : DefinitionOnly, warnings,
                new(master.Path, pointer + "/requiredItems"), costs));
        }
    }

    private static ProgressionDesignIngredient[] Ingredients(Master master, JsonNode? source, string pointer) =>
        Rows(source, pointer).Where(row => Id(row.Data["itemId"]) is not null)
            .Select(row => new ProgressionDesignIngredient(Id(row.Data["itemId"])!, master.Path, row.Pointer,
                RefPointer(row.Data["itemId"], row.Pointer + "/itemId"), Fields(master, row.Data, row.Pointer, "amount"))).ToArray();

    private static void ReadMob(Master master, Dictionary<string, Master> pools, Dictionary<string, Master> tables,
        LinkedMaster[] related, List<ProgressionDesignSource> sources, List<ProgressionDesignAddTarget> targets)
    {
        var data = master.Data;
        var baseline = data["drops"] as JsonObject;
        var profiles = Rows(data["levels"], "/levels")
            .Where(row => Integer(row.Data["level"]) is > 0).ToArray();
        AddDropTarget(master, "/drops", Integer(data["level"]), targets);
        if (profiles.Length == 0)
        {
            AddSources(master, "/drops", baseline, null, pools, tables, related, Integer(data["level"]), sources);
            return;
        }

        // Plugin のレベル差分はオブジェクトをマージし、配列や明示 null は上書きする。
        foreach (var (profile, pointer) in profiles)
        {
            var level = Integer(profile["level"]);
            var dropPointer = pointer + "/drops";
            AddDropTarget(master, dropPointer, level, targets);
            var hasOverride = profile.ContainsKey("drops");
            var drops = hasOverride ? profile["drops"] as JsonObject : baseline;
            if (drops is null) continue;
            AddSources(master, hasOverride ? dropPointer : "/drops", drops, hasOverride ? baseline : null,
                pools, tables, related.Where(link => link.Level is null || link.Level == level).ToArray(), level, sources);
        }
    }

    private static void AddDropTarget(Master master, string pointer, int? level, List<ProgressionDesignAddTarget> targets)
    {
        var data = master.Data;
        var icon = Text(data["icon"] ?? data["displayBlock"]);
        targets.Add(new(master.Id, master.Kind, master.Name, master.Path, pointer + "/items", icon,
            MasterDataService.ReadModelData(data["customModelData"]), Text(data["iconTexture"]), level));
    }

    private static void AddSources(Master master, string pointer, JsonObject? drops, JsonObject? inherited,
        Dictionary<string, Master> pools, Dictionary<string, Master> tables, LinkedMaster[] related,
        int? level, List<ProgressionDesignSource> sources)
    {
        if (drops is null) return;
        var itemsHere = drops.ContainsKey("items") || inherited is null;
        var itemsPointer = itemsHere ? pointer + "/items" : "/drops/items";
        var context = related.Select(link => link.Context).ToArray();
        foreach (var (row, rowPointer) in Rows(itemsHere ? drops["items"] : inherited?["items"], itemsPointer))
            if (ItemDrop(master, row, rowPointer) is { } drop)
                sources.Add(DropSource(master, drop, rowPointer, context, level, Availability(drop, out var warnings),
                    warnings, null));

        var tableHere = drops.ContainsKey("lootTable") || inherited is null;
        var tableRef = tableHere ? drops["lootTable"] : inherited?["lootTable"];
        if (Id(tableRef) is not { } tableId || !tables.TryGetValue(tableId, out var table)) return;
        var tablePointer = RefPointer(tableRef, (tableHere ? pointer : "/drops") + "/lootTable");
        var tableContext = new[] { master.Context(tablePointer) }.Concat(context).ToArray();
        foreach (var drop in TableDrops(table, pools))
            sources.Add(DropSource(master, drop, tablePointer, tableContext, level,
                Availability(drop, out var warnings), warnings, null));
    }

    internal static bool HasPossibleLoot(IReadOnlyList<ProgressionDesignField> fields) =>
        !fields.Any(field => JsonValueReader.Number(field.Value) is <= 0
            || Text(field.Value) is { } range && range.Split('~').All(part =>
                decimal.TryParse(part, NumberStyles.Float, CultureInfo.InvariantCulture, out var number) && number <= 0));

    private static string Availability(Drop drop, out string[] warnings)
    {
        var possible = HasPossibleLoot(drop.Rates);
        warnings = possible ? [] : ["抽選値が 0 以下のため入手できません。"];
        return possible ? Configured : DefinitionOnly;
    }

    private static ProgressionDesignSource DropSource(Master origin, Drop drop, string pointer,
        ProgressionDesignContext[] context, int? level, string availability, string[] warnings, string? mode) =>
        new(origin.Id, origin.Kind, origin.Name, drop.ItemId, origin.Path, pointer, drop.Master.Path, drop.ItemPointer,
            drop.Quantity, drop.Rates, [], [.. drop.Context, .. context], level, mode, availability, warnings, null, []);

    private static IEnumerable<Drop> TableDrops(Master table, Dictionary<string, Master> pools)
    {
        if (table.Data["pools"] is not JsonArray references) yield break;
        var rates = Fields(table, table.Data, "", "rolls");
        for (var index = 0; index < references.Count; index++)
        {
            var reference = references[index];
            if (Id(reference) is not { } id || !pools.TryGetValue(id, out var pool)) continue;
            foreach (var drop in PoolDrops(pool))
                yield return drop with
                {
                    Rates = [.. rates, .. drop.Rates],
                    Context = [table.Context(RefPointer(reference, $"/pools/{index}")), pool.Context(drop.Pointer)]
                };
        }
    }

    private static IEnumerable<Drop> PoolDrops(Master pool)
    {
        var pick = Fields(pool, pool.Data, "", "pick");
        foreach (var (row, pointer) in Rows(pool.Data["contents"], "/contents"))
            if (ItemDrop(pool, row, pointer) is { } drop)
                yield return drop with { Rates = [.. pick, .. drop.Rates] };
    }

    private static Drop? ItemDrop(Master master, JsonObject row, string pointer) => Id(row["itemId"]) is { } id
        ? new(id, master, pointer, RefPointer(row["itemId"], pointer + "/itemId"),
            Fields(master, row, pointer, "amount"), Fields(master, row, pointer, "rate"), [])
        : null;

    private static void ReadFishing(Master area, Dictionary<string, Master> fish, List<ProgressionDesignSource> sources)
    {
        var enabled = area.Data["enabled"] is not JsonValue value || !value.TryGetValue<bool>(out var flag) || flag;
        foreach (var (range, rangePointer) in Rows(area.Data["levelRanges"], "/levelRanges"))
        foreach (var (row, pointer) in Rows(range["fish"], rangePointer + "/fish"))
        {
            if (Id(row["fishId"]) is not { } id || !fish.TryGetValue(id, out var master)
                || Id(master.Data["caughtItemId"]) is not { } itemId) continue;
            var drop = new Drop(itemId, master, "", RefPointer(master.Data["caughtItemId"], "/caughtItemId"),
                Fields(master, master.Data, "", "caughtAmount"),
                [.. Fields(area, row, pointer, "chancePercent"), .. Fields(master, master.Data, "", "biteChancePercent")],
                [master.Context()]);
            var status = Availability(drop, out var warnings);
            sources.Add(DropSource(area, drop, pointer, [], Integer(range["minLevel"]), enabled ? status : DefinitionOnly,
                enabled ? warnings : ["釣りエリアが無効です。"], null));
        }
    }

    private static ILookup<string, LinkedMaster> ReadContexts(IEnumerable<Master> masters)
    {
        var relations = new List<(string Key, LinkedMaster Link)>();
        foreach (var master in masters)
        {
            var data = master.Data;
            switch (master.Kind)
            {
                case "shopNpc":
                    if (data["interactions"] is not JsonObject interactions) break;
                    foreach (var side in new[] { "leftClick", "rightClick" })
                    foreach (var (action, pointer) in Rows(interactions[side], "/interactions/" + side))
                        if (Text(action["id"]) == "gui" && action["params"] is JsonObject parameters
                            && Text(parameters["type"]) == "SHOP")
                            Add("shop", parameters["shopId"], pointer + "/params/shopId", null);
                    break;
                case "mobSpawner":
                    foreach (var (spawn, pointer) in Rows(data["spawnMobs"], "/spawnMobs"))
                        Add("mob", spawn["mobId"], pointer + "/mobId", Integer(spawn["level"]));
                    break;
                case "gatheringSpawner":
                    foreach (var (spawn, pointer) in Rows(data["spawnGatherings"], "/spawnGatherings"))
                        Add("gathering", spawn["gatheringId"], pointer + "/gatheringId", null);
                    break;
                case "dungeon":
                    if (data["encounter"] is not JsonObject encounter) break;
                    foreach (var (row, pointer) in Rows(encounter["normalMobPool"], "/encounter/normalMobPool"))
                        Add("mob", row["mobId"], pointer + "/mobId", Integer(row["level"]));
                    Add("mob", encounter["bossMobId"], "/encounter/bossMobId", Integer(encounter["bossMobLevel"]));
                    break;
            }

            void Add(string kind, JsonNode? reference, string pointer, int? level)
            {
                if (Id(reference) is { } id)
                    relations.Add((kind + ":" + id, new(master.Context(RefPointer(reference, pointer)), level)));
            }
        }
        return relations.ToLookup(relation => relation.Key, relation => relation.Link, StringComparer.Ordinal);
    }

    private static string? Kind(string path)
    {
        var kind = MasterDataService.Kind(path);
        return kind switch
        {
            "class" or "item" or "recipe" or "shop" or "gathering" or "dungeon" or "world" or "skill" or "node" or "enchant" => kind,
            "mob" => path.StartsWith("40.features.mob/npc/", MasterDataPaths.Comparison) ? "shopNpc" : "mob",
            "mob_spawner" => "mobSpawner",
            "gathering_spawner" => "gatheringSpawner",
            "progression_preset" => "preset",
            "loot_table" => "lootTable",
            "loot_pool" => "lootPool",
            "fishing_area" => "fishing",
            "fishing_fish" => "fish",
            _ => null
        };
    }

    private static IEnumerable<(JsonObject Data, string Pointer)> Rows(JsonNode? node, string pointer)
    {
        if (node is not JsonArray array) yield break;
        for (var index = 0; index < array.Count; index++)
            if (array[index] is JsonObject row)
                yield return (row, $"{pointer}/{index}");
    }

    private static ProgressionDesignField[] Fields(Master master, JsonObject row, string pointer, params string[] keys) =>
        keys.Where(key => row[key] is JsonValue node
                && node.GetValueKind() is JsonValueKind.Number or JsonValueKind.String)
            .Select(key => new ProgressionDesignField(master.Path, pointer + "/" + key, row[key]!.DeepClone())).ToArray();

    private static string? Text(JsonNode? node) =>
        JsonValueReader.String(node) is { } text && !string.IsNullOrWhiteSpace(text) ? text : null;

    private static int? Integer(JsonNode? node) => MasterDataService.ReadModelData(node);

    private static int? NodeId(JsonNode? node) => Integer(node)
        ?? (int.TryParse(Text(node), NumberStyles.None, CultureInfo.InvariantCulture, out var value) ? value : null);

    private static string? Id(JsonNode? node) => Text(node is JsonObject reference ? reference["ref"] : node)
        ?.Trim().Split(':').Last();

    private static string RefPointer(JsonNode? node, string pointer) => node is JsonObject ? pointer + "/ref" : pointer;
}
