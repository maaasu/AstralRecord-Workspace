package io.github.maaasu.astralRecord.feature.mob.skill.middleearth;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** ミドルアースの突進スキルで共有する、壁停止・対象命中を備えた直進移動です。 */
final class MiddleEarthRushMotion {

    private MiddleEarthRushMotion() {
    }

    static void start(
            @NotNull MobService mobService,
            @NotNull DamageService damageService,
            @NotNull MobInstance caster,
            @NotNull Entity entity,
            @NotNull Location targetLocation,
            @NotNull Player target,
            double speed,
            double damageRatio,
            @NotNull Runnable onComplete
    ) {
        start(
                mobService, damageService, caster, entity, targetLocation, target,
                speed, damageRatio, DamageElement.NONE, onComplete
        );
    }

    /**
     * 指定した属性で壁停止・対象命中を備えた直進移動を開始します。
     *
     * @param mobService Mobの存続確認と位置更新先
     * @param damageService 命中ダメージの適用先
     * @param caster 発動元Mob
     * @param entity 発動開始時の実体
     * @param targetLocation 突進先として固定した位置
     * @param target 命中判定対象
     * @param speed 1tickあたりの移動距離
     * @param damageRatio 攻撃力倍率
     * @param damageElement ダメージ属性
     * @param onComplete 正常終了・中断時の後続処理
     */
    static void start(
            @NotNull MobService mobService,
            @NotNull DamageService damageService,
            @NotNull MobInstance caster,
            @NotNull Entity entity,
            @NotNull Location targetLocation,
            @NotNull Player target,
            double speed,
            double damageRatio,
            @NotNull DamageElement damageElement,
            @NotNull Runnable onComplete
    ) {
        if (!isTargetAvailable(entity, target, targetLocation)) {
            onComplete.run();
            return;
        }
        Location start = entity.getLocation();
        Vector remaining = targetLocation.toVector().subtract(start.toVector());
        if (remaining.lengthSquared() <= 0.01D) {
            onComplete.run();
            return;
        }
        Vector direction = remaining.clone().normalize();
        new BukkitRunnable() {
            private Location position = start.clone();
            private double distanceLeft = remaining.length();

            @Override
            public void run() {
                MobInstance active = mobService.getInstance(caster.instanceId());
                Entity activeEntity = active == caster ? mobService.entityController().getEntity(active) : null;
                if (activeEntity == null || activeEntity.isDead()
                        || !isTargetAvailable(activeEntity, target, targetLocation)) {
                    complete();
                    return;
                }
                double step = Math.min(Math.max(0.1D, speed), distanceLeft);
                Vector movement = direction.clone().multiply(step);
                RayTraceResult blockHit = position.getWorld().rayTraceBlocks(position, movement, step);
                if (blockHit != null) {
                    complete();
                    return;
                }
                Location next = position.clone().add(movement);
                double hitDistance = rayDistance(position, next, target);
                if (hitDistance != Double.POSITIVE_INFINITY) {
                    Location hit = position.clone().add(direction.clone().multiply(hitDistance));
                    activeEntity.teleport(hit);
                    active.currentLocation(hit);
                    damageService.attack(
                            AstEntity.mob(caster), damageService.resolveEntity(target), AttackType.MELEE,
                            List.of(new DamageComponent(damageElement, damageRatio)), DamageSource.SKILL
                    );
                    complete();
                    return;
                }
                activeEntity.teleport(next);
                active.currentLocation(next);
                position = next;
                distanceLeft -= step;
                if (distanceLeft <= 0.01D) {
                    complete();
                }
            }

            private double rayDistance(@NotNull Location from, @NotNull Location to, @NotNull Player player) {
                Vector segment = to.toVector().subtract(from.toVector());
                RayTraceResult hit = player.getBoundingBox().expand(0.25D)
                        .rayTrace(from.toVector(), segment, segment.length());
                return hit == null ? Double.POSITIVE_INFINITY : hit.getHitPosition().distance(from.toVector());
            }

            private void complete() {
                cancel();
                onComplete.run();
            }
        }.runTaskTimer(mobService.plugin(), 0L, 1L);
    }

    static boolean isTargetAvailable(
            @NotNull Entity entity,
            @NotNull Player target,
            @NotNull Location targetLocation
    ) {
        return target.getUniqueId() != null
                && AccountModeGuard.isGameplayPlayer(target)
                && target.isOnline() && !target.isDead()
                && entity.getWorld() == target.getWorld()
                && entity.getWorld() == targetLocation.getWorld();
    }
}
