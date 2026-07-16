package io.github.maaasu.astralRecord.feature.currency.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * ゴールド系通貨の額面と表示情報を定義します。
 */
public enum GoldDenomination {
    GOLD("gold", "ゴールド", "GOLD_NUGGET", 1L),
    GOLD_COIN("gold_coin", "金貨", "RAW_GOLD", 10L),
    GOLD_INGOT("gold_ingot", "金の延べ棒", "GOLD_INGOT", 100L),
    GOLD_BLOCK("gold_block", "金塊", "GOLD_BLOCK", 1_000L);

    private final String itemId;
    private final String displayName;
    private final String icon;
    private final long goldValue;

    GoldDenomination(
        @NotNull String itemId,
        @NotNull String displayName,
        @NotNull String icon,
        long goldValue
    ) {
        this.itemId = itemId;
        this.displayName = displayName;
        this.icon = icon;
        this.goldValue = goldValue;
    }

    /**
     * 通貨アイテム ID を返します。
     *
     * @return 通貨アイテム ID
     */
    public @NotNull String itemId() {
        return itemId;
    }

    /**
     * プレイヤー向け表示名を返します。
     *
     * @return 表示名
     */
    public @NotNull String displayName() {
        return displayName;
    }

    /**
     * Bukkit Material 名として扱うアイコン ID を返します。
     *
     * @return アイコン ID
     */
    public @NotNull String icon() {
        return icon;
    }

    /**
     * 基本通貨ゴールドに換算した価値を返します。
     *
     * @return 1 以上のゴールド換算価値
     */
    public long goldValue() {
        return goldValue;
    }

    /**
     * アイテム ID に対応する額面を解決します。
     *
     * @param itemId 解決対象アイテム ID
     * @return 対応額面。未対応の場合は {@code null}
     */
    public static @Nullable GoldDenomination findByItemId(@Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        String normalized = itemId.trim();
        return Arrays.stream(values())
            .filter(denomination -> denomination.itemId.equalsIgnoreCase(normalized))
            .findFirst()
            .orElse(null);
    }
}
