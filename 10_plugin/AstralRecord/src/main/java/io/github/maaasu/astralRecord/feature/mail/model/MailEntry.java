package io.github.maaasu.astralRecord.feature.mail.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.util.List;

public record MailEntry(
    @NotNull String id,
    @NotNull String icon,
    @Nullable String iconTexture,
    @NotNull String title,
    @NotNull String body,
    @NotNull LocalDateTime publishFrom,
    @Nullable LocalDateTime publishTo,
    boolean receiveOnRead,
    @NotNull List<MailReward> rewards,
    boolean read,
    @Nullable LocalDateTime readAt,
    boolean currencyClaimed
) {
    /** 既存の非Webメール構築呼出との互換コンストラクタです。 */
    public MailEntry(String id, String icon, String iconTexture, String title, String body,
                     LocalDateTime publishFrom, LocalDateTime publishTo, boolean receiveOnRead,
                     List<MailReward> rewards, boolean read, LocalDateTime readAt) {
        this(id, icon, iconTexture, title, body, publishFrom, publishTo, receiveOnRead,
            rewards, read, readAt, false);
    }
}
