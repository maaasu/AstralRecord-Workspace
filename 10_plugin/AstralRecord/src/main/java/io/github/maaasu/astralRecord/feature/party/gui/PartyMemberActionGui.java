package io.github.maaasu.astralRecord.feature.party.gui;

import io.github.maaasu.astralRecord.feature.menu.view.screen.BaseMenuScreenView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * パーティーメンバーに対するリーダー操作 GUI です。
 */
public final class PartyMemberActionGui extends BaseMenuScreenView {
    public static final int HEAD_SLOT = 4;
    public static final int PROMOTE_SLOT = 11;
    public static final int KICK_SLOT = 15;
    public static final int BACK_TO_PARTY_SLOT = 22;
    public static final int CLOSE_SLOT = 23;
    public static final int SIZE = 27;

    public void open(@NotNull org.bukkit.entity.Player viewer, @NotNull UUID targetId) {
        Inventory inventory = Bukkit.createInventory(
            new Holder(targetId),
            SIZE,
            Component.text("パーティーメンバー操作", NamedTextColor.RED)
        );
        render(inventory, targetId);
        viewer.openInventory(inventory);
    }

    public boolean isInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    public @Nullable UUID getTargetId(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return holder.targetId();
        }
        return null;
    }

    private void render(@NotNull Inventory inventory, @NotNull UUID targetId) {
        fill(inventory);
        inventory.setItem(HEAD_SLOT, playerHead(targetId));
        inventory.setItem(PROMOTE_SLOT, createItem(
            Material.GOLDEN_HELMET,
            Component.text("リーダー委譲", NamedTextColor.GOLD, TextDecoration.BOLD),
            List.of(Component.text("対象プレイヤーをパーティーリーダーに変更します", NamedTextColor.GRAY))
        ));
        inventory.setItem(KICK_SLOT, createItem(
            Material.BARRIER,
            Component.text("キック", NamedTextColor.RED, TextDecoration.BOLD),
            List.of(Component.text("対象プレイヤーをパーティーから外します", NamedTextColor.GRAY))
        ));
        inventory.setItem(BACK_TO_PARTY_SLOT, backItem());
    }

    @Override
    protected void fill(@NotNull Inventory inventory) {
        ItemStack border = createItem(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), List.of());
        ItemStack panel = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = 0; slot < SIZE; slot++) {
            boolean isBorder = slot < 9 || slot >= 18 || slot % 9 == 0 || slot % 9 == 8;
            inventory.setItem(slot, isBorder ? border : panel);
        }
    }

    private @NotNull ItemStack playerHead(@NotNull UUID targetId) {
        ItemStack itemStack = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta instanceof SkullMeta skullMeta) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(targetId);
            skullMeta.setOwningPlayer(offlinePlayer);
            String displayName = offlinePlayer.getName() == null ? targetId.toString() : offlinePlayer.getName();
            skullMeta.displayName(Component.text(displayName, NamedTextColor.WHITE, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
            skullMeta.lore(List.of(Component.text("操作対象のメンバーです", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            skullMeta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(skullMeta);
        }
        return itemStack;
    }

    private record Holder(@NotNull UUID targetId) implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, SIZE);
        }
    }
}
