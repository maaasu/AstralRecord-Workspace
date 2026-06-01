package io.github.maaasu.astralRecord.feature.menu.model;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

/**
 * メニュー系 UI で共通利用するアイコン定義です。
 */
public enum MenuIconDefinition {
    ACCOUNT_INFO(Material.PLAYER_HEAD, "アカウント情報", NamedTextColor.GOLD),
    CURRENCY(Material.EMERALD, "通貨", NamedTextColor.GOLD),
    EQUIPMENT(Material.NETHERITE_CHESTPLATE, "装備", NamedTextColor.GOLD);

    private final Material material;
    private final String displayNameJa;
    private final NamedTextColor color;

    MenuIconDefinition(
        @NotNull Material material,
        @NotNull String displayNameJa,
        @NotNull NamedTextColor color
    ) {
        this.material = material;
        this.displayNameJa = displayNameJa;
        this.color = color;
    }

    /**
     * アイコン表示に使う Material を返します。
     *
     * @return アイコン Material
     */
    public @NotNull Material getMaterial() {
        return material;
    }

    /**
     * 日本語表示名を返します。
     *
     * @return 日本語表示名
     */
    public @NotNull String getDisplayNameJa() {
        return displayNameJa;
    }

    /**
     * 表示色を返します。
     *
     * @return 表示色
     */
    public @NotNull NamedTextColor getColor() {
        return color;
    }
}
