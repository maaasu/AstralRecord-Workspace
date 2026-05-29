package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.CombatStyle;
import io.github.maaasu.astralRecord.feature.mob.model.IdleBehavior;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobCombatConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.model.MobTargetingConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mob の AI を tick 単位で駆動するサービス。
 *
 * <p>実体 Mob の同期・当たり判定・経路探索は Bukkit/Paper に任せ、
 * AstralRecord 側ではターゲット選定、状態遷移、帰還、徘徊の意思決定だけを行います。</p>
 *
 * <p>100体規模の Mob を想定し、IDLE の探索・AGGRO の追跡判断・viewer 更新は
 * インスタンスごとに分散して実行します。</p>
 */
public class MobAiService {

    /** 脅威値の減衰係数（1 tick あたり）。100 tick で約 0.37 まで減衰する。 */
    private static final double THREAT_DECAY_PER_TICK = 0.99D;

    /** 脅威値を減衰させる間隔（tick）。 */
    private static final long THREAT_DECAY_INTERVAL_TICKS = 100L;

    /** IDLE 状態のターゲット探索・徘徊判断間隔。 */
    private static final long IDLE_DECISION_INTERVAL_TICKS = 10L;

    /** AGGRO / LEASHED 状態の追跡判断間隔。 */
    private static final long ACTIVE_DECISION_INTERVAL_TICKS = 5L;

    /** 頭上 packet display 用 viewer キャッシュ更新間隔。 */
    private static final long VIEWER_UPDATE_INTERVAL_TICKS = 5L;

    /** WANDER 行動で同一ターゲットを追い続ける最大 tick 数（スタック防止）。 */
    private static final long WANDER_TARGET_MAX_TICKS = 100L;

    private static final int WANDER_PAUSE_MIN_TICKS = 20;
    private static final int WANDER_PAUSE_MAX_TICKS = 60;

    private static final double COMBAT_VERTICAL_TOLERANCE = 1.25D;
    private static final double COMBAT_RANGE_BUFFER = 0.25D;
    private static final double RANGED_RETREAT_BUFFER = 1.0D;
    private static final double MIN_RETREAT_DISTANCE = 1.5D;

    private final MobService mobService;

    private BukkitTask task;
    private long internalTick;

    /**
     * コンストラクタ。
     *
     * @param mobService Mob サービス
     */
    public MobAiService(@NotNull MobService mobService) {
        this.mobService = mobService;
    }

    /**
     * tick タスクを起動します。既に起動済みなら何もしません。
     */
    public void start() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(mobService.plugin(), this::tick, 1L, 1L);
        Logger.log(LogId.I_5702);
    }

    /**
     * tick タスクを停止します。
     */
    public void stop() {
        if (task == null) return;
        task.cancel();
        task = null;
        Logger.log(LogId.I_5703);
    }

    /**
     * 全 Mob インスタンスに対して 1 tick の AI 処理を実行します。
     */
    public void tick() {
        try {
            internalTick++;
            List<MobInstance> snapshot = new ArrayList<>(mobService.getInstances());

            for (MobInstance instance : snapshot) {
                try {
                    if (!mobService.syncLocation(instance)) {
                        mobService.destroy(instance.instanceId());
                        continue;
                    }

                    if (internalTick % THREAT_DECAY_INTERVAL_TICKS == 0L) {
                        instance.threatTable().decay(THREAT_DECAY_PER_TICK);
                    }

                    if (instance.state() != MobState.DEAD && isLeashed(instance)) {
                        instance.state(MobState.LEASHED);
                    }

                    switch (instance.state()) {
                        case IDLE -> {
                            if (shouldProcess(instance, IDLE_DECISION_INTERVAL_TICKS)) {
                                tickIdle(instance);
                            }
                        }
                        case AGGRO -> {
                            if (shouldProcess(instance, ACTIVE_DECISION_INTERVAL_TICKS)) {
                                tickAggro(instance);
                            }
                        }
                        case COMBAT -> tickCombatHold(instance);
                        case LEASHED -> {
                            if (shouldProcess(instance, ACTIVE_DECISION_INTERVAL_TICKS)) {
                                tickLeashed(instance);
                            }
                        }
                        case DEAD -> mobService.destroy(instance.instanceId());
                    }
                } catch (RuntimeException ex) {
                    Logger.warn(LogId.W_5702, instance.instanceId());
                    mobService.destroy(instance.instanceId());
                }
            }

            if (internalTick % VIEWER_UPDATE_INTERVAL_TICKS == 0L) {
                mobService.updateViewers();
            }
        } catch (RuntimeException ex) {
            Logger.error(LogId.E_5702, ex);
        }
    }

    /**
     * IDLE 状態の Mob を待機設定に従って処理します。
     *
     * @param instance 対象 Mob
     */
    private void tickIdle(@NotNull MobInstance instance) {
        MobTemplate template = instance.template();
        MobTargetingConfig targeting = template.targeting();

        if (targeting != null && selectTarget(instance) != null) {
            instance.state(MobState.AGGRO);
            mobService.stopPathfinding(instance);
            return;
        }

        if (template.idle().behavior() != IdleBehavior.WANDER) {
            mobService.stopPathfinding(instance);
            return;
        }

        Location currentLoc = instance.currentLocation();
        Location wanderTarget = instance.wanderTarget();

        boolean needNewTarget = wanderTarget == null;
        if (!needNewTarget) {
            double dx = wanderTarget.getX() - currentLoc.getX();
            double dz = wanderTarget.getZ() - currentLoc.getZ();
            needNewTarget = dx * dx + dz * dz < 0.25D;
        }
        if (!needNewTarget && internalTick % WANDER_TARGET_MAX_TICKS == 0L) {
            needNewTarget = true;
        }

        if (needNewTarget) {
            instance.wanderTarget(null);
            mobService.stopPathfinding(instance);
            if (instance.wanderPauseUntilTick() == 0L) {
                instance.wanderPauseUntilTick(internalTick + randomWanderPauseTicks());
            }
            if (internalTick < instance.wanderPauseUntilTick()) {
                return;
            }

            double radius = template.idle().wanderRadius();
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            Location anchor = instance.wanderAnchor();
            double dx = (rng.nextDouble() - 0.5D) * 2.0D * radius;
            double dz = (rng.nextDouble() - 0.5D) * 2.0D * radius;
            instance.wanderTarget(anchor.clone().add(dx, 0.0D, dz));
            instance.wanderPauseUntilTick(0L);
        }

        if (instance.wanderTarget() != null) {
            moveToward(instance, instance.wanderTarget(), template.idle().speed());
        }
    }

    /**
     * AGGRO 状態の Mob をターゲットへ追跡させます。
     *
     * @param instance 対象 Mob
     */
    private void tickAggro(@NotNull MobInstance instance) {
        MobTargetingConfig targeting = instance.template().targeting();
        if (targeting == null) {
            instance.state(MobState.IDLE);
            mobService.stopPathfinding(instance);
            return;
        }

        Player target = resolveChaseTarget(instance);
        if (target == null) {
            instance.targetId(null);
            instance.state(MobState.IDLE);
            mobService.stopPathfinding(instance);
            return;
        }
        mobService.lookAt(instance, target.getEyeLocation());

        double deaggroSq = targeting.deaggroRange() * targeting.deaggroRange();
        double distSq = target.getLocation().distanceSquared(instance.currentLocation());
        if (distSq > deaggroSq) {
            instance.targetId(null);
            instance.state(MobState.IDLE);
            mobService.stopPathfinding(instance);
            return;
        }

        double preferredRange = instance.template().combat() == null
                ? 1.0D
                : instance.template().combat().preferredRange();
        double preferredSq = preferredRange * preferredRange;
        Location targetLoc = target.getLocation();
        Location currentLoc = instance.currentLocation();
        double horizontalSq = horizontalDistanceSquared(currentLoc, targetLoc);
        double verticalDiff = Math.abs(targetLoc.getY() - currentLoc.getY());
        if (verticalDiff <= COMBAT_VERTICAL_TOLERANCE
                && shouldRetreat(instance, horizontalSq, preferredRange)) {
            instance.state(MobState.COMBAT);
            moveAway(instance, targetLoc, preferredRange);
        } else if (horizontalSq <= preferredSq + COMBAT_RANGE_BUFFER
                && verticalDiff <= COMBAT_VERTICAL_TOLERANCE) {
            instance.state(MobState.COMBAT);
            mobService.stopPathfinding(instance);
        } else {
            moveToward(instance, targetLoc, instance.template().idle().speed());
        }
    }

    /**
     * COMBAT 状態の Mob について、距離が離れた場合に追跡へ戻します。
     *
     * @param instance 対象 Mob
     */
    private void tickCombatHold(@NotNull MobInstance instance) {
        Player target = resolveChaseTarget(instance);
        if (target == null) {
            instance.targetId(null);
            instance.state(MobState.IDLE);
            mobService.stopPathfinding(instance);
            return;
        }
        mobService.lookAt(instance, target.getEyeLocation());

        double preferredRange = instance.template().combat() == null
                ? 1.0D
                : instance.template().combat().preferredRange();
        double preferredSq = preferredRange * preferredRange;
        Location targetLoc = target.getLocation();
        Location currentLoc = instance.currentLocation();
        double horizontalSq = horizontalDistanceSquared(currentLoc, targetLoc);
        double verticalDiff = Math.abs(targetLoc.getY() - currentLoc.getY());
        if (verticalDiff <= COMBAT_VERTICAL_TOLERANCE
                && shouldRetreat(instance, horizontalSq, preferredRange)) {
            moveAway(instance, targetLoc, preferredRange);
            return;
        }
        if (horizontalSq > preferredSq + COMBAT_RANGE_BUFFER
                || verticalDiff > COMBAT_VERTICAL_TOLERANCE) {
            mobService.stopPathfinding(instance);
            instance.state(MobState.AGGRO);
            return;
        }
        mobService.stopPathfinding(instance);
    }

    /**
     * LEASHED 状態の Mob をスポーン地点へ帰還させます。
     *
     * @param instance 対象 Mob
     */
    private void tickLeashed(@NotNull MobInstance instance) {
        Location spawn = instance.spawnLocation();
        Location current = instance.currentLocation();
        if (current.distanceSquared(spawn) <= 1.0D) {
            mobService.stopPathfinding(instance);
            instance.state(MobState.IDLE);
            instance.targetId(null);
            instance.threatTable().snapshot().keySet().forEach(id -> instance.threatTable().remove(id));
            return;
        }
        moveToward(instance, spawn, instance.template().idle().speed());
    }

    /**
     * Mob のターゲットを選定して {@link MobInstance#targetId(UUID)} に設定します。
     *
     * @param instance 対象 Mob
     * @return 選定されたプレイヤー。候補なしなら {@code null}
     */
    @Nullable
    private Player selectTarget(@NotNull MobInstance instance) {
        MobTemplate template = instance.template();
        MobTargetingConfig targeting = template.targeting();
        if (targeting == null || template.category() == MobCategory.NPC) {
            instance.targetId(null);
            return null;
        }

        double aggroSq = targeting.aggroRange() * targeting.aggroRange();
        Location loc = instance.currentLocation();
        List<Player> candidates = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld() != loc.getWorld()) continue;
            if (player.getLocation().distanceSquared(loc) > aggroSq) continue;
            candidates.add(player);
        }

        if (candidates.isEmpty()) {
            instance.targetId(null);
            return null;
        }

        Player chosen = switch (targeting.strategy()) {
            case NEAREST -> nearest(candidates, loc);
            case HIGHEST_THREAT -> {
                UUID top = instance.threatTable().top();
                Player player = top == null ? null : Bukkit.getPlayer(top);
                yield player != null && candidates.contains(player) ? player : nearest(candidates, loc);
            }
            case RANDOM -> candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
            case LOWEST_HP -> lowestHp(candidates);
        };

        instance.targetId(chosen == null ? null : chosen.getUniqueId());
        return chosen;
    }

    /**
     * 追跡中のターゲットを解決します。
     *
     * <p>既存ターゲットが deaggroRange 内にいる間は維持し、いなければ新規選定します。</p>
     *
     * @param instance 対象 Mob
     * @return 追跡対象。存在しなければ {@code null}
     */
    @Nullable
    private Player resolveChaseTarget(@NotNull MobInstance instance) {
        MobTargetingConfig targeting = instance.template().targeting();
        UUID targetId = instance.targetId();
        if (targeting != null && targetId != null) {
            Player current = Bukkit.getPlayer(targetId);
            if (current != null && current.isOnline() && current.getWorld() == instance.currentLocation().getWorld()) {
                double deaggroSq = targeting.deaggroRange() * targeting.deaggroRange();
                if (current.getLocation().distanceSquared(instance.currentLocation()) <= deaggroSq) {
                    return current;
                }
            }
        }
        return selectTarget(instance);
    }

    /**
     * スポーン地点から leashRange を超えているか判定します。
     *
     * @param instance 対象 Mob
     * @return leashRange 超過なら {@code true}
     */
    private boolean isLeashed(@NotNull MobInstance instance) {
        MobTargetingConfig targeting = instance.template().targeting();
        if (targeting == null) return false;
        double leashSq = targeting.leashRange() * targeting.leashRange();
        return instance.currentLocation().distanceSquared(instance.spawnLocation()) > leashSq;
    }

    /**
     * ターゲット位置へ Paper Pathfinder で移動させます。
     *
     * @param instance 対象 Mob
     * @param target   目標位置
     * @param speed    AI 設定側の速度倍率
     */
    private void moveToward(@NotNull MobInstance instance, @NotNull Location target, double speed) {
        mobService.moveToward(instance, target, speed, internalTick);
    }

    private void moveAway(@NotNull MobInstance instance, @NotNull Location target, double preferredRange) {
        Location current = instance.currentLocation();
        double dx = current.getX() - target.getX();
        double dz = current.getZ() - target.getZ();
        double lengthSq = dx * dx + dz * dz;
        if (lengthSq < 1.0E-6D) {
            dx = 1.0D;
            dz = 0.0D;
            lengthSq = 1.0D;
        }

        double length = Math.sqrt(lengthSq);
        double retreatDistance = Math.max(MIN_RETREAT_DISTANCE, preferredRange + RANGED_RETREAT_BUFFER);
        Location retreatTarget = current.clone().add(dx / length * retreatDistance, 0.0D, dz / length * retreatDistance);
        moveToward(instance, retreatTarget, instance.template().idle().speed());
    }

    private boolean shouldRetreat(@NotNull MobInstance instance, double horizontalSq, double preferredRange) {
        if (!isRangedStyle(instance.template().combat())) {
            return false;
        }
        double threshold = Math.max(0.0D, preferredRange - COMBAT_RANGE_BUFFER);
        return horizontalSq < threshold * threshold;
    }

    private boolean isRangedStyle(@Nullable MobCombatConfig combat) {
        if (combat == null) {
            return false;
        }
        return combat.style() == CombatStyle.RANGED || combat.style() == CombatStyle.MAGIC;
    }

    private boolean shouldProcess(@NotNull MobInstance instance, long interval) {
        if (interval <= 1L) {
            return true;
        }
        return Math.floorMod(instance.instanceId().hashCode() + internalTick, interval) == 0L;
    }

    private long randomWanderPauseTicks() {
        return ThreadLocalRandom.current().nextLong(WANDER_PAUSE_MIN_TICKS, WANDER_PAUSE_MAX_TICKS + 1L);
    }

    private double horizontalDistanceSquared(@NotNull Location a, @NotNull Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    @Nullable
    private Player nearest(@NotNull List<Player> candidates, @NotNull Location origin) {
        Player best = null;
        double bestSq = Double.MAX_VALUE;
        for (Player player : candidates) {
            double sq = player.getLocation().distanceSquared(origin);
            if (sq < bestSq) {
                bestSq = sq;
                best = player;
            }
        }
        return best;
    }

    @Nullable
    private Player lowestHp(@NotNull List<Player> candidates) {
        Player best = null;
        double bestHp = Double.MAX_VALUE;
        for (Player player : candidates) {
            double hp = player.getHealth();
            if (hp < bestHp) {
                bestHp = hp;
                best = player;
            }
        }
        return best;
    }
}
