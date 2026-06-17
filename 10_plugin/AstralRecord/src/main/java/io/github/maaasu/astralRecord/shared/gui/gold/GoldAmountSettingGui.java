package io.github.maaasu.astralRecord.shared.gui.gold;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Gold 金額を増減して確定する共通 GUI を描画します。
 */
public final class GoldAmountSettingGui {
    public static final int SIZE = 27;
    public static final int CLEAR_SLOT = 9;
    public static final int MINUS_1000_SLOT = 10;
    public static final int MINUS_100_SLOT = 11;
    public static final int AMOUNT_SLOT = 13;
    public static final int PLUS_100_SLOT = 15;
    public static final int PLUS_1000_SLOT = 16;
    public static final int MAX_SLOT = 17;
    public static final int BACK_SLOT = 21;
    public static final int CONFIRM_SLOT = 23;

    /**
     * 指定した金額設定 GUI を開きます。
     *
     * @param viewer 表示対象プレイヤー
     * @param sourceKey 呼び出し元を識別するキー
     * @param contextId 呼び出し元側で扱うコンテキスト ID
     * @param amount 初期金額
     * @param maxAmount 設定可能な最大金額
     */
    public void open(
        @NotNull Player viewer,
        @NotNull String sourceKey,
        @NotNull UUID contextId,
        long amount,
        long maxAmount
    ) {
        long normalizedMax = Math.max(0L, maxAmount);
        long normalizedAmount = clamp(amount, normalizedMax);
        Inventory inventory = Bukkit.createInventory(
            new GoldAmountHolder(sourceKey, contextId, viewer.getUniqueId(), normalizedAmount, normalizedMax),
            SIZE,
            Component.text("Gold Amount", NamedTextColor.GOLD)
        );
        render(inventory, normalizedAmount, normalizedMax);
        viewer.openInventory(inventory);
    }

    public boolean isGoldAmountInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof GoldAmountHolder;
    }

    public @Nullable GoldAmountHolder getHolder(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof GoldAmountHolder holder ? holder : null;
    }

    public long applyDelta(@NotNull GoldAmountHolder holder, long delta) {
        return clamp(holder.amount() + delta, holder.maxAmount());
    }

    public void rerender(@NotNull Inventory inventory, long amount, long maxAmount) {
        render(inventory, clamp(amount, maxAmount), Math.max(0L, maxAmount));
    }

    private void render(@NotNull Inventory inventory, long amount, long maxAmount) {
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, filler.clone());
        }
        inventory.setItem(CLEAR_SLOT, item(
            Material.BARRIER,
            Component.text("0", NamedTextColor.RED, TextDecoration.BOLD),
            List.of(Component.text("Set to 0 Gold", NamedTextColor.GRAY))
        ));
        inventory.setItem(MINUS_1000_SLOT, item(
            Material.RED_CONCRETE,
            Component.text("-1000", NamedTextColor.RED, TextDecoration.BOLD),
            List.of()
        ));
        inventory.setItem(MINUS_100_SLOT, item(
            Material.RED_STAINED_GLASS_PANE,
            Component.text("-100", NamedTextColor.RED, TextDecoration.BOLD),
            List.of()
        ));
        inventory.setItem(AMOUNT_SLOT, item(
            Material.GOLD_INGOT,
            Component.text(amount + " Gold", NamedTextColor.GOLD, TextDecoration.BOLD),
            List.of(
                Component.text("Max: " + maxAmount + " Gold", NamedTextColor.YELLOW),
                Component.text("Use buttons to change the amount.", NamedTextColor.GRAY)
            )
        ));
        inventory.setItem(PLUS_100_SLOT, item(
            Material.LIME_STAINED_GLASS_PANE,
            Component.text("+100", NamedTextColor.GREEN, TextDecoration.BOLD),
            List.of()
        ));
        inventory.setItem(PLUS_1000_SLOT, item(
            Material.LIME_CONCRETE,
            Component.text("+1000", NamedTextColor.GREEN, TextDecoration.BOLD),
            List.of()
        ));
        inventory.setItem(MAX_SLOT, item(
            Material.EMERALD_BLOCK,
            Component.text("MAX", NamedTextColor.GREEN, TextDecoration.BOLD),
            List.of(Component.text(maxAmount + " Gold", NamedTextColor.YELLOW))
        ));
        inventory.setItem(BACK_SLOT, item(
            Material.ARROW,
            Component.text("Back", NamedTextColor.YELLOW, TextDecoration.BOLD),
            List.of()
        ));
        inventory.setItem(CONFIRM_SLOT, item(
            Material.LIME_CONCRETE,
            Component.text("Confirm", NamedTextColor.GREEN, TextDecoration.BOLD),
            List.of(Component.text(amount + " Gold", NamedTextColor.YELLOW))
        ));
    }

    private static long clamp(long amount, long maxAmount) {
        return Math.max(0L, Math.min(amount, Math.max(0L, maxAmount)));
    }

    private @NotNull ItemStack item(
        @NotNull Material material,
        @NotNull Component name,
        @NotNull List<Component> lore
    ) {
        ItemStack itemStack = new ItemStack(material);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
            meta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    public static final class GoldAmountHolder implements InventoryHolder {
        private final String sourceKey;
        private final UUID contextId;
        private final UUID viewerUuid;
        private long amount;
        private final long maxAmount;

        public GoldAmountHolder(
            @NotNull String sourceKey,
            @NotNull UUID contextId,
            @NotNull UUID viewerUuid,
            long amount,
            long maxAmount
        ) {
            this.sourceKey = sourceKey;
            this.contextId = contextId;
            this.viewerUuid = viewerUuid;
            this.amount = amount;
            this.maxAmount = maxAmount;
        }

        public @NotNull String sourceKey() {
            return sourceKey;
        }

        public @NotNull UUID contextId() {
            return contextId;
        }

        public @NotNull UUID viewerUuid() {
            return viewerUuid;
        }

        public long amount() {
            return amount;
        }

        public long maxAmount() {
            return maxAmount;
        }

        public void setAmount(long amount) {
            this.amount = amount;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, SIZE);
        }
    }
}
