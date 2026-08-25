package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mob スキル専用の見える飛び道具を処理します。
 *
 * <p>Bukkit の矢 Entity は使わず、毎 tick の線分とプレイヤー hitbox の交差で命中を判定します。
 * したがって発射後にプレイヤーが移動すれば避けられ、壁に遮られ、通常攻撃の即時ダメージにはなりません。</p>
 */
public final class MobProjectileService {

    private static final long MAX_LIFETIME_TICKS = 20L * 5L;

    private final MobService mobService;
    private final ParticleDisplayService particleDisplayService;
    private final Map<UUID, List<BukkitTask>> tasksByCaster = new HashMap<>();

    private record ProjectileImpact(@NotNull Location location, Player player) { }

    /** Mob の存続確認と表示に使うサービスを指定して構築します。 */
    public MobProjectileService(@NotNull MobService mobService, @NotNull ParticleDisplayService particleDisplayService) {
        this.mobService = mobService;
        this.particleDisplayService = particleDisplayService;
    }

    /**
     * 物理的な矢の見た目と命中判定を開始します。
     *
     * @param caster      発射元 Mob
     * @param origin      発射開始位置
     * @param direction   発射方向
     * @param speed       1 tick あたりの移動距離
     * @param hitRadius   プレイヤー hitbox に加える半径
     * @param damageRatio 攻撃力に掛けるダメージ倍率
     * @param damageService 命中時のダメージ適用先
     */
    public void launchArrow(
            @NotNull MobInstance caster,
            @NotNull Location origin,
            @NotNull Vector direction,
            double speed,
            double hitRadius,
            double damageRatio,
            @NotNull DamageService damageService
    ) {
        if (origin.getWorld() == null || direction.lengthSquared() <= 1.0E-6D) {
            return;
        }
        Vector velocity = direction.clone().normalize().multiply(Math.max(0.05D, speed));
        BukkitRunnable runnable = new BukkitRunnable() {
            private Location position = origin.clone();
            private long elapsedTicks;

            @Override
            public void run() {
                MobInstance activeCaster = mobService.getInstance(caster.instanceId());
                if (activeCaster != caster || elapsedTicks++ >= MAX_LIFETIME_TICKS) {
                    finish();
                    return;
                }
                Location next = position.clone().add(velocity);
                ProjectileImpact impact = firstImpact(position, next, Math.max(0.0D, hitRadius));
                if (impact != null) {
                    particleDisplayService.spawnForNearbyViewers(impact.location(), SharedParticleDefinitions.SKILL_HUNTER_IMPACT);
                    if (impact.player() != null) {
                        damageService.attack(
                                AstEntity.mob(caster),
                                damageService.resolveEntity(impact.player()),
                                AttackType.RANGED,
                                List.of(new DamageComponent(DamageElement.NONE, damageRatio)),
                                DamageSource.SKILL
                        );
                    }
                    finish();
                    return;
                }
                position = next;
                particleDisplayService.spawnForNearbyViewers(position, SharedParticleDefinitions.SKILL_HUNTER_ARROW);
            }

            private void finish() {
                cancel();
                removeTask(caster.instanceId(), getTaskId());
            }
        };
        BukkitTask task = runnable.runTaskTimer(mobService.plugin(), 0L, 1L);
        tasksByCaster.computeIfAbsent(caster.instanceId(), ignored -> new ArrayList<>()).add(task);
    }

    /** 指定 Mob が発射した未着弾の飛び道具を破棄します。 */
    public void clearCasterState(@NotNull UUID casterId) {
        List<BukkitTask> tasks = tasksByCaster.remove(casterId);
        if (tasks != null) {
            tasks.forEach(BukkitTask::cancel);
        }
    }

    /** Plugin 停止時に全飛び道具を破棄します。 */
    public void stop() {
        for (UUID casterId : List.copyOf(tasksByCaster.keySet())) {
            clearCasterState(casterId);
        }
    }

    private ProjectileImpact firstImpact(@NotNull Location from, @NotNull Location to, double hitRadius) {
        Player player = firstHitPlayer(from, to, hitRadius);
        double playerDistance = player == null ? Double.POSITIVE_INFINITY : rayDistance(from, to, player.getBoundingBox().expand(hitRadius));
        RayTraceResult blockHit = from.getWorld().rayTraceBlocks(from, to.toVector().subtract(from.toVector()), from.distance(to));
        double blockDistance = blockHit == null ? Double.POSITIVE_INFINITY : blockHit.getHitPosition().distance(from.toVector());
        if (playerDistance == Double.POSITIVE_INFINITY && blockDistance == Double.POSITIVE_INFINITY) {
            return null;
        }
        if (playerDistance <= blockDistance) {
            return new ProjectileImpact(
                    from.clone().add(to.toVector().subtract(from.toVector()).normalize().multiply(playerDistance)),
                    player
            );
        }
        return new ProjectileImpact(blockHit.getHitPosition().toLocation(from.getWorld()), null);
    }

    private Player firstHitPlayer(@NotNull Location from, @NotNull Location to, double hitRadius) {
        World world = from.getWorld();
        Player result = null;
        double closest = Double.POSITIVE_INFINITY;
        for (Player player : world.getPlayers()) {
            if (!player.isOnline() || player.isDead()) {
                continue;
            }
            double distance = rayDistance(from, to, player.getBoundingBox().expand(hitRadius));
            if (distance < closest) {
                closest = distance;
                result = player;
            }
        }
        return result;
    }

    private double rayDistance(@NotNull Location from, @NotNull Location to, @NotNull BoundingBox box) {
        Vector segment = to.toVector().subtract(from.toVector());
        RayTraceResult hit = box.rayTrace(from.toVector(), segment, segment.length());
        return hit == null
                ? Double.POSITIVE_INFINITY
                : hit.getHitPosition().distance(from.toVector());
    }

    private void removeTask(@NotNull UUID casterId, int taskId) {
        List<BukkitTask> tasks = tasksByCaster.get(casterId);
        if (tasks == null) {
            return;
        }
        tasks.removeIf(task -> task.getTaskId() == taskId);
        if (tasks.isEmpty()) {
            tasksByCaster.remove(casterId);
        }
    }
}
