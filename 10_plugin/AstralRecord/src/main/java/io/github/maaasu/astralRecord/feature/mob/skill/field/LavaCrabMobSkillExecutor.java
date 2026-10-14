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
 * 加熱殻: 30tickの固定円予告後、半径3mへ近接・火属性110%を主対象に一回だけ与えます。
 * 輪外・高低差1.25m超・遮蔽物で回避可能。防御無敵・反射・継続炎は付けません。
 * paramsなし、発動距離0.5〜3m、CD180tick以上、外部詠唱0のみ。
 * 中央destroy listenerとonDisableからhandleMobDestroyed/stopを接続してください。
 */
public final class LavaCrabMobSkillExecutor extends VolcanicSkillSupport {
    public static final String SKILL_ID = "mob_field_lava_crab_heated_shell";
    private static final double RADIUS = 3.0D;

    /**
     * @param mobs 管理個体とSchedulerの取得先
     * @param damage 近接火属性ダメージと有効対象の共通境界
     * @param conditions 行動不能の確認先
     * @param particles 閲覧者単位の予告・発動描画先
     */
    public LavaCrabMobSkillExecutor(@NotNull MobService mobs, @NotNull DamageService damage,
            @NotNull ConditionService conditions, @NotNull ParticleDisplayService particles) {
        super(mobs, damage, conditions, particles, 30, 1.10D, AttackType.MELEE, Sound.BLOCK_LAVA_POP);
    }

    /** @return Mob専用の加熱殻ID */
    @Override public @NotNull String id() { return SKILL_ID; }

    /** @return 予告バーに表示する技名 */
    @Override public @NotNull String displayName() { return "加熱殻"; }

    /** @return 射程3m、再使用9秒、Executor自身で1.5秒予告 */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(RADIUS, 180L, 0L); }

    /** 外周24点と四方向の中点で、接近し過ぎた時の危険円を示します。 */
    @Override protected @NotNull List<Location> warningPoints(Location origin, Vector direction) {
        List<Location> points = new ArrayList<>(28);
        for (int i = 0; i < 24; i++) {
            double angle = Math.PI * 2.0D * i / 24.0D;
            points.add(origin.clone().add(Math.cos(angle) * RADIUS, 0.12D, Math.sin(angle) * RADIUS));
        }
        for (int i = 0; i < 4; i++) {
            double angle = Math.PI * 0.5D * i;
            points.add(origin.clone().add(Math.cos(angle) * 1.5D, 0.12D, Math.sin(angle) * 1.5D));
        }
        return points;
    }

    /** 開始位置中心の水平円を使います。予告後の追跡拡大はしません。 */
    @Override protected boolean contains(Location origin, Vector direction, Location target) {
        double dx = target.getX() - origin.getX(), dz = target.getZ() - origin.getZ();
        return dx * dx + dz * dz <= RADIUS * RADIUS;
    }

    /** 甲羅から熱が抜ける炎の円と蒸気だけを描き、ブロックや火を残しません。 */
    @Override protected void impact(Location origin, Vector direction, List<Location> outline) {
        particles.spawnForNearbyViewers(origin, outline, SharedParticleDefinitions.BOSS_MECHANIC_FLAME);
        particles.spawnForNearbyViewers(origin.clone().add(0.0D, 0.7D, 0.0D),
                SharedParticleDefinitions.BOSS_MECHANIC_SMOKE.withCount(8).withOffsets(0.6D, 0.3D, 0.6D));
        origin.getWorld().playSound(origin, Sound.BLOCK_FIRE_EXTINGUISH, 0.85F, 0.7F);
    }
}
