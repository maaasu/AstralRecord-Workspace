package io.github.maaasu.astralRecord.feature.loginbonus.view;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * ログインボーナス画面を描画します。
 */
public final class LoginBonusGui {
    public static final int CLOSE_SLOT = 22;
    private static final int STATUS_SLOT = 13;
    private static final Component TITLE = Component.text("ログインボーナス", NamedTextColor.GOLD);

    /**
     * ログインボーナス画面を開きます。
     *
     * @param player 対象プレイヤー
     * @param alreadyReceivedToday 本日受け取り済みの場合 true
     * @param today 表示対象日
     */
    public void open(@NotNull Player player, boolean alreadyReceivedToday, @NotNull LocalDate today) {
        Inventory inventory = Bukkit.createInventory(
            new LoginBonusInventoryHolder(),
            LoginBonusInventoryHolder.SIZE,
            TITLE
        );
        fill(inventory);
        inventory.setItem(STATUS_SLOT, createStatusIcon(alreadyReceivedToday, today));
        inventory.setItem(CLOSE_SLOT, createCloseIcon());
        player.openInventory(inventory);
    }

    /**
     * 指定インベントリがログインボーナス画面か判定します。
     *
     * @param inventory 判定対象
     * @return ログインボーナス画面の場合 true
     */
    public boolean isLoginBonusInventory(@NotNull Inventory inventory) {
        return inventory.getHolder() instanceof LoginBonusInventoryHolder;
    }

    private void fill(@NotNull Inventory inventory) {
        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
    }

    private @NotNull ItemStack createStatusIcon(boolean alreadyReceivedToday, @NotNull LocalDate today) {
        Component name = alreadyReceivedToday
            ? Component.text("本日は受け取り済み", NamedTextColor.YELLOW)
            : Component.text("ログインボーナス", NamedTextColor.GOLD);
        List<Component> lore = alreadyReceivedToday
            ? List.of(
                Component.text(today.toString(), NamedTextColor.DARK_GRAY),
                Component.text("また明日受け取れます。", NamedTextColor.GRAY)
            )
            : List.of(
                Component.text(today.toString(), NamedTextColor.DARK_GRAY),
                Component.text("本日のログインを記録しました。", NamedTextColor.GRAY)
            );
        return createItem(alreadyReceivedToday ? Material.CLOCK : Material.GOLD_NUGGET, name, lore);
    }

    private @NotNull ItemStack createCloseIcon() {
        return createItem(
            Material.BARRIER,
            Component.text("閉じる", NamedTextColor.RED),
            List.of()
        );
    }

    private @NotNull ItemStack createItem(
        @NotNull Material material,
        @NotNull Component name,
        @NotNull List<Component> lore
    ) {
        ItemStack itemStack = new ItemStack(material);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }
}
