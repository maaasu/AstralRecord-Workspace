package io.github.maaasu.astralRecord.feature.skill.active.model;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/**
 * 仮想飛翔体の線分とMob当たり判定の正確な最初の交点です。
 *
 * @param target 命中対象
 * @param location 線分と拡張当たり判定の交点
 * @param distance 線分始点から交点までの距離
 */
public record SkillLineTargetHit(
        @NotNull AstEntity target,
        @NotNull Location location,
        double distance
) {

    /** 交点を防御的に複製します。 */
    public SkillLineTargetHit {
        location = location.clone();
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Location location() {
        return location.clone();
    }
}
