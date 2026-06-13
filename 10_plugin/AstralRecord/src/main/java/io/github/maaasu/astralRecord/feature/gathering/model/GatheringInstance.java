package io.github.maaasu.astralRecord.feature.gathering.model;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public final class GatheringInstance {
    private final UUID instanceId;
    private final GatheringDefinition definition;
    private final Location location;
    private int currentHealth;
    private UUID activePlayerId;

    public GatheringInstance(@NotNull UUID instanceId, @NotNull GatheringDefinition definition, @NotNull Location location) {
        this.instanceId = instanceId;
        this.definition = definition;
        this.location = location.clone();
        this.currentHealth = definition.maxHealth();
    }

    public @NotNull UUID instanceId() {
        return instanceId;
    }

    public @NotNull GatheringDefinition definition() {
        return definition;
    }

    public @NotNull Location location() {
        return location.clone();
    }

    public int currentHealth() {
        return currentHealth;
    }

    public void damage(int amount) {
        currentHealth = Math.max(0, currentHealth - Math.max(1, amount));
    }

    public void resetHealth() {
        currentHealth = definition.maxHealth();
        activePlayerId = null;
    }

    public @Nullable UUID activePlayerId() {
        return activePlayerId;
    }

    public void activePlayerId(@Nullable UUID activePlayerId) {
        this.activePlayerId = activePlayerId;
    }
}
