package io.github.maaasu.astralRecord.feature.mob.skill.field;

import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 灰ブレス: 40tickの向き固定予告後、長さ7m/左右30度の扇に魔法・火属性135%を主対象へ一回だけ与えます。
 * 横・背後・7m外・遮蔽物で回避可能。灰は視覚だけで、盲目・燃焼・残留雲は付けません。
 * paramsなし、発動距離0.5〜7m、CD240tick以上、外部詠唱0のみ。
 * 中央destroy listenerとonDisableからhandleMobDestroyed/stopを接続してください。
 */
public final class AshDrakeMobSkillExecutor extends VolcanicSkillSupport {
    public static final String SKILL_ID = "mob_field_ash_drake_ash_breath";
    private static final double RANGE = 7.0D;
    private static final double HALF_ANGLE = Math.PI / 6.0D;

    /**
     * @param mobs 管理個体とSchedulerの取得先
     * @param damage 魔法火属性ダメージと有効対象の共通境界
     * @param conditions 行動不能の確認先
     * @param particles 閲覧者単位の予告・灰煙描画先
     */
    public AshDrakeMobSkillExecutor(@NotNull MobService mobs, @NotNull DamageService damage,
            @NotNull ConditionService conditions, @NotNull ParticleDisplayService particles) {
        super(mobs, damage, conditions, particles, 40, 1.35D, AttackType.MAGIC, Sound.ENTITY_ENDER_DRAGON_GROWL);
    }

    /** @return Mob専用の灰ブレスID */
    @Override public @NotNull String id() { return SKILL_ID; }

    /** @return 予告バーに表示する技名 */
    @Override public @NotNull String displayName() { return "灰ブレス"; }

    /** @return 射程7m、再使用12秒、Executor自身で2秒予告 */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(RANGE, 240L, 0L); }

    /** 扇の二辺と中央筋21点、外周弧13点で予告するため横と背後が安全になります。 */
    @Override protected @NotNull List<Location> warningPoints(Location origin, Vector direction) {
        double centerAngle = Math.atan2(direction.getZ(), direction.getX());
        List<Location> points = new ArrayList<>(34);
        for (double offset : new double[]{-HALF_ANGLE, 0.0D, HALF_ANGLE}) {
            for (int i = 1; i <= 7; i++) {
                points.add(origin.clone().add(Math.cos(centerAngle + offset) * i, 0.12D,
                        Math.sin(centerAngle + offset) * i));
            }
        }
        for (int i = 0; i <= 12; i++) {
            double angle = centerAngle - HALF_ANGLE + 2.0D * HALF_ANGLE * i / 12.0D;
            points.add(origin.clone().add(Math.cos(angle) * RANGE, 0.12D, Math.sin(angle) * RANGE));
        }
        return points;
    }

    /** 固定扇の前方だけへ命中させます。背後・左右30度外へは届きません。 */
    @Override protected boolean contains(Location origin, Vector direction, Location target) {
        double dx = target.getX() - origin.getX(), dz = target.getZ() - origin.getZ();
        double squared = dx * dx + dz * dz;
        return squared <= RANGE * RANGE && (squared < 0.0001D
                || dx * direction.getX() + dz * direction.getZ() >= Math.sqrt(squared) * Math.cos(HALF_ANGLE));
    }

    /** 地上の炎輪郭と胴高の灰煙を別に描き、熱い灰の広がりを一回だけ示します。 */
    @Override protected void impact(Location origin, Vector direction, List<Location> outline) {
        particles.spawnForNearbyViewers(origin, outline, SharedParticleDefinitions.BOSS_MECHANIC_FLAME);
        List<Location> smoke = new ArrayList<>(15);
        double angle = Math.atan2(direction.getZ(), direction.getX());
        for (int distance = 1; distance <= 5; distance++) {
            for (double offset : new double[]{-HALF_ANGLE * 0.7D, 0.0D, HALF_ANGLE * 0.7D}) {
                smoke.add(origin.clone().add(Math.cos(angle + offset) * distance, 0.65D,
                        Math.sin(angle + offset) * distance));
            }
        }
        particles.spawnForNearbyViewers(origin, smoke,
                SharedParticleDefinitions.BOSS_MECHANIC_SMOKE.withOffsets(0.12D, 0.12D, 0.12D));
        origin.getWorld().playSound(origin, Sound.ENTITY_BLAZE_SHOOT, 0.95F, 0.55F);
    }
}
