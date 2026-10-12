package io.github.maaasu.astralRecord.feature.mob.skill.witchforest;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyReason;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyRequest;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
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
 * {@code mob_witch_forest_curse_web}: 詠唱終了時の主対象の足元へ半径2mの呪糸輪を固定し、20tick予告します。
 * 輪内へ遠隔・無属性45%を一度与え、実ダメージまたはシールドダメージ時に基礎確率30%・40tickの冷気を要求します。
 * 高低差1.25m超と壁越しは対象外。paramsなし、発動距離7m、120tick再使用、12tick詠唱が既定です。
 */
public final class WitchForestCurseWebMobSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_witch_forest_curse_web";
    private final WitchForestSkillSupport support;
    private final DamageService damageService;
    private final ConditionService conditionService;
    private final ParticleDisplayService particles;

    /**
     * @param support 予告と発動者の継続確認・回収先
     * @param damageService 範囲候補とダメージ適用先
     * @param conditionService 冷気の付与確率・耐性計算先
     * @param particles 呪糸輪の描画先
     */
    public WitchForestCurseWebMobSkillExecutor(@NotNull WitchForestSkillSupport support,
            @NotNull DamageService damageService, @NotNull ConditionService conditionService,
            @NotNull ParticleDisplayService particles) {
        this.support = support;
        this.damageService = damageService;
        this.conditionService = conditionService;
        this.particles = particles;
    }

    /** @return Mobマスターから参照する専用技ID */
    @Override public @NotNull String id() { return SKILL_ID; }
    /** @return 頭上詠唱バーの技名 */
    @Override public @NotNull String displayName() { return "呪糸の輪"; }
    /** @return 発動距離7m・120tick再使用・12tick詠唱 */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(7.0D, 120L, 12L); }

    /** @param context 詠唱開始時の主対象 @return 固定呪糸輪の予告を開始できた場合true */
    @Override public boolean cast(@NotNull MobSkillContext context) {
        Entity caster = support.activeCaster(context);
        if (caster == null || caster.getLocation().distanceSquared(context.target().getLocation()) > 49.0D
                || Math.abs(caster.getLocation().getY() - context.target().getLocation().getY()) > 1.25D
                || !WitchForestSkillSupport.hasClearPath(caster.getLocation(), context.target())) return false;
        Location center = context.target().getLocation();
        List<Location> points = new ArrayList<>(24);
        for (int index = 0; index < 24; index++) {
            double angle = Math.PI * 2.0D * index / 24.0D;
            points.add(center.clone().add(Math.cos(angle) * 2.0D, 0.08D, Math.sin(angle) * 2.0D));
        }
        center.getWorld().playSound(center, Sound.ENTITY_SPIDER_AMBIENT, 0.7F, 0.6F);
        support.warnThenImpact(context, center, points, SharedParticleDefinitions.MOB_WITCH_FOREST_WEB_WARNING,
                () -> impact(context, center, points));
        return true;
    }

    /** 固定輪の内側へ一度だけ攻撃し、成功した命中だけに冷気を要求します。 */
    private void impact(@NotNull MobSkillContext context, @NotNull Location center, @NotNull List<Location> points) {
        particles.spawnForNearbyViewers(center, points, SharedParticleDefinitions.MOB_WITCH_FOREST_WEB_IMPACT);
        center.getWorld().playSound(center, Sound.BLOCK_SCULK_SENSOR_CLICKING, 0.8F, 1.4F);
        for (LivingEntity target : damageService.mobCombatTargets(center, 2.0D, 1.25D)) {
            if (support.impactCaster(context) == null) return;
            if (!damageService.isMobCombatTarget(target)) continue;
            double dx = target.getLocation().getX() - center.getX();
            double dz = target.getLocation().getZ() - center.getZ();
            if (dx * dx + dz * dz > 4.0D || Math.abs(target.getLocation().getY() - center.getY()) > 1.25D
                    || !WitchForestSkillSupport.hasClearPath(center, target)) continue;
            AstEntity victim = damageService.resolveEntity(target);
            var result = damageService.attack(AstEntity.mob(context.mob()), victim, AttackType.RANGED,
                    List.of(new DamageComponent(DamageElement.NONE, 0.45D)), DamageSource.SKILL);
            if (!result.evaded() && (result.finalDamage() > 0.0D || result.shieldDamage() > 0.0D)) {
                conditionService.applyCondition(new ConditionApplyRequest(victim, AstEntity.mob(context.mob()),
                        ConditionType.CHILLED, AttackType.RANGED, 40L, 30.0D, 1.0D,
                        null, null, null, null, ConditionApplyReason.SKILL));
            }
        }
    }
}
