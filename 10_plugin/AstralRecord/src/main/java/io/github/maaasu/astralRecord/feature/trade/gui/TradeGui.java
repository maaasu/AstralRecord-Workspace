package io.github.maaasu.astralRecord.feature.trade.gui;

import io.github.maaasu.astralRecord.feature.trade.model.TradeSession;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class TradeGui {

    public void open(@NotNull Player viewer, @NotNull TradeSession session) {
        Inventory inventory = Bukkit.createInventory(
            new TradeHolder(session.getSessionId(), viewer.getUniqueId()),
            TradeGuiLayout.SIZE,
            Component.text(session.getPartnerName(viewer.getUniqueId()) + "とのトレード", NamedTextColor.WHITE)
        );
        render(inventory, viewer.getUniqueId(), session);
        viewer.openInventory(inventory);
    }

    public boolean isTradeInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof TradeHolder;
    }

    public @Nullable TradeHolder getTradeHolder(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof TradeHolder holder ? holder : null;
    }

    public @NotNull List<ItemStack> collectOwnItems(@NotNull Inventory inventory) {
        List<ItemStack> items = new ArrayList<>();
        for (int slot : TradeGuiLayout.OWN_SLOTS) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.getType().isAir()) {
                items.add(item.clone());
            }
        }
        return items;
    }

    public void clearTradeInventory(@NotNull Inventory inventory) {
        if (!isTradeInventory(inventory)) {
            return;
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, null);
        }
    }

    private void render(@NotNull Inventory inventory, @NotNull UUID viewerUuid, @NotNull TradeSession session) {
        fill(inventory, Material.GRAY_STAINED_GLASS_PANE);
        placeItems(inventory, TradeGuiLayout.OWN_SLOT_LIST, session.getItems(viewerUuid));
        placeItems(inventory, TradeGuiLayout.PARTNER_SLOT_LIST, session.getPartnerItems(viewerUuid));
        for (int slot : TradeGuiLayout.DIVIDER_SLOTS) {
            inventory.setItem(slot, actionItem(Material.BLACK_STAINED_GLASS_PANE, Component.text(" ", NamedTextColor.DARK_GRAY), List.of()));
        }
        inventory.setItem(TradeGuiLayout.GOLD_SLOT, goldItem(
            session.getGoldAmount(viewerUuid),
            session.getPartnerGoldAmount(viewerUuid)
        ));
        inventory.setItem(TradeGuiLayout.READY_SLOT, readyItem(session.isReady(viewerUuid), session.isPartnerReady(viewerUuid)));
    }

    private void placeItems(@NotNull Inventory inventory, @NotNull List<Integer> slots, @NotNull List<ItemStack> items) {
        for (int i = 0; i < slots.size(); i++) {
            inventory.setItem(slots.get(i), i < items.size() ? items.get(i).clone() : null);
        }
    }

    private void fill(@NotNull Inventory inventory, @NotNull Material material) {
        ItemStack filler = actionItem(material, Component.text(" ", NamedTextColor.DARK_GRAY), List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
    }

    private @NotNull ItemStack readyItem(boolean viewerReady, boolean partnerReady) {
        if (viewerReady) {
            return actionItem(
                Material.YELLOW_CONCRETE,
                Component.text("準備完了中", NamedTextColor.YELLOW, TextDecoration.BOLD),
                List.of(Component.text("もう一度押すと準備をキャンセルします。", NamedTextColor.GRAY))
            );
        }
        if (partnerReady) {
            return actionItem(
                Material.EMERALD_BLOCK,
                Component.text("取引を成立する", NamedTextColor.GREEN, TextDecoration.BOLD),
                List.of(Component.text("相手は準備完了しています。", NamedTextColor.GRAY))
            );
        }
        return actionItem(
            Material.LIME_CONCRETE,
            Component.text("準備完了", NamedTextColor.GREEN, TextDecoration.BOLD),
            List.of(Component.text("相手の準備完了を待ちます。", NamedTextColor.GRAY))
        );
    }

    private @NotNull ItemStack goldItem(long viewerGold, long partnerGold) {
        return actionItem(
            Material.GOLD_INGOT,
            Component.text("Gold", NamedTextColor.GOLD, TextDecoration.BOLD),
            List.of(
                Component.text("Your offer: " + viewerGold + " Gold", NamedTextColor.YELLOW),
                Component.text("Partner offer: " + partnerGold + " Gold", NamedTextColor.GRAY),
                Component.text("Click to set your Gold offer.", NamedTextColor.GREEN)
            )
        );
    }

    private @NotNull ItemStack actionItem(@NotNull Material material, @NotNull Component name, @NotNull List<Component> lore) {
        return GuiItems.create(material, name, lore);
    }

    public record TradeHolder(@NotNull UUID sessionId, @NotNull UUID viewerUuid) implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, TradeGuiLayout.SIZE);
        }
    }
}
