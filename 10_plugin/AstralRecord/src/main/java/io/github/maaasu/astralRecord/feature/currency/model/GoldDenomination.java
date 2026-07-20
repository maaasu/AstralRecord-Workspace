package io.github.maaasu.astralRecord.feature.currency.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * ゴールド系通貨の額面と表示情報を定義します。
 */
public enum GoldDenomination {
    GOLD("gold", "ルーンの金片", "GOLD_NUGGET", 1L),
    GOLD_COIN("gold_coin", "ヴァルハラの金貨", "RAW_GOLD", 10L),
    GOLD_INGOT("gold_ingot", "ミズガルズの黄金インゴット", "GOLD_INGOT", 100L),
    GOLD_BLOCK("gold_block", "アースガルズの黄金ブロック", "GOLD_BLOCK", 1_000L),
    GOLD_DIAMOND("gold_diamond", "ビフレストのダイヤ", "DIAMOND", 10_000L),
    GOLD_DIAMOND_BLOCK("gold_diamond_block", "神域のダイヤ結晶", "DIAMOND_BLOCK", 100_000L),
    YGGDRASIL_STAR_CORE("yggdrasil_star_core", "ユグドラシルの星核", "NETHER_STAR", 1_000_000L);

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
     * 1段階下のゴールド額面を返します。
     *
     * @return 下位額面。最小額面の場合は {@code null}
     */
    public @Nullable GoldDenomination lower() {
        int index = ordinal() - 1;
        return index < 0 ? null : values()[index];
    }

    /**
     * 1段階上のゴールド額面を返します。
     *
     * @return 上位額面。最大額面の場合は {@code null}
     */
    public @Nullable GoldDenomination higher() {
        int index = ordinal() + 1;
        return index >= values().length ? null : values()[index];
    }

    /**
     * この額面1個を直下の額面へ崩したときの個数を返します。
     *
     * @return 下位額面との交換比率。最小額面の場合は0
     */
    public long lowerExchangeRatio() {
        GoldDenomination lower = lower();
        return lower == null ? 0L : goldValue / lower.goldValue;
    }

    /**
     * 最上位のゴールド額面を返します。
     *
     * @return 最上位額面
     */
    public static @NotNull GoldDenomination highest() {
        GoldDenomination[] denominations = values();
        return denominations[denominations.length - 1];
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
