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
    private String recruitmentMessage = "";
    private boolean recruitmentApprovalRequired = true;
    private boolean recruitmentPublished;

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

    /**
     * 掲示板へ表示する募集内容を返します。
     *
     * @return 募集内容。未設定時は空文字列
     */
    public @NotNull String getRecruitmentMessage() {
        return recruitmentMessage;
    }

    /**
     * 掲示板へ表示する募集内容を更新します。
     *
     * @param recruitmentMessage 検証済みの募集内容
     */
    public void setRecruitmentMessage(@NotNull String recruitmentMessage) {
        this.recruitmentMessage = recruitmentMessage;
    }

    /**
     * 掲示板経由の参加にリーダー承認が必要か返します。
     *
     * @return 承認が必要なら {@code true}
     */
    public boolean isRecruitmentApprovalRequired() {
        return recruitmentApprovalRequired;
    }

    /**
     * 掲示板経由の参加にリーダー承認が必要か更新します。
     *
     * @param recruitmentApprovalRequired 承認が必要なら {@code true}
     */
    public void setRecruitmentApprovalRequired(boolean recruitmentApprovalRequired) {
        this.recruitmentApprovalRequired = recruitmentApprovalRequired;
    }

    /**
     * 現在パーティー掲示板へ掲載中か返します。
     *
     * @return 掲載中なら {@code true}
     */
    public boolean isRecruitmentPublished() {
        return recruitmentPublished;
    }

    /**
     * パーティー掲示板への掲載状態を更新します。
     *
     * @param recruitmentPublished 掲載中にする場合は {@code true}
     */
    public void setRecruitmentPublished(boolean recruitmentPublished) {
        this.recruitmentPublished = recruitmentPublished;
    }
}
