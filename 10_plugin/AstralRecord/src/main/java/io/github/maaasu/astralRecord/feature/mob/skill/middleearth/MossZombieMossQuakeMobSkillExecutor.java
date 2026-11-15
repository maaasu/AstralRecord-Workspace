package io.github.maaasu.astralRecord.feature.mob.skill.middleearth;

import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.skill.BossMobDamageEffect;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code mob_moss_zombie_moss_quake}: 残りHPが半分を切った苔むしたゾンビが、周囲の地面を揺らします。
 *
 * <p>HPが最大値の50%以下の時だけ発動します。開始時のゾンビ足元を中心に半径4.5mの円を40tick予告し、
 * 予告中は通常AIを止めて腕を振り上げます。期限で円の中へ近接・無属性の攻撃力90%と外向きの押し出しを
 * 一度だけ与えます。円の外へ歩いて出れば当たりません。高低差1.25m超・壁の向こうへは当たりません。
 * paramsは受け付けません。既定は発動距離4.5m、300tick再使用、詠唱0tick（予告は技内で行う）です。
 * HP条件を満たさない時は発動せず、再使用時間も消費しません。</p>
 */
public final class MossZombieMossQuakeMobSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_moss_zombie_moss_quake";
    static final double HEALTH_RATIO = 0.50D;
    static final double RADIUS = 4.5D;
    static final long WARNING_TICKS = 40L;
    static final double DAMAGE_RATIO = 0.90D;
    static final double KNOCKBACK = 0.55D;
    private final MossZombieSkillSupport support;

    /** @param support 苔むしたゾンビの共有処理 */
    public MossZombieMossQuakeMobSkillExecutor(@NotNull MossZombieSkillSupport support) {
        this.support = support;
    }

    /** @return Mobマスタから参照する専用技ID */
    @Override public @NotNull String id() { return SKILL_ID; }
    /** @return 頭上詠唱バーの技名 */
    @Override public @NotNull String displayName() { return "苔むす地鳴り"; }
    /** @return 円と同じ4.5m・300tick再使用・詠唱0tick */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(RADIUS, 300L, 0L); }

    /**
     * @param mob 判定する Mob
     * @return 最大HPが正で、現在HPが50%以下なら true
     */
    static boolean belowThreshold(@NotNull MobInstance mob) {
        return mob.maxHealth() > 0.0D && mob.currentHealth() <= mob.maxHealth() * HEALTH_RATIO;
    }

    /** @param context 開始時の主対象 @return HP条件を満たし、円の予告を開始できた場合true */
    @Override
    public boolean cast(@NotNull MobSkillContext context) {
        if (!belowThreshold(context.mob())) {
            return false;
        }
        Entity entity = support.startingCaster(context);
        if (entity == null) {
            return false;
        }
        Location center = entity.getLocation();
        List<Location> points = new ArrayList<>(104);
        MossZombieSkillSupport.ring(center, RADIUS, 56, points);
        MossZombieSkillSupport.ring(center, RADIUS * 2.0D / 3.0D, 32, points);
        MossZombieSkillSupport.ring(center, RADIUS / 3.0D, 16, points);
        center.getWorld().playSound(center, Sound.ENTITY_ZOMBIE_VILLAGER_CONVERTED, 1.0F, 0.55F);
        support.warnThenImpact(context, center, points, WARNING_TICKS, MossZombieMotion.Pose.RAISE,
                MossZombieMotion.Pose.SLAM, SharedParticleDefinitions.MOB_MOSS_ZOMBIE_WARNING,
                List.of(MossZombieMotion.Effect.circle(MossZombieMotion.EffectStyle.RING, center, RADIUS, 1.3D)),
                impactEffects(center), () -> impact(context, center, points));
        return true;
    }

    /** 円の中心・内側6か所・外側10か所から苔の棘を伸ばします。 */
    static @NotNull List<MossZombieMotion.Effect> impactEffects(@NotNull Location center) {
        List<MossZombieMotion.Effect> effects = new ArrayList<>(17);
        effects.add(MossZombieMotion.Effect.circle(MossZombieMotion.EffectStyle.SPIKES, center, 0.9D, 1.2D));
        for (int index = 0; index < 6; index++) {
            effects.add(MossZombieMotion.Effect.circle(MossZombieMotion.EffectStyle.SPIKES,
                    MossZombieSkillSupport.offset(center, Math.PI * 2.0D * index / 6.0D, 2.0D), 0.9D, 1.2D));
        }
        for (int index = 0; index < 10; index++) {
            effects.add(MossZombieMotion.Effect.circle(MossZombieMotion.EffectStyle.SPIKES,
                    MossZombieSkillSupport.offset(center, Math.PI * 2.0D * (index + 0.5D) / 10.0D, 3.6D), 0.9D, 1.2D));
        }
        return effects;
    }

    /**
     * @param center 円の中心
     * @param point 判定地点
     * @return 半径4.5mの円の中なら true
     */
    static boolean inQuake(@NotNull Location center, @NotNull Location point) {
        return MossZombieSkillSupport.horizontalSquared(center, point) <= RADIUS * RADIUS;
    }

    /** 固定した円の中へ一度だけ地鳴りを与えます。 */
    private void impact(@NotNull MobSkillContext context, @NotNull Location center, @NotNull List<Location> points) {
        support.particles().spawnForNearbyViewers(center, points, SharedParticleDefinitions.MOB_MOSS_ZOMBIE_IMPACT);
        BossMobDamageEffect.showCircle(support.particles(), center, RADIUS);
        center.getWorld().playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.7F, 0.6F);
        center.getWorld().playSound(center, Sound.BLOCK_MOSS_BREAK, 1.4F, 0.5F);
        support.hitArea(context.mob(), center, RADIUS, point -> inQuake(center, point), AttackType.MELEE,
                DAMAGE_RATIO, KNOCKBACK);
    }
}
