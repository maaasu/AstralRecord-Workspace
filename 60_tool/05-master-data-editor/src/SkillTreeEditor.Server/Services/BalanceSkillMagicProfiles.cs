using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Services;

/// <summary>Plugin の魔法スキル実行処理を固定距離・単体戦闘用の効果計画へ写す。</summary>
internal static class BalanceSkillMagicProfiles
{
    private const string Mage = "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/executor/active/mage/";
    private const string Wizard = "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/executor/active/wizard/";
    private const string Archmage = "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/executor/active/archmage/";
    private const string Executor = "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/executor/";
    private const string Skill = "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/";

    /// <summary>実装IDに一致する魔法スキルを解決し、未対応効果には理由を返す。</summary>
    internal static BalanceSkillProfile? Resolve(JsonObject definition)
    {
        var id = BalanceCombatData.Text(definition["implementationId"]);
        var p = BalanceSkillParameters.Params(definition);
        return id switch
        {
            "mage_fireball" => Ready(id, p, ["range", "radius", "damageRatio", "maxTargets", "projectileSpeed", "projectileHitRadius"],
                [new("damage", Value: N(p, "damageRatio", 1.32), Element: "FIRE",
                    FlightSpeed: N(p, "projectileSpeed", 1.45), HitRadius: N(p, "projectileHitRadius", .45), Range: N(p, "range", 16))],
                [Mage + "MageFireballExecutor.java"], "射程内、遮蔽なし、火球は対象へ命中する。範囲内の他対象は存在しない。"),
            "mage_frost_ball" => Ready(id, p, KnownParameters(id),
                [new("damage", Value: N(p, "damageRatio", .45), Element: "ICE",
                    FlightSpeed: N(p, "projectileSpeed", 1.45), HitRadius: N(p, "projectileHitRadius", .45), Range: N(p, "range", 16)),
                    new("condition", Status: "FROZEN", Chance: N(p, "freezeChance", 75),
                        DurationTicks: I(p, "freezeDurationTicks", 40), Trigger: "landed", Target: "enemy",
                        FlightSpeed: N(p, "projectileSpeed", 1.45), HitRadius: N(p, "projectileHitRadius", .45), Range: N(p, "range", 16))],
                [Mage + "MageFrostBallExecutor.java"], "射程内、遮蔽なしで氷球が単体へ命中する。凍結中の敵行動停止を反映する。"),
            "mage_heal_aura" => Ready(id, p, ["radius", "height", "healAmount", "healPercent"],
                [new("heal", Value: N(p, "healPercent", 1) / 100, Basis: "currentHealth", Target: "player", Minimum: N(p, "healAmount", 5))],
                [Mage + "MageHealAuraExecutor.java"], "術者が回復範囲内にいる。味方は存在しない。現在HPに対する割合と固定下限の大きい方を回復する。"),
            "wizard_arcane_lance" => Ready(id, p, ["range", "hitRadius", "maxTargets", "damageRatio"],
                [new("damage", Value: N(p, "damageRatio", 3.2), Range: N(p, "range", 20), HitRadius: N(p, "hitRadius", .3))],
                [Wizard + "WizardArcaneLanceExecutor.java"], "地形に遮られず、直線上の単体へ光槍が命中する。"),
            "wizard_prismatic_dart" => Ready(id, p, ["range", "hitRadius", "projectileSpeed", "damageRatio"],
                [new("damage", Value: N(p, "damageRatio", 1.4), FlightSpeed: N(p, "projectileSpeed", 1.6),
                    HitRadius: N(p, "hitRadius", .3), Range: N(p, "range", 16))],
                [Wizard + "WizardPrismaticDartExecutor.java"], "射程内、遮蔽なしで結晶弾が単体へ命中する。飛翔体の外れは計算しない。"),
            "wizard_elemental_ball" => Ready(id, p, ["range", "radius", "damageRatio", "maxTargets", "projectileSpeed", "projectileHitRadius"],
                [new("damage", Evaluate: view => N(p, "damageRatio", 2.45) * (view.Conditions.Count > 0 ? 2 : 1),
                    FlightSpeed: N(p, "projectileSpeed", 1.45), HitRadius: N(p, "projectileHitRadius", .45), Range: N(p, "range", 16))],
                [Wizard + "WizardElementalBallExecutor.java"], "射程内、遮蔽なしで魔力球が命中する。命中直前に敵が何らかの状態異常中なら倍率を2倍にする。"),
            "wizard_mana_siphon" => Ready(id, p, ["range", "hitRadius", "projectileSpeed", "damageRatio", "manaRecovery"],
                [new("damage", Value: N(p, "damageRatio", 1.2), FlightSpeed: N(p, "projectileSpeed", 1.2),
                    HitRadius: N(p, "hitRadius", .35), Range: N(p, "range", 14)),
                    new("resource", Value: N(p, "manaRecovery", 20), Target: "player", Status: "MP", Trigger: "landed",
                        FlightSpeed: N(p, "projectileSpeed", 1.2), HitRadius: N(p, "hitRadius", .35), Range: N(p, "range", 14))],
                [Wizard + "WizardManaSiphonExecutor.java"], "射程内、遮蔽なしで吸収弾が単体へ命中する。命中成立時だけMPを固定量回復する。"),
            "wizard_arcane_overload" => Ready(id, p, ["range", "radius", "maxTargets", "damageRatio", "manaThreshold", "empoweredMultiplier"],
                [new("damage", Evaluate: view => N(p, "damageRatio", 3.8) *
                    (view.Stat("MAX_MANA") > 0 && view.Mp / view.Stat("MAX_MANA") >= N(p, "manaThreshold", .6)
                        ? N(p, "empoweredMultiplier", 1.4) : 1), EvaluateAtCast: true, Range: N(p, "range", 16))],
                [Wizard + "WizardArcaneOverloadExecutor.java"], "発動時・費用消費前のMP残量から強化倍率を固定する。照準地点の単体のみ命中する。"),
            "wizard_self_heal" => Ready(id, p, ["healPercent"],
                [new("heal", Value: N(p, "healPercent", 25) / 100, Basis: "maxHealth", Target: "player")],
                [Wizard + "WizardSelfHealExecutor.java"], "術者の最大HPに対する割合を回復する。回復追撃bonusは適用しない。"),
            "wizard_mana_veil" => Ready(id, p, ["durationTicks", "incomingMultiplier", "outgoingMultiplier", "manaRegenBonus"],
                [new("incoming", Value: N(p, "incomingMultiplier", .8), DurationTicks: I(p, "durationTicks", 100)),
                    new("outgoing", Value: N(p, "outgoingMultiplier", .8), DurationTicks: I(p, "durationTicks", 100)),
                    new("stat", Value: N(p, "manaRegenBonus", 40), DurationTicks: I(p, "durationTicks", 100), Status: "MP_REGEN", Mode: "FLAT")],
                [Wizard + "WizardManaVeilExecutor.java"], "術者自身の100tick効果。被ダメージと与ダメージの双方を減らし、MP自然回復を増す。"),
            "wizard_cinder_lance" => Ready(id, p, KnownParameters(id),
                [new("damage", Tick: 1, Value: N(p, "damageRatio", 2.2), Element: "FIRE",
                    Range: N(p, "range", 16), HitRadius: N(p, "hitRadius", .6)),
                    new("condition", Tick: 1, Status: "BURNING", Chance: N(p, "conditionChance", 70),
                        DurationTicks: I(p, "conditionTicks", 140), Target: "enemy", Trigger: "landed",
                        Range: N(p, "range", 16), HitRadius: N(p, "hitRadius", .6))],
                [Wizard + "WizardCinderLanceExecutor.java"], "費用決済の次tickに視線上の単体へ命中する。燃焼の継続効果を反映する。"),
            "wizard_storm_chain" => Ready(id, p, KnownParameters(id),
                [new("damage", Tick: 1, Value: N(p, "damageRatio", 2), Element: "LIGHTNING",
                    Range: N(p, "range", 16), HitRadius: N(p, "hitRadius", .5)),
                    new("condition", Tick: 1, Status: "SHOCKED", Chance: N(p, "conditionChance", 70),
                        DurationTicks: I(p, "conditionTicks", 120), Target: "enemy", Trigger: "landed",
                        Range: N(p, "range", 16), HitRadius: N(p, "hitRadius", .5))],
                [Wizard + "WizardStormChainExecutor.java"], "費用決済の次tickに視線上の単体へ命中する。周囲に他の敵がいないため連鎖しない。感電を反映する。"),
            "wizard_triad_convergence" => Ready(id, p, KnownParameters(id),
                [new("damage", Components: [new("FIRE", N(p, "damageRatio", 1.2)),
                        new("ICE", N(p, "damageRatio", 1.2)), new("LIGHTNING", N(p, "damageRatio", 1.2))],
                    Evaluate: view => 1 + N(p, "conditionBonusRatio", .25) *
                        new[] { "BURNING", "CHILLED", "SHOCKED" }.Count(view.HasCondition), Range: N(p, "range", 16))],
                [Wizard + "WizardTriadConvergenceExecutor.java"], "照準地点の単体へ火・氷・雷の3成分を一撃で適用する。対象の3種状態異常を命中直前に数える。"),
            "archmage_astral_spear" => Ready(id, p, KnownParameters(id),
                [new("damage", Evaluate: view => N(p, "power", 2) * (1 + .3 * view.CircleCount), EvaluateAtCast: true,
                    Range: N(p, "range", 18))],
                [Archmage + "ArchmageAstralSpearExecutor.java", Archmage + "ArchmageStrikeSkillExecutor.java"],
                "射程内、遮蔽なしの単体へ光槍が命中する。発動時の現存陣数を最大3として計算する。"),
            "archmage_starfall" => Ready(id, p, KnownParameters(id),
                [new("damage", Evaluate: view => N(p, "power", 1.4) * (1 + .2 * view.CircleCount), EvaluateAtCast: true,
                    Range: N(p, "range", 16))],
                [Archmage + "ArchmageStarfallExecutor.java", Archmage + "ArchmageStrikeSkillExecutor.java"],
                "照準地点の単体へ命中する。発動時の現存陣数を最大3として計算する。"),
            "archmage_orbital_volley" => OrbitalVolley(id, p),
            "archmage_aegis_circle" => Field(id, p, "ArchmageAegisCircleExecutor.java", "resource", "SHIELD", "maxShield"),
            "archmage_meteor_circle" => Field(id, p, "ArchmageMeteorCircleExecutor.java", "damage"),
            "archmage_renewal_circle" => Field(id, p, "ArchmageRenewalCircleExecutor.java", "heal", basis: "maxHealth"),
            "archmage_vigor_circle" => Field(id, p, "ArchmageVigorCircleExecutor.java", "resource", "ENG", "maxEnergy"),
            _ => Unsupported(id)
        };
    }

    private static BalanceSkillProfile Ready(string id, JsonObject p, string[] keys,
        BalanceSkillEffect[] effects, string[] sources, params string[] assumptions)
    {
        ValidateParameters(id, p, keys);
        return new(id, effects, References(id, sources, effects), keys, assumptions, []);
    }

    private static BalanceSkillProfile Field(string id, JsonObject p, string executor, string kind,
        string status = "", string basis = "flat")
    {
        ValidateParameters(id, p, KnownParameters(id));
        var duration = I(p, "durationTicks", 160);
        var interval = I(p, "pulseTicks", 20);
        if (!p.ContainsKey("durationTicks") || !p.ContainsKey("pulseTicks")
            || duration < 10 || duration % 10 != 0 || interval < 10 || interval % 10 != 0)
            throw new ArgumentException($"{id} の陣時間は10tick単位で指定してください。");
        if (duration > 72_000 || ((long)duration + interval - 1) / interval > 1_999)
            throw new ArgumentException($"{id} の陣効果は計算モデルの上限を超えています。");
        var effects = new List<BalanceSkillEffect> { new("circle", Status: id, DurationTicks: duration,
            Range: N(p, "range", 16)) };
        // Plugin repeat(delay=1, period=10): 最初の pulse は1tick、以後は pulseTicks 間隔。
        for (var age = 0; age < duration; age += interval)
        {
            effects.Add(new(kind, Tick: age + 1, Value: N(p, "power", 1), Target: kind == "damage" ? "enemy" : "player",
                Status: status, Basis: basis, Scope: id, Range: N(p, "range", 16)));
        }
        return new(id, effects,
            References(id, [Archmage + executor, Archmage + "ArchmageFieldSkillExecutor.java"], effects), KnownParameters(id),
            [kind == "damage"
                ? "射程内の単体の敵を陣の中心に置き、効果時間中そこに留まる。地形遮蔽はなく、同種の旧陣は再設置時に終了する。"
                : "術者を陣の中心に置き、効果時間中そこに留まる。味方は存在せず、同種の旧陣は再設置時に終了する。"], []);
    }

    private static BalanceSkillProfile OrbitalVolley(string id, JsonObject p)
    {
        ValidateParameters(id, p, KnownParameters(id));
        var effects = Enumerable.Range(0, 5).Select(index =>
            new BalanceSkillEffect("damage", Tick: index * 3, Value: N(p, "power", .75),
                When: view => index < 2 + view.CircleCount, EvaluateAtCast: true,
                Range: N(p, "range", 18))).ToArray();
        return new(id, effects,
            References(id, [Archmage + "ArchmageOrbitalVolleyExecutor.java", Archmage + "ArchmageStrikeSkillExecutor.java"], effects),
            KnownParameters(id),
            ["照準地点の単体へ全星弾を3tick間隔で当てる。発動時の現存陣数で弾数2～5を固定する。"], []);
    }

    private static BalanceSkillProfile? Unsupported(string id) => id switch
    {
        "mage_arcane_flow" => Block(id, Executor + "MageArcaneFlowSkillExecutor.java", "異なる魔法の連鎖に伴う詠唱時間短縮が必要です。"),
        "mage_blink" => Block(id, Executor + "passive/mage/MageBlinkSkillExecutor.java", "空中シフト発動の移動と着地点判定は固定距離モデルで再現できません。"),
        "mage_frost_blizzard" => Block(id, Mage + "MageFrostBlizzardExecutor.java", "移動する竜巻の接触判定と10tick周期の継続命中が必要です。"),
        "mage_sparking" => Block(id, Mage + "MageSparkingExecutor.java", "5発の旋回弾の軌道・命中と感電付与を再現する必要があります。"),
        "wizard_burn_meteor_strike" => Block(id, Executor + "WizardBurnMeteorStrikeSkillExecutor.java", "SKILL由来BURNING付与成功時の追加MP消費と隕石追撃が必要です。"),
        "wizard_elemental_prism" => Block(id, Wizard + "WizardElementalPrismExecutor.java", "設置プリズムが他の魔法を吸収して発射するruntime状態が必要です。"),
        "wizard_emulate_spark" => Block(id, Wizard + "WizardEmulateSparkExecutor.java", "跳弾軌道、同一敵への最大4hit、感電付与が必要です。"),
        "wizard_lightning_strike" => Block(id, Executor + "WizardLightningStrikeSkillExecutor.java", "SKILL由来SHOCKED付与成功時の追加MP消費と落雷追撃が必要です。"),
        "wizard_meteor" => Block(id, Wizard + "WizardMeteorExecutor.java", "60tick後の着弾、広がる波、燃焼付与を含む距離依存命中が必要です。"),
        "wizard_prism_condition" => Block(id, Executor + "WizardPrismConditionSkillExecutor.java", "状態異常付与による魔法陣生成と接触MP回復が必要です。"),
        "wizard_rime_nova" => Block(id, Wizard + "WizardRimeNovaExecutor.java", "2tick刻みの氷波とFROZEN・CHILLED、BURNING増幅が必要です。"),
        "wizard_spectral_orrery" => Block(id, Executor + "WizardSpectralOrreryExecutor.java", "200tick内の火氷雷3属性履歴と3成分の追撃が必要です。"),
        "archmage_astral_ray" => Circle(id, "ArchmageAstralRayExecutor.java", "現存陣数に応じた円と10tick後のランダム対象光線"),
        "archmage_bind_circle" => Circle(id, "ArchmageBindCircleExecutor.java", "引き寄せと拘束"),
        "archmage_celestial_circle" => Circle(id, "ArchmageCelestialCircleExecutor.java", "陣内のプレイヤーbuff"),
        "archmage_clear_circle" => Circle(id, "ArchmageClearCircleExecutor.java", "接触した味方の状態異常浄化"),
        "archmage_gravity_nova" => Circle(id, "ArchmageGravityNovaExecutor.java", "現存陣数に応じた範囲と押し戻し"),
        "archmage_heal_circle" => Circle(id, "ArchmageHealCircleExecutor.java", "接触した味方の回復"),
        "archmage_phoenix_resonance" => Block(id, Executor + "ArchmagePhoenixResonanceExecutor.java", "不死鳥召喚の追撃と消滅を再現する必要があります。"),
        "archmage_reborn_protect_circle" => Circle(id, "ArchmageRebornProtectCircleExecutor.java", "死亡回復と混乱buff"),
        "archmage_repulsion_circle" => Circle(id, "ArchmageRepulsionCircleExecutor.java", "敵の押し戻しpulse"),
        "archmage_stasis_circle" => Circle(id, "ArchmageStasisCircleExecutor.java", "移動速度低下pulse"),
        _ => null
    };

    private static BalanceSkillProfile Circle(string id, string executor, string effect)
        => Block(id, Archmage + executor, $"{effect}には現存陣の登録・終了と位置条件を再現する必要があります。");

    private static BalanceSkillProfile Block(string id, string source, string reason)
        => new(id, [], [source], KnownParameters(id), [], [reason]);

    private static string[] References(string id, IEnumerable<string> direct, IEnumerable<BalanceSkillEffect> effects)
    {
        var shared = new List<string> { Executor + "active/support/PlayerActiveSkillExecutor.java" };
        if (effects.Any() && id.StartsWith("wizard_", StringComparison.Ordinal) && id != "wizard_self_heal")
            shared.Add(Wizard + "WizardSkillSupport.java");
        if (id.StartsWith("archmage_", StringComparison.Ordinal))
        {
            shared.Add(Archmage + "ArchmageBuildSupport.java");
            shared.Add(Archmage + "ArchmageConstellationEffects.java");
        }
        if (effects.Any(effect => effect.FlightSpeed > 0))
            shared.Add(Skill + "active/model/SkillProjectileSpec.java");
        return direct.Concat(shared).Distinct(StringComparer.Ordinal).ToArray();
    }

    private static void ValidateGeometry(string id, JsonObject p, IEnumerable<string> known)
    {
        foreach (var key in new[] { "range", "radius", "hitRadius", "projectileHitRadius", "projectileSpeed", "placementRange", "height" })
            if (known.Contains(key, StringComparer.Ordinal) && p.ContainsKey(key) && N(p, key, 0) <= 0)
                throw new ArgumentException($"{id} の params.{key} は正数で指定してください。");
        if (known.Contains("maxTargets", StringComparer.Ordinal) && p.ContainsKey("maxTargets") && I(p, "maxTargets", 0) < 1)
            throw new ArgumentException($"{id} の params.maxTargets は1以上の整数で指定してください。");
    }

    private static void ValidateParameters(string id, JsonObject p, string[] known)
    {
        ValidateGeometry(id, p, known);
        if (id.StartsWith("archmage_", StringComparison.Ordinal))
        {
            // ArchmageStrikeSkillExecutor / ArchmageFieldSkillExecutor.validateParams.
            foreach (var key in new[] { "range", "radius", "power" }) RequirePositive(id, p, key);
            return;
        }
        if (id is "mage_fireball" or "mage_frost_ball" or "mage_heal_aura" or "wizard_elemental_ball")
        {
            // These four executors validate their own parameters instead of WizardSkillSupport.
            foreach (var key in known.Where(key => key is not ("maxTargets" or "freezeChance" or "freezeDurationTicks")))
                RequirePositive(id, p, key);
            if (known.Contains("maxTargets", StringComparer.Ordinal)) RequirePositiveInteger(id, p, "maxTargets");
            if (id == "mage_frost_ball")
            {
                RequirePositiveInteger(id, p, "freezeDurationTicks");
                var chance = RequiredNumber(id, p, "freezeChance");
                if (chance is < 0 or > 100) throw new ArgumentException($"{id} の params.freezeChance は0～100で指定してください。");
            }
            return;
        }
        if (id == "wizard_self_heal")
        {
            var percent = RequiredNumber(id, p, "healPercent");
            if (percent is <= 0 or > 100) throw new ArgumentException($"{id} の params.healPercent は0超～100で指定してください。");
            return;
        }
        // WizardSkillSupport.validateParams: all constructor-required keys are mandatory,
        // positive, bounded, and ticks/target counts must be integral.
        foreach (var key in known.Where(key => key != "manaRegenBonus"))
        {
            var value = RequiredNumber(id, p, key);
            var limit = key.EndsWith("Ticks", StringComparison.Ordinal) ? 400
                : key == "maxTargets" ? 8
                : key == "range" ? 24
                : key.EndsWith("Radius", StringComparison.Ordinal) || key == "radius" ? 6
                : key == "conditionChance" ? 100
                : key == "manaRecovery" ? 50
                : key is "manaThreshold" or "incomingMultiplier" ? 1 : 20;
            if (value <= 0 || value > limit || ((key.EndsWith("Ticks", StringComparison.Ordinal) || key == "maxTargets") && value != Math.Truncate(value)))
                throw new ArgumentException($"{id} の params.{key} は正数かつ上限{limit}以内で指定してください。");
        }
        if (id == "wizard_mana_veil")
        {
            var bonus = RequiredNumber(id, p, "manaRegenBonus");
            if (bonus is <= 0 or > 100) throw new ArgumentException($"{id} の params.manaRegenBonus は0超～100で指定してください。");
            if (N(p, "outgoingMultiplier", 0) > 1) throw new ArgumentException($"{id} の params.outgoingMultiplier は1以下で指定してください。");
        }
    }

    private static double RequiredNumber(string id, JsonObject p, string key)
    {
        if (!p.ContainsKey(key)) throw new ArgumentException($"{id} の params.{key} は必須です。");
        return N(p, key, 0);
    }

    private static void RequirePositive(string id, JsonObject p, string key)
    {
        if (RequiredNumber(id, p, key) <= 0) throw new ArgumentException($"{id} の params.{key} は正数で指定してください。");
    }

    private static void RequirePositiveInteger(string id, JsonObject p, string key)
    {
        if (!p.ContainsKey(key) || I(p, key, 0) < 1)
            throw new ArgumentException($"{id} の params.{key} は1以上の整数で指定してください。");
    }

    private static string[] KnownParameters(string id) => id switch
    {
        "mage_arcane_flow" => ["castTimeReductionPercent"],
        "mage_blink" => ["maxDistance"],
        "mage_frost_ball" => ["range", "radius", "damageRatio", "maxTargets", "projectileSpeed", "projectileHitRadius", "freezeChance", "freezeDurationTicks"],
        "mage_frost_blizzard" => ["damageRatio", "radius", "height", "movementSpeed", "durationTicks", "damageIntervalTicks", "maxTargets", "orbitVelocity", "inwardVelocity", "verticalVelocity"],
        "mage_sparking" => ["damageRatio", "projectileCount", "range", "projectileSpeed", "spiralRadiusGrowth", "spiralDegreesPerTick", "projectileHitRadius", "durationTicks", "shockChance", "shockDurationTicks"],
        "wizard_burn_meteor_strike" => [],
        "wizard_cinder_lance" => ["range", "hitRadius", "maxTargets", "damageRatio", "conditionChance", "conditionTicks"],
        "wizard_elemental_prism" => ["placementRange", "radius", "damageRatio", "maxTargets", "projectileCount", "durationTicks", "projectileSpeed"],
        "wizard_emulate_spark" => ["damageRatio", "projectileCount", "maxHitsPerTarget", "projectileSpeedPerSecond", "projectileHitRadius", "bounceHeight", "spreadAngle", "durationTicks", "shockChance", "shockDurationTicks"],
        "wizard_lightning_strike" => ["range", "damageRatio"],
        "wizard_meteor" => ["range", "radius", "damageRatio", "burningChance", "burningDurationTicks", "impactDelayTicks"],
        "wizard_prism_condition" => ["radius", "durationTicks", "manaRecoveryRatio"],
        "wizard_rime_nova" => ["radius", "maxTargets", "damageRatio", "burningMultiplier", "conditionChance", "conditionTicks", "frozenTicks"],
        "wizard_spectral_orrery" => ["damageRatio", "radius", "range", "maxTargets", "chainWindowTicks"],
        "wizard_storm_chain" => ["range", "hitRadius", "maxTargets", "chainRadius", "damageRatio", "conditionChance", "conditionTicks"],
        "wizard_triad_convergence" => ["range", "radius", "maxTargets", "damageRatio", "conditionBonusRatio"],
        "archmage_aegis_circle" or "archmage_meteor_circle" or "archmage_renewal_circle" or "archmage_repulsion_circle" or "archmage_stasis_circle" or "archmage_vigor_circle"
            => ["range", "radius", "power", "durationTicks", "pulseTicks"],
        "archmage_astral_ray" => ["range", "damageRatio", "circleCount", "circleIntervalTicks"],
        "archmage_astral_spear" or "archmage_gravity_nova" or "archmage_orbital_volley" or "archmage_starfall"
            => ["range", "radius", "power"],
        "archmage_bind_circle" => ["range", "radius", "pullTicks", "bindTicks", "circleLifetimeTicks"],
        "archmage_celestial_circle" => ["placementRange", "durationTicks", "effectLevel", "radius", "finalDamageBonus", "otherBonus"],
        "archmage_clear_circle" or "archmage_heal_circle" or "archmage_reborn_protect_circle" =>
            id == "archmage_heal_circle" ? ["range", "radius", "healPercent", "durationTicks"] : ["range", "radius", "durationTicks"],
        "archmage_phoenix_resonance" => ["phoenixMax", "targetRange", "despawnDelayTicks", "burningChance"],
        _ => []
    };

    private static double N(JsonObject p, string key, double fallback) => BalanceSkillParameters.Number(p, key, fallback);
    private static int I(JsonObject p, string key, int fallback) => BalanceSkillParameters.Integer(p, key, fallback);
}
