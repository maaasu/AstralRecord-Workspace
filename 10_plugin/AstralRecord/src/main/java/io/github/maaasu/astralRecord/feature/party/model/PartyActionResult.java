package io.github.maaasu.astralRecord.feature.party.model;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import org.jetbrains.annotations.NotNull;

/**
 * パーティー操作の成否と表示メッセージを表します。
 */
public record PartyActionResult(
    boolean success,
    @NotNull PlayerMsgId messageId,
    @NotNull Object[] args
) {
    public static @NotNull PartyActionResult success(@NotNull PlayerMsgId messageId, Object... args) {
        return new PartyActionResult(true, messageId, args);
    }

    public static @NotNull PartyActionResult failure(@NotNull PlayerMsgId messageId, Object... args) {
        return new PartyActionResult(false, messageId, args);
    }
}
