package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.DamageCalculator;
import io.github.maaasu.astralRecord.feature.combat.service.CombatTimingCalculator;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import static io.github.maaasu.astralRecord.feature.combat.model.AttackType.*;
import static io.github.maaasu.astralRecord.feature.combat.model.DamageElement.*;

/** 習得個体の戦闘性能を状態の変更や抽選なしで表示します。 */
public final class SkillDamagePreview {
    private static final String TARGET = "攻撃範囲内の単体Mob。防御・耐性・回避・盾は0、装備範囲は実効範囲の中央、会心率は平均";
    private static final List<DamageComponent> TRIAD = List.of(
            new DamageComponent(FIRE, 1), new DamageComponent(ICE, 1), new DamageComponent(LIGHTNING, 1));

    private SkillDamagePreview() {}

    /** 表示用に小数第2位まで丸めます。 */
    public static String number(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /** 現ビルドの中央値と習得個体の定義から、直接ダメージと発動周期を計算します。 */
    public static List<String> describe(StatusSnapshot stats, SkillDefinition skill, long cooldown, long cast, int circles) {
        var params = new SkillParamReader(skill.getId(), skill.getParams());
        try {
            String id = skill.getImplementationId();
            var attack = attack(id, params, stats, circles);
            if (attack == null) {
                return supportOrCondition(stats, skill, params, cooldown, cast);
            }
            if (!attack.valid()) return List.of("期待DPS算出不可（倍率・回数・間隔の定義が無効）");
            var lines = new ArrayList<String>();
            lines.add(effectiveAttack(stats, attack.type));
            double star = skill.getImplementationId().equals(HunterCrashArrowExecutor.ID) ? params.getDouble("superStarCriticalChance", 50)
                    : stats.getMaxValue(StatusType.SUPER_CRITICAL_RATE);
            lines.add("会心倍率 " + number(DamageCalculator.normalCriticalMultiplier(stats) * 100)
                    + "%　超星会心率 " + number(Math.clamp(star, 0, 100)) + "%");
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
            String scenario = switch (id) {
                case SwordmasterGaleReaperExecutor.ID, SwordmasterCrimsonDriveExecutor.ID -> "通過した同一対象へ1回。撃破によるCTリセットは周期に含めない";
                case SwordmasterMirageRetreatExecutor.ID -> "全段で対象が範囲内";
                case SwordsmanExeptStampExecutor.ID -> "着地時に範囲内。飛行・着地待ちの時間は周期に含めない";
                case PaladinHolySmashExecutor.ID -> "聖域外。聖域内で命中時のCT "
                        + seconds(CombatTimingCalculator.resolveCooldownTicks(params.getInt("reducedCooldownTicks", 0),
                        stats.getMaxValue(StatusType.COOLDOWN_REDUCTION)));
                case SharpshooterPhantomShotExecutor.ID -> "継承なし。継承中のCT "
                        + seconds(CombatTimingCalculator.resolveCooldownTicks(skill.getCooldownTicks() * 2,
                        stats.getMaxValue(StatusType.COOLDOWN_REDUCTION)));
                case WizardArcaneOverloadExecutor.ID -> "発動前の現在MPで強化条件を判定";
                case ArchmageAstralSpearExecutor.ID, ArchmageStarfallExecutor.ID, ArchmageOrbitalVolleyExecutor.ID,
                        ArchmageAstralRayExecutor.ID -> "現在の魔法陣 " + circles + "基で計算";
                default -> "";
            };
            if (!scenario.isEmpty()) lines.add(scenario);
            lines.add("1回の発動の期待ダメージ " + number(total));
            appendDps(lines, total, cast, cooldown, attack);
            lines.add(TARGET);
            lines.add("発動直後の無傷・状態異常なしの敵を想定。飛翔時間、継承・剣気等、誘発追撃・持続ダメージは省略");
            return lines;
        } catch (SkillParameterException invalid) {
            return List.of("期待DPS算出不可（個体の攻撃パラメーターが無効）");
        }
    }

    /** 固定した発動周期と最終打撃を待つ条件でDPSを表示します。 */
    private static void appendDps(List<String> lines, double total, long cast, long cooldown, Attack attack) {
        long cycle = cast + Math.max(cooldown, attack.delay + (attack.count - 1L) * attack.interval);
        if (cycle > 0) {
            lines.add("直接攻撃の期待DPS " + number(total * 20.0 / cycle) + "（" + seconds(cycle) + "周期）");
        }
    }

    /** tick数を表示用の秒へ変換します。 */
    private static String seconds(long ticks) {
        return number(ticks / 20.0) + "秒";
    }

    private static String effectiveAttack(StatusSnapshot stats, AttackType type) {
        String label = switch (type) { case MELEE -> "近接"; case RANGED -> "間接"; case MAGIC -> "魔法"; };
        double chance = 100 * DamageCalculator.criticalChance(stats, DamageSource.SKILL);
        return label + "攻撃力 " + number(DamageCalculator.calculateAttackPower(stats::getMaxValue, type))
                + "　命中 " + number(DamageCalculator.calculateHitChance(stats.getMaxValue(StatusType.ACCURACY), 0))
                + "%　会心 " + number(chance) + "%";
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

    /** 誘発ごとの攻撃性能とビルドから求められる支援値を表示します。定義上の効果は解決済みLoreを使います。 */
    private static List<String> supportOrCondition(StatusSnapshot stats, SkillDefinition skill, SkillParamReader params,
                                                    long cooldown, long cast) {
        return switch (skill.getImplementationId()) {
            case HunterFadeShotExecutor.ID -> {
                double hit = expected(stats, RANGED, NONE, params.getDouble("damageRatio", 0));
                int pellets = params.getInt("pelletCount", 0);
                yield List.of(effectiveAttack(stats, RANGED),
                        "矢1本の期待ダメージ " + number(hit) + "　同じ敵に全" + pellets + "本が当たる場合 " + number(hit * pellets),
                        TARGET);
            }
            case HunterArrowRainExecutor.ID -> {
                var ratios = params.getDoubleList("damageRatios", List.of(0.84, 0.36));
                if (ratios.size() != 2) throw new SkillParameterException("damageRatios", "初矢と降矢の倍率が必要です");
                yield List.of(effectiveAttack(stats, RANGED),
                        "初矢の期待ダメージ " + number(expected(stats, RANGED, NONE, ratios.getFirst()))
                                + "　降矢1本 " + number(expected(stats, RANGED, NONE, ratios.get(1))), TARGET);
            }
            case SharpshooterSpreadingAmbitionExecutor.ID -> List.of(effectiveAttack(stats, RANGED),
                    "直撃1本の期待ダメージ " + number(expected(stats, RANGED, NONE, params.getDouble("damageRatio", 0)))
                            + "　爆風1回 " + number(expected(stats, RANGED, NONE, params.getDouble("explosionDamageRatio", 0))),
                    TARGET);
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
                yield List.of(effectiveAttack(stats, MAGIC), "追撃1回（3属性合計）の期待ダメージ "
                        + number(hit.expectedHit(stats, ratio, stats.getMaxValue(StatusType.SUPER_CRITICAL_RATE))), TARGET);
            }
            case ArchmagePhoenixResonanceExecutor.ID -> perHit(stats, MAGIC, FIRE, 0.3, "不死鳥の通常攻撃1回");
            case PaladinDivineChaserSkillExecutor.ID -> {
                double power = (stats.getMaxValue(StatusType.MELEE_DEFENSE) + stats.getMaxValue(StatusType.MAGIC_DEFENSE))
                        * stats.getMaxValue(StatusType.STRENGTH) / 100 + stats.getMaxValue(StatusType.DEFENSE) / 2;
                yield List.of("追撃1回の基礎ダメージ " + number(Math.max(0, power) * params.getDouble("damageRatio", 0))
                        + "（現在の装備。会心・補正前）");
            }
            case WizardSelfHealExecutor.ID -> List.of("基礎回復 " + number(stats.getMaxValue(StatusType.MAX_HEALTH)
                    * params.getDouble("healPercent", 0) / 100.0) + " HP（自己回復）");
            case PaladinHolyReprieveExecutor.ID -> List.of("基礎回復 " + number(params.getDouble("healAmount", 0)
                    + stats.getMaxValue(StatusType.MAX_SHIELD) * params.getDouble("shieldHealRatio", 0)) + " HP（聖域外）");
            case PaladinGuardianRenewalExecutor.ID, SwordmasterSereneMendExecutor.ID -> List.of("基礎回復 "
                    + number(stats.getMaxValue(StatusType.MAX_HEALTH) * params.getDouble("healHpRatio", 0)) + " HP（追加効果前）");
            case MageHealAuraExecutor.ID -> List.of("自分の現在HPでの基礎回復 " + number(Math.max(params.getDouble("healAmount", 5),
                    stats.getCurrentHp() * params.getDouble("healPercent", 1) / 100)) + " HP");
            default -> List.of();
        };
    }

    /** 発動頻度を仮定せず、攻撃判定1回の期待値を表示します。 */
    private static List<String> perHit(StatusSnapshot stats, AttackType type, DamageElement element, double ratio, String label) {
        return List.of(effectiveAttack(stats, type), label + "の期待ダメージ " + number(expected(stats, type, element, ratio)), TARGET);
    }

    /** 固定対象に対する攻撃判定1回の期待ダメージを返します。 */
    private static double expected(StatusSnapshot stats, AttackType type, DamageElement element, double ratio) {
        return DamageCalculator.expectedDirectHit(stats, type, element, DamageSource.SKILL, ratio);
    }

    /** 元の弾の属性ごとに、屈折弾1発と全弾集中時の誘発1回の期待値を返します。 */
    private static List<String> prism(StatusSnapshot stats, SkillParamReader params) {
        double ratio = params.getDouble("damageRatio", 0);
        int count = params.getInt("projectileCount", 0);
        var lines = new ArrayList<String>();
        lines.add(effectiveAttack(stats, MAGIC));
        lines.add("屈折弾1発／単体集中時の誘発1回の期待ダメージ");
        for (var element : List.of(NONE, FIRE, ICE, LIGHTNING)) {
            String label = switch (element) {
                case FIRE -> "火";
                case ICE -> "氷";
                case LIGHTNING -> "雷";
                default -> "無";
            };
            double hit = expected(stats, MAGIC, element, ratio);
            lines.add(label + " " + number(hit) + "／" + number(hit * count));
        }
        lines.add(TARGET);
        return lines;
    }

    /** 残存シールドから味方への付与量を求め、聖柱の攻撃は単体が範囲内にいる場合を表示します。 */
    private static List<String> paladinShield(StatusSnapshot stats, SkillParamReader params, long cooldown, long cast) {
        double remaining = stats.getCurrentShield();
        var lines = new ArrayList<String>();
        if (remaining < params.getDouble("minimumCurrentShield", 2)) {
            lines.add("現在はシールド不足で発動不可");
        } else {
            lines.add("味方がいる場合、1人あたりの一時シールド " + number(remaining * params.getDouble("transferRatio", 0.5)));
        }
        var pillar = pulsing(MELEE, NONE, params.getDouble("pillarDamageRatio", 0.5),
                params.getInt("temporaryShieldDurationTicks", 400), params.getInt("pillarImpactIntervalTicks", 20));
        double hit = expected(stats, MELEE, NONE, pillar.firstRatio);
        double total = hit * pillar.count;
        lines.add("味方がいない場合、聖柱1回の期待ダメージ " + number(hit)
                + "　同じ敵が全" + pillar.count + "回範囲内の場合 " + number(total));
        appendDps(lines, total, cast, cooldown, pillar);
        lines.add(TARGET);
        return lines;
    }
}
