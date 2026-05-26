package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.IdleBehavior;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mob の AI を tick 単位で駆動するサービス。
 *
 * <p>{@link BukkitTask} で 1 tick 周期に {@link #tick()} を実行し、
 * 全 Mob インスタンスの状態遷移と表示更新を行う。</p>
 */
public class MobAiService {

    /** 脅威値の減衰係数（1 tick あたり）。100 tick で約 0.37 まで減衰する。 */
    private static final double THREAT_DECAY_PER_TICK = 0.99;

    /** 脅威値を減衰させる間隔（tick）。 */
    private static final long THREAT_DECAY_INTERVAL_TICKS = 100L;

    private final MobService mobService;
    private final MobCombatService combatService;

    private BukkitTask task;
    private long internalTick;

    /**
     * コンストラクタ。
     *
     * @param mobService    Mob サービス
     * @param combatService 戦闘サービス
     */
    public MobAiService(@NotNull MobService mobService, @NotNull MobCombatService combatService) {
        this.mobService = mobService;
        this.combatService = combatService;
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
            List<MobInstance> deathQueue = new ArrayList<>();
            long serverTick = internalTick;

            for (MobInstance instance : snapshot) {
                try {
                    if (instance.state() == MobState.DEAD) {
                        deathQueue.add(instance);
                        continue;
                    }

                    // 脅威値の減衰
                    if (internalTick % THREAT_DECAY_INTERVAL_TICKS == 0L) {
                        instance.threatTable().decay(THREAT_DECAY_PER_TICK);
                    }

                    // leashRange 判定
                    if (isLeashed(instance)) {
                        instance.state(MobState.LEASHED);
                    }

                    switch (instance.state()) {
                        case IDLE -> tickIdle(instance);
                        case AGGRO -> tickAggro(instance);
                        case COMBAT -> {
                            combatService.tickCombat(instance, serverTick);
                            // tickCombat 内で state が変わる可能性がある
                        }
                        case LEASHED -> tickLeashed(instance);
                        case DEAD -> deathQueue.add(instance);
                    }
                } catch (RuntimeException ex) {
                    Logger.warn(LogId.W_5702, instance.instanceId());
                    instance.state(MobState.DEAD);
                    deathQueue.add(instance);
                }
            }

            // 死亡キュー処理
            for (MobInstance dead : deathQueue) {
                try {
                    combatService.handleDeath(dead);
                } catch (RuntimeException ex) {
                    Logger.error(LogId.E_5703, ex, dead.template().id());
                    mobService.destroy(dead.instanceId());
                }
            }

            // 表示範囲更新
            mobService.updateViewers();
        } catch (RuntimeException ex) {
            Logger.error(LogId.E_5702, ex);
        }
    }

    /**
     * IDLE 状態の Mob を {@link IdleBehavior} に従って動かします。
     * 同時に aggroRange 内のプレイヤーを検知して AGGRO に遷移します。
     */
    private void tickIdle(@NotNull MobInstance instance) {
        MobTemplate template = instance.template();
        MobTargetingConfig targeting = template.targeting();

        // 接敵チェック
        if (targeting != null) {
            Player target = combatService.selectTarget(instance);
            if (target != null) {
                instance.state(MobState.AGGRO);
                return;
            }
        }

        // 待機行動
        IdleBehavior behavior = template.idle().behavior();
        if (behavior == IdleBehavior.WANDER) {
            // 30 tick に 1 回程度ランダムに移動する（負荷抑制）
            if (internalTick % 30L != 0L) return;
            double radius = template.idle().wanderRadius();
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            Location anchor = instance.wanderAnchor();
            double dx = (rng.nextDouble() - 0.5) * 2.0 * radius;
            double dz = (rng.nextDouble() - 0.5) * 2.0 * radius;
            Location next = anchor.clone().add(dx, 0.0, dz);
            instance.currentLocation(next);
        }
        // STATIONARY / PATROL は当面何もしない
    }

    /**
     * AGGRO 状態の Mob のターゲット追跡を行います。
     */
    private void tickAggro(@NotNull MobInstance instance) {
        MobTargetingConfig targeting = instance.template().targeting();
        if (targeting == null) {
            instance.state(MobState.IDLE);
            return;
        }

        Player target = combatService.selectTarget(instance);
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
            // ターゲットに近づく（線形補間。経路探索なし）
            moveToward(instance, target.getLocation());
        }
    }

    /**
     * LEASHED 状態の Mob をスポーン地点へ移動させます。到達したら IDLE に復帰します。
     */
    private void tickLeashed(@NotNull MobInstance instance) {
        Location spawn = instance.spawnLocation();
        Location current = instance.currentLocation();
        if (current.distanceSquared(spawn) <= 1.0) {
            instance.currentLocation(spawn);
            instance.state(MobState.IDLE);
            instance.threatTable().snapshot().keySet().forEach(id -> instance.threatTable().remove(id));
            return;
        }
        moveToward(instance, spawn);
    }

    /**
     * スポーン地点から leashRange を超えているか判定します。
     */
    private boolean isLeashed(@NotNull MobInstance instance) {
        MobTargetingConfig targeting = instance.template().targeting();
        if (targeting == null) return false;
        double leashSq = targeting.leashRange() * targeting.leashRange();
        return instance.currentLocation().distanceSquared(instance.spawnLocation()) > leashSq;
    }

    /**
     * 対象位置へ最大 0.25 ブロック分接近します。
     */
    private void moveToward(@NotNull MobInstance instance, @NotNull Location target) {
        Location current = instance.currentLocation();
        if (current.getWorld() != target.getWorld()) return;

        double dx = target.getX() - current.getX();
        double dy = target.getY() - current.getY();
        double dz = target.getZ() - current.getZ();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 0.1) return;

        double step = 0.25;
        double scale = Math.min(step / length, 1.0);
        Location next = current.clone().add(dx * scale, dy * scale, dz * scale);
        instance.currentLocation(next);
    }
}
