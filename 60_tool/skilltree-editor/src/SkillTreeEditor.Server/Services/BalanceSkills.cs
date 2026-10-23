using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Services;

internal static class BalanceSkills
{
    private const string Root = "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/";
    private static readonly HashSet<string> RootFields = new(StringComparer.Ordinal)
    {
        "allowedSigilIds", "castTimeTicks", "conditionalLore", "cooldownTicks", "description", "icon", "iconTexture", "id",
        "implementationId", "learnRequiredItems", "levelUpRequiredItems", "levels", "lore", "manaCost", "maxLevel", "name",
        "onCast", "params", "passive", "requiredLevel", "resourceCost", "resourceType", "schemaVersion", "sigilSlotsByLevel", "tags", "type"
    };

    internal static BalanceSkillProfile Resolve(JsonObject definition, JsonObject? catalog = null)
    {
        var implementation = BalanceCombatData.Text(definition["implementationId"]);
        try
        {
            var profile = BalanceSkillMeleeProfiles.Resolve(definition) ?? BalanceSkillRangedProfiles.Resolve(definition)
                ?? BalanceSkillMagicProfiles.Resolve(definition) ?? BuiltIn(definition)
                ?? new BalanceSkillProfile(implementation, [], [], [], [], ["この実行処理の計算アダプターは未実装です。"]);
            if (catalog is not null && profile.Supported) profile = BalanceSkillBuffs.Resolve(definition, catalog, profile);
            var reasons = profile.Reasons.ToList();
            var parameters = BalanceSkillParameters.Params(definition);
            if (implementation is "adventurer_mana_burst" or "adventurer_smash")
            {
                var positive = implementation == "adventurer_mana_burst" ? new[] { "range", "damageRatio", "angle" }
                    : new[] { "primaryRadius", "impactRadius", "damageRatio", "secondaryRatio", "secondaryKnockback" };
                foreach (var key in positive)
                    if (BalanceSkillParameters.Number(parameters, key) <= 0) reasons.Add($"params.{key} はPluginの必須正数です。");
                var countKey = implementation == "adventurer_mana_burst" ? "maxTargets" : "maxSecondaryTargets";
                if (BalanceSkillParameters.Integer(parameters, countKey) < 1) reasons.Add($"params.{countKey} は1以上の整数です。");
                if (implementation == "adventurer_mana_burst" && BalanceSkillParameters.Number(parameters, "angle") > 180)
                    reasons.Add("params.angle は180以下で指定してください。");
                if (implementation == "adventurer_smash" && BalanceSkillParameters.Integer(parameters, "impactDelayTicks") < 0)
                    reasons.Add("params.impactDelayTicks は0以上で指定してください。");
            }
            foreach (var key in definition.Select(pair => pair.Key).Where(key => !RootFields.Contains(key)))
                reasons.Add($"スキルの追加項目 {key} の効果を検証できません。");
            if (definition["params"] is not null and not JsonObject) reasons.Add("params はオブジェクトで指定してください。");
            foreach (var key in BalanceSkillParameters.Params(definition).Select(pair => pair.Key)
                         .Where(key => !profile.KnownParameters.Contains(key, StringComparer.Ordinal)))
                reasons.Add($"実行処理で未検証のパラメーター params.{key} が指定されています。");
            if (definition["passive"] is not null)
                reasons.Add("パッシブのバインド・発動トリガーを通常の優先順位発動として再現できません。");
            if (definition["onCast"] is JsonObject onCast && onCast.Any(pair => pair.Key != "sound"))
                reasons.Add("onCast.sound 以外の追加発動効果は未検証です。");
            if (definition["onCast"] is not null and not JsonObject)
                reasons.Add("onCast はオブジェクトで指定してください。");
            if (definition["levels"] is JsonArray levels && levels.OfType<JsonObject>().Any(level =>
                    BalanceCombatData.Integer(level["level"], 2) <= 1))
                reasons.Add("Lv1に適用される追加レベル効果は未対応です。基礎定義のみのLv1を扱います。");
            var resource = BalanceCombatData.Text(definition["resourceType"]);
            if (resource is not ("MANA" or "ENERGY")) reasons.Add($"リソース {resource} の専用制御は未対応です。");
            foreach (var key in new[] { "resourceCost", "manaCost", "castTimeTicks", "cooldownTicks" })
                if (definition.ContainsKey(key) && BalanceSkillParameters.Number(definition, key) < 0)
                    reasons.Add($"{key} は0以上で指定してください。");
            foreach (var key in new[] { "castTimeTicks", "cooldownTicks" })
                if (definition.ContainsKey(key) && BalanceSkillParameters.Integer(definition, key) > 72_000)
                    reasons.Add($"{key} は72000tick以下で指定してください。");
            if (profile.Effects.Count > 2_000 || profile.Effects.Any(effect => effect.Tick < 0 || effect.Tick > 72_000
                || !double.IsFinite(effect.Value) || effect.DurationTicks < 0 || !double.IsFinite(effect.FlightSpeed)))
                reasons.Add("効果数・時刻・数値が計算モデルの上限を超えています。");
            return profile with { Reasons = reasons.Distinct(StringComparer.Ordinal).ToArray() };
        }
        catch (ArgumentException ex)
        {
            return new(implementation, [], [], [], [], [ex.Message]);
        }
    }

    private static BalanceSkillProfile? BuiltIn(JsonObject definition)
    {
        var id = BalanceCombatData.Text(definition["implementationId"]);
        var p = BalanceSkillParameters.Params(definition);
        if (id == "adventurer_mana_burst") return new(id,
            [new("damage", Value: BalanceSkillParameters.Number(p, "damageRatio", 1.32), Range: BalanceSkillParameters.Number(p, "range", 7))],
            [Root + "executor/active/adventurer/AdventurerManaBurstExecutor.java"],
            ["range", "angle", "damageRatio", "maxTargets"], ["停止した敵1体を前方扇形と射程の内側に置きます。"], []);
        if (id == "adventurer_smash") return new(id,
            [new("damage", Tick: Math.Max(0, BalanceSkillParameters.Integer(p, "impactDelayTicks", 8)),
                Value: BalanceSkillParameters.Number(p, "damageRatio", 3.6), AttackType: "MELEE", Range: BalanceSkillParameters.Number(p, "primaryRadius", 4))],
            [Root + "executor/active/adventurer/AdventurerSmashExecutor.java"],
            ["primaryRadius", "impactRadius", "damageRatio", "secondaryRatio", "secondaryKnockback", "impactDelayTicks", "maxSecondaryTargets"],
            ["単独の主対象を射程内に置きます。別の敵にだけ当たる副対象攻撃は発生しません。"], []);
        if (id == "adventurer_quick_shot") return new(id,
            [new("damage", Value: 2.25, AttackType: "RANGED", FlightSpeed: 2.2, HitRadius: .45, Range: 12)],
            [Root + "executor/active/adventurer/AdventurerQuickShotExecutor.java"],
            ["inheritanceMasteryName", "inheritanceBuffName", "inheritanceDurationTicks", "inheritanceCriticalRate", "range", "damageRatio"],
            ["継承パッシブ・継承バフを装着していない単独射撃です。Pluginと同様、射程12・倍率2.25はexecutor定数で、同名paramsでは変更されません。"], []);
        return null;
    }

    internal static JsonArray Catalog(JsonObject catalog)
    {
        var masters = BalanceCombatData.Masters(catalog).ToArray();
        var classes = masters.Where(master => master.Kind == "class").ToArray();
        var nodes = masters.Where(master => master.Kind == "node").ToArray();
        var result = new JsonArray();
        foreach (var master in masters.Where(master => master.Kind == "skill").OrderBy(master => BalanceCombatData.Text(master.Content["id"]), StringComparer.Ordinal))
        {
            var id = BalanceCombatData.Text(master.Content["id"]);
            var profile = Resolve(master.Content, catalog);
            var links = new JsonArray();
            foreach (var job in classes)
            {
                var classId = BalanceCombatData.Text(job.Content["id"]);
                if (BalanceCombatData.Array(job.Content["usableSkills"]).Select(BalanceCombatData.Id).Contains(id, StringComparer.Ordinal))
                    links.Add(new JsonObject { ["classId"] = classId, ["kind"] = "class", ["path"] = job.Path });
                foreach (var node in nodes.Where(node => BalanceCombatData.Text(node.Content["unlockCondition"]?["classId"]) == classId
                    && BalanceCombatData.Array(node.Content["effects"]).OfType<JsonObject>().Any(effect => BalanceCombatData.Text(effect["type"]) == "skill"
                        && BalanceCombatData.Id(effect["skillId"]) == id)))
                    links.Add(new JsonObject { ["classId"] = classId, ["kind"] = "node", ["path"] = node.Path,
                        ["nodeId"] = node.Content["nodeId"]?.DeepClone() ?? node.Content["id"]?.DeepClone(), ["requirements"] = node.Content["unlockCondition"]?.DeepClone() });
            }
            result.Add(new JsonObject
            {
                ["id"] = id, ["implementationId"] = profile.ImplementationId, ["path"] = master.Path,
                ["name"] = master.Content["name"]?.DeepClone(), ["supported"] = profile.Supported,
                ["kind"] = master.Content["passive"] is null ? "active" : "passive",
                ["reasons"] = Strings(profile.Reasons), ["assumptions"] = Strings(profile.Assumptions),
                ["sources"] = Strings(profile.Sources), ["classLinks"] = links,
                ["cooldownTicks"] = master.Content["cooldownTicks"]?.DeepClone(), ["castTimeTicks"] = master.Content["castTimeTicks"]?.DeepClone(),
                ["resourceType"] = master.Content["resourceType"]?.DeepClone(), ["resourceCost"] = master.Content["resourceCost"]?.DeepClone(),
                ["maxLevel"] = master.Content["maxLevel"]?.DeepClone()
            });
        }
        return result;
    }

    private static JsonArray Strings(IEnumerable<string> values) => new(values.Select(value => (JsonNode?)JsonValue.Create(value)).ToArray());
}
