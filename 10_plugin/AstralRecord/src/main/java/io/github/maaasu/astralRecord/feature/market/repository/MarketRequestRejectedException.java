package io.github.maaasu.astralRecord.feature.market.repository;

import org.jetbrains.annotations.NotNull;

/** Market API が transaction を確定せず、4xx で操作を拒否したことを表します。 */
public final class MarketRequestRejectedException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    private final int statusCode;

    public MarketRequestRejectedException(int statusCode, @NotNull String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
