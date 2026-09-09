package io.github.maaasu.astralRecord.shared.gui;

import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * GUI 用 ItemStack の共通生成処理を提供します。
 */
public final class GuiItems {
    private static final String FOREST_GREEN_ARROW_UP_TEXTURE =
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNGNlMzZmY2IxZTVmNmIzNjUxN2ZiYmViOWNiZjRiMGMwNWMzMGQ4YmRiNTE1NDgyNGU2MGU2ZDU1MGY1MjhlOSJ9fX0=";
    private static final String FOREST_GREEN_ARROW_DOWN_TEXTURE =
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzM1YzFlZjEyN2Y1YzUyY2IzODlhOGFjNmRmM2Y2ZmM2NmNkMzdmMjYwOTRiM2UxZTc2ZDAxNzcxMTViYjA4ZiJ9fX0=";
    private static final String FOREST_GREEN_ARROW_RIGHT_TEXTURE =
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYWM5YzY3YTlmMTY4NWNkMWRhNDNlODQxZmU3ZWJiMTdmNmFmNmVhMTJhN2UxZjI3MjJmNWU3ZjA4OThkYjlmMyJ9fX0=";
    private static final String FOREST_GREEN_ARROW_LEFT_TEXTURE =
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMWExZWYzOThhMTdmMWFmNzQ3NzAxNDUxN2Y3ZjE0MWQ4ODZkZjQxYTMyYzczOGNjOGE4M2ZiNTAyOTdiZDkyMSJ9fX0=";

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
     * 前ページへ移動する Forest Green Arrow Left のボタンを生成します。
     *
     * @param name 表示名
     * @param lore 説明行
     * @return 前ページボタン
     */
    public static @NotNull ItemStack previousPageButton(@NotNull Component name, @NotNull List<Component> lore) {
        return texturedHead(name, lore, FOREST_GREEN_ARROW_LEFT_TEXTURE);
    }

    /**
     * 次ページへ移動する Forest Green Arrow Right のボタンを生成します。
     *
     * @param name 表示名
     * @param lore 説明行
     * @return 次ページボタン
     */
    public static @NotNull ItemStack nextPageButton(@NotNull Component name, @NotNull List<Component> lore) {
        return texturedHead(name, lore, FOREST_GREEN_ARROW_RIGHT_TEXTURE);
    }

    /**
     * インベントリ行を上下へ移動する Forest Green Arrow のボタンを生成します。
     *
     * @param up 上方向なら {@code true}
     * @param name 表示名
     * @param lore 説明行
     * @param availableMoves 現在位置から移動可能な行数
     * @return スクロールボタン。スタック数は移動可能行数（0件時は1）
     */
    public static @NotNull ItemStack scrollButton(
        boolean up,
        @NotNull Component name,
        @NotNull List<Component> lore,
        int availableMoves
    ) {
        ItemStack itemStack = texturedHead(
            name,
            lore,
            up ? FOREST_GREEN_ARROW_UP_TEXTURE : FOREST_GREEN_ARROW_DOWN_TEXTURE
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
        if (!(itemStack.getItemMeta() instanceof SkullMeta skullMeta)) {
            return itemStack;
        }
        PlayerProfile profile = Bukkit.createProfile(
            UUID.nameUUIDFromBytes(texture.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        );
        profile.setProperty(new ProfileProperty("textures", texture));
        skullMeta.setPlayerProfile(profile);
        itemStack.setItemMeta(skullMeta);
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
