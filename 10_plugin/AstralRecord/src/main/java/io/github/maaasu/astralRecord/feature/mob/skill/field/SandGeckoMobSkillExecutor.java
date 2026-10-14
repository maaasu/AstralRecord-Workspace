package io.github.maaasu.astralRecord.feature.mob.skill.field;

import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
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
 * スナヤモリの砂散らし。30tick予告後、固定した5m/左右40度の扇へ一回だけ砂を払い、
 * 近接無属性35%と盲目30tickを主対象だけへ適用します。毒・減速・行動不能は付けません。
 * 扇外/背後/遮蔽物で回避可能。paramsなし、射程0.5〜5m、CD220tick以上、詠唱0のみ。
 * 中央の破棄listener/onDisableからhandleMobDestroyed/stopを呼ぶ必要があります。
 */
public final class SandGeckoMobSkillExecutor extends MesaDebuffSkillSupport {
    public static final String SKILL_ID = "mob_field_sand_gecko_sand_scatter";
    private static final double RANGE = 5.0D;
    private static final double HALF_ANGLE = Math.toRadians(40.0D);

    /** 共通ダメージ・期限付き盲目・有限予告の依存先を指定します。 */
    public SandGeckoMobSkillExecutor(@NotNull MobService mobService, @NotNull DamageService damageService,
            @NotNull ConditionService conditionService, @NotNull ParticleDisplayService particles) {
        super(mobService, damageService, conditionService, particles, 30, 0.35D, ConditionType.BLINDNESS, 30L,
                SharedParticleDefinitions.MOB_CLAY_GUARD_LANDING_RING, SharedParticleDefinitions.MOB_CLAY_GUARD_LANDING_RING,
                Sound.ENTITY_SILVERFISH_STEP, Sound.BLOCK_SAND_BREAK);
    }

    /** @return Mobマスター専用の砂散らしID */
    @Override public @NotNull String id() { return SKILL_ID; }

    /** @return 頭上表示する技名 */
    @Override public @NotNull String displayName() { return "砂散らし"; }

    /** @return 5m、再使用11秒、Executor内予告のため詠唱0 */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(RANGE, 220L, 0L); }

    /** 扇の二辺・中央筋と円弧を31点で示し、細い毒針の予告と区別します。 */
    @Override protected @NotNull List<Location> warningPoints(Location origin, Vector direction) {
        double baseAngle = Math.atan2(direction.getZ(), direction.getX());
        List<Location> points = new ArrayList<>(31);
        for (double angle : new double[]{-HALF_ANGLE, 0.0D, HALF_ANGLE}) {
            for (int i = 1; i <= 6; i++) {
                double distance = RANGE * i / 6.0D;
                points.add(origin.clone().add(Math.cos(baseAngle + angle) * distance, 0.08D,
                        Math.sin(baseAngle + angle) * distance));
            }
        }
        for (int i = 0; i <= 12; i++) {
            double angle = baseAngle - HALF_ANGLE + HALF_ANGLE * 2.0D * i / 12.0D;
            points.add(origin.clone().add(Math.cos(angle) * RANGE, 0.08D, Math.sin(angle) * RANGE));
        }
        return points;
    }

    /** 主対象足元を固定扇の角度・距離で判定し、背後へ回った対象を除外します。 */
    @Override protected boolean contains(Location origin, Vector direction, Location target) {
        double dx = target.getX() - origin.getX(), dz = target.getZ() - origin.getZ();
        double distanceSquared = dx * dx + dz * dz;
        if (distanceSquared > RANGE * RANGE) return false;
        return distanceSquared < 0.0001D
                || (dx * direction.getX() + dz * direction.getZ()) >= Math.sqrt(distanceSquared) * Math.cos(HALF_ANGLE);
    }
}
