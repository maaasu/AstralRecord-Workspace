package io.github.maaasu.astralRecord.feature.item.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * アイテムのレアリティ順序とユーザー向け表示名を一元管理します。
 */
public enum ItemRarity {
    COMMON("common", "コモン", 1),
    UNCOMMON("uncommon", "アンコモン", 2),
    RARE("rare", "レア", 3),
    EPIC("epic", "エピック", 4),
    LEGENDARY("legendary", "レジェンダリー", 5),
    MYTHIC("mythic", "ミシック", 6);

    private final String value;
    private final String displayNameJa;
    private final int rank;

    ItemRarity(@NotNull String value, @NotNull String displayNameJa, int rank) {
        this.value = value;
        this.displayNameJa = displayNameJa;
        this.rank = rank;
    }

    public @NotNull String value() {
        return value;
    }

    public @NotNull String displayNameJa() {
        return displayNameJa;
    }

    public int rank() {
        return rank;
    }

    public static @Nullable ItemRarity find(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
            .filter(rarity -> rarity.value.equalsIgnoreCase(value.trim()))
            .findFirst()
            .orElse(null);
    }

    public static @NotNull String displayNameJa(@Nullable String value) {
        ItemRarity rarity = find(value);
        if (rarity != null) {
            return rarity.displayNameJa;
        }
        return value == null || value.isBlank() ? "不明" : value.trim();
    }

    public static int rankOf(@Nullable String value) {
        ItemRarity rarity = find(value);
        return rarity == null ? 0 : rarity.rank;
    }

    public static @NotNull List<String> orderedValues() {
        return Arrays.stream(values()).map(ItemRarity::value).toList();
    }
}
