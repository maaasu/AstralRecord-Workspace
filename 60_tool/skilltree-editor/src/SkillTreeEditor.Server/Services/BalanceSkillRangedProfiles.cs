using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Services;

// These profiles follow the executor's first projectile collision with one stationary target.
// Every implementation ID is explicit: a master alias may change id without changing behavior.
internal static class BalanceSkillRangedProfiles
{
    private const string SkillMaster = "40_filebase/30.features.skill/v1.";
    private const string ExecutorRoot = "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/executor/active/";
    private const string SingleTarget = "単体・固定距離・遮蔽物なし。射線上の最初のMobへ実executorの飛翔速度と当たり半径で命中する。";
    private const string NoInheritance = "継承の心得を未習得・継承バフなしとする。";

    internal static BalanceSkillProfile? Resolve(JsonObject definition)
    {
        string? id = definition["implementationId"]?.GetValue<string>();
        if (string.IsNullOrWhiteSpace(id)) return null;
        JsonObject p = BalanceSkillParameters.Params(definition);
        return id switch
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
            "hunter_fade_shot" => Unsupported(id, "HunterFadeShotExecutor.java", "扇状の複数矢・拡散角と後方移動により、固定射線の単体衝突へ縮約できない。"),
            "hunter_arrow_rain" => Unsupported(id, "HunterArrowRainExecutor.java", "初弾とランダム配置の雨矢・重力・範囲内複数命中があり、固定射線でhit列を確定できない。"),
            "hunter_crash_arrow" => Unsupported(id, "HunterCrashArrowExecutor.java", "シールド破壊倍率、超星クリティカル抽選と継承追撃の処理が必要。"),
            "hunter_heal_arrow" => Unsupported(id, "HunterHealArrowExecutor.java", "着弾後の範囲回復・地面着弾・敵への直撃の分岐が必要。"),
            "hunter_spell_step" => Unsupported(id, "HunterSpellStepSkillExecutor.java", "回避入力後の時間窓で発動するパッシブで、単体スキル発動ではない。", directExecutor: true),
            "archer_air_shift" => Unsupported(id, "ArcherAirShiftSkillExecutor.java", "空中回避操作による移動パッシブで、固定距離モデルに移動がない。", directExecutor: true),
            "archer_heal_arrow_alpha" => Unsupported(id, "ArcherHealArrowAlphaExecutor.java", "詠唱時のHEALING_INCREASE buffと着弾後の範囲回復・直撃の分岐が必要。"),
            "archer_starweave_followup" => Unsupported(id, "RangedStarweaveFollowupExecutor.java", "通常弓攻撃の有効命中後に発動するパッシブ追撃で、独立castではない。", directExecutor: true),
            "sharpshooter_glacial_trap" => Unsupported(id, "SharpshooterGlacialTrapExecutor.java", "地面への着弾位置から遅延範囲攻撃・凍結するため、単体射線衝突と異なる。"),
            "sharpshooter_inheritance_mastery" => Unsupported(id, "SharpshooterInheritanceMasterySkillExecutor.java", "12種の継承バフ・通常攻撃追撃・再消費を管理するパッシブで、単独castではない。", directExecutor: true),
            "phantom_archer_shadow_stitch" => Unsupported(id, "PhantomArcherSkillExecutor.java", "有効命中で霊印を蓄積し、以後の倍率・消費へ波及する専用stateが必要。"),
            "phantom_archer_soul_pierce" => Unsupported(id, "PhantomArcherSkillExecutor.java", "霊印数に応じた倍率、状態異常特効、貫通後の霊印消費が必要。"),
            "phantom_archer_dread_bloom" => Unsupported(id, "PhantomArcherSkillExecutor.java", "直撃に加えて着弾点の範囲判定、盲目抽選、霊印蓄積が必要。"),
            "phantom_archer_spectral_sentry" => Unsupported(id, "PhantomArcherSkillExecutor.java", "設置型の再照準・複数射撃・霊印蓄積と召喚寿命の専用stateが必要。"),
            "phantom_archer_echo_volley" => Unsupported(id, "PhantomArcherSkillExecutor.java", "連射時の再照準・貫通・各有効命中での霊印蓄積が必要。"),
            "phantom_archer_mist_retreat" => Unsupported(id, "PhantomArcherSkillExecutor.java", "後退移動・回避補正・次の射撃を強める待機状態が必要。"),
            "phantom_archer_eclipse_shot" => Unsupported(id, "PhantomArcherSkillExecutor.java", "チャージ後の射撃、貫通、霊印数依存倍率と霊印消費が必要。"),
            "phantom_archer_curse_weaving" => Unsupported(id, "PhantomArcherPassiveExecutor.java", "霊印別の常時倍率と状態異常付与率・持続補正を持つパッシブで、単独castではない。"),
            "phantom_archer_spectral_attunement" => Unsupported(id, "PhantomArcherPassiveExecutor.java", "通常弓攻撃の有効命中で霊印を蓄積し、常時CT・MP補正を持つパッシブである。"),
            "phantom_archer_wraith_step" => Unsupported(id, "PhantomArcherPassiveExecutor.java", "回避後の待機状態と次の専用矢への独立追加hitを持つパッシブである。"),
            _ => null
        };
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
        if (recovery > 0) reasons.Add("連続有効命中の実時間窓によるMP/ENG回復は現在のeffect planで表せない。");
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
        if (pellets != 1) reasons.Add("Lv2以降の3/5散弾は複数射線・爆発の着弾位置と重複命中が必要。");
        var profile = Profile(id,
            [new("damage", AttackType: "RANGED", Element: "NONE",
                Evaluate: view => ratio * (view.Conditions.Count > 0 ? 2 : 1),
                FlightSpeed: N(p, "projectileSpeed", 1.6), HitRadius: N(p, "projectileHitRadius", .6),
                Range: N(p, "range", 24), FlightOriginOffset: .8)],
            ["SharpshooterSpreadingAmbitionExecutor.java"],
            ["inheritanceMasteryName", "inheritanceBuffName", "inheritanceDurationTicks", "inheritanceConsumptionTicks",
             "range", "damageRatio", "explosionDamageRatio", "explosionRadius", "maxExplosionTargets", "pelletCount",
             "spreadAngle", "projectileSpeed", "projectileHitRadius"],
            [SingleTarget, NoInheritance, "Lv1の中央1矢のみ。発射起点は視点の前方0.8ブロック。",
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
        return new(id, effects, sources, keys, assumptions, reasons ?? []);
    }

    private static BalanceSkillProfile Unsupported(string id, string executor, string reason, bool directExecutor = false)
    {
        string folder = id.StartsWith("hunter_") || id.StartsWith("archer_") ? "hunter/"
            : id.StartsWith("phantom_archer_") ? "phantomarcher/" : "sharpshooter/";
        string path = directExecutor
            ? "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/executor/" + executor
            : ExecutorRoot + folder + executor;
        return new(id, [], [SkillMaster + id + ".yml", path], UnsupportedKnownParameters(id), [], [reason]);
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
