package io.github.maaasu.astralRecord.feature.mob.skill.middleearth;

import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code mob_moss_zombie_moss_slam}: 苔むしたゾンビが両腕を振り上げ、前方の扇形へ叩きつけます。
 *
 * <p>開始時のゾンビ足元と主対象方向を固定し、前方90度・半径3.5mを30tick予告します。予告中は
 * 通常AIを止めて腕を振り上げ、期限で近接・無属性の攻撃力80%と弱い押し出しを一度だけ与えます。
 * 背後・側面・高低差1.25m超・壁の向こうへは当たりません。paramsは受け付けません。
 * 既定は発動距離3.5m、140tick再使用、詠唱0tick（予告は技内で行う）です。</p>
 */
public final class MossZombieMossSlamMobSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_moss_zombie_moss_slam";
    static final double RADIUS = 3.5D;
    static final double HALF_ANGLE_RADIANS = Math.toRadians(45.0D);
    static final long WARNING_TICKS = 30L;
    static final double DAMAGE_RATIO = 0.80D;
    static final double KNOCKBACK = 0.35D;
    private final MossZombieSkillSupport support;

    /** @param support 苔むしたゾンビの共有処理 */
    public MossZombieMossSlamMobSkillExecutor(@NotNull MossZombieSkillSupport support) {
        this.support = support;
    }

    /** @return Mobマスタから参照する専用技ID */
    @Override public @NotNull String id() { return SKILL_ID; }
    /** @return 頭上詠唱バーの技名 */
    @Override public @NotNull String displayName() { return "苔腕の叩きつけ"; }
    /** @return 3.5m・140tick再使用・詠唱0tick */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(RADIUS, 140L, 0L); }

    /** @param context 開始時の主対象と方向 @return 固定扇形の予告を開始できた場合true */
    @Override
    public boolean cast(@NotNull MobSkillContext context) {
        Entity entity = support.startingCaster(context);
        if (entity == null) {
            return false;
        }
        Location center = entity.getLocation();
        Vector direction = context.target().getLocation().toVector().subtract(center.toVector()).setY(0.0D);
        if (direction.lengthSquared() < 0.01D) {
            direction = new Vector(context.direction().getX(), 0.0D, context.direction().getZ());
        }
        if (direction.lengthSquared() < 0.01D) {
            return false;
        }
        direction.normalize();
        center.setDirection(direction);
        Vector facing = direction.clone();
        List<Location> points = fanPoints(center, facing);
        center.getWorld().playSound(center, Sound.ENTITY_ZOMBIE_AMBIENT, 1.0F, 0.6F);
        support.warnThenImpact(context, center, points, WARNING_TICKS, MossZombieMotion.Pose.RAISE,
                MossZombieMotion.Pose.SLAM, SharedParticleDefinitions.MOB_MOSS_ZOMBIE_WARNING,
                () -> impact(context, center, facing, points));
        return true;
    }

    /** 扇形の弧と左右の縁、内部の弧を固定予告点として作ります。 */
    static @NotNull List<Location> fanPoints(@NotNull Location center, @NotNull Vector facing) {
        List<Location> points = new ArrayList<>(72);
        double base = Math.atan2(facing.getZ(), facing.getX());
        for (int row = 1; row <= 3; row++) {
            double radius = RADIUS * row / 3.0D;
            int columns = 8 + row * 4;
            for (int column = 0; column <= columns; column++) {
                double angle = base - HALF_ANGLE_RADIANS + 2.0D * HALF_ANGLE_RADIANS * column / columns;
                points.add(center.clone().add(Math.cos(angle) * radius, 0.1D, Math.sin(angle) * radius));
            }
        }
        for (int sign : new int[]{-1, 1}) {
            double angle = base + sign * HALF_ANGLE_RADIANS;
            for (double distance = 0.5D; distance < RADIUS; distance += 0.5D) {
                points.add(center.clone().add(Math.cos(angle) * distance, 0.1D, Math.sin(angle) * distance));
            }
        }
        return points;
    }

    /**
     * @param center 判定中心
     * @param facing 正規化済みの固定方向
     * @param point 判定地点
     * @return 前方90度・半径3.5mの扇形内なら true
     */
    static boolean inFan(@NotNull Location center, @NotNull Vector facing, @NotNull Location point) {
        double x = point.getX() - center.getX();
        double z = point.getZ() - center.getZ();
        double squared = x * x + z * z;
        if (squared > RADIUS * RADIUS) {
            return false;
        }
        if (squared < 0.25D) {
            return true;
        }
        double length = Math.sqrt(squared);
        return (x * facing.getX() + z * facing.getZ()) / length >= Math.cos(HALF_ANGLE_RADIANS);
    }

    /** 固定した扇形内の対象へ一度だけ叩きつけを与えます。 */
    private void impact(@NotNull MobSkillContext context, @NotNull Location center, @NotNull Vector facing,
                        @NotNull List<Location> points) {
        support.particles().spawnForNearbyViewers(center, points, SharedParticleDefinitions.MOB_MOSS_ZOMBIE_IMPACT);
        center.getWorld().playSound(center, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.9F, 0.7F);
        center.getWorld().playSound(center, Sound.BLOCK_MOSS_BREAK, 1.2F, 0.6F);
        support.hitArea(context.mob(), center, RADIUS, point -> inFan(center, facing, point),
                AttackType.MELEE, DAMAGE_RATIO, KNOCKBACK);
    }
}
