package io.github.maaasu.astralRecord.feature.skill.executor.active.hunter;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillBallisticProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileTermination;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.service.SkillPresentationUtil;
import io.github.maaasu.astralRecord.feature.status.model.HealthRecoveryContext;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 重力の影響を強く受け、Mobを貫通して命中地点ごとに回復エリアを作るハンターの矢です。 */
public final class HunterHealArrowExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "hunter_heal_arrow";
    private static final double GRAVITY_PER_TICK = 0.14D;
    private static final int MAX_PROJECTILE_TICKS = 80;
    private static final double MAX_PROJECTILE_DISTANCE = 48.0D;
    private static final int MAX_PROJECTILE_HITS = Integer.MAX_VALUE;
    private static final int AREA_RING_INTERVAL_TICKS = 5;
    private static final int AREA_RING_POINTS = 20;

    /** 共有発動スキルサービスで初期化します。 */
    public HunterHealArrowExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "radius");
        requirePositive(params, "healAmount");
        requirePositive(params, "damageRatio");
        requirePositive(params, "projectileSpeed");
        requirePositive(params, "projectileHitRadius");
        requirePositiveInt(params, "areaDurationTicks");
        double damageRatio = params.getDouble("damageRatio", 0.0D);
        if (Math.abs(damageRatio - 0.45D) > 1.0E-9D) {
            throw new SkillParameterException(
                    "damageRatio",
                    "ヒールアローの敵Mobへのダメージ倍率は45%で固定です"
            );
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double projectileSpeed = params.getDouble("projectileSpeed", 1.25D);
        SkillBallisticProjectileSpec projectile = new SkillBallisticProjectileSpec(
                context.direction().multiply(projectileSpeed),
                GRAVITY_PER_TICK,
                MAX_PROJECTILE_TICKS,
                MAX_PROJECTILE_DISTANCE,
                params.getDouble("projectileHitRadius", 0.45D),
                true,
                MAX_PROJECTILE_HITS,
                SharedParticleDefinitions.HUNTER_HEAL_ARROW_TRAIL,
                SharedParticleDefinitions.HUNTER_HEAL_ARROW_IMPACT
        );
        context.services().projectiles().launchBallisticWithTermination(
                context.player(),
                context.eyeLocation(),
                projectile,
                (target, impact) -> {
                    context.services().combat().hit(
                            context.attacker(),
                            target,
                            AttackType.RANGED,
                            DamageElement.NONE,
                            params.getDouble("damageRatio", 0.45D)
                    );
                    createHealingArea(context, impact, params);
                },
                termination -> {
                    if (termination.type() == SkillProjectileTermination.Type.BLOCK) {
                        createHealingArea(context, termination.location(), params);
                    }
                }
        );
        context.services().effects().sound(context.eyeLocation(), Sound.ENTITY_ARROW_SHOOT, 1.0F, 0.78F);
        return context.success();
    }

    private void createHealingArea(
            @NotNull PlayerActiveSkillContext context,
            @NotNull Location center,
            @NotNull SkillParamReader params
    ) {
        if (center.getWorld() == null) {
            return;
        }
        Location areaCenter = center.clone();
        double radius = params.getDouble("radius", 2.0D);
        double healAmount = params.getDouble("healAmount", 12.0D);
        int durationTicks = params.getInt("areaDurationTicks", 60);
        Set<UUID> healedPlayers = new HashSet<>();
        context.services().effects().point(areaCenter, SharedParticleDefinitions.HUNTER_HEAL_ARROW_IMPACT);
        context.services().effects().ring(
                areaCenter,
                radius,
                AREA_RING_POINTS,
                SharedParticleDefinitions.HUNTER_HEAL_ARROW_AREA
        );
        context.services().effects().sound(areaCenter, Sound.BLOCK_BEACON_POWER_SELECT, 0.8F, 1.35F);
        Player player = context.player();
        context.services().tasks().repeat(
                player.getUniqueId(),
                ID + ":area:" + UUID.randomUUID(),
                0L,
                1L,
                durationTicks,
                tick -> tickHealingArea(context, areaCenter, radius, healAmount, healedPlayers, tick)
        );
    }

    private void tickHealingArea(
            @NotNull PlayerActiveSkillContext context,
            @NotNull Location center,
            double radius,
            double healAmount,
            @NotNull Set<UUID> healedPlayers,
            int tick
    ) {
        if (tick % AREA_RING_INTERVAL_TICKS == 0) {
            context.services().effects().ring(
                    center,
                    radius,
                    AREA_RING_POINTS,
                SharedParticleDefinitions.HUNTER_HEAL_ARROW_AREA
            );
        }
        HealthRecoveryContext recoveryContext = HealthRecoveryContext.by(
                context.caster().player(),
                SkillPresentationUtil.plainName(context.source().skill(), "スキル")
        );
        for (AstPlayer target : context.services().targeting().playersInRadius(center, radius, radius)) {
            UUID targetId = target.getBukkit().getUniqueId();
            if (!healedPlayers.add(targetId)) {
                continue;
            }
            double recovered = context.services().combat().recoverHp(target, healAmount, recoveryContext);
            if (recovered > 0.0D) {
                context.services().effects().point(
                        target.getBukkit().getLocation().add(0.0D, 1.0D, 0.0D),
                        SharedParticleDefinitions.HUNTER_HEAL_ARROW_HEAL
                );
            }
        }
    }

    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        double value = params.getDouble(key, Double.NaN);
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new SkillParameterException(key, "ヒールアローの params[" + key + "] は正数が必要です");
        }
    }

    private static void requirePositiveInt(@NotNull SkillParamReader params, @NotNull String key) {
        if (params.getInt(key, 0) <= 0) {
            throw new SkillParameterException(key, "ヒールアローの params[" + key + "] は正の整数が必要です");
        }
    }
}
