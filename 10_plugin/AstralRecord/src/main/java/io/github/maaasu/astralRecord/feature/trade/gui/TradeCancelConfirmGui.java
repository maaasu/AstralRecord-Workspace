package io.github.maaasu.astralRecord.feature.trade.gui;

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

import java.util.List;
import java.util.UUID;

public final class TradeCancelConfirmGui {
    public static final int SIZE = 27;
    public static final int CANCEL_SLOT = 11;
    public static final int BACK_SLOT = 15;

    public void open(@NotNull Player viewer, @NotNull UUID sessionId) {
        open(viewer, sessionId, () -> { }, () -> { });
    }

    /**
     * 中止確認画面を開き、遅延遷移の表示完了または取消を通知します。
     * @param viewer 表示対象
     * @param sessionId 対象取引
     * @param onOpened 表示完了時の処理
     * @param onCancelled 遷移取消時の処理
     */
    public void open(@NotNull Player viewer, @NotNull UUID sessionId,
                     @NotNull Runnable onOpened, @NotNull Runnable onCancelled) {
        Inventory inventory = Bukkit.createInventory(
            new CancelHolder(sessionId, viewer.getUniqueId()),
            SIZE,
            Component.text("トレード中止確認", NamedTextColor.RED)
        );
        fill(inventory);
        inventory.setItem(CANCEL_SLOT, actionItem(
            Material.RED_CONCRETE,
            Component.text("トレードを中止する", NamedTextColor.RED, TextDecoration.BOLD),
            List.of(Component.text("提示アイテムを返却して中止します。", NamedTextColor.GRAY))
        ));
        inventory.setItem(BACK_SLOT, actionItem(
            Material.LIME_CONCRETE,
            Component.text("トレードへ戻る", NamedTextColor.GREEN, TextDecoration.BOLD),
            List.of(Component.text("取引 GUI へ戻ります。", NamedTextColor.GRAY))
        ));
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(viewer, inventory, onOpened, onCancelled);
    }

    public boolean isCancelInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof CancelHolder;
    }

    public @Nullable CancelHolder getCancelHolder(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof CancelHolder holder ? holder : null;
    }

    private void fill(@NotNull Inventory inventory) {
        ItemStack filler = actionItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" ", NamedTextColor.DARK_GRAY), List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
    }

    private @NotNull ItemStack actionItem(@NotNull Material material, @NotNull Component name, @NotNull List<Component> lore) {
        return GuiItems.create(material, name, lore);
    }

    public record CancelHolder(@NotNull UUID sessionId, @NotNull UUID viewerUuid) implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, SIZE);
        }
    }
}
