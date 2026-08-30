package io.github.maaasu.astralRecord.feature.market.repository;

import org.jetbrains.annotations.NotNull;

/**
 * Market API から応答を受信できず、冪等キー付き mutation を再送できる場合の例外です。
 */
public final class MarketTransportException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public MarketTransportException(@NotNull String message, @NotNull Throwable cause) {
        super(message, cause);
    }
}
