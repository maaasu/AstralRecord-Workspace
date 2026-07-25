package io.github.maaasu.astralRecord.feature.condition.model;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntityType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** 現在付与されている状態異常です。 */
public final class ActiveCondition {
    private final UUID conditionId;
    private final ConditionType type;
    private final UUID targetId;
    private final AstEntityType targetType;
    private UUID sourceId;
    private AstEntityType sourceType;
    private final AstEntity target;
    private AstEntity source;
    private final long startedAtMs;
    private long expiresAtMs;
    private long lastTickAtMs;
    private long nextTickAtMs;
    private long nextControlAtMs;
    private long controlBlockedUntilMs;
    private double strength;
    private double snapshotPower;
    private double healthRate;
    private int tickIntervalTicks;

    public ActiveCondition(
            @NotNull UUID conditionId,
            @NotNull ConditionType type,
            @NotNull AstEntity target,
            @Nullable AstEntity source,
            long startedAtMs,
            long expiresAtMs,
            long nextTickAtMs,
            long nextControlAtMs,
            double strength,
            double snapshotPower,
            double healthRate,
            int tickIntervalTicks
    ) {
        this.conditionId = conditionId;
        this.type = type;
        this.target = target;
        this.source = source;
        this.targetId = target.id();
        this.targetType = target.type();
        this.sourceId = source == null ? null : source.id();
        this.sourceType = source == null ? null : source.type();
        this.startedAtMs = startedAtMs;
        this.expiresAtMs = expiresAtMs;
        this.nextTickAtMs = nextTickAtMs;
        this.nextControlAtMs = nextControlAtMs;
        this.strength = strength;
        this.snapshotPower = snapshotPower;
        this.healthRate = healthRate;
        this.tickIntervalTicks = tickIntervalTicks;
    }

    public @NotNull UUID conditionId() { return conditionId; }
    public @NotNull ConditionType type() { return type; }
    public @NotNull UUID targetId() { return targetId; }
    public @NotNull AstEntityType targetType() { return targetType; }
    public @Nullable UUID sourceId() { return sourceId; }
    public @Nullable AstEntityType sourceType() { return sourceType; }
    public @NotNull AstEntity target() { return target; }
    public @Nullable AstEntity source() { return source; }
    public long startedAtMs() { return startedAtMs; }
    public long expiresAtMs() { return expiresAtMs; }
    public long lastTickAtMs() { return lastTickAtMs; }
    public long nextTickAtMs() { return nextTickAtMs; }
    public long nextControlAtMs() { return nextControlAtMs; }
    public long controlBlockedUntilMs() { return controlBlockedUntilMs; }
    public double strength() { return strength; }
    public double snapshotPower() { return snapshotPower; }
    public double healthRate() { return healthRate; }
    public int tickIntervalTicks() { return tickIntervalTicks; }

    public void expiresAtMs(long value) { expiresAtMs = value; }
    public void lastTickAtMs(long value) { lastTickAtMs = value; }
    public void nextTickAtMs(long value) { nextTickAtMs = value; }
    public void nextControlAtMs(long value) { nextControlAtMs = value; }
    public void controlBlockedUntilMs(long value) { controlBlockedUntilMs = value; }
    public void source(@Nullable AstEntity value) {
        source = value;
        sourceId = value == null ? null : value.id();
        sourceType = value == null ? null : value.type();
    }
    public void strength(double value) { strength = value; }
    public void snapshotPower(double value) { snapshotPower = value; }
    public void healthRate(double value) { healthRate = value; }
    public void tickIntervalTicks(int value) { tickIntervalTicks = value; }

    public boolean expired(long nowMs) { return expiresAtMs <= nowMs; }
    public boolean intermittentControlActive(long nowMs) { return controlBlockedUntilMs > nowMs; }
}
