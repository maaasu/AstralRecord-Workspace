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
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mob の AI を tick 単位で駆動するサービス。
 *
 * <p>このサービスは、マスタデータの AI 定義に基づく待機移動・追跡・帰還と、
 * パケット表示の更新のみを担当します。攻撃・ドロップ処理はこの段階では実行しません。</p>
 */
public class MobAiService {

    /** 脅威値の減衰係数（1 tick あたり）。100 tick で約 0.37 まで減衰する。 */
    private static final double THREAT_DECAY_PER_TICK = 0.99;

    /** 脅威値を減衰させる間隔（tick）。 */
    private static final long THREAT_DECAY_INTERVAL_TICKS = 100L;

    /** 1 tick あたりの基準移動量（ブロック）。 */
    private static final double BASE_STEP_PER_TICK = 0.25;

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
     * 同時に aggroRange 内のプレイヤーを検知して AGGRO に遷移します。
     *
     * @param instance 対象 Mob
     */
    private void tickIdle(@NotNull MobInstance instance) {
        MobTemplate template = instance.template();
        MobTargetingConfig targeting = template.targeting();

        if (targeting != null && selectTarget(instance) != null) {
            instance.state(MobState.AGGRO);
            return;
        }

        IdleBehavior behavior = template.idle().behavior();
        if (behavior == IdleBehavior.WANDER) {
            if (internalTick % 30L != 0L) return;
            double radius = template.idle().wanderRadius();
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            Location anchor = instance.wanderAnchor();
            double dx = (rng.nextDouble() - 0.5) * 2.0 * radius;
            double dz = (rng.nextDouble() - 0.5) * 2.0 * radius;
            Location next = anchor.clone().add(dx, 0.0, dz);
            moveToward(instance, next, template.idle().speed());
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
            return;
        }

        Player target = resolveChaseTarget(instance);
        if (target == null) {
            instance.targetId(null);
            instance.state(MobState.IDLE);
            return;
        }

        double deaggroSq = targeting.deaggroRange() * targeting.deaggroRange();
        double distSq = target.getLocation().distanceSquared(instance.currentLocation());
        if (distSq > deaggroSq) {
            instance.targetId(null);
            instance.state(MobState.IDLE);
            return;
        }

        double preferredRange = instance.template().combat() == null
                ? 1.0
                : instance.template().combat().preferredRange();
        double preferredSq = preferredRange * preferredRange;
        if (distSq <= preferredSq + 1.0) {
            instance.state(MobState.COMBAT);
        } else {
            moveToward(instance, target.getLocation(), instance.template().idle().speed());
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
        if (target.getLocation().distanceSquared(instance.currentLocation()) > preferredSq + 1.0) {
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
     * 対象位置へ移動します。
     *
     * @param instance 対象 Mob
     * @param target   目標位置
     * @param speed    移動速度倍率
     */
    private void moveToward(@NotNull MobInstance instance, @NotNull Location target, double speed) {
        Location current = instance.currentLocation();
        if (current.getWorld() != target.getWorld()) return;

        double dx = target.getX() - current.getX();
        double dy = target.getY() - current.getY();
        double dz = target.getZ() - current.getZ();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 0.1) return;

        double step = BASE_STEP_PER_TICK * Math.max(0.0, speed);
        if (step <= 0.0) return;
        double scale = Math.min(step / length, 1.0);
        Location next = current.clone().add(dx * scale, dy * scale, dz * scale);
        instance.currentLocation(next);
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
