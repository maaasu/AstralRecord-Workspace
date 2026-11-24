package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.DamageCalculator;
import io.github.maaasu.astralRecord.feature.combat.service.CombatTimingCalculator;
import io.github.maaasu.astralRecord.feature.skill.gui.SkillLore;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;

import io.github.maaasu.astralRecord.feature.skill.executor.*;
import io.github.maaasu.astralRecord.feature.skill.executor.active.adventurer.*;
import io.github.maaasu.astralRecord.feature.skill.executor.active.archmage.*;
import io.github.maaasu.astralRecord.feature.skill.executor.active.hunter.*;
import io.github.maaasu.astralRecord.feature.skill.executor.active.mage.*;
import io.github.maaasu.astralRecord.feature.skill.executor.active.paladin.*;
import io.github.maaasu.astralRecord.feature.skill.executor.active.sharpshooter.*;
import io.github.maaasu.astralRecord.feature.skill.executor.active.swordmaster.*;
import io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman.*;
import io.github.maaasu.astralRecord.feature.skill.executor.active.wizard.*;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import static io.github.maaasu.astralRecord.feature.combat.model.AttackType.*;
import static io.github.maaasu.astralRecord.feature.combat.model.DamageElement.*;

/** 習得個体の戦闘性能を状態の変更や抽選なしで表示します。 */
public final class SkillDamagePreview {
    private static final List<Component> TARGET = List.of(
            note("攻撃範囲内の単体Mob"),
            note("防御・耐性・回避・盾は0"),
            note("装備の実効範囲中央・平均会心率で算出")
    );
    private static final List<DamageComponent> TRIAD = List.of(
            new DamageComponent(FIRE, 1), new DamageComponent(ICE, 1), new DamageComponent(LIGHTNING, 1));

    private SkillDamagePreview() {}

    /**
     * 性能の要約、個別の計算値、計算の前提をそれぞれ見出しなしで保持します。
     *
     * @param summary 性能欄へ追加する結果
     * @param details 能力値・周期などの詳細
     * @param conditions 対象・適用条件・計算から外れる効果
     */
    public record Report(List<Component> summary, List<Component> details, List<Component> conditions) {
        /** 呼出元から渡された行のリストを固定します。 */
        public Report {
            summary = List.copyOf(summary);
            details = List.copyOf(details);
            conditions = List.copyOf(conditions);
        }

        /** 表示できる性能項目がない場合の空レポートを返します。 */
        public static Report empty() {
            return new Report(List.of(), List.of(), List.of());
        }

        /** 現在の状態や定義では性能を表示できない理由を返します。 */
        public static Report unavailable(String reason) {
            return new Report(List.of(Component.text(reason, NamedTextColor.RED)), List.of(), List.of());
        }
    }

    /** 計算した数値を単位付きの項目として表示します。 */
    private static Component row(String label, double value, String unit) {
        return SkillLore.row(label, SkillLore.value(number(value) + unit));
    }

    /** 個別の計算条件・補足を1行で表示します。 */
    private static Component note(String condition) {
        return SkillLore.note(condition);
    }

    /** 表示用に小数第2位まで丸めます。 */
    public static String number(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /**
     * 現ビルドの中央値と習得個体の定義から性能を計算します。
     *
     * @param stats 実効ステータス
     * @param skill 個体補正を反映したスキル定義
     * @param cooldown 実効クールダウン tick
     * @param cast 実効詠唱 tick
     * @param circles 現在の魔法陣の数
     * @return 性能の要約とその詳細・算出条件
     */
    public static Report describe(StatusSnapshot stats, SkillDefinition skill, long cooldown, long cast, int circles) {
        var params = new SkillParamReader(skill.getId(), skill.getParams());
        try {
            String id = skill.getImplementationId();
            var attack = attack(id, params, stats, circles);
            if (attack == null) {
                return supportOrCondition(stats, skill, params, cooldown, cast);
            }
            if (!attack.valid()) return Report.unavailable("期待DPS算出不可（倍率・回数・間隔の定義が無効）");
            double star = skill.getImplementationId().equals(HunterCrashArrowExecutor.ID) ? params.getDouble("superStarCriticalChance", 50)
                    : stats.getMaxValue(StatusType.SUPER_CRITICAL_RATE);
            double total = attack.expectedHit(stats, attack.firstRatio, star);
            double followup = attack.count > 1 ? attack.expectedHit(stats, attack.repeatRatio, star) : 0;
            if (skill.getImplementationId().equals(SwordmasterCrescentDuetExecutor.ID)
                    || skill.getImplementationId().equals(SwordmasterStarCleaveExecutor.ID)) {
                double hit = DamageCalculator.calculateHitChance(stats.getMaxValue(StatusType.ACCURACY), 0) / 100;
                double normal = DamageCalculator.criticalChance(stats, DamageSource.SKILL);
                double firstCritical = hit * (1 - (1 - normal) * (1 - Math.clamp(star / 100, 0, 1)));
                if (total <= 0) firstCritical = 0;
                if (skill.getImplementationId().equals(SwordmasterStarCleaveExecutor.ID)) {
                    followup *= firstCritical;
                } else {
                    double boosted = attack.expectedHit(stats, attack.repeatRatio * params.getDouble("criticalMultiplier", 1), star);
                    followup += firstCritical * (boosted - followup);
                }
            }
            total += (attack.count - 1.0) * followup;
            var summary = new ArrayList<Component>();
            var details = new ArrayList<>(attackDetails(stats, attack.type, star));
            if (attack.count > 1) {
                details.add(row("攻撃回数", attack.count, "回"));
                details.add(row("次の攻撃まで", attack.interval / 20.0, "秒"));
            }
            var conditions = new ArrayList<>(TARGET);
            summary.add(row("1回の期待ダメージ", total, ""));
            appendDps(summary, details, "直接期待DPS", total, cast, cooldown, attack);
            appendScenario(id, skill, params, stats, circles, details, conditions);
            conditions.add(note("無傷・状態異常なしの敵で算出"));
            conditions.add(note("飛翔・継承・剣気は計算外"));
            conditions.add(note("誘発追撃・持続ダメージは計算外"));
            return new Report(summary, details, conditions);
        } catch (SkillParameterException invalid) {
            return Report.unavailable("期待DPS算出不可（個体の攻撃パラメーターが無効）");
        }
    }

    /** 特定の攻撃で変わる算出条件と実効CTを追加します。 */
    private static void appendScenario(String id, SkillDefinition skill, SkillParamReader params,
                                       StatusSnapshot stats, int circles, List<Component> details,
                                       List<Component> conditions) {
        switch (id) {
            case SwordmasterGaleReaperExecutor.ID, SwordmasterCrimsonDriveExecutor.ID -> {
                conditions.add(note("通過した同一対象へ1回命中"));
                conditions.add(note("撃破時のCTリセットは計算外"));
            }
            case SwordmasterMirageRetreatExecutor.ID -> conditions.add(note("全段で対象が範囲内"));
            case SwordsmanExeptStampExecutor.ID -> {
                conditions.add(note("着地時に対象が範囲内"));
                conditions.add(note("飛行・着地待ち時間は計算外"));
            }
            case PaladinHolySmashExecutor.ID -> {
                conditions.add(note("直接攻撃の期待値は聖域外"));
                details.add(row("聖域内で命中時のCT", CombatTimingCalculator.resolveCooldownTicks(
                        params.getInt("reducedCooldownTicks", 0), stats.getMaxValue(StatusType.COOLDOWN_REDUCTION)) / 20.0, "秒"));
            }
            case SharpshooterPhantomShotExecutor.ID -> {
                conditions.add(note("直接攻撃の期待値は継承なし"));
                details.add(row("継承中のCT", CombatTimingCalculator.resolveCooldownTicks(
                        skill.getCooldownTicks() * 2, stats.getMaxValue(StatusType.COOLDOWN_REDUCTION)) / 20.0, "秒"));
            }
            case AdventurerLightningBoltExecutor.ID -> conditions.add(note("主対象のみ集計。感電連鎖は計算外"));
            case AdventurerAstralEdgeExecutor.ID -> conditions.add(note("薙ぎ払いに続く突き刺しまで集計"));
            case WizardArcaneOverloadExecutor.ID -> conditions.add(note("発動前の現在MPで強化を判定"));
            case ArchmageAstralSpearExecutor.ID, ArchmageStarfallExecutor.ID, ArchmageOrbitalVolleyExecutor.ID,
                    ArchmageAstralRayExecutor.ID -> details.add(row("現在の魔法陣", circles, "基"));
            default -> { }
        }
    }

    /** 固定した発動周期と最終打撃を待つ条件でDPSを表示します。 */
    private static void appendDps(List<Component> summary, List<Component> details,
                                  String label, double total, long cast, long cooldown, Attack attack) {
        long cycle = cast + Math.max(cooldown, attack.delay + (attack.count - 1L) * attack.interval);
        if (cycle > 0) {
            summary.add(row(label, total * 20.0 / cycle, ""));
            details.add(row("算出周期", cycle / 20.0, "秒"));
        }
    }

    /**
     * 実行側で確定した次の武器通常攻撃を表示します。
     *
     * @param stats 装備の実効範囲中央のステータス
     * @param type 装備の攻撃種別
     * @param hits 1回の操作で発生する打撃数
     * @param cycle 装備・劣化込みの実効周期 tick
     * @param expectedPerHit 1打撃の期待ダメージ
     * @return 武器通常攻撃の性能レポート
     */
    public static Report normalAttack(StatusSnapshot stats, AttackType type, int hits, long cycle, double expectedPerHit) {
        double total = expectedPerHit * hits;
        var details = new ArrayList<>(attackDetails(stats, type, stats.getMaxValue(StatusType.SUPER_CRITICAL_RATE),
                DamageSource.NORMAL_ATTACK));
        details.add(row("次の通常攻撃", hits, "撃"));
        details.add(row("算出周期", cycle / 20.0, "秒"));
        var conditions = new ArrayList<>(TARGET);
        conditions.add(note("次回の劣化段階で固定"));
        conditions.add(note("反撃・継承弾・追尾弾は計算外"));
        Component dps = cycle > 0 ? row("直接期待DPS", total * 20 / cycle, "")
                : note("期待DPS算出不可（周期未定義）");
        return new Report(List.of(row("1回の期待ダメージ", total, ""), dps), details, conditions);
    }

    /** スキルの期待値に使用した能力値と確率を返します。 */
    private static List<Component> attackDetails(StatusSnapshot stats, AttackType type, double star) {
        return attackDetails(stats, type, star, DamageSource.SKILL);
    }

    /** 攻撃の期待値に使用した能力値と確率を1項目ずつ表示します。 */
    private static List<Component> attackDetails(StatusSnapshot stats, AttackType type, double star, DamageSource source) {
        String label = switch (type) { case MELEE -> "近接"; case RANGED -> "間接"; case MAGIC -> "魔法"; };
        return List.of(
                row(label + "攻撃力", DamageCalculator.calculateAttackPower(stats::getMaxValue, type), ""),
                row("命中率", DamageCalculator.calculateHitChance(stats.getMaxValue(StatusType.ACCURACY), 0), "%"),
                row("会心率", DamageCalculator.criticalChance(stats, source) * 100, "%"),
                row("会心倍率", DamageCalculator.normalCriticalMultiplier(stats) * 100, "%"),
                row("超星会心率", Math.clamp(star, 0, 100), "%"));
    }

    private record Attack(AttackType type, List<DamageComponent> elements, double firstRatio, double repeatRatio,
                          int count, int interval, int delay) {
        Attack(AttackType type, DamageElement element, double first, double next, int count, int interval, int delay) {
            this(type, List.of(new DamageComponent(element, 1)), first, next, count, interval, delay);
        }

        double expectedHit(StatusSnapshot stats, double ratio, double starRate) {
            var components = elements.stream().map(c -> new DamageComponent(c.element(), c.ratio() * ratio)).toList();
            return DamageCalculator.expectedDirectHit(stats, type, components, DamageSource.SKILL, starRate);
        }

        boolean valid() {
            return Double.isFinite(firstRatio) && firstRatio > 0 && Double.isFinite(repeatRatio)
                    && repeatRatio > 0 && count > 0 && interval >= 0 && delay >= 0;
        }
    }

    private static Attack single(AttackType type, DamageElement element, double ratio) {
        return new Attack(type, element, ratio, ratio, 1, 0, 0);
    }

    // implementationId は戦闘レジストリのキー。回復・補助にも攻撃種タグが付くためタグから推定しない。
    private static Attack attack(String id, SkillParamReader p, StatusSnapshot stats, int circles) {
        double ratio = p.getDouble("damageRatio", Double.NaN);
        return switch (id) {
            case AdventurerQuickShotExecutor.ID -> single(RANGED, NONE, AdventurerQuickShotExecutor.DAMAGE_RATIO);
            case AdventurerSmashExecutor.ID -> new Attack(MELEE, NONE, ratio, ratio, 1, 0, p.getInt("impactDelayTicks", 8));
            case SwordsmanShieldDrainExecutor.ID, SwordmasterHeartseekerExecutor.ID, SwordmasterVortexEdgeExecutor.ID,
                 SwordmasterRiposteExecutor.ID, SwordmasterRevengeAccelExecutor.ID, SwordmasterGaleReaperExecutor.ID, PaladinBlessedLanceExecutor.ID, PaladinHolySmashExecutor.ID, PaladinShieldImpactExecutor.ID
                    -> single(MELEE, NONE, ratio);
            case SwordmasterCrimsonDriveExecutor.ID -> single(MELEE, FIRE, ratio);
            case SwordmasterThunderLineExecutor.ID -> single(MELEE, LIGHTNING, ratio);
            case SwordmasterFrostBloomExecutor.ID -> single(MELEE, ICE, ratio);
            case SwordsmanExeptStampExecutor.ID -> single(MELEE, NONE, ratio);
            case HunterCrashArrowExecutor.ID, AdventurerBlastArrowExecutor.ID, HunterHealArrowExecutor.ID, ArcherHealArrowAlphaExecutor.ID,
                 SharpshooterRicochetArrowExecutor.ID, SharpshooterPiercingLineExecutor.ID,
                 "phantom_archer_shadow_stitch", "phantom_archer_soul_pierce", "phantom_archer_dread_bloom"
                    -> single(RANGED, NONE, ratio);
            case SharpshooterFireArrowExecutor.ID -> single(RANGED, FIRE, ratio);
            case SharpshooterIceArrowExecutor.ID -> single(RANGED, ICE, ratio);
            case SharpshooterGlacialTrapExecutor.ID -> new Attack(RANGED, ICE, ratio, ratio, 1, 0, 12);
            case AdventurerManaBurstExecutor.ID, WizardArcaneLanceExecutor.ID, WizardPrismaticDartExecutor.ID,
                 WizardManaSiphonExecutor.ID, WizardElementalBallExecutor.ID -> single(MAGIC, NONE, ratio);
            case AdventurerLightningBoltExecutor.ID, WizardStormChainExecutor.ID -> single(MAGIC, LIGHTNING, ratio);
            case WizardRimeNovaExecutor.ID, MageFrostBallExecutor.ID -> single(MAGIC, ICE, ratio);
            case MageFireballExecutor.ID, WizardCinderLanceExecutor.ID -> single(MAGIC, FIRE, ratio);
            case WizardMeteorExecutor.ID -> new Attack(MAGIC, FIRE, ratio, ratio, 1, 0, p.getInt("impactDelayTicks", 60));
            case "phantom_archer_eclipse_shot" -> new Attack(RANGED, NONE, ratio, ratio, 1, 0, p.getInt("chargeTicks", 16));
            case WizardArcaneOverloadExecutor.ID -> {
                double maxMp = stats.getMaxValue(StatusType.MAX_MANA);
                boolean empowered = maxMp > 0 && stats.getCurrentMp() / maxMp >= p.getDouble("manaThreshold", 1);
                yield single(MAGIC, NONE, ratio * (empowered ? p.getDouble("empoweredMultiplier", 1) : 1));
            }
            case WizardTriadConvergenceExecutor.ID, SwordmasterTrinityEdgeExecutor.ID -> new Attack(
                    id.equals(WizardTriadConvergenceExecutor.ID) ? MAGIC : MELEE,
                    TRIAD,
                    ratio, ratio, 1, 0, 0);
            case SharpshooterThermalBreakExecutor.ID -> new Attack(RANGED,
                    List.of(new DamageComponent(FIRE, 0.5), new DamageComponent(ICE, 0.5)), ratio, ratio, 1, 0, 0);
            case ArchmageAstralSpearExecutor.ID, ArchmageStarfallExecutor.ID, ArchmageGravityNovaExecutor.ID -> {
                double perCircle = switch (id) {
                    case ArchmageAstralSpearExecutor.ID -> 0.3;
                    case ArchmageStarfallExecutor.ID -> 0.2;
                    default -> 0;
                };
                yield single(MAGIC, NONE, p.getDouble("power", 1) * (1 + Math.min(3, circles) * perCircle));
            }
            case ArchmageOrbitalVolleyExecutor.ID -> {
                double power = p.getDouble("power", 1);
                yield new Attack(MAGIC, NONE, power, power, 2 + Math.min(3, circles), 3, 0);
            }
            case ArchmageAstralRayExecutor.ID -> new Attack(MAGIC, NONE, ratio, ratio, p.getInt("circleCount", 1) + circles * 2,
                    p.getInt("circleIntervalTicks", 5), 10);
            case SwordmasterCrescentDuetExecutor.ID -> new Attack(MELEE, NONE, ratio, ratio, 2, p.getInt("intervalTicks", 1), 1);
            case SwordmasterStarCleaveExecutor.ID -> new Attack(MELEE, NONE, ratio, p.getDouble("burstRatio", 0), 2, 1, 0);
            case AdventurerAstralEdgeExecutor.ID, SwordsmanFlameRushExecutor.ID -> {
                var ratios = p.getDoubleList("damageRatios", List.of());
                boolean flame = id.equals(SwordsmanFlameRushExecutor.ID);
                yield ratios.size() != 2 ? null : new Attack(MELEE, flame ? FIRE : NONE, ratios.get(0), ratios.get(1),
                        2, flame ? p.getInt("secondHitDelayTicks", 0) : 4, 0);
            }
            case SwordmasterBladeTempestExecutor.ID, SwordmasterFlameLotusExecutor.ID, SwordmasterMirageRetreatExecutor.ID ->
                    new Attack(MELEE, id.equals(SwordmasterFlameLotusExecutor.ID) ? FIRE : NONE, ratio, ratio,
                            p.getInt("hitCount", 0), p.getInt("intervalTicks", 0), 1);
            case SharpshooterPhantomShotExecutor.ID -> new Attack(RANGED, NONE, ratio, ratio, 3, p.getInt("shotIntervalTicks", 5), 0);
            case "phantom_archer_echo_volley", "phantom_archer_spectral_sentry" ->
                    new Attack(RANGED, NONE, ratio, ratio, p.getInt("shotCount", 0), p.getInt("shotIntervalTicks", 0), 1);
            case PaladinHolySmiteExecutor.ID, ArchmageMeteorCircleExecutor.ID, MageFrostBlizzardExecutor.ID -> {
                boolean ice = id.equals(MageFrostBlizzardExecutor.ID);
                boolean holy = id.equals(PaladinHolySmiteExecutor.ID);
                int interval = ice ? p.getInt("damageIntervalTicks", 10)
                        : holy ? p.getInt("impactIntervalTicks", 20) : p.getInt("pulseTicks", 20);
                int duration = p.getInt("durationTicks", ice ? 200 : holy ? 20 : 160);
                if (!holy && !ice) ratio = p.getDouble("power", 1);
                yield pulsing(holy ? MELEE : MAGIC, ice ? ICE : NONE, ratio, duration, interval);
            }
            default -> null;
        };
    }

    /** 最初のtickから寿命内に発生する周期攻撃を表します。 */
    private static Attack pulsing(AttackType type, DamageElement element, double ratio, int duration, int interval) {
        int count = interval > 0 && duration > 0 ? 1 + (duration - 1) / interval : 0;
        return new Attack(type, element, ratio, ratio, count, interval, 1);
    }

    /** 発動周期を決められない攻撃と、回復・支援スキルの性能を表示します。 */
    private static Report supportOrCondition(StatusSnapshot stats, SkillDefinition skill, SkillParamReader params,
                                             long cooldown, long cast) {
        return switch (skill.getImplementationId()) {
            case HunterFadeShotExecutor.ID -> {
                double hit = expected(stats, RANGED, NONE, params.getDouble("damageRatio", 0));
                int pellets = params.getInt("pelletCount", 0);
                yield partialAttack(stats, RANGED,
                        row("矢1本の期待ダメージ", hit, ""),
                        row("同じ敵に全" + pellets + "本命中時", hit * pellets, ""));
            }
            case HunterArrowRainExecutor.ID -> {
                var ratios = params.getDoubleList("damageRatios", List.of(0.84, 0.36));
                if (ratios.size() != 2) throw new SkillParameterException("damageRatios", "初矢と降矢の倍率が必要です");
                yield partialAttack(stats, RANGED,
                        row("初矢の期待ダメージ", expected(stats, RANGED, NONE, ratios.getFirst()), ""),
                        row("降矢1本の期待ダメージ", expected(stats, RANGED, NONE, ratios.get(1)), ""));
            }
            case SharpshooterSpreadingAmbitionExecutor.ID -> partialAttack(stats, RANGED,
                    row("直撃1本の期待ダメージ", expected(stats, RANGED, NONE, params.getDouble("damageRatio", 0)), ""),
                    row("爆風1回の期待ダメージ", expected(stats, RANGED, NONE, params.getDouble("explosionDamageRatio", 0)), ""));
            case PaladinShieldBashExecutor.ID -> perHit(stats, MELEE, NONE, params.getDouble("damageRatio", 0), "接触1回");
            case MageSparkingExecutor.ID, WizardEmulateSparkExecutor.ID ->
                    perHit(stats, MAGIC, LIGHTNING, params.getDouble("damageRatio", 0), "弾1発");
            case WizardElementalPrismExecutor.ID -> prism(stats, params);
            case PaladinShieldExecutor.ID -> paladinShield(stats, params, cooldown, cast);
            case WizardLightningStrikeSkillExecutor.ID -> perHit(stats, MAGIC, LIGHTNING, params.getDouble("damageRatio", 0), "落雷1回");
            case WizardBurnMeteorStrikeSkillExecutor.ID -> perHit(stats, MAGIC, FIRE, 1.5, "メテオ1回");
            case MeleeDualCrestExecutor.ID -> perHit(stats, MELEE, NONE, params.getDouble("damageRatio", 0), "追撃1回");
            case "swordmaster_seijaku_issen" -> perHit(stats, MELEE, NONE,
                    params.getDouble("counterDamageRatio", 0) * (1 + params.getDouble("counterDamageBonusPerStack", 0)),
                    "反撃1回（剣気1つ）");
            case SwordsmanBastionStrikeExecutor.ID -> perHit(stats, MELEE, NONE, params.getDouble("damageRatio", 0), "反撃1回");
            case RangedStarweaveFollowupExecutor.ID -> perHit(stats, RANGED, NONE, params.getDouble("followupRatio", 0), "追矢1本（連続ボーナスなし）");
            case WizardSpectralOrreryExecutor.ID -> {
                double ratio = params.getDouble("damageRatio", 0);
                var hit = new Attack(MAGIC, TRIAD, ratio, ratio, 1, 0, 0);
                yield partialAttack(stats, MAGIC, row("追撃1回（3属性合計）の期待ダメージ",
                        hit.expectedHit(stats, ratio, stats.getMaxValue(StatusType.SUPER_CRITICAL_RATE)), ""));
            }
            case ArchmagePhoenixResonanceExecutor.ID -> perHit(stats, MAGIC, FIRE, 0.3, "不死鳥の通常攻撃1回");
            case PaladinDivineChaserSkillExecutor.ID -> {
                double power = (stats.getMaxValue(StatusType.MELEE_DEFENSE) + stats.getMaxValue(StatusType.MAGIC_DEFENSE))
                        * stats.getMaxValue(StatusType.STRENGTH) / 100 + stats.getMaxValue(StatusType.DEFENSE) / 2;
                yield support(row("追撃1回の基礎ダメージ", Math.max(0, power) * params.getDouble("damageRatio", 0), ""),
                        "現在の装備で算出。会心・補正前");
            }
            case WizardSelfHealExecutor.ID -> support(row("基礎回復",
                    stats.getMaxValue(StatusType.MAX_HEALTH) * params.getDouble("healPercent", 0) / 100.0, " HP"), "自己回復");
            case PaladinHolyReprieveExecutor.ID -> support(row("基礎回復", params.getDouble("healAmount", 0)
                    + stats.getMaxValue(StatusType.MAX_SHIELD) * params.getDouble("shieldHealRatio", 0), " HP"), "聖域外");
            case PaladinGuardianRenewalExecutor.ID, SwordmasterSereneMendExecutor.ID -> support(row("基礎回復",
                    stats.getMaxValue(StatusType.MAX_HEALTH) * params.getDouble("healHpRatio", 0), " HP"), "追加効果前");
            case MageHealAuraExecutor.ID -> {
                double minimum = params.getDouble("healAmount", 5);
                double percent = params.getDouble("healPercent", 1);
                yield new Report(List.of(row("自分の現在HPでの基礎回復",
                        Math.max(minimum, stats.getCurrentHp() * percent / 100), " HP")),
                        List.of(row("最低回復量", minimum, " HP"), row("対象の現在HPに対する割合", percent, "%")),
                        List.of(note("対象ごとに最低値と現在HP割合の大きい方を適用")));
            }
            default -> Report.empty();
        };
    }

    /** 発動頻度を仮定せず、攻撃判定1回の期待値を表示します。 */
    private static Report perHit(StatusSnapshot stats, AttackType type, DamageElement element, double ratio, String label) {
        return partialAttack(stats, type, row(label + "の期待ダメージ", expected(stats, type, element, ratio), ""));
    }

    /** 一部の攻撃判定だけ集計できる場合に、その数値と算出条件を返します。 */
    private static Report partialAttack(StatusSnapshot stats, AttackType type, Component... metrics) {
        return new Report(List.of(metrics), attackDetails(stats, type, stats.getMaxValue(StatusType.SUPER_CRITICAL_RATE)), TARGET);
    }

    /** 一つの補助効果の数値と適用条件を返します。 */
    private static Report support(Component metric, String condition) {
        return new Report(List.of(metric), List.of(), List.of(note(condition)));
    }

    /** 固定対象に対する攻撃判定1回の期待ダメージを返します。 */
    private static double expected(StatusSnapshot stats, AttackType type, DamageElement element, double ratio) {
        return DamageCalculator.expectedDirectHit(stats, type, element, DamageSource.SKILL, ratio);
    }

    /** 元の弾の属性ごとに、屈折弾1発と全弾集中時の誘発1回の期待値を返します。 */
    private static Report prism(StatusSnapshot stats, SkillParamReader params) {
        double ratio = params.getDouble("damageRatio", 0);
        int count = params.getInt("projectileCount", 0);
        var details = new ArrayList<>(attackDetails(stats, MAGIC, stats.getMaxValue(StatusType.SUPER_CRITICAL_RATE)));
        for (var element : List.of(NONE, FIRE, ICE, LIGHTNING)) {
            String label = switch (element) {
                case FIRE -> "火";
                case ICE -> "氷";
                case LIGHTNING -> "雷";
                default -> "無";
            };
            double hit = expected(stats, MAGIC, element, ratio);
            details.add(row(label + "属性の屈折弾1発", hit, ""));
            details.add(row(label + "属性の全弾集中時", hit * count, ""));
        }
        return new Report(List.of(note("屈折弾の期待ダメージは属性別")), details, TARGET);
    }

    /** 残存シールドから味方への付与量を求め、聖柱の攻撃は単体が範囲内にいる場合を表示します。 */
    private static Report paladinShield(StatusSnapshot stats, SkillParamReader params, long cooldown, long cast) {
        double remaining = stats.getCurrentShield();
        var summary = new ArrayList<Component>();
        var details = new ArrayList<Component>();
        var conditions = new ArrayList<>(TARGET);
        if (remaining < params.getDouble("minimumCurrentShield", 2)) {
            summary.add(Component.text("現在はシールド不足で発動不可", NamedTextColor.RED));
        } else {
            summary.add(row("味方1人あたりの一時シールド", remaining * params.getDouble("transferRatio", 0.5), ""));
        }
        var pillar = pulsing(MELEE, NONE, params.getDouble("pillarDamageRatio", 0.5),
                params.getInt("temporaryShieldDurationTicks", 400), params.getInt("pillarImpactIntervalTicks", 20));
        double hit = expected(stats, MELEE, NONE, pillar.firstRatio);
        double total = hit * pillar.count;
        details.add(row("聖柱1回の期待ダメージ", hit, ""));
        details.add(row("聖柱全" + pillar.count + "回の期待ダメージ", total, ""));
        appendDps(details, details, "聖柱の期待DPS", total, cast, cooldown, pillar);
        conditions.add(note("聖柱は味方がいないときに発生"));
        conditions.add(note("同じ敵が聖柱の全打撃で範囲内"));
        return new Report(summary, details, conditions);
    }
}
