package io.github.maaasu.astralRecord.feature.skill.executor.active.adventurer;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 右から左へ薙ぎ払い、命中対象へ追撃の突きを加える冒険者のアストラルエッジです。 */
public final class AdventurerAstralEdgeExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "adventurer_astral_edge";
    private static final String SWEEP_SCOPE = ID + ":sweep";
    private static final String THRUST_SCOPE = ID + ":thrust";
    private static final double DEFAULT_REACH = 5.5D;
    private static final int DEFAULT_MAX_TARGETS = 5;
    private static final List<Double> DEFAULT_DAMAGE_RATIOS = List.of(1.2D, 0.6D);
    private static final double DEFAULT_ENERGY_RECOVERY_RATIO = 0.05D;
    private static final double[] SWEEP_RADIUS_BASES = {2.4D, 3.9D, 5.4D};
    private static final double SWEEP_START_ANGLE = 55.0D;
    private static final double SWEEP_END_ANGLE = -55.0D;
    private static final int SWEEP_FRAMES = 6;
    private static final int MAX_TARGETS = 5;

    /** 共有発動スキルサービスで初期化します。 */
    public AdventurerAstralEdgeExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "reach");
        List<Double> damageRatios = params.getDoubleList("damageRatios", List.of());
        if (damageRatios.size() != DEFAULT_DAMAGE_RATIOS.size()
                || damageRatios.stream().anyMatch(value -> !(value > 0.0D))) {
            throw new SkillParameterException(
                    "damageRatios",
                    "アストラルエッジの params[damageRatios] は正数2件の配列が必要です"
            );
        }
        if (params.getInt("maxTargets", 0) < 1) {
            throw new SkillParameterException(
                    "maxTargets",
                    "アストラルエッジの params[maxTargets] は1以上の整数が必要です"
            );
        }
        double energyRecoveryRatio = params.getDouble("energyRecoveryRatio", 0.0D);
        if (!(energyRecoveryRatio > 0.0D && energyRecoveryRatio <= 1.0D)) {
            throw new SkillParameterException(
                    "energyRecoveryRatio",
                    "アストラルエッジの params[energyRecoveryRatio] は0より大きく1以下が必要です"
            );
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        Player player = context.player();
        AstEntity attacker = context.attacker();
        Location origin = context.eyeLocation();
        var direction = context.direction();
        World castWorld = player.getWorld();
        SkillParamReader params = context.params();
        double reach = params.getDouble("reach", DEFAULT_REACH);
        int maxTargets = params.getInt("maxTargets", DEFAULT_MAX_TARGETS);
        List<Double> damageRatios = params.getDoubleList("damageRatios", DEFAULT_DAMAGE_RATIOS);
        double damageRatio = damageRatios.get(0);
        double thrustDamageRatio = damageRatios.get(1);
        double energyRecoveryRatio = params.getDouble("energyRecoveryRatio", DEFAULT_ENERGY_RECOVERY_RATIO);
        double[] sweepRadii = scaledSweepRadii(reach);
        Set<UUID> hitTargetIds = new HashSet<>();

        context.services().tasks().repeat(
                player.getUniqueId(),
                SWEEP_SCOPE,
                0L,
                1L,
                SWEEP_FRAMES,
                frame -> {
                    if (!player.isOnline() || player.getWorld() != castWorld) {
                        context.services().tasks().cancel(player.getUniqueId(), SWEEP_SCOPE);
                        return;
                    }
                    double headStart = SWEEP_START_ANGLE
                            + (SWEEP_END_ANGLE - SWEEP_START_ANGLE) * frame / SWEEP_FRAMES;
                    double headEnd = SWEEP_START_ANGLE
                            + (SWEEP_END_ANGLE - SWEEP_START_ANGLE) * (frame + 1) / SWEEP_FRAMES;
                    for (double radius : sweepRadii) {
                        context.services().effects().viewArcSegment(
                                origin,
                                direction,
                                radius,
                                headStart,
                                headEnd,
                                5,
                                SharedParticleDefinitions.ADVENTURER_ASTRAL_EDGE_CRIT
                        );
                        context.services().effects().viewArcSegment(
                                origin,
                                direction,
                                radius,
                                headStart,
                                Math.min(SWEEP_START_ANGLE, headStart + 18.0D),
                                4,
                                SharedParticleDefinitions.ADVENTURER_ASTRAL_EDGE_SPARK
                        );
                    }
                    List<AstEntity> frameTargets = context.services().targeting().inViewArcSegment(
                            player,
                            origin,
                            direction,
                            reach,
                            headStart,
                            headEnd,
                            maxTargets,
                            true
                    );
                    for (AstEntity target : frameTargets) {
                        if (hitTargetIds.size() >= maxTargets || !hitTargetIds.add(target.id())) {
                            continue;
                        }
                        hitSweepTarget(
                                context,
                                attacker,
                                player,
                                castWorld,
                                target,
                                damageRatio,
                                thrustDamageRatio,
                                energyRecoveryRatio
                        );
                    }
                }
        );
        context.services().effects().sound(
                origin,
                Sound.ENTITY_PLAYER_ATTACK_SWEEP,
                1.0F,
                1.05F
        );
        return context.success();
    }

    /**
     * 薙ぎ払いの命中と、独立した遅延突き刺しを対象へ適用します。
     *
     * @param context 発動者・共有サービス・解決済みスキルを保持するコンテキスト
     * @param attacker 発動者の攻撃エンティティ
     * @param player Bukkitプレイヤー
     * @param castWorld 発動時ワールド
     * @param target 命中対象
     * @param damageRatio 薙ぎ払い倍率
     * @param thrustDamageRatio 突き刺し倍率
     * @param energyRecoveryRatio 最大ENGに対する一撃ごとの回復割合
     */
    private void hitSweepTarget(
            @NotNull PlayerActiveSkillContext context,
            @NotNull AstEntity attacker,
            @NotNull Player player,
            @NotNull World castWorld,
            @NotNull AstEntity target,
            double damageRatio,
            double thrustDamageRatio,
            double energyRecoveryRatio
    ) {
        DamageResult sweepResult = context.services().combat().hit(
                attacker,
                target,
                AttackType.MELEE,
                DamageElement.NONE,
                damageRatio
        );
        recoverEnergyOnHit(context, sweepResult, energyRecoveryRatio);
        Location targetLocation = target.location().add(0.0D, 0.9D, 0.0D);
        context.services().effects().sound(
                targetLocation,
                Sound.ENTITY_PLAYER_ATTACK_CRIT,
                0.85F,
                1.2F
        );
        String targetScope = THRUST_SCOPE + ":" + target.id();
        context.services().tasks().later(player.getUniqueId(), targetScope, 4L, () -> {
            if (!player.isOnline() || player.getWorld() != castWorld) {
                return;
            }
            Location currentTargetLocation = target.location().add(0.0D, 0.9D, 0.0D);
            context.services().effects().line(
                    player.getEyeLocation(),
                    currentTargetLocation,
                    0.38D,
                    SharedParticleDefinitions.SKILL_SWORD_EDGE
            );
            DamageResult thrustResult = context.services().combat().hit(
                    attacker,
                    target,
                    AttackType.MELEE,
                    DamageElement.NONE,
                    thrustDamageRatio
            );
            recoverEnergyOnHit(context, thrustResult, energyRecoveryRatio);
            context.services().effects().point(
                    currentTargetLocation,
                    SharedParticleDefinitions.SKILL_SWORD_EDGE
            );
            context.services().effects().sound(
                    currentTargetLocation,
                    Sound.ENTITY_PLAYER_ATTACK_CRIT,
                    0.85F,
                    1.2F
            );
        });
    }

    /**
     * 回避されず、HPまたはShieldへ有効なダメージが適用された一撃のENG回復を処理します。
     *
     * @param context 発動者・共有サービス・解決済みスキルを保持するコンテキスト
     * @param result 一撃のダメージ結果
     * @param energyRecoveryRatio 最大ENGに対する回復割合
     */
    private void recoverEnergyOnHit(
            @NotNull PlayerActiveSkillContext context,
            @NotNull DamageResult result,
            double energyRecoveryRatio
    ) {
        if (result.evaded() || (result.finalDamage() <= 0.0D && result.shieldDamage() <= 0.0D)) {
            return;
        }
        context.services().combat().recoverEnergyByMaxRatio(
                context.caster().player(),
                energyRecoveryRatio
        );
    }

    private static double[] scaledSweepRadii(double reach) {
        double[] radii = new double[SWEEP_RADIUS_BASES.length];
        for (int i = 0; i < SWEEP_RADIUS_BASES.length; i++) {
            radii[i] = SWEEP_RADIUS_BASES[i] * reach / DEFAULT_REACH;
        }
        return radii;
    }

    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "アストラルエッジの params[" + key + "] は正数が必要です");
        }
    }
}
