using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Services;

// These profiles follow the executor's first projectile collision with one stationary target.
// Every implementation ID is explicit: a master alias may change id without changing behavior.
internal static class BalanceSkillRangedProfiles
{
    private const string SkillMaster = "40_filebase/30.features.skill/v1.";
    private const string ExecutorRoot = "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/executor/active/";
    private const string SingleTarget = "敵1体・遮蔽物なし。各発射時の位置と照準から、実executorの飛翔速度と当たり半径で最初のMobへの衝突を調べる。";
    private const string NoInheritance = "継承の心得を未習得・継承バフなしとする。";

    internal static BalanceSkillProfile? Resolve(JsonObject definition)
    {
        string? id = definition["implementationId"]?.GetValue<string>();
        if (string.IsNullOrWhiteSpace(id)) return null;
        JsonObject p = BalanceSkillParameters.Params(definition);
        if (id.StartsWith("phantom_archer_", StringComparison.Ordinal) || id == "archer_starweave_followup")
            return RuntimeProfile(definition, id, p);
        if (OtherRuntimeProfile(definition, id, p) is { } runtime) return runtime;
        var profile = id switch
        {
            "sharpshooter_fire_arrow" => ElementalArrow(id, p, "FIRE", "BURNING", 24.5, 100,
                "SharpshooterElementalArrowExecutor.java", "SharpshooterFireArrowExecutor.java"),
            "sharpshooter_ice_arrow" => ElementalArrow(id, p, "ICE", "FROZEN", 50, 40,
                "SharpshooterElementalArrowExecutor.java", "SharpshooterIceArrowExecutor.java"),
            "sharpshooter_piercing_line" => PiercingLine(p),
            "sharpshooter_ricochet_arrow" => Ricochet(p),
            "sharpshooter_thermal_break" => ThermalBreak(p),
            "sharpshooter_phantom_shot" => PhantomShot(p),
            "sharpshooter_spreading_ambition" => SpreadingAmbition(p),
            "hunter_build_up" => BuildUp(p),
            _ => null
        };
        if (profile is not null && id.StartsWith("sharpshooter_", StringComparison.Ordinal))
        {
            string[] required = id switch
            {
                "sharpshooter_fire_arrow" or "sharpshooter_ice_arrow" => ["conditionChance", "inheritedConditionChance", "conditionDurationTicks"],
                "sharpshooter_piercing_line" => ["maxHits", "pierceDecay"],
                "sharpshooter_ricochet_arrow" => ["bounceRadius", "bounceCount", "bounceDecay"],
                "sharpshooter_thermal_break" => ["statusBonusRatio"],
                "sharpshooter_phantom_shot" => ["shotIntervalTicks", "successiveHitWindowTicks", "resourceRecoveryRatio"],
                "sharpshooter_spreading_ambition" => ["explosionDamageRatio", "explosionRadius", "maxExplosionTargets", "pelletCount", "spreadAngle"],
                _ => []
            };
            var missing = required.Concat(["range", "damageRatio", "projectileSpeed", "projectileHitRadius"])
                .Where(key => !p.ContainsKey(key)).Select(key => $"params.{key} はPluginの必須項目です。");
            profile = profile with { Reasons = profile.Reasons.Concat(missing).ToArray() };
        }
        return profile;
    }

    private static BalanceSkillProfile? OtherRuntimeProfile(JsonObject definition, string id, JsonObject p)
    {
        string executor = id switch
        {
            "adventurer_blast_arrow" => "adventurer/AdventurerBlastArrowExecutor.java",
            "hunter_fade_shot" => "hunter/HunterFadeShotExecutor.java",
            "hunter_arrow_rain" => "hunter/HunterArrowRainExecutor.java",
            "hunter_crash_arrow" => "hunter/HunterCrashArrowExecutor.java",
            "hunter_heal_arrow" => "hunter/HunterHealArrowExecutor.java",
            "archer_heal_arrow_alpha" => "hunter/ArcherHealArrowAlphaExecutor.java",
            "hunter_spell_step" => "../HunterSpellStepSkillExecutor.java",
            "archer_air_shift" => "../ArcherAirShiftSkillExecutor.java",
            "sharpshooter_glacial_trap" => "sharpshooter/SharpshooterGlacialTrapExecutor.java",
            "sharpshooter_inheritance_mastery" => "../SharpshooterInheritanceMasterySkillExecutor.java",
            _ => ""
        };
        if (executor.Length == 0) return null;
        bool passive = id is "hunter_spell_step" or "archer_air_shift" or "sharpshooter_inheritance_mastery";
        var reasons = new List<string>();
        void Positive(params string[] keys)
        {
            foreach (string key in keys)
                if (N(p, key, 0) <= 0) reasons.Add($"params.{key} はPluginの必須正数です。");
        }
        void Integer(string key, int min = 1, int max = int.MaxValue)
        {
            if (!p.ContainsKey(key) || I(p, key, -1) < min || I(p, key, -1) > max)
                reasons.Add($"params.{key} は{min}から{max}の必須整数です。");
        }
        void Chance(string key)
        {
            if (!p.ContainsKey(key) || N(p, key, -1) is < 0 or > 100) reasons.Add($"params.{key} は0から100の必須確率です。");
        }
        if (id is "adventurer_blast_arrow" or "hunter_fade_shot" or "hunter_crash_arrow" or "sharpshooter_glacial_trap")
            Positive("range", "damageRatio", "projectileSpeed", "projectileHitRadius");
        switch (id)
        {
            case "adventurer_blast_arrow": Positive("radius"); Integer("maxTargets"); break;
            case "hunter_fade_shot":
                Positive("backstepVelocity", "spreadAngle"); Integer("pelletCount", 3, 9);
                if (I(p, "pelletCount", 0) % 2 != 1 || N(p, "spreadAngle", 0) > 60)
                    reasons.Add("フェイドショットの散弾数は奇数、全角は60以下です。");
                break;
            case "hunter_crash_arrow": Positive("shieldBreakMultiplier"); Chance("superStarCriticalChance"); break;
            case "hunter_arrow_rain":
                Positive("range", "radius", "openingSpeed", "openingHitRadius", "rainHitRadius"); Integer("arrowCount", 1, 2000);
                var ratios = BalanceSkillParameters.Numbers(p, "damageRatios");
                if (ratios.Length != 2 || ratios.Any(ratio => ratio <= 0)) reasons.Add("params.damageRatios は正数2要素の必須配列です。");
                break;
            case "hunter_heal_arrow":
            case "archer_heal_arrow_alpha":
                Positive("radius", "damageRatio", "projectileSpeed", "projectileHitRadius", id == "hunter_heal_arrow" ? "healAmount" : "healPercent");
                Integer("areaDurationTicks", 1, 72000);
                if (Math.Abs(N(p, "damageRatio", 0) - .36) > 1e-9) reasons.Add("敵への倍率はPluginと同じ0.36固定です。");
                break;
            case "sharpshooter_glacial_trap": Positive("radius"); Integer("maxTargets", 1, 10); Chance("conditionChance"); Integer("conditionTicks", 1, 600); break;
            case "hunter_spell_step":
                if (I(p, "windowTicks", -1) != 20) reasons.Add("スペルステップの有効期間は20tick固定です。");
                break;
            case "archer_air_shift":
                if (N(p, "horizontalStrength", 0) != 1.45 || N(p, "verticalStrength", 0) != .25)
                    reasons.Add("エアーシフトの水平速度1.45・垂直速度0.25はPlugin固定値です。");
                if ((definition["passive"] as JsonObject)?["bindRequired"]?.GetValueKind() != System.Text.Json.JsonValueKind.False
                    || BalanceCombatData.Text(definition["resourceType"]) != "ENERGY" || BalanceCombatData.Number(definition["resourceCost"], -1) != 15)
                    reasons.Add("エアーシフトはバインド不要・ENERGY・消費15固定です。");
                break;
            case "sharpshooter_inheritance_mastery":
                if (N(p, "normalAttackDamageMultiplier", 0) != .5 || N(p, "inheritedSkillDamageMultiplier", 0) != .5
                    || (definition["passive"] as JsonObject)?["bindRequired"]?.GetValueKind() != System.Text.Json.JsonValueKind.True || BalanceCombatData.Integer(definition["maxLevel"], 1) != 1)
                    reasons.Add("継承の心得は最大Lv1・バインド必須・通常/継承倍率0.5固定です。");
                ValidateInheritance(p, reasons);
                break;
        }
        if (id is "hunter_spell_step" or "archer_air_shift")
        {
            if (string.IsNullOrWhiteSpace(p["triggerSound"]?.ToString()) || N(p, "triggerSoundVolume", .8) < 0 || N(p, "triggerSoundPitch", 1) <= 0)
                reasons.Add("発動音は必須の非空文字列、音量は非負、音程は正数です。");
        }
        var sources = new List<string> { SkillMaster + id + ".yml", ExecutorRoot + executor };
        if (executor.StartsWith("../", StringComparison.Ordinal)) sources[1] = ExecutorRoot.Replace("/active/", "/", StringComparison.Ordinal) + executor[3..];
        if (id == "archer_heal_arrow_alpha") { sources.Add(ExecutorRoot + "hunter/HunterHealArrowExecutor.java"); sources.Add("40_filebase/70.shared.buff/v1.archer_heal_arrow_alpha.yml"); }
        if (id == "hunter_spell_step") sources.Add(ExecutorRoot.Replace("/executor/active/", "/service/", StringComparison.Ordinal) + "SpellStepSkillRuntimeService.java");
        if (id == "archer_air_shift") sources.Add(ExecutorRoot.Replace("/executor/active/", "/service/", StringComparison.Ordinal) + "AirShiftSkillRuntimeService.java");
        if (id == "sharpshooter_inheritance_mastery") sources.Add(ExecutorRoot.Replace("/executor/active/", "/service/", StringComparison.Ordinal) + "InheritanceBuffService.java");
        IReadOnlyList<string> keys = id == "adventurer_blast_arrow"
            ? ["inheritanceMasteryName", "inheritanceBuffName", "inheritanceDurationTicks", "range", "radius", "damageRatio", "maxTargets", "projectileSpeed", "projectileHitRadius"]
            : UnsupportedKnownParameters(id);
        return new(id, [], sources, keys,
            ["単独プレイヤー対1体の敵、平面の床、指定した位置・照準・入力を使う20tick/秒の戦闘モデル。",
             "雨矢の円内乱数と重力弾道をseed付きで生成する。Plugin乱数列との同一性は保証しない。回復エリアは各エリアで自分と入力済み味方をそれぞれ一度だけ回復する。"],
            reasons, Runtime: true, Passive: passive,
            Requirements: id is "hunter_fade_shot" or "archer_air_shift" ? ["velocity_replay"] : []);
    }

    private static void ValidateInheritance(JsonObject p, List<string> reasons)
    {
        if (p["inheritanceBuffs"] is not JsonArray rows) { reasons.Add("params.inheritanceBuffs は必須の配列です。"); return; }
        string[] knownSources = ["hunter_arrow_rain", "adventurer_blast_arrow", "adventurer_quick_shot", "hunter_crash_arrow",
            "sharpshooter_fire_arrow", "sharpshooter_ice_arrow", "sharpshooter_spreading_ambition", "sharpshooter_phantom_shot",
            "sharpshooter_piercing_line", "sharpshooter_thermal_break", "sharpshooter_ricochet_arrow", "sharpshooter_glacial_trap"];
        var ids = new HashSet<string>(StringComparer.Ordinal);
        foreach (var raw in rows)
        {
            if (raw is not JsonObject row) { reasons.Add("継承バフ行はオブジェクトです。"); continue; }
            string buff = BalanceCombatData.Text(row["buffId"]);
            if (!buff.StartsWith("buff:", StringComparison.Ordinal) || buff.Length <= 5 || !ids.Add(buff)) reasons.Add("継承buff参照はbuff:付きで重複不可です。");
            if (I(row, "durationConsumptionTicks", 0) <= 0) reasons.Add("継承バフの時間消費は正整数です。");
            if (row.ContainsKey("consumeSourceSkillResources") && row["consumeSourceSkillResources"]?.GetValueKind() != System.Text.Json.JsonValueKind.True
                && row["consumeSourceSkillResources"]?.GetValueKind() != System.Text.Json.JsonValueKind.False) reasons.Add("継承リソース再消費設定はbooleanです。");
            if (row.ContainsKey("normalAttackExtraCount") || row.ContainsKey("normalAttackExtraIntervalTicks"))
                if (I(row, "normalAttackExtraCount", 0) is < 1 or > 4 || I(row, "normalAttackExtraIntervalTicks", 0) <= 0
                    || row["consumeSourceSkillResources"]?.GetValueKind() != System.Text.Json.JsonValueKind.False) reasons.Add("追加通常攻撃は1〜4回・正整数間隔・再消費falseです。");
            string source = BalanceCombatData.Text(row["sourceSkillId"]);
            if (!source.StartsWith("skill:", StringComparison.Ordinal) || source.Length <= 6)
                reasons.Add("このモデルは検証済み元スキル参照による継承だけを扱います。");
            else if (!knownSources.Contains(source[6..], StringComparer.Ordinal))
                reasons.Add($"継承元 {source} のcallbackは計算未対応です。");
            if (row.ContainsKey("resourceType") || row.ContainsKey("resourceCost")) reasons.Add("sourceSkillId と resourceType/resourceCost は併用できません。");
            foreach (string key in row.Select(pair => pair.Key).Except(["buffId", "durationConsumptionTicks", "sourceSkillId", "consumeSourceSkillResources", "normalAttackExtraCount", "normalAttackExtraIntervalTicks"], StringComparer.Ordinal))
                reasons.Add($"継承バフの追加項目 {key} は未検証です。");
        }
    }

    private static BalanceSkillProfile? RuntimeProfile(JsonObject definition, string id, JsonObject p)
    {
        string[] known = ["phantom_archer_shadow_stitch", "phantom_archer_soul_pierce", "phantom_archer_dread_bloom",
            "phantom_archer_spectral_sentry", "phantom_archer_echo_volley", "phantom_archer_mist_retreat",
            "phantom_archer_eclipse_shot", "phantom_archer_curse_weaving", "phantom_archer_spectral_attunement",
            "phantom_archer_wraith_step", "archer_starweave_followup"];
        if (!known.Contains(id, StringComparer.Ordinal)) return null;
        bool passive = id is "phantom_archer_curse_weaving" or "phantom_archer_spectral_attunement"
            or "phantom_archer_wraith_step" or "archer_starweave_followup";
        var reasons = new List<string>();
        void Positive(params string[] keys)
        {
            foreach (string key in keys)
                if (N(p, key, 0) <= 0) reasons.Add($"params.{key} はPluginの必須正数です。");
        }
        void Count(string key, int min, int max)
        {
            if (!p.ContainsKey(key) || I(p, key, -1) < min || I(p, key, -1) > max)
                reasons.Add($"params.{key} は{min}から{max}の整数です。");
        }
        if (passive)
        {
            if ((definition["passive"] as JsonObject)?["bindRequired"]?.GetValueKind() != System.Text.Json.JsonValueKind.True)
                reasons.Add("Pluginと同じくパッシブのバインドを必要とします。");
            if (id != "archer_starweave_followup" && (BalanceCombatData.Integer(definition["maxLevel"], 1) != 1
                || BalanceCombatData.Array(definition["levels"]).Any() || BalanceCombatData.Array(definition["allowedSigilIds"]).Any()
                || BalanceCombatData.Array(definition["sigilSlotsByLevel"]).Any()))
                reasons.Add("Pluginは最大Lv1・レベル差分なし・シジル枠なしの固有パッシブを要求します。");
            if (id == "phantom_archer_curse_weaving") Positive("markBonusRatio");
            if (id == "phantom_archer_wraith_step") { Positive("phantomDamageRatio"); Count("readyDurationTicks", 1, 400); }
            if (id == "archer_starweave_followup")
            {
                if (BalanceCombatData.Integer(definition["maxLevel"], 1) != 5
                    || BalanceCombatData.Integer(definition["cooldownTicks"]) != 120)
                    reasons.Add("星糸追矢はPluginと同じ最大Lv5・固定120tickの再使用間隔を要求します。");
                if (!p.ContainsKey("followupRatio") || N(p, "followupRatio", -1) is < 0 or > 2
                    || !p.ContainsKey("comboBonusRatio") || N(p, "comboBonusRatio", -1) is < 0 or > 1
                    || !p.ContainsKey("range") || N(p, "range", -1) is < 1 or > 40)
                    reasons.Add("星糸追矢は倍率0〜2・連携加算0〜1・射程1〜40の必須値を要求します。");
                if (I(p, "flightTicks", -1) != 6) reasons.Add("星糸追矢の飛翔時間はPluginと同じ6tickです。");
            }
        }
        else if (id == "phantom_archer_mist_retreat")
        { Positive("backstepVelocity", "evasionMultiplier"); Count("durationTicks", 1, 200); }
        else
        {
            Positive("damageRatio", "projectileSpeed", "projectileHitRadius");
            if (id == "phantom_archer_spectral_sentry")
            { Positive("placementRange", "targetRadius"); Count("shotIntervalTicks", 2, 100); Count("shotCount", 1, 16); }
            else Positive("range");
            if (id is "phantom_archer_soul_pierce" or "phantom_archer_eclipse_shot" or "phantom_archer_echo_volley") Count("maxHits", 1, 8);
            if (id is "phantom_archer_soul_pierce" or "phantom_archer_eclipse_shot") Positive("markDamageRatio");
            if (id == "phantom_archer_soul_pierce") Positive("conditionBonusRatio");
            if (id == "phantom_archer_eclipse_shot") Count("chargeTicks", 1, 40);
            if (id == "phantom_archer_echo_volley") { Count("shotCount", 2, 6); Count("shotIntervalTicks", 2, 40); }
            if (id is "phantom_archer_shadow_stitch" or "phantom_archer_dread_bloom")
            {
                if (!p.ContainsKey("conditionChance") || N(p, "conditionChance", -1) is < 0 or > 100)
                    reasons.Add("params.conditionChance は0から100の必須確率です。");
                Count("conditionTicks", 1, 400);
            }
            if (id == "phantom_archer_dread_bloom") { Positive("impactRadius"); Count("maxTargets", 1, 12); }
        }
        string root = "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/";
        string source = id == "archer_starweave_followup" ? root + "executor/RangedStarweaveFollowupExecutor.java"
            : ExecutorRoot + "phantomarcher/" + (passive ? "PhantomArcherPassiveExecutor.java" : "PhantomArcherSkillExecutor.java");
        var sources = new List<string> { SkillMaster + id + ".yml", source,
            ExecutorRoot + "phantomarcher/PhantomArcherRuntime.java" };
        if (id == "archer_starweave_followup") sources.Add(root + "service/RangedStarweaveRuntimeService.java");
        if (!passive) sources.Add(ExecutorRoot + "phantomarcher/PhantomArcherCastLifecycle.java");
        return new(id, [], sources, UnsupportedKnownParameters(id),
            ["弓・短弓・長弓を継続装備し、習得・使用許可・パッシブの装備とバインドは有効とする。",
             "霊印は所有者と対象ごと最大3層、最終付与から120tick。有効命中だけで付与・消費する。",
             "1体の敵と床面、指定入力・位置・射線のモデルで評価する。転職・装備変更・切断は戦闘中に起きない。"],
            reasons, Runtime: true, Passive: passive,
            Requirements: id == "phantom_archer_mist_retreat" ? ["bow", "velocity_replay"] : ["bow"]);
    }

    private static BalanceSkillProfile ElementalArrow(string id, JsonObject p, string element,
        string condition, double defaultChance, int defaultDuration, params string[] executors)
    {
        double range = N(p, "range", 16), speed = N(p, "projectileSpeed", 1.6), radius = N(p, "projectileHitRadius", .6);
        var reasons = ProjectileReasons(p, 16, 1.15, 1.6, .6);
        if (N(p, "conditionChance", defaultChance) is < 0 or > 100
            || N(p, "inheritedConditionChance", condition == "BURNING" ? 25 : 50) is < 0 or > 100)
            reasons.Add("実executorの状態異常付与率は0%から100%に限る。");
        if (I(p, "conditionDurationTicks", defaultDuration) < 1)
            reasons.Add("実executorの状態異常持続tickは1以上を要する。");
        return Profile(id,
            [new("damage", Value: N(p, "damageRatio", 1.15), AttackType: "RANGED", Element: element,
                FlightSpeed: speed, HitRadius: radius, Range: range),
             new("condition", AttackType: "RANGED", Target: "enemy", Status: condition, Trigger: "landed",
                Chance: N(p, "conditionChance", defaultChance), DurationTicks: I(p, "conditionDurationTicks", defaultDuration),
                FlightSpeed: speed, HitRadius: radius, Range: range)],
            executors,
            ["inheritanceMasteryName", "inheritanceBuffName", "inheritanceDurationTicks", "range", "damageRatio",
             "projectileSpeed", "projectileHitRadius", "conditionChance", "inheritedConditionChance", "conditionDurationTicks"],
            [SingleTarget, NoInheritance, "状態異常は有効命中後、対象耐性と付与率補正を適用する。"], reasons);
    }

    private static BalanceSkillProfile PiercingLine(JsonObject p)
    {
        const string id = "sharpshooter_piercing_line";
        var reasons = ProjectileReasons(p, 23, 1, 2, .55);
        if (I(p, "maxHits", 3) is < 2 or > 6 || N(p, "pierceDecay", .8) is <= 0 or > 1)
            reasons.Add("実executorの貫通数は2〜6、貫通減衰は0超1以下である必要がある。");
        return Profile(id, [ProjectileDamage(p, N(p, "damageRatio", 1), "NONE", 23, 2, .55)],
            ["SharpshooterPiercingLineExecutor.java"],
            ["inheritanceDurationTicks", "inheritanceConsumptionTicks", "damageRatio", "maxHits", "pierceDecay", "range", "projectileSpeed", "projectileHitRadius"],
            [SingleTarget, NoInheritance, "敵は1体のみで貫通後の後続対象がいない。最初の命中倍率は減衰前の値。"], reasons);
    }

    private static BalanceSkillProfile Ricochet(JsonObject p)
    {
        const string id = "sharpshooter_ricochet_arrow";
        var reasons = ProjectileReasons(p, 19, 1, 1.8, .6);
        if (N(p, "bounceRadius", 5) <= 0 || I(p, "bounceCount", 2) is < 1 or > 4
            || N(p, "bounceDecay", .7) is <= 0 or > 1)
            reasons.Add("実executorの反跳半径は正、回数1〜4、減衰0超1以下を要する。");
        return Profile(id, [ProjectileDamage(p, N(p, "damageRatio", 1), "NONE", 19, 1.8, .6)],
            ["SharpshooterRicochetArrowExecutor.java"],
            ["inheritanceDurationTicks", "inheritanceConsumptionTicks", "damageRatio", "bounceRadius", "bounceCount", "bounceDecay", "range", "projectileSpeed", "projectileHitRadius"],
            [SingleTarget, NoInheritance, "既に命中した同じ敵へは反跳せず、他の敵は存在しない。"], reasons);
    }

    private static BalanceSkillProfile ThermalBreak(JsonObject p)
    {
        const string id = "sharpshooter_thermal_break";
        double baseRatio = N(p, "damageRatio", 1), bonus = N(p, "statusBonusRatio", 0);
        var reasons = ProjectileReasons(p, 18, 1, 1.8, .6);
        if (bonus <= 0) reasons.Add("実executorの状態異常特効倍率は正数を要する。");
        return Profile(id,
            [new("damage", AttackType: "RANGED", Components: [new("FIRE", .5), new("ICE", .5)],
                Evaluate: view => baseRatio + (view.HasCondition("BURNING") || view.HasCondition("FROZEN") ? bonus : 0),
                FlightSpeed: N(p, "projectileSpeed", 1.8), HitRadius: N(p, "projectileHitRadius", .6), Range: N(p, "range", 18))],
            ["SharpshooterThermalBreakExecutor.java"],
            ["inheritanceDurationTicks", "inheritanceConsumptionTicks", "damageRatio", "statusBonusRatio", "range", "projectileSpeed", "projectileHitRadius"],
            [SingleTarget, NoInheritance, "命中直前の炎上または凍結で特効を判定し、火氷を各1/2成分として同一hitで計算する。"], reasons);
    }

    private static BalanceSkillProfile PhantomShot(JsonObject p)
    {
        const string id = "sharpshooter_phantom_shot";
        int interval = I(p, "shotIntervalTicks", 5);
        double recovery = N(p, "resourceRecoveryRatio", 0);
        var reasons = ProjectileReasons(p, 16, 1.70, 1.8, 1.10);
        if (interval < 1 || I(p, "successiveHitWindowTicks", 40) < 1 || recovery is < 0 or > 1)
            reasons.Add("実executorの発射間隔と連続命中窓は正整数、回復率は0〜1を要する。");
        BalanceSkillEffect shot(int tick) => ProjectileDamage(p, N(p, "damageRatio", 1.70), "NONE", 16, 1.8, 1.10) with { Tick = tick };
        return Profile(id, [shot(0), shot(interval), shot(interval * 2)],
            ["SharpshooterPhantomShotExecutor.java"],
            ["inheritanceMasteryName", "inheritanceBuffName", "inheritanceDurationTicks", "range", "damageRatio", "projectileSpeed",
             "projectileHitRadius", "shotIntervalTicks", "successiveHitWindowTicks", "resourceRecoveryRatio"],
            [SingleTarget, NoInheritance, "実executorは3発を0/間隔/2間隔tickで発射し、各弾で命中を独立判定する。"], reasons);
    }

    private static BalanceSkillProfile SpreadingAmbition(JsonObject p)
    {
        const string id = "sharpshooter_spreading_ambition";
        double ratio = N(p, "damageRatio", 2);
        int pellets = I(p, "pelletCount", 1);
        var reasons = ProjectileReasons(p, 24, 2, 1.6, .6);
        if (N(p, "explosionDamageRatio", 1) <= 0 || N(p, "explosionRadius", 3) <= 0
            || I(p, "maxExplosionTargets", 6) < 1 || N(p, "spreadAngle", 20) is < 0 or > 60)
            reasons.Add("実executorの爆発倍率・半径・最大対象数は正、拡散角は0〜60度を要する。");
        if (pellets is not (1 or 3 or 5)) reasons.Add("実executorの散弾数は1/3/5のいずれかです。");
        var profile = Profile(id,
            [new("damage", AttackType: "RANGED", Element: "NONE",
                Evaluate: view => ratio * (view.Conditions.Count > 0 ? 2 : 1),
                FlightSpeed: N(p, "projectileSpeed", 1.6), HitRadius: N(p, "projectileHitRadius", .6),
                Range: N(p, "range", 24), FlightOriginOffset: .8)],
            ["SharpshooterSpreadingAmbitionExecutor.java"],
            ["inheritanceMasteryName", "inheritanceBuffName", "inheritanceDurationTicks", "inheritanceConsumptionTicks",
             "range", "damageRatio", "explosionDamageRatio", "explosionRadius", "maxExplosionTargets", "pelletCount",
             "spreadAngle", "projectileSpeed", "projectileHitRadius"],
            [SingleTarget, NoInheritance, "指定された奇数本の散弾を全角内に等間隔で放つ。発射起点は視点の前方0.8ブロック。",
             "直撃対象は同じ矢の範囲爆発から除外される。着弾直前に状態異常が1種でもあれば直撃倍率を2倍にする。"],
            reasons);
        return profile with { Sources = [.. profile.Sources, ExecutorRoot + "hunter/HunterFadeShotExecutor.java"] };
    }

    private static BalanceSkillProfile BuildUp(JsonObject p)
    {
        const string id = "hunter_build_up";
        int duration = I(p, "buffDurationTicks", 200);
        double recovery = N(p, "energyRecoveryRatio", .01);
        double increase = N(p, "rangedAttackIncreaseRatio", .11);
        int level = (int)Math.Round(recovery * 100, MidpointRounding.ToEven);
        var reasons = new List<string>();
        if (p["buffId"]?.ToString() != "buff:hunter_build_up")
            reasons.Add("実executorはbuff:hunter_build_up参照だけを受け付ける。");
        if (level is < 1 or > 5 || Math.Abs(recovery - level / 100.0) > 1e-9)
            reasons.Add("実executorの毎秒ENG回復率はスキルレベルに対応する1%から5%に限る。");
        if (Math.Abs(increase - (.10 + recovery)) > 1e-9)
            reasons.Add("実executorは間接攻撃補正が10%+ENG回復率であることを検証する。");
        if (duration != 200)
            reasons.Add("実executorのENG回復は固定10回・200tickで、変更されたbuff期間と一致しない。");
        var effects = new List<BalanceSkillEffect> { new("stat", Value: increase, Status: "RANGED_ATTACK", Mode: "SCALAR",
            DurationTicks: duration, Scope: id) };
        for (int tick = 20; tick <= 200; tick += 20)
            effects.Add(new("resource", Tick: tick, Value: recovery, Basis: "maxEnergy", Status: "ENG", Scope: id));
        var profile = Profile(id, effects, ["HunterBuildUpExecutor.java"],
            ["buffId", "buffDurationTicks", "energyRecoveryRatio", "rangedAttackIncreaseRatio"],
            ["buff:hunter_build_up のレベル別定義をRANGED_ATTACK SCALARとして適用する。",
             "ENGは20tick間隔で10回、各回最大ENGの指定率を回復し上限で切る。"],
            reasons);
        string suffix = level <= 1 ? "" : "_lv" + level;
        return profile with { Sources = [.. profile.Sources, "40_filebase/70.shared.buff/v1.hunter_build_up" + suffix + ".yml"] };
    }

    private static BalanceSkillEffect ProjectileDamage(JsonObject p, double ratio, string element,
        double defaultRange, double defaultSpeed, double defaultRadius)
        => new("damage", Value: ratio, AttackType: "RANGED", Element: element,
            FlightSpeed: N(p, "projectileSpeed", defaultSpeed), HitRadius: N(p, "projectileHitRadius", defaultRadius),
            Range: N(p, "range", defaultRange));

    private static BalanceSkillProfile Profile(string id, IReadOnlyList<BalanceSkillEffect> effects,
        IReadOnlyList<string> executors, IReadOnlyList<string> keys, IReadOnlyList<string> assumptions,
        IReadOnlyList<string>? reasons = null)
    {
        var sources = new List<string> { SkillMaster + id + ".yml" };
        foreach (string executor in executors)
            sources.Add(ExecutorRoot + (id.StartsWith("hunter_") ? "hunter/" : "sharpshooter/") + executor);
        bool runtime = id.StartsWith("sharpshooter_", StringComparison.Ordinal);
        return new(id, effects, sources, keys, assumptions.Where(value => !runtime || value != NoInheritance).ToArray(),
            reasons ?? [], Runtime: runtime);
    }

    private static IReadOnlyList<string> UnsupportedKnownParameters(string id) => id switch
    {
        "hunter_fade_shot" => ["range", "damageRatio", "pelletCount", "spreadAngle", "projectileSpeed", "projectileHitRadius", "backstepVelocity"],
        "hunter_arrow_rain" => ["inheritanceMasteryName", "inheritanceBuffName", "inheritanceDurationTicks", "range", "radius", "arrowCount", "damageRatios", "openingSpeed", "openingHitRadius", "rainHitRadius"],
        "hunter_crash_arrow" => ["inheritanceMasteryName", "inheritanceBuffName", "inheritanceDurationTicks", "range", "damageRatio", "shieldBreakMultiplier", "superStarCriticalChance", "projectileSpeed", "projectileHitRadius"],
        "hunter_heal_arrow" => ["radius", "healAmount", "damageRatio", "projectileSpeed", "projectileHitRadius", "areaDurationTicks"],
        "hunter_spell_step" => ["windowTicks", "triggerSound", "triggerSoundVolume", "triggerSoundPitch"],
        "archer_air_shift" => ["horizontalStrength", "verticalStrength", "triggerSound", "triggerSoundVolume", "triggerSoundPitch"],
        "archer_heal_arrow_alpha" => ["radius", "healPercent", "damageRatio", "projectileSpeed", "projectileHitRadius", "areaDurationTicks"],
        "archer_starweave_followup" => ["followupRatio", "comboBonusRatio", "range", "flightTicks"],
        "sharpshooter_spreading_ambition" => ["inheritanceMasteryName", "inheritanceBuffName", "inheritanceDurationTicks", "inheritanceConsumptionTicks", "range", "damageRatio", "explosionDamageRatio", "explosionRadius", "maxExplosionTargets", "pelletCount", "spreadAngle", "projectileSpeed", "projectileHitRadius"],
        "sharpshooter_glacial_trap" => ["inheritanceDurationTicks", "inheritanceConsumptionTicks", "damageRatio", "radius", "maxTargets", "conditionChance", "conditionTicks", "range", "projectileSpeed", "projectileHitRadius"],
        "sharpshooter_inheritance_mastery" => ["normalAttackDamageMultiplier", "inheritedSkillDamageMultiplier", "inheritanceBuffs"],
        "phantom_archer_shadow_stitch" => ["range", "damageRatio", "projectileSpeed", "projectileHitRadius", "conditionChance", "conditionTicks"],
        "phantom_archer_soul_pierce" => ["markDamageRatio", "range", "damageRatio", "projectileSpeed", "projectileHitRadius", "maxHits", "conditionBonusRatio"],
        "phantom_archer_dread_bloom" => ["range", "damageRatio", "projectileSpeed", "projectileHitRadius", "impactRadius", "maxTargets", "conditionChance", "conditionTicks"],
        "phantom_archer_spectral_sentry" => ["shotCount", "placementRange", "targetRadius", "damageRatio", "projectileSpeed", "projectileHitRadius", "shotIntervalTicks"],
        "phantom_archer_echo_volley" => ["range", "damageRatio", "projectileSpeed", "projectileHitRadius", "maxHits", "shotCount", "shotIntervalTicks"],
        "phantom_archer_mist_retreat" => ["backstepVelocity", "durationTicks", "evasionMultiplier"],
        "phantom_archer_eclipse_shot" => ["range", "damageRatio", "projectileSpeed", "projectileHitRadius", "maxHits", "markDamageRatio", "chargeTicks"],
        "phantom_archer_curse_weaving" => ["markBonusRatio", "modifiers"],
        "phantom_archer_spectral_attunement" => ["modifiers"],
        "phantom_archer_wraith_step" => ["phantomDamageRatio", "readyDurationTicks", "modifiers"],
        _ => []
    };

    private static double N(JsonObject p, string key, double fallback) => BalanceSkillParameters.Number(p, key, fallback);
    private static int I(JsonObject p, string key, int fallback) => BalanceSkillParameters.Integer(p, key, fallback);

    private static List<string> ProjectileReasons(JsonObject p, double range, double damage,
        double speed, double hitRadius)
    {
        var reasons = new List<string>();
        if (N(p, "range", range) <= 0 || N(p, "damageRatio", damage) <= 0
            || N(p, "projectileSpeed", speed) <= 0 || N(p, "projectileHitRadius", hitRadius) <= 0)
            reasons.Add("実executorは射程・ダメージ倍率・飛翔速度・当たり半径に正数を要する。");
        return reasons;
    }
}
