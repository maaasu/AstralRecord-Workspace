package io.github.maaasu.astralRecord.feature.party.model;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * オンライン中だけ保持する一時パーティーです。
 */
public final class Party {
    private final UUID partyId;
    private final LinkedHashSet<UUID> members;
    private final Instant createdAt;
    private UUID leaderId;

    /**
     * パーティーを作成します。
     *
     * @param partyId パーティーID
     * @param leaderId リーダーのプレイヤーUUID
     */
    public Party(@NotNull UUID partyId, @NotNull UUID leaderId) {
        this.partyId = partyId;
        this.leaderId = leaderId;
        this.members = new LinkedHashSet<>();
        this.members.add(leaderId);
        this.createdAt = Instant.now();
    }

    public @NotNull UUID getPartyId() {
        return partyId;
    }

    public @NotNull UUID getLeaderId() {
        return leaderId;
    }

    public void setLeaderId(@NotNull UUID leaderId) {
        this.leaderId = leaderId;
    }

    public @NotNull Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isLeader(@NotNull UUID playerId) {
        return leaderId.equals(playerId);
    }

    public boolean contains(@NotNull UUID playerId) {
        return members.contains(playerId);
    }

    public void addMember(@NotNull UUID playerId) {
        members.add(playerId);
    }

    public void removeMember(@NotNull UUID playerId) {
        members.remove(playerId);
    }

    public int size() {
        return members.size();
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }

    public @NotNull List<UUID> members() {
        return new ArrayList<>(members);
    }
}
