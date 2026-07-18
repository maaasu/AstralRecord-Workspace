package io.github.maaasu.astralRecord.shared.gui;

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
        return itemStack;
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
