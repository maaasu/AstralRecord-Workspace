package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.IdleBehavior;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mob の AI を tick 単位で駆動するサービス。
 *
 * <p>マスタデータの AI 定義に基づく待機移動・追跡・帰還と、
 * パケット表示の更新のみを担当します。攻撃・ドロップ処理は行いません。</p>
 *
 * <p>移動はブロック衝突判定を持つ A* 経路探索（{@link MobNavigator}）を使用し、
 * 地形に沿って歩くバニラモブに近い動作を実現します。</p>
 */
public class MobAiService {

    /** 脅威値の減衰係数（1 tick あたり）。100 tick で約 0.37 まで減衰する。 */
    private static final double THREAT_DECAY_PER_TICK = 0.99;

    /** 脅威値を減衰させる間隔（tick）。 */
    private static final long THREAT_DECAY_INTERVAL_TICKS = 100L;

    /** WANDER 行動で同一ターゲットを追い続ける最大 tick 数（スタック防止）。 */
    private static final long WANDER_TARGET_MAX_TICKS = 100L;

    /** 頭部をプレイヤーに向ける範囲（ブロック）の二乗。 */
    private static final double HEAD_LOOK_RANGE_SQ = 25.0; // 5ブロック

    /** モブの目線高さ（足元からのオフセット）。 */
    private static final double MOB_EYE_HEIGHT = 1.0;

    private static final double COMBAT_VERTICAL_TOLERANCE = 1.25;

    private static final double COMBAT_RANGE_BUFFER = 0.25;

    private final MobService mobService;
    private final MobAiCalculator aiCalculator = new MobAiCalculator();

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
                    if (internalTick % THREAT_DECAY_INTERVAL_TICKS == 0L) {
                        instance.threatTable().decay(THREAT_DECAY_PER_TICK);
                    }

                    if (isLeashed(instance)) {
                        instance.state(MobState.LEASHED);
                    }

                    switch (instance.state()) {
                        case IDLE -> tickIdle(instance);
                        case AGGRO -> tickAggro(instance);
                        case COMBAT -> tickCombatHold(instance);
                        case LEASHED -> tickLeashed(instance);
                        case DEAD -> mobService.destroy(instance.instanceId());
                    }

                    if (instance.state() != MobState.DEAD) {
                        tickHeadLook(instance);
                    }
                } catch (RuntimeException ex) {
                    Logger.warn(LogId.W_5702, instance.instanceId());
                    mobService.destroy(instance.instanceId());
                }
            }

            mobService.updateViewers();
        } catch (RuntimeException ex) {
            Logger.error(LogId.E_5702, ex);
        }
    }

    /**
     * IDLE 状態の Mob を {@link IdleBehavior} に従って動かします。
     * aggroRange 内のプレイヤーを検知して AGGRO に遷移します。
     *
     * @param instance 対象 Mob
     */
    private void tickIdle(@NotNull MobInstance instance) {
        MobTemplate template = instance.template();
        MobTargetingConfig targeting = template.targeting();

        if (targeting != null && selectTarget(instance) != null) {
            instance.state(MobState.AGGRO);
            instance.clearNavPath();
            return;
        }

        if (template.idle().behavior() != IdleBehavior.WANDER) return;

        Location currentLoc = instance.currentLocation();
        Location wanderTarget = instance.wanderTarget();

        boolean needNewTarget = wanderTarget == null;
        if (!needNewTarget) {
            double dx = wanderTarget.getX() - currentLoc.getX();
            double dz = wanderTarget.getZ() - currentLoc.getZ();
            needNewTarget = dx * dx + dz * dz < 0.25; // 0.5ブロック以内で到達とみなす
        }
        if (!needNewTarget && internalTick % WANDER_TARGET_MAX_TICKS == 0L) {
            needNewTarget = true; // タイムアウトで新しいターゲット
        }

        if (needNewTarget) {
            instance.wanderTarget(null);
            instance.clearNavPath();
            if (instance.wanderPauseUntilTick() == 0L) {
                instance.wanderPauseUntilTick(internalTick + aiCalculator.randomWanderPauseTicks());
            }
            if (internalTick < instance.wanderPauseUntilTick()) {
                return;
            }

            double radius = template.idle().wanderRadius();
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            Location anchor = instance.wanderAnchor();
            double dx = (rng.nextDouble() - 0.5) * 2.0 * radius;
            double dz = (rng.nextDouble() - 0.5) * 2.0 * radius;
            instance.wanderTarget(anchor.clone().add(dx, 0.0, dz));
            instance.wanderPauseUntilTick(0L);
            instance.clearNavPath();
        }

        if (instance.wanderTarget() != null) {
            moveToward(instance, instance.wanderTarget(), template.idle().speed());
        }
    }

    /**
     * AGGRO 状態の Mob がターゲットへ接近します。
     *
     * @param instance 対象 Mob
     */
    private void tickAggro(@NotNull MobInstance instance) {
        MobTargetingConfig targeting = instance.template().targeting();
        if (targeting == null) {
            instance.state(MobState.IDLE);
            instance.clearNavPath();
            return;
        }

        Player target = resolveChaseTarget(instance);
        if (target == null) {
            instance.targetId(null);
            instance.state(MobState.IDLE);
            instance.clearNavPath();
            return;
        }

        double deaggroSq = targeting.deaggroRange() * targeting.deaggroRange();
        double distSq = target.getLocation().distanceSquared(instance.currentLocation());
        if (distSq > deaggroSq) {
            instance.targetId(null);
            instance.state(MobState.IDLE);
            instance.clearNavPath();
            return;
        }

        double preferredRange = instance.template().combat() == null
                ? 1.0
                : instance.template().combat().preferredRange();
        double preferredSq = preferredRange * preferredRange;
        Location targetLoc = target.getLocation();
        Location currentLoc = instance.currentLocation();
        double horizontalSq = horizontalDistanceSquared(currentLoc, targetLoc);
        double verticalDiff = Math.abs(targetLoc.getY() - currentLoc.getY());
        if (horizontalSq <= preferredSq + COMBAT_RANGE_BUFFER
                && verticalDiff <= COMBAT_VERTICAL_TOLERANCE) {
            instance.state(MobState.COMBAT);
        } else {
            moveToward(instance, targetLoc, instance.template().idle().speed());
        }
    }

    /**
     * COMBAT 状態では攻撃を行わず、射程内なら待機し、離れたら追跡へ戻します。
     *
     * @param instance 対象 Mob
     */
    private void tickCombatHold(@NotNull MobInstance instance) {
        Player target = resolveChaseTarget(instance);
        if (target == null) {
            instance.state(MobState.AGGRO);
            return;
        }

        double preferredRange = instance.template().combat() == null
                ? 1.0
                : instance.template().combat().preferredRange();
        double preferredSq = preferredRange * preferredRange;
        Location targetLoc = target.getLocation();
        Location currentLoc = instance.currentLocation();
        double horizontalSq = horizontalDistanceSquared(currentLoc, targetLoc);
        double verticalDiff = Math.abs(targetLoc.getY() - currentLoc.getY());
        if (horizontalSq > preferredSq + COMBAT_RANGE_BUFFER
                || verticalDiff > COMBAT_VERTICAL_TOLERANCE) {
            instance.clearNavPath();
            instance.state(MobState.AGGRO);
        }
    }

    /**
     * LEASHED 状態の Mob をスポーン地点へ移動させます。到達したら IDLE に復帰します。
     *
     * @param instance 対象 Mob
     */
    private void tickLeashed(@NotNull MobInstance instance) {
        Location spawn = instance.spawnLocation();
        Location current = instance.currentLocation();
        if (current.distanceSquared(spawn) <= 1.0) {
            instance.currentLocation(spawn);
            instance.state(MobState.IDLE);
            instance.targetId(null);
            instance.clearNavPath();
            instance.threatTable().snapshot().keySet().forEach(id -> instance.threatTable().remove(id));
            return;
        }
        moveToward(instance, spawn, instance.template().idle().speed());
    }

    /**
     * 視認中のプレイヤーのうち 5m 以内で最も近いプレイヤーに頭部を向けます。
     * 該当プレイヤーがいない場合は頭部を体の向きに合わせます。
     *
     * @param instance 対象 Mob
     */
    private void tickHeadLook(@NotNull MobInstance instance) {
        Set<UUID> viewerIds = mobService.getViewers(instance.instanceId());
        Location mobLoc = instance.currentLocation();

        Player nearest = null;
        double nearestSq = HEAD_LOOK_RANGE_SQ;

        for (UUID viewerId : viewerIds) {
            Player player = Bukkit.getPlayer(viewerId);
            if (player == null || !player.isOnline()) continue;
            if (player.getWorld() != mobLoc.getWorld()) continue;
            double sq = player.getLocation().distanceSquared(mobLoc);
            if (sq < nearestSq) {
                nearestSq = sq;
                nearest = player;
            }
        }

        if (nearest != null) {
            Location playerLoc = nearest.getLocation();
            double dx = playerLoc.getX() - mobLoc.getX();
            double dy = (playerLoc.getY() + nearest.getEyeHeight()) - (mobLoc.getY() + MOB_EYE_HEIGHT);
            double dz = playerLoc.getZ() - mobLoc.getZ();
            float headYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float headPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            instance.headYaw(headYaw);
            instance.headPitch(headPitch);
        } else {
            // 近くにプレイヤーがいない場合は体の向きと一致させる
            instance.headYaw(mobLoc.getYaw());
            instance.headPitch(0.0f);
        }
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
     * ターゲット位置へ向けて 1 tick 分移動します。
     *
     * <p>A* 経路探索を用いてブロック衝突を考慮した経路を計算します。
     * 経路が見つからない場合はターゲットへの直線フォールバックを使用します。
     * 移動方向に体（yaw）を向け、移動後の位置をブロック表面に合わせます。</p>
     *
     * @param instance 対象 Mob
     * @param target   目標位置
     * @param speed    AI 設定側の速度倍率
     */
    private void moveToward(@NotNull MobInstance instance, @NotNull Location target, double speed) {
        aiCalculator.moveToward(instance, target, speed, internalTick);
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
