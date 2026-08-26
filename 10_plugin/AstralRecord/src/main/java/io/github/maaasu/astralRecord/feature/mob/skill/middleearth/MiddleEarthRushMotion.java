package io.github.maaasu.astralRecord.feature.mob.skill.middleearth;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
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
                if (activeEntity == null || activeEntity.isDead() || activeEntity.getWorld() != targetLocation.getWorld()) {
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
                            List.of(new DamageComponent(DamageElement.NONE, damageRatio)), DamageSource.SKILL
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
}
