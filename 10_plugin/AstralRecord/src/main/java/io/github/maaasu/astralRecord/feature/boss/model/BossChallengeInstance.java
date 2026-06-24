package io.github.maaasu.astralRecord.feature.boss.model;

import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Runtime state for a single boss challenge.
 */
public final class BossChallengeInstance {
    private final UUID challengeId;
    private final String partyKey;
    private final UUID initiatorId;
    private final MobTemplate bossTemplate;
    private final BossChallengeConfig config;
    private final List<UUID> participantIds;
    private final long createdAtMs;
    private BossChallengeState state = BossChallengeState.PREPARING;
    private BossFieldInstance field;
    private UUID bossMobInstanceId;
    private long startedAtMs;

    public BossChallengeInstance(
            @NotNull UUID challengeId,
            @NotNull String partyKey,
            @NotNull UUID initiatorId,
            @NotNull MobTemplate bossTemplate,
            @NotNull BossChallengeConfig config,
            @NotNull List<UUID> participantIds
    ) {
        this.challengeId = challengeId;
        this.partyKey = partyKey;
        this.initiatorId = initiatorId;
        this.bossTemplate = bossTemplate;
        this.config = config;
        this.participantIds = List.copyOf(participantIds);
        this.createdAtMs = System.currentTimeMillis();
    }

    public @NotNull UUID challengeId() {
        return challengeId;
    }

    public @NotNull String partyKey() {
        return partyKey;
    }

    public @NotNull UUID initiatorId() {
        return initiatorId;
    }

    public @NotNull MobTemplate bossTemplate() {
        return bossTemplate;
    }

    public @NotNull BossChallengeConfig config() {
        return config;
    }

    public @NotNull List<UUID> participantIds() {
        return participantIds;
    }

    public long createdAtMs() {
        return createdAtMs;
    }

    public @NotNull BossChallengeState state() {
        return state;
    }

    public void state(@NotNull BossChallengeState state) {
        this.state = state;
    }

    public @Nullable BossFieldInstance field() {
        return field;
    }

    public void field(@Nullable BossFieldInstance field) {
        this.field = field;
    }

    public @Nullable UUID bossMobInstanceId() {
        return bossMobInstanceId;
    }

    public void bossMobInstanceId(@Nullable UUID bossMobInstanceId) {
        this.bossMobInstanceId = bossMobInstanceId;
    }

    public long startedAtMs() {
        return startedAtMs;
    }

    public void markStarted() {
        this.startedAtMs = System.currentTimeMillis();
        this.state = BossChallengeState.IN_PROGRESS;
    }
}
