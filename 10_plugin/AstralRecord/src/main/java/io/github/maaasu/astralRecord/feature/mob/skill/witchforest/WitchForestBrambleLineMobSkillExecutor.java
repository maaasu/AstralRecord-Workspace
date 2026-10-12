package io.github.maaasu.astralRecord.feature.mob.skill.witchforest;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
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
 * {@code mob_witch_forest_bramble_line}: 詠唱開始方向の1.5m・3m・4.5mに半径0.85mの茨輪を固定します。
 * 20tickの予告後、いずれかの輪内へ近接・無属性110%を一度だけ与えます。重なっても重複命中しません。
 * 高低差1.25m超と壁越しは対象外。paramsなし、発動距離5m、140tick再使用、20tick詠唱が既定です。
 */
public final class WitchForestBrambleLineMobSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_witch_forest_bramble_line";
    private final WitchForestSkillSupport support;
    private final DamageService damageService;
    private final ParticleDisplayService particles;

    /**
     * @param support 予告と発動者の継続確認・回収先
     * @param damageService 範囲候補とダメージ適用先
     * @param particles 茨輪と突き上げの描画先
     */
    public WitchForestBrambleLineMobSkillExecutor(@NotNull WitchForestSkillSupport support,
            @NotNull DamageService damageService, @NotNull ParticleDisplayService particles) {
        this.support = support;
        this.damageService = damageService;
        this.particles = particles;
    }

    /** @return Mobマスターから参照する専用技ID */
    @Override public @NotNull String id() { return SKILL_ID; }
    /** @return 頭上詠唱バーの技名 */
    @Override public @NotNull String displayName() { return "茨の突き列"; }
    /** @return 発動距離5m・140tick再使用・20tick詠唱 */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(5.0D, 140L, 20L); }

    /** @param context 詠唱開始時の方向と主対象 @return 三つの固定茨輪を予告できた場合true */
    @Override public boolean cast(@NotNull MobSkillContext context) {
        Entity caster = support.activeCaster(context);
        Vector direction = context.direction().clone().setY(0.0D);
        if (caster == null || direction.lengthSquared() < 0.01D) return false;
        direction.normalize();
        Location origin = caster.getLocation();
        List<Location> centers = new ArrayList<>(3);
        List<Location> points = new ArrayList<>(24);
        for (int row = 1; row <= 3; row++) {
            Location center = origin.clone().add(direction.clone().multiply(1.5D * row));
            centers.add(center);
            for (int index = 0; index < 8; index++) {
                double angle = Math.PI * 2.0D * index / 8.0D;
                points.add(center.clone().add(Math.cos(angle) * 0.85D, 0.08D, Math.sin(angle) * 0.85D));
            }
        }
        origin.getWorld().playSound(origin, Sound.BLOCK_ROOTED_DIRT_BREAK, 0.8F, 0.65F);
        support.warnThenImpact(context, origin, points, SharedParticleDefinitions.MOB_WITCH_FOREST_BRAMBLE_WARNING,
                () -> impact(context, origin, centers, points));
        return true;
    }

    /** いずれかの茨輪に入った対象へ、壁判定の後に一度だけ近接ダメージを与えます。 */
    private void impact(@NotNull MobSkillContext context, @NotNull Location origin,
            @NotNull List<Location> centers, @NotNull List<Location> points) {
        particles.spawnForNearbyViewers(origin, points, SharedParticleDefinitions.MOB_WITCH_FOREST_BRAMBLE_IMPACT);
        origin.getWorld().playSound(origin, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.8F, 0.6F);
        for (LivingEntity target : damageService.mobCombatTargets(origin, 5.35D, 1.25D)) {
            if (support.impactCaster(context) == null) return;
            if (!damageService.isMobCombatTarget(target)) continue;
            if (Math.abs(target.getLocation().getY() - origin.getY()) > 1.25D
                    || !withinBramble(target.getLocation(), centers)
                    || !WitchForestSkillSupport.hasClearPath(origin, target)) continue;
            damageService.attack(AstEntity.mob(context.mob()), damageService.resolveEntity(target), AttackType.MELEE,
                    List.of(new DamageComponent(DamageElement.NONE, 1.1D)), DamageSource.SKILL);
        }
    }

    /** 水平円の和集合で判定し、円の重なりによる複数回命中を防ぎます。 */
    private boolean withinBramble(@NotNull Location feet, @NotNull List<Location> centers) {
        for (Location center : centers) {
            double dx = feet.getX() - center.getX();
            double dz = feet.getZ() - center.getZ();
            if (dx * dx + dz * dz <= 0.85D * 0.85D) return true;
        }
        return false;
    }
}
