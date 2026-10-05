package io.github.maaasu.astralRecord.feature.mob.skill.eriva;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** エリヴァ森林の専用技に閉じた、継続可否・予告・遮蔽物の共通処理です。 */
final class ErivaForestSkillSupport {

    private ErivaForestSkillSupport() { }

    /**
     * 発動時と同じ戦闘・対象が継続し、行動可能な発動者だけを返します。
     * @param context 発動文脈
     * @param mobService Mob実体の取得先
     * @param damageService 主対象の有効性判定先
     * @param conditionService 行動不能状態の判定先
     * @param requiresMovement 移動不能時にも技を中断するか
     * @return 有効な実体。破棄、帰還、対象変更、行動不能時はnull
     */
    static @Nullable Entity activeCaster(@NotNull MobSkillContext context, @NotNull MobService mobService,
                                        @NotNull DamageService damageService,
                                        @NotNull ConditionService conditionService, boolean requiresMovement) {
        var mob = context.mob();
        if (mobService.getInstance(mob.instanceId()) != mob || mob.state() != MobState.COMBAT
                || !context.target().getUniqueId().equals(mob.targetId())
                || !damageService.isMobCombatTarget(context.target())) {
            return null;
        }
        Entity entity = mobService.entityController().getEntity(mob);
        AstEntity caster = AstEntity.mob(mob);
        if (entity == null || !entity.isValid() || entity.isDead()
                || entity.getWorld() != context.origin().getWorld()
                || entity.getWorld() != context.target().getWorld()
                || !conditionService.canRunAi(caster) || !conditionService.canCastSkill(caster)
                || !conditionService.canAttack(caster)
                || (requiresMovement && !conditionService.canMove(caster))) {
            return null;
        }
        return entity;
    }

    /**
     * 20tickの固定予告中は通常AIを止め、完了または中断時に解除します。
     * @param context 発動文脈
     * @param mobService Mob取得・Schedulerの所有者
     * @param damageService 主対象の有効性判定先
     * @param conditionService 行動不能状態の判定先
     * @param particles 閲覧者単位の描画先
     * @param center 描画中心
     * @param points 最大24点の固定予告座標
     * @param warning 予告粒子
     * @param impact 20tick後に一度だけ実行する効果
     */
    static void warnThenImpact(@NotNull MobSkillContext context, @NotNull MobService mobService,
                               @NotNull DamageService damageService, @NotNull ConditionService conditionService,
                               @NotNull ParticleDisplayService particles, @NotNull Location center,
                               @NotNull List<Location> points, @NotNull SharedParticleDefinition warning,
                               @NotNull Runnable impact) {
        context.mob().scriptedAction(true);
        particles.spawnForNearbyViewers(center, points, warning);
        new BukkitRunnable() {
            private int elapsed;

            @Override
            public void run() {
                if (activeCaster(context, mobService, damageService, conditionService, false) == null) {
                    finish();
                    return;
                }
                elapsed += 5;
                if (elapsed >= 20) {
                    try {
                        impact.run();
                    } finally {
                        finish();
                    }
                } else {
                    particles.spawnForNearbyViewers(center, points, warning);
                }
            }

            /** 通常AIの停止を解除し、予告taskを終了します。 */
            private void finish() {
                cancel();
                context.mob().scriptedAction(false);
            }
        }.runTaskTimer(mobService.plugin(), 5L, 5L);
    }

    /**
     * 範囲内の対象と判定中心の間に遮蔽物がないかを確認します。
     * @param origin 判定中心の足元
     * @param target 範囲内の候補
     * @return 胴体への線分が壁に遮られない場合true
     */
    static boolean hasClearPath(@NotNull Location origin, @NotNull LivingEntity target) {
        Location from = origin.clone().add(0.0D, 0.5D, 0.0D);
        Location to = target.getLocation().add(0.0D, Math.min(1.0D, target.getHeight() * 0.5D), 0.0D);
        Vector direction = to.toVector().subtract(from.toVector());
        double distance = direction.length();
        return distance < 0.01D || from.getWorld().rayTraceBlocks(from, direction, distance) == null;
    }

    /**
     * 最大0.75mの突進区間を0.15m以下で刻み、実体の幅・高さを含めて衝突を確認します。
     * @param entity 前進する実体
     * @param from 現在位置
     * @param to 次の位置
     * @return 全サンプルが読込済みで、ブロックの衝突形状へ重ならない場合true
     */
    static boolean isRushStepClear(@NotNull Entity entity, @NotNull Location from, @NotNull Location to) {
        World world = from.getWorld();
        if (world == null || world != entity.getWorld() || world != to.getWorld()) {
            return false;
        }
        Vector delta = to.toVector().subtract(from.toVector());
        int steps = Math.max(1, (int) Math.ceil(delta.length() / 0.15D));
        BoundingBox original = entity.getBoundingBox();
        for (int sample = 1; sample <= steps; sample++) {
            Vector shift = from.toVector().add(delta.clone().multiply((double) sample / steps))
                    .subtract(entity.getLocation().toVector());
            BoundingBox bounds = original.clone().shift(shift);
            int minY = (int) Math.floor(bounds.getMinY());
            int maxY = (int) Math.floor(Math.nextDown(bounds.getMaxY()));
            if (minY < world.getMinHeight() || maxY >= world.getMaxHeight()) {
                return false;
            }
            for (int x = (int) Math.floor(bounds.getMinX()); x <= (int) Math.floor(Math.nextDown(bounds.getMaxX())); x++) {
                for (int z = (int) Math.floor(bounds.getMinZ()); z <= (int) Math.floor(Math.nextDown(bounds.getMaxZ())); z++) {
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        return false;
                    }
                    for (int y = minY; y <= maxY; y++) {
                        if (world.getBlockAt(x, y, z).getCollisionShape().overlaps(bounds.clone().shift(-x, -y, -z))) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }
}
