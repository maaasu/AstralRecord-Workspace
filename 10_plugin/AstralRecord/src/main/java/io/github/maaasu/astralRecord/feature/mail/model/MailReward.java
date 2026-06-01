package io.github.maaasu.astralRecord.feature.mail.model;

import org.jetbrains.annotations.NotNull;

public record MailReward(
    @NotNull String itemId,
    @NotNull String category,
    int amount
) {
}
