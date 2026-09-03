package io.github.maaasu.astralRecord.shared.challenge;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Dungeon と Boss の挑戦参加予約を同じ索引で原子的に管理します。 */
public final class ChallengeParticipationRegistry {
    private final Map<UUID, Reservation> reservationsByOwner = new HashMap<>();
    private final Map<String, UUID> ownerByPartyKey = new HashMap<>();
    private final Map<UUID, UUID> ownerByParticipant = new HashMap<>();

    /**
     * パーティーキーと参加者一覧を一つの挑戦所有者として予約します。
     * 既存の同一所有者による予約は、新しい参加者一覧へ置き換えます。
     *
     * @param ownerId 挑戦所有者の一意なID
     * @param partyKey 挑戦パーティーキー
     * @param participantIds 予約する参加者UUID
     * @param displayName 他の挑戦から表示する挑戦名
     * @return 予約成功、または競合中の挑戦名
     */
    public synchronized @NotNull ReservationResult reserve(
            @NotNull UUID ownerId,
            @NotNull String partyKey,
            @NotNull Collection<UUID> participantIds,
            @NotNull String displayName
    ) {
        if (partyKey.isBlank()) {
            throw new IllegalArgumentException("partyKey must not be blank");
        }
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        Set<UUID> normalizedParticipants = new LinkedHashSet<>(participantIds);
        UUID partyOwner = ownerByPartyKey.get(partyKey);
        if (partyOwner != null && !partyOwner.equals(ownerId)) {
            return ReservationResult.conflict(displayNameOf(partyOwner));
        }
        for (UUID participantId : normalizedParticipants) {
            UUID participantOwner = ownerByParticipant.get(participantId);
            if (participantOwner != null && !participantOwner.equals(ownerId)) {
                return ReservationResult.conflict(displayNameOf(participantOwner));
            }
        }

        releaseInternal(ownerId);
        Reservation reservation = new Reservation(ownerId, partyKey, normalizedParticipants, displayName);
        reservationsByOwner.put(ownerId, reservation);
        ownerByPartyKey.put(partyKey, ownerId);
        for (UUID participantId : normalizedParticipants) {
            ownerByParticipant.put(participantId, ownerId);
        }
        return ReservationResult.success();
    }

    /**
     * 指定参加者だけを既存予約から外します。パーティーキー予約は維持します。
     *
     * @param ownerId 挑戦所有者の一意なID
     * @param participantId 予約から外す参加者UUID
     * @return 予約が存在し、参加者を外せた場合は {@code true}
     */
    public synchronized boolean removeParticipant(
            @NotNull UUID ownerId,
            @NotNull UUID participantId
    ) {
        Reservation reservation = reservationsByOwner.get(ownerId);
        if (reservation == null || !reservation.participantIds().contains(participantId)) {
            return false;
        }
        Set<UUID> remaining = new LinkedHashSet<>(reservation.participantIds());
        remaining.remove(participantId);
        reserve(ownerId, reservation.partyKey(), remaining, reservation.displayName());
        return true;
    }

    /**
     * 挑戦終了後にパーティーキーと参加者の予約をすべて解放します。
     *
     * @param ownerId 挑戦所有者の一意なID
     */
    public synchronized void release(@NotNull UUID ownerId) {
        releaseInternal(ownerId);
    }

    /**
     * 指定所有者が予約を保持しているか返します。
     *
     * @param ownerId 挑戦所有者の一意なID
     * @return 予約中なら {@code true}
     */
    public synchronized boolean contains(@NotNull UUID ownerId) {
        return reservationsByOwner.containsKey(ownerId);
    }

    private void releaseInternal(@NotNull UUID ownerId) {
        Reservation reservation = reservationsByOwner.remove(ownerId);
        if (reservation == null) {
            return;
        }
        ownerByPartyKey.remove(reservation.partyKey(), ownerId);
        for (UUID participantId : reservation.participantIds()) {
            ownerByParticipant.remove(participantId, ownerId);
        }
    }

    private @Nullable String displayNameOf(@NotNull UUID ownerId) {
        Reservation reservation = reservationsByOwner.get(ownerId);
        return reservation == null ? null : reservation.displayName();
    }

    private record Reservation(
            @NotNull UUID ownerId,
            @NotNull String partyKey,
            @NotNull Set<UUID> participantIds,
            @NotNull String displayName
    ) {
        private Reservation {
            participantIds = Set.copyOf(participantIds);
        }
    }

    /** 予約処理の結果です。 */
    public record ReservationResult(boolean acquired, @Nullable String conflictingDisplayName) {
        private static @NotNull ReservationResult success() {
            return new ReservationResult(true, null);
        }

        private static @NotNull ReservationResult conflict(@Nullable String displayName) {
            return new ReservationResult(false, displayName);
        }
    }
}
