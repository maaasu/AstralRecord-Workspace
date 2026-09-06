package io.github.maaasu.astralRecord.feature.world.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 管理者用ワールドテレポートアイテムの生成と識別を担当します。
 */
public final class AdminWorldTeleportItemService {
    private static final Material ITEM_MATERIAL = Material.NETHER_STAR;
    private static final NamespacedKey ITEM_MARKER_KEY =
            new NamespacedKey("astralrecord", "admin_world_teleport_item");

    /**
     * 管理者用ワールドテレポートアイテムを1個生成します。
     *
     * @return PDC で識別可能な管理者用アイテム
     */
    public @NotNull ItemStack createItem() {
        ItemStack item = new ItemStack(ITEM_MATERIAL);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.displayName(Component.text(
                "ワールドテレポート",
                NamedTextColor.AQUA,
                TextDecoration.BOLD
        ));
        meta.lore(List.of(
                Component.text("右クリックでワールド一覧を開きます", NamedTextColor.GRAY),
                Component.text("管理者専用", NamedTextColor.RED)
        ));
        meta.getPersistentDataContainer().set(ITEM_MARKER_KEY, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * 指定アイテムが管理者用ワールドテレポートアイテムか判定します。
     *
     * @param item 判定対象アイテム
     * @return 対象アイテムなら {@code true}
     */
    public boolean isTeleportItem(@Nullable ItemStack item) {
        if (item == null || item.getType() != ITEM_MATERIAL || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        Byte marker = meta.getPersistentDataContainer().get(ITEM_MARKER_KEY, PersistentDataType.BYTE);
        return marker != null && marker != 0;
    }
}
