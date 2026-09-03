package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillBinding;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillRegistry;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Mob 専用スキルの詠唱、クールダウン、Executor呼び出しを管理します。 */
public final class MobSkillService {

    /** 上下照準を持たない地上戦スキルに許容する高低差。 */
    public static final double GROUND_TARGET_VERTICAL_TOLERANCE = 1.25D;

    private final MobService mobService;
    private final MobSkillRegistry registry;
    private final Map<UUID, Map<String, Long>> cooldownUntilByMob = new HashMap<>();
    private final Map<UUID, BukkitTask> castingTasks = new HashMap<>();
    private @Nullable ConditionService conditionService;

    /** Mob サービスとMob専用レジストリを指定して構築します。 */
    public MobSkillService(@NotNull MobService mobService, @NotNull MobSkillRegistry registry) {
        this.mobService = mobService;
        this.registry = registry;
    }

    /** 行動不能・詠唱時間補正の参照先を設定します。 */
    public void setConditionService(@Nullable ConditionService conditionService) {
        this.conditionService = conditionService;
    }

    /**
     * 次に使用するスキルの発動開始距離を返します。
     *
     * @param binding Mob マスター上のスキル紐付け
     * @param fallback 登録不備時のフォールバック距離
     * @return 発動開始距離
     */
    public double activationRange(@NotNull MobSkillBinding binding, double fallback) {
        MobSkillExecutor executor = registry.find(binding.id());
        return executor == null ? Math.max(0.0D, fallback) : executor.resolveTiming(binding).activationRange();
    }

    /**
     * Mob が現在位置から指定スキルの発動距離内にいるかを判定します。
     *
     * <p>上下照準を許可するExecutorは三次元距離、それ以外は地上戦用の高低差制限と水平距離で判定します。
     * 未登録のスキルは、通常攻撃へフォールバックできるよう指定された距離と地上戦判定で扱います。</p>
     *
     * @param instance      発動するMob
     * @param binding       Mobマスター上のスキル紐付け
     * @param target        発動候補の対象
     * @param fallbackRange Executor未登録時に使用する発動距離
     * @return 現在位置から発動距離内の場合は {@code true}
     */
    public boolean isWithinActivationRange(
            @NotNull MobInstance instance,
            @NotNull MobSkillBinding binding,
            @NotNull Player target,
            double fallbackRange
    ) {
        MobSkillExecutor executor = registry.find(binding.id());
        double activationRange = executor == null
                ? Math.max(0.0D, fallbackRange)
                : executor.resolveTiming(binding).activationRange();
        return isWithinActivationRange(
                instance,
                target,
                activationRange,
                executor != null && executor.allowsVerticalTargeting()
        );
    }

    /**
     * Mob スキルの詠唱または即時発動を試みます。
     *
     * @param instance   発動するMob
     * @param binding    Mob マスター上のスキル紐付け
     * @param target     発動開始時の対象
     * @param serverTick 現在のAI tick
     * @return 詠唱開始または即時発動に成功した場合は {@code true}
     */
    public boolean tryCast(
            @NotNull MobInstance instance,
            @NotNull MobSkillBinding binding,
            @NotNull Player target,
            long serverTick
    ) {
        MobSkillExecutor executor = registry.find(binding.id());
        if (executor == null || instance.isSkillCasting() || !isGameplayTargetPlayer(target)) {
            return false;
        }
        try {
            executor.validate(binding);
            MobSkillTiming timing = effectiveTiming(instance, executor.resolveTiming(binding));
            if (isOnCooldown(instance.instanceId(), binding.id(), serverTick)) {
                return false;
            }
            if (!isWithinActivationRange(
                    instance,
                    target,
                    timing.activationRange(),
                    executor.allowsVerticalTargeting()
            )) {
                return false;
            }
            Location origin = instance.currentLocation().add(0.0D, 1.35D, 0.0D);
            Vector direction = target.getEyeLocation().toVector().subtract(origin.toVector());
            MobSkillContext context = new MobSkillContext(instance, target, binding, timing, origin, direction);
            if (timing.castTimeTicks() <= 0L) {
                if (!executor.cast(context)) {
                    return false;
                }
                startCooldown(instance.instanceId(), binding.id(), serverTick, timing.cooldownTicks());
                return true;
            }
            beginCast(instance, binding.id(), executor, context, serverTick);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /** 指定Mobの詠唱・クールダウンを破棄します。 */
    public void clearCasterState(@NotNull UUID mobInstanceId) {
        BukkitTask task = castingTasks.remove(mobInstanceId);
        if (task != null) {
            task.cancel();
        }
        MobInstance instance = mobService.getInstance(mobInstanceId);
        if (instance != null) {
            instance.clearSkillCasting();
        }
        cooldownUntilByMob.remove(mobInstanceId);
    }

    /** Plugin停止時に残っている全Mobスキル状態を破棄します。 */
    public void stop() {
        for (UUID mobInstanceId : java.util.List.copyOf(castingTasks.keySet())) {
            clearCasterState(mobInstanceId);
        }
        cooldownUntilByMob.clear();
    }

    private @NotNull MobSkillTiming effectiveTiming(@NotNull MobInstance instance, @NotNull MobSkillTiming timing) {
        double multiplier = conditionService == null
                ? 1.0D
                : conditionService.castTimeMultiplier(AstEntity.mob(instance));
        return new MobSkillTiming(
                timing.activationRange(),
                timing.cooldownTicks(),
                (long) Math.ceil(timing.castTimeTicks() * Math.max(0.0D, multiplier))
        );
    }

    private void beginCast(
            @NotNull MobInstance instance,
            @NotNull String skillId,
            @NotNull MobSkillExecutor executor,
            @NotNull MobSkillContext context,
            long serverTick
    ) {
        long castTimeTicks = context.timing().castTimeTicks();
        instance.startSkillCasting(executor.displayName(), castTimeTicks);
        startCooldown(instance.instanceId(), skillId, serverTick, context.timing().cooldownTicks());
        BukkitRunnable runnable = new BukkitRunnable() {
            private long elapsedTicks;

            @Override
            public void run() {
                MobInstance active = mobService.getInstance(instance.instanceId());
                if (active != instance || active.state() == MobState.DEAD || !context.target().isOnline()
                        || !isGameplayTargetPlayer(context.target())) {
                    finish();
                    return;
                }
                elapsedTicks++;
                active.updateSkillCastingRemaining(Math.max(0L, castTimeTicks - elapsedTicks));
                if (elapsedTicks >= castTimeTicks) {
                    executor.cast(context);
                    finish();
                }
            }

            private void finish() {
                cancel();
                castingTasks.remove(instance.instanceId());
                MobInstance active = mobService.getInstance(instance.instanceId());
                if (active != null) {
                    active.clearSkillCasting();
                }
            }
        };
        BukkitTask task = runnable.runTaskTimer(mobService.plugin(), 0L, 1L);
        castingTasks.put(instance.instanceId(), task);
    }

    private boolean isOnCooldown(@NotNull UUID mobInstanceId, @NotNull String skillId, long serverTick) {
        return cooldownUntilByMob.getOrDefault(mobInstanceId, Map.of()).getOrDefault(skillId, Long.MIN_VALUE) > serverTick;
    }

    private boolean isGameplayTargetPlayer(@NotNull Player player) {
        return player.getUniqueId() != null && AccountModeGuard.isGameplayPlayer(player);
    }

    private void startCooldown(@NotNull UUID mobInstanceId, @NotNull String skillId, long serverTick, long cooldownTicks) {
        cooldownUntilByMob.computeIfAbsent(mobInstanceId, ignored -> new HashMap<>())
                .put(skillId, serverTick + Math.max(0L, cooldownTicks));
    }

    private boolean isWithinActivationRange(
            @NotNull MobInstance instance,
            @NotNull Player target,
            double activationRange,
            boolean allowsVerticalTargeting
    ) {
        Location mobLocation = instance.currentLocation();
        Location targetLocation = target.getLocation();
        if (mobLocation.getWorld() != targetLocation.getWorld()) {
            return false;
        }
        double x = mobLocation.getX() - targetLocation.getX();
        double y = mobLocation.getY() - targetLocation.getY();
        double z = mobLocation.getZ() - targetLocation.getZ();
        if (allowsVerticalTargeting) {
            return x * x + y * y + z * z <= activationRange * activationRange;
        }
        if (Math.abs(y) > GROUND_TARGET_VERTICAL_TOLERANCE) {
            return false;
        }
        return x * x + z * z <= activationRange * activationRange;
    }
}
