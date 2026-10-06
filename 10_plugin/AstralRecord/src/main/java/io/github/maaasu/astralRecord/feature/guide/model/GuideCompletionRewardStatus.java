package io.github.maaasu.astralRecord.feature.guide.model;

import java.util.UUID;

/** API が返すアカウント単位の全ガイド達成報酬状態です。 */
public record GuideCompletionRewardStatus(
    UUID accountId,
    boolean configured,
    boolean allCompleted,
    boolean claimed,
    boolean canClaim,
    String mailId
) {
}
