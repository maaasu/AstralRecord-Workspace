package io.github.maaasu.astralRecord.feature.mob.skill.middleearth;

import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
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
 * {@code mob_moss_zombie_spore_pool}: 離れた主対象の足元へ苔胞子だまりを固定し、破裂させます。
 *
 * <p>主対象が4m以上離れている時だけ発動し、開始時の主対象の真下の床（3m以内）に半径2mの輪を30tick予告します。
 * 予告中は通常AIを止めて両腕を広げ、期限で輪の中へ魔法・無属性の攻撃力60%を一度だけ与えます。
 * 状態異常・持続ダメージ・押し出しはありません。高低差1.25m超・壁の向こうへは当たりません。
 * paramsは受け付けません。既定は発動距離12m、200tick再使用、詠唱0tick（予告は技内で行う）です。</p>
 */
public final class MossZombieSporePoolMobSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_moss_zombie_spore_pool";
    static final double MIN_DISTANCE = 4.0D;
    static final double RADIUS = 2.0D;
    static final long WARNING_TICKS = 30L;
    static final double DAMAGE_RATIO = 0.60D;
    private final MossZombieSkillSupport support;

    /** @param support 苔むしたゾンビの共有処理 */
    public MossZombieSporePoolMobSkillExecutor(@NotNull MossZombieSkillSupport support) {
        this.support = support;
    }

    /** @return Mobマスタから参照する専用技ID */
    @Override public @NotNull String id() { return SKILL_ID; }
    /** @return 頭上詠唱バーの技名 */
    @Override public @NotNull String displayName() { return "苔胞子だまり"; }
    /** @return 12m・200tick再使用・詠唱0tick */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(12.0D, 200L, 0L); }

    /** @param context 開始時の主対象 @return 足元の固定輪を開始できた場合true */
    @Override
    public boolean cast(@NotNull MobSkillContext context) {
        Entity entity = support.startingCaster(context);
        if (entity == null) {
            return false;
        }
        Location center = MossZombieSkillSupport.groundBelow(context.target().getLocation());
        if (center == null
                || MossZombieSkillSupport.horizontalSquared(entity.getLocation(), center) < MIN_DISTANCE * MIN_DISTANCE
                || Math.abs(entity.getLocation().getY() - center.getY()) > MossZombieSkillSupport.VERTICAL_REACH
                || !MossZombieSkillSupport.hasClearPath(entity.getLocation(), context.target())) {
            return false;
        }
        List<Location> points = new ArrayList<>(56);
        MossZombieSkillSupport.ring(center, RADIUS, 40, points);
        MossZombieSkillSupport.ring(center, RADIUS * 0.5D, 16, points);
        center.getWorld().playSound(entity.getLocation(), Sound.ENTITY_ZOMBIE_AMBIENT, 1.0F, 0.8F);
        center.getWorld().playSound(center, Sound.BLOCK_FUNGUS_PLACE, 0.9F, 0.65F);
        support.warnThenImpact(context, center, points, WARNING_TICKS, MossZombieMotion.Pose.ROAR,
                MossZombieMotion.Pose.NONE, SharedParticleDefinitions.MOB_MOSS_ZOMBIE_SPORE_WARNING,
                () -> impact(context, center, points));
        return true;
    }

    /**
     * @param center 輪の中心
     * @param point 判定地点
     * @return 半径2mの輪の中なら true
     */
    static boolean inPool(@NotNull Location center, @NotNull Location point) {
        return MossZombieSkillSupport.horizontalSquared(center, point) <= RADIUS * RADIUS;
    }

    /** 固定した輪の中へ一度だけ胞子の破裂を与えます。 */
    private void impact(@NotNull MobSkillContext context, @NotNull Location center, @NotNull List<Location> points) {
        support.particles().spawnForNearbyViewers(center, points, SharedParticleDefinitions.MOB_ERIVA_SPORE_IMPACT);
        support.particles().spawnForNearbyViewers(center.clone().add(0.0D, 0.5D, 0.0D),
                SharedParticleDefinitions.MOB_MOSS_ZOMBIE_SPORE_CLOUD);
        center.getWorld().playSound(center, Sound.BLOCK_SLIME_BLOCK_BREAK, 0.9F, 0.9F);
        support.hitArea(context.mob(), center, RADIUS, point -> inPool(center, point), AttackType.MAGIC,
                DAMAGE_RATIO, 0.0D);
    }
}
