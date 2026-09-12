package io.github.maaasu.astralRecord.feature.party.model;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * 承認制のパーティー掲示板から送られた参加申請です。
 *
 * @param partyId 申請先パーティーID
 * @param requesterId 申請者のプレイヤーUUID
 * @param createdAt 申請作成日時
 */
public record PartyJoinRequest(
    @NotNull UUID partyId,
    @NotNull UUID requesterId,
    @NotNull Instant createdAt
) {
}
