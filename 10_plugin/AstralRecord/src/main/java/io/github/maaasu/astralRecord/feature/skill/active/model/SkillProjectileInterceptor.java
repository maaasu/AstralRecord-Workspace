package io.github.maaasu.astralRecord.feature.skill.active.model;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** 地形より手前の飛翔経路にある吸収対象を探します。 */
@FunctionalInterface
public interface SkillProjectileInterceptor {

    /**
     * 線分上の最初の吸収対象を返します。
     *
     * @param origin 線分の始点
     * @param direction 単位方向ベクトル
     * @param maxDistance 地形衝突までを上限とする線分長
     * @param projectileRadius 飛翔体の当たり判定半径
     * @return 最初の交点。対象がなければnull
     */
    @Nullable SkillProjectileInterception firstHit(
            @NotNull Location origin,
            @NotNull Vector direction,
            double maxDistance,
            double projectileRadius
    );
}
