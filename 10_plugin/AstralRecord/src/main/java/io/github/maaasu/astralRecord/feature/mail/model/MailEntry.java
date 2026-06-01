package io.github.maaasu.astralRecord.feature.mail.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.util.List;

public record MailEntry(
    @NotNull String id,
    @NotNull String icon,
    @NotNull String title,
    @NotNull String body,
    @NotNull LocalDateTime publishFrom,
    @Nullable LocalDateTime publishTo,
    boolean receiveOnRead,
    @NotNull List<MailReward> rewards,
    boolean read,
    @Nullable LocalDateTime readAt
) {
}
