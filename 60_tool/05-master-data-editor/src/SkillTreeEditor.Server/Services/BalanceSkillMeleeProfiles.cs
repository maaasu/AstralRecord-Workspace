using System.Text.Json;
using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Services;

/// <summary>Executor-checked plans for the melee class branch. An unsupported descriptor is intentional.</summary>
internal static class BalanceSkillMeleeProfiles
{
    private const string Base = "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/executor/";
    private const string SwordmasterSupport = Base + "active/swordmaster/SwordmasterSkillSupport.java";
    private const string PaladinSupport = Base + "active/paladin/PaladinBuildSkillSupport.java";

    internal static BalanceSkillProfile? Resolve(JsonObject definition)
    {
        var id = definition["implementationId"]?.ToString() ?? definition["id"]?.ToString() ?? "";
        var parameters = BalanceSkillParameters.Params(definition);
        var p = parameters;
        var assumptions = new[] { "スキルLv1・シジル未装着・静止した敵1体を射程内で視認できる条件です。" };
        BalanceSkillProfile Supported(string file, string[] keys, params BalanceSkillEffect[] effects)
            => new(id, effects, Sources(file), keys, assumptions, ValidateParameters(id, p, keys));
        BalanceSkillProfile Stopped(string file, string reason, params string[] keys)
            => new(id, [], Sources(file), keys, assumptions, [reason]);
        BalanceSkillProfile Assume(BalanceSkillProfile profile, string explanation)
            => profile with { Assumptions = [.. profile.Assumptions, explanation] };
        BalanceSkillEffect Hit(int tick, double ratio, string element = "NONE", string trigger = "always",
            Func<BalanceSkillView, double>? evaluate = null)
            => new("damage", tick, ratio, "MELEE", element, Trigger: trigger, Evaluate: evaluate,
                Range: Reach(), HitRadius: p["hitRadius"] is null ? 0 : N("hitRadius"));

        return id switch
        {
            "adventurer_astral_edge" => AstralEdge(),
            "swordsman_flame_rush" => FlameRush(),
            "swordmaster_blade_tempest" => BladeTempest(),
            "swordmaster_crescent_duet" => Supported("active/swordmaster/SwordmasterCrescentDuetExecutor.java",
                ["range", "angle", "maxTargets", "damageRatio", "criticalMultiplier", "intervalTicks"],
                Hit(1, N("damageRatio")),
                Hit(1 + I("intervalTicks"), N("damageRatio"), evaluate: view =>
                    N("damageRatio") * (view.LastCritical ? N("criticalMultiplier") : 1))),
            "swordmaster_heartseeker" => Supported("active/swordmaster/SwordmasterHeartseekerExecutor.java",
                ["range", "hitRadius", "maxTargets", "damageRatio", "executeThreshold", "executeMultiplier", "healHpRatio"],
                Hit(0, N("damageRatio"), evaluate: view => N("damageRatio") *
                    (view.EnemyHp / Math.Max(1, view.Enemy.GetValueOrDefault("MAX_HEALTH")) <= N("executeThreshold")
                        ? N("executeMultiplier") : 1)),
                new BalanceSkillEffect("heal", Value: N("healHpRatio"), Basis: "maxHealth", Trigger: "killed")),
            "swordmaster_star_cleave" => Supported("active/swordmaster/SwordmasterStarCleaveExecutor.java",
                ["range", "angle", "maxTargets", "damageRatio", "burstRatio", "burstRadius"],
                Hit(0, N("damageRatio")), Hit(1, N("burstRatio"), trigger: "critical")),
            "swordmaster_overdrive" => Supported("active/swordmaster/SwordmasterOverdriveExecutor.java",
                ["durationTicks", "outgoingMultiplier", "incomingMultiplier"],
                new BalanceSkillEffect("outgoing", Value: N("outgoingMultiplier"), DurationTicks: I("durationTicks")),
                new BalanceSkillEffect("incoming", Value: N("incomingMultiplier"), DurationTicks: I("durationTicks"))),
            "swordmaster_trinity_edge" => Supported("active/swordmaster/SwordmasterTrinityEdgeExecutor.java",
                ["range", "angle", "maxTargets", "damageRatio", "conditionBonusRatio"],
                new BalanceSkillEffect("damage", AttackType: "MELEE", Components:
                    [new("FIRE", N("damageRatio")), new("ICE", N("damageRatio")), new("LIGHTNING", N("damageRatio"))],
                    Evaluate: view => 1 + new[] { "BURNING", "CHILLED", "SHOCKED" }
                        .Count(view.HasCondition) * N("conditionBonusRatio"), Range: Reach())),
            "paladin_blessed_lance" => Assume(Supported("active/paladin/PaladinBlessedLanceExecutor.java",
                ["range", "hitRadius", "maxTargets", "damageRatio", "fieldMultiplier"], Hit(1, N("damageRatio"))),
                "聖域外で発動し、聖域による倍率はありません。"),
            "paladin_holy_reprieve" => Assume(Supported("active/paladin/PaladinHolyReprieveExecutor.java",
                ["radius", "height", "healAmount", "shieldHealRatio", "fieldMultiplier"],
                new BalanceSkillEffect("heal", Evaluate: view => N("healAmount") +
                    view.Stat("MAX_SHIELD") * N("shieldHealRatio"))),
                "聖域外で発動し、術者自身が回復範囲と視線内にいます。"),
            "paladin_holy_smash" => Assume(Supported("active/paladin/PaladinHolySmashExecutor.java",
                ["range", "targetAngle", "maxTargets", "damageRatio", "defenseReductionRatio",
                    "defenseDebuffDurationTicks", "energyRecoveryAmount", "reducedCooldownTicks"],
                Hit(0, N("damageRatio"))), "聖域外で発動し、聖域中の防御低下・ENG回復・CD短縮は生じません。"),
            "paladin_shield_impact" => Stopped("active/paladin/PaladinShieldImpactExecutor.java",
                "GUARD消費と命中後の敵DEF倍率低下を同時に処理する必要があります。",
                "radius", "damageRatio", "defenseReductionRatio", "defenseDebuffDurationTicks"),

            // Every known executor in this class branch has a descriptor. The reason is part of strict-mode output.
            "adventurer_blast_arrow" => StopAdventurer("AdventurerBlastArrowExecutor", "飛翔体の着弾位置・時間と継承バフに依存します。"),
            "adventurer_lightning_bolt" => StopAdventurer("AdventurerLightningBoltExecutor", "飛翔体、連鎖対象、プリズムと状態異常に依存します。"),
            "adventurer_meditation" => Stopped("MeditationSkillExecutor.java", "チャネル中の状態とMP回復倍率の変化に依存します。"),
            "swordsman_bastion_strike" => Stopped("SwordsmanBastionStrikeExecutor.java", "被弾を契機とするパッシブ反撃と現在MP全消費に依存します。"),
            "swordsman_challenging_roar" => StopSwordsman("SwordsmanChallengingRoarExecutor", "挑発による敵の対象選択は固定1対1モデルの発動効果外です。"),
            "swordsman_exept_stamp" => StopSwordsman("SwordsmanExeptStampExecutor", "飛翔・スニーク・着地で命中の有無と時刻が変わります。"),
            "swordsman_shield_activate" => Stopped("SwordsmanShieldActivateSkillExecutor.java", "シールド常時パッシブであり、castは失敗します。"),
            "swordsman_shield_drain" => Supported("active/swordsman/SwordsmanShieldDrainExecutor.java",
                ["range", "targetAngle", "maxTargets", "damageRatio", "shieldBreakMultiplier", "shieldAbsorbRatio"],
                new BalanceSkillEffect("damage", Value: N("damageRatio"), AttackType: "MELEE",
                    ShieldMultiplier: N("shieldBreakMultiplier"), Range: Reach()),
                new BalanceSkillEffect("resource", Value: N("shieldAbsorbRatio"), Basis: "lastShieldDamage",
                    Status: "SHIELD", Trigger: "landed")),
            "swordmaster_crimson_drive" => StopSwordmaster("SwordmasterCrimsonDriveExecutor", "dash成功・経路上命中・炎上・撃破時CD短縮に依存します。"),
            "swordmaster_flame_lotus" => RepeatedCondition("SwordmasterFlameLotusExecutor", "FIRE", "BURNING"),
            "swordmaster_frost_bloom" => Supported("active/swordmaster/SwordmasterFrostBloomExecutor.java",
                ["radius", "maxTargets", "damageRatio", "shockBonusRatio", "conditionChance", "conditionTicks"],
                Hit(0, N("damageRatio"), "ICE"),
                new BalanceSkillEffect("condition", AttackType: "MELEE", Status: "CHILLED", Chance: N("conditionChance"),
                    DurationTicks: I("conditionTicks"), Target: "enemy", Trigger: "landed", Range: Reach(), HitRadius: HitRadius()),
                new BalanceSkillEffect("damage", Value: N("shockBonusRatio"), AttackType: "MELEE", Element: "ICE",
                    Trigger: "landed", When: view => view.HasCondition("SHOCKED"), Range: Reach())),
            "swordmaster_gale_reaper" => StopSwordmaster("SwordmasterGaleReaperExecutor", "dash成功と移動経路上の命中に依存します。"),
            "swordmaster_mirage_retreat" => StopSwordmaster("SwordmasterMirageRetreatExecutor", "backstep成功後の位置から3回の対象検索に依存します。"),
            "swordmaster_revenge_accel" => StopSwordmaster("SwordmasterRevengeAccelExecutor", "静寂一閃の剣気消費状態に依存します。"),
            "swordmaster_riposte" => StopSwordmaster("SwordmasterRiposteExecutor", "静寂一閃の剣気消費状態に依存します。"),
            "swordmaster_seijaku_issen" => Stopped("SwordmasterSeijakuIssenExecutor.java", "被弾と通常攻撃に結合したパッシブ反撃です。"),
            "swordmaster_serene_mend" => StopSwordmaster("SwordmasterSereneMendExecutor", "剣気有無による追加HP/ENG回復と剣気獲得に依存します。"),
            "swordmaster_thunder_line" => Supported("active/swordmaster/SwordmasterThunderLineExecutor.java",
                ["range", "hitRadius", "maxTargets", "damageRatio", "conditionChance", "conditionTicks"],
                Hit(0, N("damageRatio"), "LIGHTNING"),
                new BalanceSkillEffect("condition", AttackType: "MELEE", Status: "SHOCKED", Chance: N("conditionChance"),
                    DurationTicks: I("conditionTicks"), Target: "enemy", Trigger: "landed", Range: Reach(), HitRadius: HitRadius())),
            "swordmaster_vortex_edge" => StopSwordmaster("SwordmasterVortexEdgeExecutor", "引寄せで以後の距離と命中を変更します。"),
            "paladin_defense_conversion" => Stopped("PaladinDefenseConversionSkillExecutor.java", "防御転換パッシブであり、castは失敗します。"),
            "paladin_divine_chaser" => Stopped("PaladinDivineChaserSkillExecutor.java", "他スキル発動後の追尾弾を追加するパッシブです。"),
            "paladin_fortress" => StopPaladin("PaladinFortressExecutor", "220tick間の挑発と敵対象選択に依存します。"),
            "paladin_guard_convert" => Stopped("PaladinGuardConvertSkillExecutor.java", "GUARD変換パッシブであり、castは失敗します。"),
            "paladin_guardian_bulwark" => StopPaladin("PaladinGuardianBulwarkExecutor", "GUARD8の消費と持続被ダメ/与ダメ倍率が必要です。"),
            "paladin_guardian_chain" => StopPaladin("PaladinGuardianChainExecutor", "20tick間隔の引寄せで距離と命中を変更します。"),
            "paladin_guardian_protect" => StopPaladin("PaladinGuardianProtectExecutor", "パーティ対象への90%ダメージ肩代わりで単独では発動しません。"),
            "paladin_guardian_renewal" => StopPaladin("PaladinGuardianRenewalExecutor", "GUARD8消費の自己回復でGUARD資源が必要です。"),
            "paladin_holy_control" => StopPaladin("PaladinHolyControlExecutor", "聖柱状態・HP200消費・周期バフ/デバフに依存します。"),
            "paladin_holy_field" => StopPaladin("PaladinHolyFieldExecutor", "聖域の位置と盾破壊倍率による他スキル連携に依存します。"),
            "paladin_holy_smite" => HolySmite(),
            "paladin_shield" => StopPaladin("PaladinShieldExecutor", "盾50%消費、パーティの有無による付与/聖柱分岐に依存します。"),
            "paladin_shield_bash" => StopPaladin("PaladinShieldBashExecutor", "GUARD消費・移動経路の反復命中・鈍足/ノックバックに依存します。"),
            "administrator_just_dodge" => Stopped("AdministratorJustDodgeSkillExecutor.java", "回避成功時の無敵とENG回復を扱うパッシブです。"),
            "administrator_shield_recharge" => Stopped("AdministratorShieldRechargeSkillExecutor.java", "盾再充填設定のパッシブであり、castは失敗します。"),
            _ => null
        };

        double N(string key) => BalanceSkillParameters.Number(p, key);
        int I(string key) => BalanceSkillParameters.Integer(p, key);
        double HitRadius() => p["hitRadius"] is null ? 0 : N("hitRadius");
        double Reach() => p["range"] is not null ? N("range")
            : p["reach"] is not null ? N("reach") : p["radius"] is not null ? N("radius") : 0;
        BalanceSkillProfile StopAdventurer(string name, string reason) => Stopped("active/adventurer/" + name + ".java", reason);
        BalanceSkillProfile StopSwordsman(string name, string reason) => Stopped("active/swordsman/" + name + ".java", reason);
        BalanceSkillProfile StopSwordmaster(string name, string reason) => Stopped("active/swordmaster/" + name + ".java", reason);
        BalanceSkillProfile StopPaladin(string name, string reason) => Stopped("active/paladin/" + name + ".java", reason);

        BalanceSkillProfile AstralEdge()
        {
            var ratios = BalanceSkillParameters.Numbers(p, "damageRatios");
            if (ratios.Length != 2) return Stopped("active/adventurer/AdventurerAstralEdgeExecutor.java",
                "damageRatiosは実executorと同じ2要素が必要です。", "reach", "maxTargets", "damageRatios", "energyRecoveryRatio");
            return Assume(Supported("active/adventurer/AdventurerAstralEdgeExecutor.java",
                ["reach", "maxTargets", "damageRatios", "energyRecoveryRatio"],
                Hit(0, ratios[0]),
                new BalanceSkillEffect("resource", Value: N("energyRecoveryRatio"), Basis: "maxEnergy",
                    Status: "ENG", Trigger: "landed"),
                Hit(4, ratios[1]),
                new BalanceSkillEffect("resource", Tick: 4, Value: N("energyRecoveryRatio"),
                    Basis: "maxEnergy", Status: "ENG", Trigger: "landed")),
                "敵が初撃の掃引と4tick後の2撃目に入り、攻撃側が継続して行動できます。");
        }

        BalanceSkillProfile FlameRush()
        {
            var ratios = BalanceSkillParameters.Numbers(p, "damageRatios");
            const string file = "active/swordsman/SwordsmanFlameRushExecutor.java";
            var keys = new[] { "range", "targetAngle", "maxTargets", "damageRatios", "secondHitDelayTicks", "burningChance", "burningDurationTicks" };
            if (ratios.Length != 2) return Stopped(file, "damageRatiosは実executorと同じ2要素が必要です。", keys);
            return Supported(file, keys, Hit(0, ratios[0], "FIRE"), Hit(I("secondHitDelayTicks"), ratios[1], "FIRE"),
                new BalanceSkillEffect("condition", Tick: I("secondHitDelayTicks"), AttackType: "MELEE", Status: "BURNING",
                    Chance: N("burningChance"), DurationTicks: I("burningDurationTicks"),
                    Target: "enemy", Trigger: "landed", Range: Reach()));
        }

        BalanceSkillProfile BladeTempest()
        {
            var keys = new[] { "radius", "maxTargets", "damageRatio", "hitCount", "intervalTicks" };
            var count = I("hitCount");
            if (count is < 1 or > 6) return Stopped("active/swordmaster/SwordmasterBladeTempestExecutor.java",
                "hitCountは実executorの範囲1〜6が必要です。", keys);
            return Supported("active/swordmaster/SwordmasterBladeTempestExecutor.java", keys,
                Enumerable.Range(0, count).Select(index => Hit(1 + index * I("intervalTicks"), N("damageRatio"))).ToArray());
        }

        BalanceSkillProfile RepeatedCondition(string executor, string element, string condition)
        {
            var keys = new[] { "radius", "maxTargets", "damageRatio", "hitCount", "intervalTicks", "conditionChance", "conditionTicks" };
            var count = I("hitCount");
            if (count is < 1 or > 6) return Stopped("active/swordmaster/" + executor + ".java",
                "hitCountは実executorの範囲1〜6が必要です。", keys);
            var effects = new List<BalanceSkillEffect>();
            for (var index = 0; index < count; index++)
            {
                var tick = 1 + index * I("intervalTicks");
                effects.Add(Hit(tick, N("damageRatio"), element));
                effects.Add(new BalanceSkillEffect("condition", Tick: tick, AttackType: "MELEE", Status: condition,
                    Chance: N("conditionChance"), DurationTicks: I("conditionTicks"),
                    Target: "enemy", Trigger: "landed", Range: Reach()));
            }
            return Supported("active/swordmaster/" + executor + ".java", keys, effects.ToArray());
        }

        BalanceSkillProfile HolySmite()
        {
            var file = "active/paladin/PaladinHolySmiteExecutor.java";
            var keys = new[] { "damageRatio", "radius", "durationTicks", "impactIntervalTicks", "maxTargets",
                "weaknessChance", "weaknessDurationTicks", "weaknessStrength", "pillarHeight", "slashRingRadius" };
            var duration = I("durationTicks");
            var interval = I("impactIntervalTicks");
            if (duration is < 1 or > 600 || interval is < 1 or > 600)
                return Stopped(file, "聖柱の持続・打撃間隔が実行可能な正の範囲外です。", keys);
            var effects = new List<BalanceSkillEffect>();
            for (var tick = 0; tick < duration; tick += interval)
            {
                effects.Add(Hit(tick, N("damageRatio")));
                effects.Add(new BalanceSkillEffect("condition", Tick: tick, AttackType: "MELEE", Status: "WEAKNESS",
                    Chance: N("weaknessChance"), DurationTicks: I("weaknessDurationTicks"),
                    Value: N("weaknessStrength"), Target: "enemy", Trigger: "landed", Range: Reach()));
            }
            return Assume(Supported(file, keys, effects.ToArray()),
                "聖柱の中心に敵が常時入り、ランダム対象は唯一の敵になります。");
        }
    }

    private static IReadOnlyList<string> Sources(string relative)
    {
        var sources = new List<string> { Base + relative };
        if (relative.StartsWith("active/swordmaster/", StringComparison.Ordinal)) sources.Add(SwordmasterSupport);
        if (relative.StartsWith("active/paladin/", StringComparison.Ordinal)) sources.Add(PaladinSupport);
        return sources;
    }

    private static IReadOnlyList<string> ValidateParameters(string implementationId, JsonObject parameters,
        IReadOnlyList<string> keys)
    {
        var errors = new List<string>();
        foreach (var key in keys)
        {
            var node = parameters[key];
            if (key == "damageRatios")
            {
                if (node is not JsonArray array || array.Count == 0
                    || array.Any(value => value?.GetValueKind() != JsonValueKind.Number
                    || !double.TryParse(value.ToString(), System.Globalization.NumberStyles.Float,
                        System.Globalization.CultureInfo.InvariantCulture, out var number) || !double.IsFinite(number)))
                    errors.Add($"{key} は数値配列が必要です。");
            }
            else if (node is not JsonValue || node.GetValueKind() != JsonValueKind.Number
                     || !double.TryParse(node.ToString(), System.Globalization.NumberStyles.Float,
                         System.Globalization.CultureInfo.InvariantCulture, out var value)
                     || !double.IsFinite(value))
                errors.Add($"{key} は有限の数値が必要です。");
        }
        if (errors.Count > 0) return errors;

        // Mirror each executor's validateParams, including the shared Swordmaster and Paladin helpers.
        foreach (var key in keys)
        {
            if (parameters[key] is JsonArray values)
            {
                if (key == "damageRatios" && (values.Count != 2 || values.Any(value => Number(value!) <= 0)))
                    errors.Add("damageRatios は正数2件の配列が必要です。");
                continue;
            }

            var number = Number(parameters[key]!);
            var allowZero = false;
            var maximum = double.PositiveInfinity;
            var integer = false;

            if (implementationId.StartsWith("swordmaster_", StringComparison.Ordinal))
            {
                integer = key.EndsWith("Ticks", StringComparison.Ordinal) || key is "maxTargets" or "hitCount";
                maximum = key.EndsWith("Ticks", StringComparison.Ordinal) ? 400
                    : key == "maxTargets" ? 12 : key == "hitCount" ? 6
                    : key == "angle" ? 180 : key == "range" ? 12
                    : key == "radius" || key.EndsWith("Radius", StringComparison.Ordinal) ? 6
                    : key.EndsWith("HpRatio", StringComparison.Ordinal) || key == "executeThreshold" ? 1
                    : key == "conditionChance" ? 100 : 30;
            }
            else if (implementationId is "paladin_blessed_lance" or "paladin_holy_reprieve")
            {
                integer = key.EndsWith("Ticks", StringComparison.Ordinal) || key == "maxTargets";
                maximum = key switch
                {
                    "range" or "radius" or "height" or "maxTargets" => 12,
                    "hitRadius" or "healHpRatio" or "shieldHealRatio" or "incomingMultiplier" or "outgoingMultiplier" => 1,
                    "durationTicks" => 100,
                    "healAmount" => 200,
                    _ => 5
                };
            }
            else if (implementationId == "adventurer_astral_edge")
            {
                integer = key == "maxTargets";
                if (key == "energyRecoveryRatio") maximum = 1;
            }
            else if (implementationId is "swordsman_flame_rush" or "swordsman_shield_drain")
            {
                integer = key == "maxTargets" || key.EndsWith("Ticks", StringComparison.Ordinal);
                if (key == "targetAngle") maximum = 180;
                if (key is "burningChance" or "secondHitDelayTicks") allowZero = true;
                if (key is "burningChance") maximum = 100;
                if (key == "shieldAbsorbRatio") maximum = 1;
            }
            else if (implementationId == "paladin_holy_smash")
            {
                integer = key == "maxTargets" || key.EndsWith("Ticks", StringComparison.Ordinal);
                if (key == "targetAngle") maximum = 180;
                if (key == "defenseReductionRatio") { allowZero = true; maximum = 100; }
            }
            else if (implementationId == "paladin_holy_smite")
            {
                integer = key == "maxTargets" || key.EndsWith("Ticks", StringComparison.Ordinal);
                if (key == "weaknessChance") { allowZero = true; maximum = 100; }
            }

            if (number < 0 || !allowZero && number == 0 || number > maximum
                || integer && (number != Math.Truncate(number) || number > int.MaxValue))
                errors.Add($"{key} は実Plugin executorの許容範囲外です。");
        }
        return errors;
    }

    private static double Number(JsonNode value) => double.Parse(value.ToString(),
        System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture);
}
