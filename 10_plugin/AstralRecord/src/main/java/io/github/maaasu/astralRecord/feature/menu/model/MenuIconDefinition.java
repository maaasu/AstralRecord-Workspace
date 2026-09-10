package io.github.maaasu.astralRecord.feature.menu.model;

import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * メニュー系 UI で共通利用するアイコン定義です。
 */
public enum MenuIconDefinition {
    UNSET(Material.GRAY_DYE, "未設定", NamedTextColor.GRAY, "ショートカット未設定"),
    MAIN_MENU(Material.NETHER_STAR, "メニュー", NamedTextColor.GREEN, "クリックしてメニューを開く"),
    ACCOUNT_INFO(Material.PLAYER_HEAD, "プレイヤー情報", NamedTextColor.GOLD, "プロフィールとステータスを確認"),
    QUEST(Material.MAP, "クエスト", NamedTextColor.GREEN, "受領中のクエストを確認・破棄"),
    PLAYER_SETTING(Material.COMPARATOR, "プレイヤー設定", NamedTextColor.AQUA, "表示設定を変更"),
    EQUIPMENT(Material.NETHERITE_CHESTPLATE, "装備", NamedTextColor.GOLD, "現在装備中の防具"),
    TRASH(Material.LAVA_BUCKET, "ゴミ箱", NamedTextColor.RED, "アイテムを破棄する"),
    GUIDE(Material.KNOWLEDGE_BOOK, "ガイド", NamedTextColor.LIGHT_PURPLE, "ヘルプを開く"),
    RETURN_TO_BASE(Material.BEACON, "帰還", NamedTextColor.AQUA, "3秒間移動しなければ拠点へ帰還"),
    ADVENTURE_RECORD(Material.SPYGLASS, "冒険記録", NamedTextColor.GOLD, "モブ情報一覧とモブ検索を開く"),
    MAIL(Material.CHEST, "メール", NamedTextColor.GOLD, "お知らせと報酬を確認"),
    SKILL_BIND(Material.ENCHANTING_TABLE, "スキルマネージャー", NamedTextColor.AQUA, "習得・強化・バインドを管理"),
    CURRENCY(
        Material.PLAYER_HEAD,
        "Bag of Seeds",
        NamedTextColor.GOLD,
        "所持通貨を確認",
        GuiItems.CURRENCY_HEAD_TEXTURE,
        List.of(
            Component.text("Custom Head ID: 129729", NamedTextColor.GRAY),
            Component.text("www.minecraft-heads.com", NamedTextColor.BLUE)
        ),
        Component.text("Bag of Seeds", NamedTextColor.GOLD, TextDecoration.BOLD, TextDecoration.UNDERLINED)
    ),
    PARTY(Material.IRON_CHAIN, "パーティー", NamedTextColor.AQUA, "作成・招待・参加状況を確認"),
    PLAYER_LIST(Material.NAME_TAG, "プレイヤー一覧", NamedTextColor.YELLOW, "参加中プレイヤーの基本情報を確認");

    private final Material material;
    private final Component displayName;
    private final String displayNameJa;
    private final NamedTextColor color;
    private final String descriptionJa;
    private final String iconTexture;
    private final List<Component> iconLore;

    MenuIconDefinition(
        @NotNull Material material,
        @NotNull String displayNameJa,
        @NotNull NamedTextColor color,
        @NotNull String descriptionJa
    ) {
        this(material, displayNameJa, color, descriptionJa, null, List.of(), Component.text(displayNameJa, color));
    }

    MenuIconDefinition(
        @NotNull Material material,
        @NotNull String displayNameJa,
        @NotNull NamedTextColor color,
        @NotNull String descriptionJa,
        @Nullable String iconTexture,
        @NotNull List<Component> iconLore,
        @NotNull Component displayName
    ) {
        this.material = material;
        this.displayName = displayName;
        this.displayNameJa = displayNameJa;
        this.color = color;
        this.descriptionJa = descriptionJa;
        this.iconTexture = iconTexture;
        this.iconLore = List.copyOf(iconLore);
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
     * 装飾を含む表示名を返します。
     *
     * @return 表示名コンポーネント
     */
    public @NotNull Component getDisplayName() {
        return displayName;
    }

    /**
     * 表示色を返します。
     *
     * @return 表示色
     */
    public @NotNull NamedTextColor getColor() {
        return color;
    }

    /**
     * 基本説明を返します。
     *
     * @return 日本語の基本説明
     */
    public @NotNull String getDescriptionJa() {
        return descriptionJa;
    }

    /**
     * 固定アイコンへ適用する textures 値を返します。
     *
     * @return Base64 textures 値。固定値がない場合は null
     */
    public @Nullable String getIconTexture() {
        return iconTexture;
    }

    /**
     * 固定アイコンへ適用する追加 lore を返します。
     *
     * @return 固定アイコン lore
     */
    public @NotNull List<Component> getIconLore() {
        return iconLore;
    }
}
