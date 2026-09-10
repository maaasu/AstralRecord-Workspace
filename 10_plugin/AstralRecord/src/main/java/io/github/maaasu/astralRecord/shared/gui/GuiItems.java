package io.github.maaasu.astralRecord.shared.gui;

import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * GUI 用 ItemStack の共通生成処理を提供します。
 */
public final class GuiItems {
    /** カレンシーアイコンに使用する固定 textures 値。 */
    public static final String CURRENCY_HEAD_TEXTURE =
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNmY5NmZmYjk5NzhlNTM2NGQxODkzMjA0ZWY0NzkxNjJjZjU2ZTE5NWRhN2NhYzE0MTBlYmEwNjkzMDUzOTViOCJ9fX0=";
    private static final String OAK_WOOD_ARROW_UP_TEXTURE =
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzA0MGZlODM2YTZjMmZiZDJjN2E5YzhlYzZiZTUxNzRmZGRmMWFjMjBmNTVlMzY2MTU2ZmE1ZjcxMmUxMCJ9fX0=";
    private static final String OAK_WOOD_ARROW_DOWN_TEXTURE =
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzQzNzM0NmQ4YmRhNzhkNTI1ZDE5ZjU0MGE5NWU0ZTc5ZGFlZGE3OTVjYmM1YTEzMjU2MjM2MzEyY2YifX19";
    private static final String OAK_WOOD_ARROW_RIGHT_TEXTURE =
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMTliZjMyOTJlMTI2YTEwNWI1NGViYTcxM2FhMWIxNTJkNTQxYTFkODkzODgyOWM1NjM2NGQxNzhlZDIyYmYifX19";
    private static final String OAK_WOOD_ARROW_LEFT_TEXTURE =
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYmQ2OWUwNmU1ZGFkZmQ4NGU1ZjNkMWMyMTA2M2YyNTUzYjJmYTk0NWVlMWQ0ZDcxNTJmZGM1NDI1YmMxMmE5In19fQ==";
    private static final String OAK_WOOD_BLANK_TEXTURE =
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNWRiNTMyYjVjY2VkNDZiNGI1MzVlY2UxNmVjZWQ3YmJjNWNhYzU1NTk0ZDYxZThiOGY4ZWFjNDI5OWM5ZmMifX19";

    private GuiItems() {
    }

    /**
     * 表示名と lore のイタリックを無効化した GUI 用 ItemStack を生成します。
     *
     * @param material アイテム種別
     * @param name 表示名
     * @param lore 説明行
     * @return GUI 表示用 ItemStack
     */
    public static @NotNull ItemStack create(
        @NotNull Material material,
        @NotNull Component name,
        @NotNull List<Component> lore
    ) {
        var itemStack = new ItemStack(material);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(noItalic(name));
            meta.lore(lore.stream().map(GuiItems::noItalic).toList());
            meta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(meta);
        }
        ItemStackFactory.hideBundleContentsTooltip(itemStack);
        return itemStack;
    }

    /**
     * master 指定の PLAYER_HEAD textures を必要時だけ適用して GUI ItemStack を生成します。
     *
     * @param material アイテム種別
     * @param name 表示名
     * @param lore 説明行
     * @param iconTexture Base64 textures 値
     * @return GUI 表示用 ItemStack
     */
    public static @NotNull ItemStack create(
        @NotNull Material material,
        @NotNull Component name,
        @NotNull List<Component> lore,
        @Nullable String iconTexture
    ) {
        ItemStack itemStack = create(material, name, lore);
        HeadTextureItemStackSupport.apply(itemStack, iconTexture);
        return itemStack;
    }

    /**
     * 非同期操作の完了待ちを示す共通の時計アイコンを生成します。
     *
     * @return 処理中表示用 ItemStack
     */
    public static @NotNull ItemStack processingItem() {
        return create(
            Material.CLOCK,
            Component.text("処理中...", NamedTextColor.YELLOW),
            List.of(Component.text("完了までお待ちください", NamedTextColor.GRAY))
        );
    }

    /**
     * 永続データで識別できる灰色ガラスのプレースホルダーを生成します。
     *
     * @param markerKey プレースホルダー識別キー
     * @return プレースホルダー ItemStack
     */
    public static @NotNull ItemStack placeholder(@NotNull NamespacedKey markerKey) {
        var itemStack = create(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(markerKey, PersistentDataType.INTEGER, 1);
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    /**
     * 戻り先が存在しない GUI に表示する共通の閉じるボタンを生成します。
     *
     * @return 閉じるボタン ItemStack
     */
    public static @NotNull ItemStack closeButton() {
        return create(
            Material.BARRIER,
            Component.text("閉じる", NamedTextColor.RED, TextDecoration.BOLD),
            List.of(Component.text("画面を閉じます", NamedTextColor.GRAY))
        );
    }

    /**
     * 前画面へ戻る GUI で共通利用する戻るボタンを生成します。
     *
     * @return 戻るボタン ItemStack
     */
    public static @NotNull ItemStack backButton() {
        return create(
            Material.SPECTRAL_ARROW,
            Component.text("戻る", NamedTextColor.WHITE, TextDecoration.BOLD),
            List.of(Component.text("前の画面へ戻ります", NamedTextColor.GRAY))
        );
    }

    /**
     * 前ページへ移動する Oak Wood Arrow Left のボタンを生成します。
     *
     * @param name 表示名
     * @param lore 説明行
     * @return 前ページボタン
     */
    public static @NotNull ItemStack previousPageButton(@NotNull Component name, @NotNull List<Component> lore) {
        return previousPageButton(name, lore, true);
    }

    /**
     * 前ページの可否に応じたページングボタンを生成します。
     *
     * @param name 表示名
     * @param lore 説明行
     * @param enabled 前ページへ移動可能なら {@code true}
     * @return 有効時は Oak Wood Arrow Left、無効時は Oak Wood Blank
     */
    public static @NotNull ItemStack previousPageButton(
        @NotNull Component name,
        @NotNull List<Component> lore,
        boolean enabled
    ) {
        return texturedHead(name, lore, enabled ? OAK_WOOD_ARROW_LEFT_TEXTURE : OAK_WOOD_BLANK_TEXTURE);
    }

    /**
     * 次ページへ移動する Oak Wood Arrow Right のボタンを生成します。
     *
     * @param name 表示名
     * @param lore 説明行
     * @return 次ページボタン
     */
    public static @NotNull ItemStack nextPageButton(@NotNull Component name, @NotNull List<Component> lore) {
        return nextPageButton(name, lore, true);
    }

    /**
     * 次ページの可否に応じたページングボタンを生成します。
     *
     * @param name 表示名
     * @param lore 説明行
     * @param enabled 次ページへ移動可能なら {@code true}
     * @return 有効時は Oak Wood Arrow Right、無効時は Oak Wood Blank
     */
    public static @NotNull ItemStack nextPageButton(
        @NotNull Component name,
        @NotNull List<Component> lore,
        boolean enabled
    ) {
        return texturedHead(name, lore, enabled ? OAK_WOOD_ARROW_RIGHT_TEXTURE : OAK_WOOD_BLANK_TEXTURE);
    }

    /**
     * インベントリ行を上下へ移動する Oak Wood Arrow のボタンを生成します。
     *
     * @param up 上方向なら {@code true}
     * @param name 表示名
     * @param lore 説明行
     * @param availableMoves 現在位置から移動可能な行数
     * @param enabled 移動可能なら {@code true}
     * @return スクロールボタン。スタック数は移動可能行数（0件時は1）
     */
    public static @NotNull ItemStack scrollButton(
        boolean up,
        @NotNull Component name,
        @NotNull List<Component> lore,
        int availableMoves,
        boolean enabled
    ) {
        if (!enabled) {
            return texturedHead(name, lore, OAK_WOOD_BLANK_TEXTURE);
        }
        ItemStack itemStack = texturedHead(
            name,
            lore,
            up ? OAK_WOOD_ARROW_UP_TEXTURE : OAK_WOOD_ARROW_DOWN_TEXTURE
        );
        itemStack.setAmount(Math.max(1, availableMoves));
        return itemStack;
    }

    private static @NotNull ItemStack texturedHead(
        @NotNull Component name,
        @NotNull List<Component> lore,
        @NotNull String texture
    ) {
        ItemStack itemStack = create(Material.PLAYER_HEAD, name, lore);
        HeadTextureItemStackSupport.apply(itemStack, texture);
        return itemStack;
    }

    /**
     * 指定した素材と永続データキーを持つ GUI マーカーかを判定します。
     *
     * @param itemStack 判定対象
     * @param expectedMaterial 期待する素材
     * @param markerKey マーカーキー
     * @return マーカー付き ItemStack なら true
     */
    public static boolean hasMarker(
        @Nullable ItemStack itemStack,
        @NotNull Material expectedMaterial,
        @NotNull NamespacedKey markerKey
    ) {
        if (itemStack == null || itemStack.getType() != expectedMaterial || !itemStack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = itemStack.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(markerKey, PersistentDataType.INTEGER);
    }

    /**
     * コンポーネントのイタリック装飾を無効化します。
     *
     * @param component 対象コンポーネント
     * @return イタリックを無効化したコンポーネント
     */
    public static @NotNull Component noItalic(@NotNull Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }
}
