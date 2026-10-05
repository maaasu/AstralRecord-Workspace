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
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code mob_eriva_bark_root_sweep}: 詠唱開始方向を中心に前方90度・4mを20tick予告し、近接・無属性90%で一度薙ぎ払います。
 * 予告中心は詠唱終了時のMob足元に固定し、背後・高低差1.25m超・壁の向こうへは当たりません。
 * paramsは受け付けません。24tick詠唱、120tick再使用、発動距離4mが既定です。
 */
public final class ErivaBarkRootSweepMobSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_eriva_bark_root_sweep";
    private final MobService mobService;
    private final DamageService damageService;
    private final ConditionService conditionService;
    private final ParticleDisplayService particles;

    /**
     * @param mobService Mob実体とSchedulerの取得先
     * @param damageService 範囲候補とダメージ適用先
     * @param conditionService 行動不能状態の取得先
     * @param particles 予告・根打ちの描画先
     */
    public ErivaBarkRootSweepMobSkillExecutor(@NotNull MobService mobService, @NotNull DamageService damageService,
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
    @Override public @NotNull String displayName() { return "根枝の薙ぎ払い"; }
    /** @return 4m・120tick再使用・24tick詠唱 */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(4.0D, 120L, 24L); }

    /** @param context 詠唱開始時の方向と主対象 @return 固定扇形の予告を開始できた場合true */
    @Override
    public boolean cast(@NotNull MobSkillContext context) {
        Entity entity = ErivaForestSkillSupport.activeCaster(context, mobService, damageService, conditionService, false);
        Vector direction = new Vector(context.direction().getX(), 0.0D, context.direction().getZ());
        if (entity == null || direction.lengthSquared() < 0.01D) {
            return false;
        }
        direction.normalize();
        Location center = entity.getLocation();
        List<Location> points = new ArrayList<>(24);
        double facing = Math.atan2(direction.getZ(), direction.getX());
        for (int row = 1; row <= 3; row++) {
            for (int column = 0; column < 8; column++) {
                double angle = facing - Math.PI / 4.0D + Math.PI / 2.0D * column / 7.0D;
                double radius = 4.0D * row / 3.0D;
                points.add(center.clone().add(Math.cos(angle) * radius, 0.1D, Math.sin(angle) * radius));
            }
        }
        center.getWorld().playSound(center, Sound.BLOCK_ROOTED_DIRT_BREAK, 0.9F, 0.65F);
        ErivaForestSkillSupport.warnThenImpact(context, mobService, damageService, conditionService, particles,
                center, points, SharedParticleDefinitions.MOB_ERIVA_ROOT_WARNING,
                () -> impact(context, center, direction, points));
        return true;
    }

    /** 固定した扇形内にいる有効対象へ、遮蔽物を確認して一度だけダメージを与えます。 */
    private void impact(@NotNull MobSkillContext context, @NotNull Location center,
                        @NotNull Vector direction, @NotNull List<Location> points) {
        particles.spawnForNearbyViewers(center, points, SharedParticleDefinitions.MOB_ERIVA_ROOT_IMPACT);
        center.getWorld().playSound(center, Sound.ENTITY_IRON_GOLEM_ATTACK, 0.9F, 0.65F);
        for (LivingEntity target : damageService.mobCombatTargets(center, 4.0D, 1.25D)) {
            Vector offset = target.getLocation().toVector().subtract(center.toVector()).setY(0);
            double length = offset.length();
            if (length > 4.0D || Math.abs(target.getLocation().getY() - center.getY()) > 1.25D
                    || (length > 0.01D && direction.dot(offset.multiply(1.0D / length)) < Math.cos(Math.PI / 4.0D))
                    || !ErivaForestSkillSupport.hasClearPath(center, target)) {
                continue;
            }
            damageService.attack(AstEntity.mob(context.mob()), damageService.resolveEntity(target), AttackType.MELEE,
                    List.of(new DamageComponent(DamageElement.NONE, 0.9D)), DamageSource.SKILL);
        }
    }
}
