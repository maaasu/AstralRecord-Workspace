package io.github.maaasu.astralRecord.feature.menu.model;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

/**
 * メニュー系 UI で共通利用するアイコン定義です。
 */
public enum MenuIconDefinition {
    UNSET(Material.GRAY_DYE, "未設定", NamedTextColor.GRAY, "ショートカット未設定"),
    MAIN_MENU(Material.NETHER_STAR, "メニュー", NamedTextColor.GREEN, "クリックしてメニューを開く"),
    ACCOUNT_INFO(Material.PLAYER_HEAD, "プレイヤー情報", NamedTextColor.GOLD, "プロフィールとステータスを確認"),
    QUEST(Material.WRITABLE_BOOK, "クエスト", NamedTextColor.GREEN, "受領中のクエストを確認・破棄"),
    PLAYER_SETTING(Material.COMPARATOR, "プレイヤー設定", NamedTextColor.AQUA, "表示設定を変更"),
    EQUIPMENT(Material.NETHERITE_CHESTPLATE, "装備", NamedTextColor.GOLD, "現在装備中の防具"),
    TRASH(Material.LAVA_BUCKET, "ゴミ箱", NamedTextColor.RED, "アイテムを破棄する"),
    GUIDE(Material.BOOK, "ガイド", NamedTextColor.LIGHT_PURPLE, "ヘルプを開く"),
    RETURN_TO_BASE(Material.BEACON, "帰還", NamedTextColor.AQUA, "3秒間移動しなければ拠点へ帰還"),
    ADVENTURE_RECORD(Material.WRITTEN_BOOK, "冒険記録", NamedTextColor.GOLD, "魔物録・厄災録・モブ検索を開く"),
    MAIL(Material.WRITABLE_BOOK, "メール", NamedTextColor.GOLD, "お知らせと報酬を確認"),
    SKILL_BIND(Material.ENCHANTED_BOOK, "スキル設定", NamedTextColor.AQUA, "スキルプリセットを設定"),
    CURRENCY(Material.BUNDLE, "カレンシー", NamedTextColor.GOLD, "所持通貨を確認"),
    PARTY(Material.PLAYER_HEAD, "パーティー", NamedTextColor.AQUA, "作成・招待・参加状況を確認"),
    PLAYER_LIST(Material.SPYGLASS, "プレイヤー一覧", NamedTextColor.YELLOW, "参加中プレイヤーの基本情報を確認");

    private final Material material;
    private final String displayNameJa;
    private final NamedTextColor color;
    private final String descriptionJa;

    MenuIconDefinition(
        @NotNull Material material,
        @NotNull String displayNameJa,
        @NotNull NamedTextColor color,
        @NotNull String descriptionJa
    ) {
        this.material = material;
        this.displayNameJa = displayNameJa;
        this.color = color;
        this.descriptionJa = descriptionJa;
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

    /**
     * 基本説明を返します。
     *
     * @return 日本語の基本説明
     */
    public @NotNull String getDescriptionJa() {
        return descriptionJa;
    }
}
