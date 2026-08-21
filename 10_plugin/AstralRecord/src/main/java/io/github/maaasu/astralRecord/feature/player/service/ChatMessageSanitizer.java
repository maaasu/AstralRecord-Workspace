package io.github.maaasu.astralRecord.feature.player.service;

import org.jetbrains.annotations.NotNull;

/**
 * プレイヤー間で共有するチャット本文を、単一行のプレーンテキストへ正規化する。
 * Component.text へ渡す前提のため、{@code &} や {@code §} は変換しない。
 */
public final class ChatMessageSanitizer {

    private ChatMessageSanitizer() {
        // utility class
    }

    /**
     * チャット本文を単一行へ正規化する。
     *
     * @param message 元の本文
     * @return 正規化された本文
     */
    public static @NotNull String normalize(@NotNull String message) {
        return normalize(message, Integer.MAX_VALUE);
    }

    /**
     * チャット本文を単一行へ正規化し、最大コードポイント数で切り詰める。
     *
     * @param message 元の本文
     * @param maxLength 最大コードポイント数
     * @return 正規化された本文
     */
    public static @NotNull String normalize(@NotNull String message, int maxLength) {
        if (maxLength <= 0) {
            return "";
        }

        StringBuilder normalized = new StringBuilder(message.length());
        message.codePoints().forEach(codePoint -> {
            if (codePoint == '\r' || codePoint == '\n' || codePoint == '\t') {
                normalized.append(' ');
            } else if (Character.isISOControl(codePoint)) {
                normalized.append(' ');
            } else {
                normalized.appendCodePoint(codePoint);
            }
        });

        String trimmed = normalized.toString().trim();
        int codePointCount = trimmed.codePointCount(0, trimmed.length());
        if (codePointCount <= maxLength) {
            return trimmed;
        }
        int endIndex = trimmed.offsetByCodePoints(0, maxLength);
        return trimmed.substring(0, endIndex).trim();
    }
}
