package io.github.maaasu.astralRecord.feature.condition.model;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntityType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 現在付与されている状態異常を表します。
 */
public final class ActiveCondition {
    private final UUID conditionId;
    private final ConditionType type;
    private final UUID targetId;
    private final AstEntityType targetType;
    private final UUID sourceId;
    private final AstEntityType sourceType;
    private final AstEntity target;
    private final AstEntity source;
    private final long startedAtMs;
    private long expiresAtMs;
    private long lastTickAtMs;
    private long nextTickAtMs;
    private int stack;
    private double snapshotPower;
    private int tickIntervalTicks;
    private DamageType damageType;
    private DamageElement damageElement;

    public ActiveCondition(
            @NotNull UUID conditionId,
            @NotNull ConditionType type,
            @NotNull AstEntity target,
            @Nullable AstEntity source,
            long startedAtMs,
            long expiresAtMs,
            long nextTickAtMs,
            int stack,
            double snapshotPower,
            int tickIntervalTicks,
            @NotNull DamageType damageType,
            @NotNull DamageElement damageElement
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
        this.stack = stack;
        this.snapshotPower = snapshotPower;
        this.tickIntervalTicks = tickIntervalTicks;
        this.damageType = damageType;
        this.damageElement = damageElement;
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
    public int stack() { return stack; }
    public double snapshotPower() { return snapshotPower; }
    public int tickIntervalTicks() { return tickIntervalTicks; }
    public @NotNull DamageType damageType() { return damageType; }
    public @NotNull DamageElement damageElement() { return damageElement; }

    public void expiresAtMs(long expiresAtMs) { this.expiresAtMs = expiresAtMs; }
    public void lastTickAtMs(long lastTickAtMs) { this.lastTickAtMs = lastTickAtMs; }
    public void nextTickAtMs(long nextTickAtMs) { this.nextTickAtMs = nextTickAtMs; }
    public void stack(int stack) { this.stack = stack; }
    public void snapshotPower(double snapshotPower) { this.snapshotPower = snapshotPower; }
    public void tickIntervalTicks(int tickIntervalTicks) { this.tickIntervalTicks = tickIntervalTicks; }
    public void damageType(@NotNull DamageType damageType) { this.damageType = damageType; }
    public void damageElement(@NotNull DamageElement damageElement) { this.damageElement = damageElement; }

    /**
     * 指定時刻で期限切れかどうかを返します。
     *
     * @param nowMs 現在時刻ミリ秒
     * @return 期限切れなら true
     */
    public boolean expired(long nowMs) {
        return expiresAtMs <= nowMs;
    }
}
