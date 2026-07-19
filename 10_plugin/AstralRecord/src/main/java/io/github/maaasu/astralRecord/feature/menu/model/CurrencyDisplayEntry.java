package io.github.maaasu.astralRecord.feature.menu.model;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * メニューアイコンに表示する通貨残高のスナップショットです。
 *
 * @param currencyId 通貨アイテム ID
 * @param displayName 通貨の表示名
 * @param amount 所持数
 */
public record CurrencyDisplayEntry(
    @NotNull String currencyId,
    @NotNull Component displayName,
    long amount
) {
    public CurrencyDisplayEntry {
        Objects.requireNonNull(currencyId, "currencyId");
        Objects.requireNonNull(displayName, "displayName");
        if (currencyId.isBlank()) {
            throw new IllegalArgumentException("currencyId must not be blank");
        }
        if (amount < 0L) {
            throw new IllegalArgumentException("amount must not be negative");
        }
    }
}
