package io.github.maaasu.astralRecord.feature.mob.skill.eriva;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * {@code mob_eriva_thornfang_pounce}: 詠唱開始方向へ最大6m直進し、主対象へ近接・無属性85%の一撃を与えます。
 * 移動速度は0.75m/tick、主対象のhitboxを0.25m拡張して判定します。壁または進路外への回避で空振りします。
 * paramsは受け付けません。16tick詠唱、120tick再使用、発動距離6mが既定です。
 */
public final class ErivaThornfangPounceMobSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_eriva_thornfang_pounce";
    private final MobService mobService;
    private final DamageService damageService;
    private final ConditionService conditionService;
    private final ParticleDisplayService particles;

    /**
     * @param mobService Mob実体とSchedulerの取得先
     * @param damageService 命中判定・ダメージ適用先
     * @param conditionService 行動不能状態の取得先
     * @param particles 突進・命中演出の描画先
     */
    public ErivaThornfangPounceMobSkillExecutor(@NotNull MobService mobService, @NotNull DamageService damageService,
                                               @NotNull ConditionService conditionService,
                                               @NotNull ParticleDisplayService particles) {
        this.mobService = mobService;
        this.damageService = damageService;
        this.conditionService = conditionService;
        this.particles = particles;
    }

    /** @return Mobマスタから参照する専用技ID */
    @Override public @NotNull String id() { return SKILL_ID; }
    /** @return 頭上詠唱バーの技名 */
    @Override public @NotNull String displayName() { return "棘牙の飛び掛かり"; }
    /** @return 6m・120tick再使用・16tick詠唱 */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(6.0D, 120L, 16L); }

    /** @param context 詠唱開始時の方向と主対象 @return 突進を開始できた場合true */
    @Override
    public boolean cast(@NotNull MobSkillContext context) {
        Entity entity = ErivaForestSkillSupport.activeCaster(context, mobService, damageService, conditionService, true);
        Vector direction = new Vector(context.direction().getX(), 0.0D, context.direction().getZ());
        if (entity == null || direction.lengthSquared() < 0.01D) {
            return false;
        }
        direction.normalize();
        Location start = entity.getLocation();
        context.mob().scriptedAction(true);
        start.getWorld().playSound(start, Sound.ENTITY_WOLF_GROWL, 0.8F, 0.8F);
        new BukkitRunnable() {
            private Location position = start.clone();
            private int steps;

            @Override
            public void run() {
                Entity active = ErivaForestSkillSupport.activeCaster(context, mobService, damageService, conditionService, true);
                if (active == null || steps >= 8) {
                    finish();
                    return;
                }
                Vector movement = direction.clone().multiply(0.75D);
                Location next = position.clone().add(movement);
                // 壁の角でも実体が食い込まないよう、幅・高さを含めて区間を検査します。
                if (!ErivaForestSkillSupport.isRushStepClear(active, position, next)
                        || next.clone().add(0, -0.5D, 0).getBlock().isPassable()) {
                    finish();
                    return;
                }
                Location traceStart = position.clone().add(0, 0.5D, 0);
                RayTraceResult hit = context.target().getBoundingBox().expand(0.25D)
                        .rayTrace(traceStart.toVector(), direction, 0.75D);
                boolean overlapping = context.target().getBoundingBox().expand(0.25D).contains(traceStart.toVector());
                particles.spawnForNearbyViewers(traceStart, SharedParticleDefinitions.MOB_ERIVA_THORNFANG_TRAIL);
                if (hit != null || overlapping) {
                    var result = damageService.attack(AstEntity.mob(context.mob()), damageService.resolveEntity(context.target()),
                            AttackType.MELEE, List.of(new DamageComponent(DamageElement.NONE, 0.85D)), DamageSource.SKILL);
                    if (!result.evaded() && (result.finalDamage() > 0 || result.shieldDamage() > 0)) {
                        particles.spawnForNearbyViewers(context.target().getLocation().add(0, 0.6D, 0),
                                SharedParticleDefinitions.MOB_ERIVA_THORNFANG_HIT);
                        next.getWorld().playSound(next, Sound.ENTITY_FOX_BITE, 0.9F, 0.7F);
                    }
                    finish();
                    return;
                }
                if (!active.teleport(next)) {
                    finish();
                    return;
                }
                context.mob().currentLocation(next);
                position = next;
                steps++;
            }

            /** 通常AIの停止を解除し、最大8回の突進taskを終了します。 */
            private void finish() {
                cancel();
                context.mob().scriptedAction(false);
            }
        }.runTaskTimer(mobService.plugin(), 0L, 1L);
        return true;
    }
}
