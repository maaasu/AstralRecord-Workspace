package io.github.maaasu.astralRecord.feature.gathering.model;

import io.github.maaasu.astralRecord.feature.mob.model.MobDropConfig;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;

import java.util.List;

public record GatheringDefinition(
        int schemaVersion,
        @NotNull String id,
        @NotNull String category,
        @NotNull String name,
        int maxHealth,
        @NotNull Material displayBlock,
        @NotNull Vector3f displayScale,
        @NotNull List<String> requiredToolTags,
        @NotNull MobDropConfig drops
) {

    public GatheringDefinition {
        maxHealth = Math.max(1, maxHealth);
        requiredToolTags = requiredToolTags == null ? List.of() : List.copyOf(requiredToolTags);
    }
}
