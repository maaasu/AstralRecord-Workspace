package io.github.maaasu.astralRecord.feature.mail.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public record MailReward(
    @NotNull String itemId,
    @NotNull String category,
    int amount,
    @Nullable UUID instanceId
) {
    /** 既存の個体IDなし報酬を構築します。 */
    public MailReward(@NotNull String itemId, @NotNull String category, int amount) {
        this(itemId, category, amount, null);
    }
}
