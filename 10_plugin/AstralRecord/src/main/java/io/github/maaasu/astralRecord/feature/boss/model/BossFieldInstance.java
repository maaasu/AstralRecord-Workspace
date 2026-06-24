package io.github.maaasu.astralRecord.feature.boss.model;

import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Loaded field world generated for one boss challenge.
 */
public record BossFieldInstance(
        @NotNull UUID challengeId,
        @NotNull String worldName,
        @NotNull Path worldFolder,
        @NotNull World world
) {
}
