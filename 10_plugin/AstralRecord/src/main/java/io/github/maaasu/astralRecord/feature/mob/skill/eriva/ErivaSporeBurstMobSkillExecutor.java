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
import org.bukkit.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code mob_eriva_spore_burst}: 詠唱終了時の主対象の足元へ半径2.5mの胞子輪を固定し、20tick後に魔法・無属性100%で一度破裂します。
 * 高低差1.25m超と壁の向こうへは当たらず、状態異常や持続ダメージは与えません。
 * paramsは受け付けません。24tick詠唱、50tick再使用、発動距離9mが既定です。
 */
public final class ErivaSporeBurstMobSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_eriva_spore_burst";
    private final MobService mobService;
    private final DamageService damageService;
    private final ConditionService conditionService;
    private final ParticleDisplayService particles;

    /**
     * @param mobService Mob実体とSchedulerの取得先
     * @param damageService 範囲候補とダメージ適用先
     * @param conditionService 行動不能状態の取得先
     * @param particles 予告・胞子破裂の描画先
     */
    public ErivaSporeBurstMobSkillExecutor(@NotNull MobService mobService, @NotNull DamageService damageService,
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
    @Override public @NotNull String displayName() { return "胞子の破裂輪"; }
    /** @return 9m・50tick再使用・24tick詠唱 */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(9.0D, 50L, 24L); }

    /** @param context 詠唱開始時の主対象 @return 足元の固定予告を開始できた場合true */
    @Override
    public boolean cast(@NotNull MobSkillContext context) {
        Entity entity = ErivaForestSkillSupport.activeCaster(context, mobService, damageService, conditionService, false);
        if (entity == null || entity.getLocation().distanceSquared(context.target().getLocation()) > 81.0D
                || !ErivaForestSkillSupport.hasClearPath(entity.getLocation(), context.target())) {
            return false;
        }
        Location center = context.target().getLocation();
        List<Location> points = new ArrayList<>(24);
        for (int index = 0; index < 24; index++) {
            double angle = Math.PI * 2.0D * index / 24.0D;
            points.add(center.clone().add(Math.cos(angle) * 2.5D, 0.1D, Math.sin(angle) * 2.5D));
        }
        center.getWorld().playSound(center, Sound.BLOCK_FUNGUS_PLACE, 0.9F, 0.65F);
        ErivaForestSkillSupport.warnThenImpact(context, mobService, damageService, conditionService, particles,
                center, points, SharedParticleDefinitions.MOB_ERIVA_SPORE_WARNING,
                () -> impact(context, center, points));
        return true;
    }

    /** 固定した胞子輪の中へ一度だけ魔法ダメージを与えます。 */
    private void impact(@NotNull MobSkillContext context, @NotNull Location center, @NotNull List<Location> points) {
        particles.spawnForNearbyViewers(center, points, SharedParticleDefinitions.MOB_ERIVA_SPORE_IMPACT);
        particles.spawnForNearbyViewers(center.clone().add(0, 0.5D, 0), SharedParticleDefinitions.MOB_ERIVA_SPORE_CLOUD);
        center.getWorld().playSound(center, Sound.BLOCK_SLIME_BLOCK_BREAK, 0.9F, 1.2F);
        for (LivingEntity target : damageService.mobCombatTargets(center, 2.5D, 1.25D)) {
            double x = target.getLocation().getX() - center.getX();
            double z = target.getLocation().getZ() - center.getZ();
            if (x * x + z * z > 6.25D || Math.abs(target.getLocation().getY() - center.getY()) > 1.25D
                    || !ErivaForestSkillSupport.hasClearPath(center, target)) {
                continue;
            }
            damageService.attack(AstEntity.mob(context.mob()), damageService.resolveEntity(target), AttackType.MAGIC,
                    List.of(new DamageComponent(DamageElement.NONE, 1.0D)), DamageSource.SKILL);
        }
    }
}
