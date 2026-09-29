package io.github.maaasu.astralRecord.feature.skill.executor.active.archmage;

import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** アークメイジの追加スキルに共通する設置位置と描画を扱います。 */
final class ArchmageBuildSupport {
    private ArchmageBuildSupport() { }

    /**
     * 視点先の歩行可能な地表を返します。
     * @param context 発動者と地形判定
     * @param range 最大射程
     * @return 地表上の描画中心。適切な地表がない場合はnull
     */
    static @Nullable Location groundAtSight(@NotNull PlayerActiveSkillContext context, double range) {
        Location eye = context.eyeLocation();
        Location impact = context.services().targeting().blockImpact(eye, context.direction(), range);
        Location probe = impact == null ? eye.clone().add(context.direction().multiply(range)) : impact;
        Location ground = context.services().targeting().groundAt(probe, 3, 32);
        World world = ground.getWorld();
        if (world == null || ground.clone().subtract(0.0D, 0.2D, 0.0D).getBlock().isPassable()
                || !ground.getBlock().isPassable() || eye.distanceSquared(ground) > range * range) {
            return null;
        }
        return ground.add(0.0D, 0.06D, 0.0D);
    }

    /**
     * 発動者の現存魔法陣数を過度な重複設置から守りつつ参照します。
     * @param context 発動者と共有陣状態
     * @return 0〜3の陣数
     */
    static int circleBonus(@NotNull PlayerActiveSkillContext context) {
        return Math.min(3, context.services().circles().count(context.player().getUniqueId()));
    }

    /**
     * 陣の輪郭と十字を共通粒子で描きます。
     * @param context 描画サービス
     * @param center 中心
     * @param radius 半径
     * @param hostile 敵に作用する陣ならtrue
     */
    static void drawCircle(@NotNull PlayerActiveSkillContext context, @NotNull Location center,
                           double radius, boolean hostile) {
        SharedParticleDefinition outer = hostile
                ? SharedParticleDefinitions.SKILL_ARCHMAGE_CELESTIAL_VIOLET
                : SharedParticleDefinitions.SKILL_ARCHMAGE_HEAL_CIRCLE_MINT;
        context.services().effects().ring(center, radius, 36, outer);
        context.services().effects().ring(center, radius * 0.62D, 24,
                SharedParticleDefinitions.SKILL_ARCHMAGE_CLEAR_CIRCLE_GOLD);
        Vector east = new Vector(radius * 0.55D, 0.0D, 0.0D);
        Vector south = new Vector(0.0D, 0.0D, radius * 0.55D);
        context.services().effects().line(center.clone().add(east), center.clone().subtract(east), 0.4D, outer);
        context.services().effects().line(center.clone().add(south), center.clone().subtract(south), 0.4D, outer);
    }
}
