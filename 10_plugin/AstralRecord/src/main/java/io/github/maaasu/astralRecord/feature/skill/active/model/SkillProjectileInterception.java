package io.github.maaasu.astralRecord.feature.skill.active.model;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/**
 * 仮想 projectile の経路上にある、Mobと地形以外の吸収対象です。
 *
 * @param location 線分上の最初の交点
 * @param onHit 交点がMobや地形より先に命中した場合だけ実行する処理
 */
public record SkillProjectileInterception(
        @NotNull Location location,
        @NotNull Runnable onHit
) {
}
