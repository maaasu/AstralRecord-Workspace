package io.github.maaasu.astralRecord.feature.mob.skill.witchforest;

import io.github.maaasu.astralRecord.feature.boss.service.BossParticleEffect;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 魔女の森の固定予告を管理し、帰還・行動不能・破棄・停止時に回収します。 */
public final class WitchForestSkillSupport {
    private final MobService mobService;
    private final DamageService damageService;
    private final ConditionService conditionService;
    private final ParticleDisplayService particles;
    private final Map<UUID, BukkitTask> warningTasks = new HashMap<>();

    /**
     * 同期スケジューラーと戦闘状態・演出の依存先を指定します。
     * @param mobService Mob実体と同期スケジューラーの取得先
     * @param damageService 主対象の有効性判定先
     * @param conditionService 行動不能の判定先
     * @param particles 閲覧者ごとの粒子描画先
     */
    public WitchForestSkillSupport(@NotNull MobService mobService, @NotNull DamageService damageService,
            @NotNull ConditionService conditionService, @NotNull ParticleDisplayService particles) {
        this.mobService = mobService;
        this.damageService = damageService;
        this.conditionService = conditionService;
        this.particles = particles;
    }

    /** 同じ戦闘・主対象・ワールドで行動可能な発動者だけを返します。 */
    @Nullable Entity activeCaster(@NotNull MobSkillContext context) {
        var mob = context.mob();
        if (mob.state() != MobState.COMBAT
                || !context.target().getUniqueId().equals(mob.targetId())
                || !damageService.isMobCombatTarget(context.target())
                || context.target().getWorld() != context.origin().getWorld()) {
            return null;
        }
        return impactCaster(context);
    }

    /** 効果開始後は主対象の撃破に依存せず、発動者自身の存続・行動可能性だけを確認します。 */
    @Nullable Entity impactCaster(@NotNull MobSkillContext context) {
        var mob = context.mob();
        if (!mobService.plugin().isEnabled() || mobService.getInstance(mob.instanceId()) != mob
                || mob.state() == MobState.DEAD || mob.currentHealth() <= 0.0D) return null;
        Entity entity = mobService.entityController().getEntity(mob);
        AstEntity caster = AstEntity.mob(mob);
        return entity != null && entity.isValid() && !entity.isDead()
                && entity.getWorld() == context.origin().getWorld()
                && conditionService.canRunAi(caster) && conditionService.canCastSkill(caster)
                && conditionService.canAttack(caster) ? entity : null;
    }

    /**
     * 24点以下の固定予告を5tickごとに表示し、20tick後に一度だけ効果を実行します。
     * 予告中は通常AIを停止し、終了・中断・破棄時に解除します。
     */
    void warnThenImpact(@NotNull MobSkillContext context, @NotNull Location center,
            @NotNull List<Location> points, @NotNull SharedParticleDefinition warning,
            @NotNull Runnable impact) {
        clearCasterState(context.mob().instanceId());
        context.mob().scriptedAction(true);
        BukkitRunnable runnable = new BukkitRunnable() {
            private int elapsed;

            /** 戦闘継続を確認し、予告の完了時にだけ効果を実行します。 */
            @Override public void run() {
                if (activeCaster(context) == null) {
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
                    particles.spawnForNearbyViewers(center, points,
                            BossParticleEffect.attackWarning(context.mob(), warning));
                }
            }

            /** タスクと通常AIの停止を同時に解除します。 */
            private void finish() {
                cancel();
                warningTasks.remove(context.mob().instanceId());
                context.mob().scriptedAction(false);
            }
        };
        warningTasks.put(context.mob().instanceId(), runnable.runTaskTimer(mobService.plugin(), 5L, 5L));
        particles.spawnForNearbyViewers(center, points, BossParticleEffect.attackWarning(context.mob(), warning));
    }

    /** 対象の胴体までの経路を調べ、壁越しの範囲命中を防ぎます。 */
    static boolean hasClearPath(@NotNull Location origin, @NotNull LivingEntity target) {
        if (origin.getWorld() == null || origin.getWorld() != target.getWorld()) return false;
        Location from = origin.clone().add(0.0D, 0.5D, 0.0D);
        Location to = target.getLocation().add(0.0D, Math.min(1.0D, target.getHeight() * 0.5D), 0.0D);
        Vector direction = to.toVector().subtract(from.toVector());
        double distance = direction.length();
        return distance < 0.01D || from.getWorld().rayTraceBlocks(from, direction, distance,
                FluidCollisionMode.NEVER, true) == null;
    }

    /**
     * 発動者の破棄時に残った予告と通常AIの停止を解除します。
     * @param mobInstanceId 回収するMobインスタンスID
     */
    public void clearCasterState(@NotNull UUID mobInstanceId) {
        BukkitTask task = warningTasks.remove(mobInstanceId);
        if (task != null) {
            task.cancel();
            var mob = mobService.getInstance(mobInstanceId);
            if (mob != null) mob.scriptedAction(false);
        }
    }

    /** Plugin停止時にすべての予告タスクと保持参照を回収します。 */
    public void stop() {
        for (UUID mobInstanceId : List.copyOf(warningTasks.keySet())) clearCasterState(mobInstanceId);
    }
}
