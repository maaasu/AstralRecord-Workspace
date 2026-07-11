package io.github.maaasu.astralRecord.feature.boss.model;

import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime state for a single boss challenge.
 */
public final class BossChallengeInstance {
    private final UUID challengeId;
    private final String partyKey;
    private final UUID initiatorId;
    private final MobTemplate bossTemplate;
    private final BossChallengeConfig config;
    private final List<UUID> expectedParticipantIds;
    private final long createdAtMs;
    private final Map<UUID, Double> damageByPlayerId = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> deathsByPlayerId = new ConcurrentHashMap<>();
    private volatile List<UUID> participantIds = List.of();
    private boolean participantsConfirmed;
    private int deathCount;
    private BossChallengeState state = BossChallengeState.PREPARING;
    private BossFieldInstance field;
    private UUID bossMobInstanceId;
    private long startedAtMs;
    private long resultWaitEndsAtMs;
    private BukkitTask resultWaitTask;
    private DisplayTextService.ManagedTextDisplay resultDisplay;

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
        this.expectedParticipantIds = List.copyOf(participantIds);
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

    public @NotNull List<UUID> expectedParticipantIds() {
        return expectedParticipantIds;
    }

    public @NotNull List<UUID> participantIds() {
        return participantIds;
    }

    /**
     * ボスフィールドへ実際に入場する参加者を確定します。
     *
     * @param participantIds 入場時点で条件を満たしたプレイヤー UUID
     */
    public void confirmParticipants(@NotNull List<UUID> participantIds) {
        this.participantIds = List.copyOf(participantIds);
        this.participantsConfirmed = true;
    }

    public boolean participantsConfirmed() {
        return participantsConfirmed;
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

    /**
     * Adds effective boss damage dealt by a participant.
     *
     * @param playerId participant player UUID
     * @param amount damage amount
     */
    public void addDamage(@NotNull UUID playerId, double amount) {
        if (amount <= 0.0D) {
            return;
        }
        damageByPlayerId.merge(playerId, amount, Double::sum);
    }

    public @NotNull Map<UUID, Double> damageSnapshot() {
        return Map.copyOf(damageByPlayerId);
    }

    /**
     * 参加者の死亡回数を加算します。
     *
     * @param playerId 死亡した参加者 UUID
     * @return パーティー共有の累計死亡回数
     */
    public int recordDeath(@NotNull UUID playerId) {
        deathsByPlayerId.merge(playerId, 1, Integer::sum);
        return ++deathCount;
    }

    public int deathCount() {
        return deathCount;
    }

    public int playerDeathCount(@NotNull UUID playerId) {
        return deathsByPlayerId.getOrDefault(playerId, 0);
    }

    public @NotNull Map<UUID, Integer> deathSnapshot() {
        return Map.copyOf(deathsByPlayerId);
    }

    public long resultWaitEndsAtMs() {
        return resultWaitEndsAtMs;
    }

    public void resultWaitEndsAtMs(long resultWaitEndsAtMs) {
        this.resultWaitEndsAtMs = resultWaitEndsAtMs;
    }

    public @Nullable BukkitTask resultWaitTask() {
        return resultWaitTask;
    }

    public void resultWaitTask(@Nullable BukkitTask resultWaitTask) {
        this.resultWaitTask = resultWaitTask;
    }

    public @Nullable DisplayTextService.ManagedTextDisplay resultDisplay() {
        return resultDisplay;
    }

    public void resultDisplay(@Nullable DisplayTextService.ManagedTextDisplay resultDisplay) {
        this.resultDisplay = resultDisplay;
    }
}
