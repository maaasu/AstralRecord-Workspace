package io.github.maaasu.astralRecord.feature.skill.active.model;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/** particleを一括描画する線分です。 */
public record SkillEffectLineSegment(@NotNull Location start, @NotNull Location end) {

    /** 可変なLocationを防御的に複製します。 */
    public SkillEffectLineSegment {
        start = start.clone();
        end = end.clone();
    }

    /** {@inheritDoc} */
    @Override public @NotNull Location start() { return start.clone(); }

    /** {@inheritDoc} */
    @Override public @NotNull Location end() { return end.clone(); }
}
