package io.github.maaasu.astralRecord.feature.trade.gui;

import io.github.maaasu.astralRecord.feature.account.service.AccountDisplayNameFormatter;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.trade.model.TradeSession;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
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

public final class TradeGui {

    public void open(@NotNull Player viewer, @NotNull TradeSession session) {
        open(viewer, session, () -> { }, () -> { });
    }

    /**
     * 送信画面を開き、遅延遷移の表示完了または取消を通知します。
     * @param viewer 表示対象
     * @param session 表示する取引
     * @param onOpened 表示完了時の処理
     * @param onCancelled 遷移取消時の処理
     */
    public void open(@NotNull Player viewer, @NotNull TradeSession session,
                     @NotNull Runnable onOpened, @NotNull Runnable onCancelled) {
        Component partnerName = partnerName(viewer, session);
        Inventory inventory = Bukkit.createInventory(
            new TradeHolder(session.getSessionId(), viewer.getUniqueId()),
            TradeGuiLayout.SIZE,
            partnerName.append(Component.text("へアイテム・送金", NamedTextColor.WHITE))
        );
        render(inventory, viewer.getUniqueId(), session);
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(viewer, inventory, onOpened, onCancelled);
    }

    private @NotNull Component partnerName(@NotNull Player viewer, @NotNull TradeSession session) {
        AstPlayer partner = AstPlayerCache.get(session.getPartnerUuid(viewer.getUniqueId()));
        if (partner != null) {
            return AccountDisplayNameFormatter.toComponent(partner.getAccount());
        }
        return Component.text(session.getPartnerName(viewer.getUniqueId()), NamedTextColor.WHITE);
    }

    public boolean isTradeInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof TradeHolder;
    }

    public @Nullable TradeHolder getTradeHolder(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof TradeHolder holder ? holder : null;
    }

    /**
     * 現在表示中の同一セッションの送信 GUI だけを再描画します。
     *
     * @param viewer 再描画対象プレイヤー
     * @param session 表示する送信セッション
     * @return 同一セッションの送信 GUI を再描画した場合は {@code true}
     */
    public boolean refreshIfOpen(@NotNull Player viewer, @NotNull TradeSession session) {
        Inventory inventory = viewer.getOpenInventory().getTopInventory();
        TradeHolder holder = getTradeHolder(inventory);
        if (holder == null
            || !holder.viewerUuid().equals(viewer.getUniqueId())
            || !holder.sessionId().equals(session.getSessionId())) {
            return false;
        }
        render(inventory, viewer.getUniqueId(), session);
        return true;
    }

    public void clearTradeInventory(@NotNull Inventory inventory) {
        if (!isTradeInventory(inventory)) {
            return;
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, null);
        }
    }

    /** 送信者の予約品だけを描画し、最下行へ金額・送信・終了操作を配置します。 */
    private void render(@NotNull Inventory inventory, @NotNull UUID viewerUuid, @NotNull TradeSession session) {
        fill(inventory, Material.GRAY_STAINED_GLASS_PANE);
        placeItems(inventory, TradeGuiLayout.OWN_SLOT_LIST, session.getItems(viewerUuid));
        inventory.setItem(TradeGuiLayout.GOLD_SLOT, goldItem(session.getGoldAmount(viewerUuid)));
        inventory.setItem(TradeGuiLayout.SEND_SLOT, actionItem(Material.CHEST,
            Component.text("送信する", NamedTextColor.GREEN, TextDecoration.BOLD), List.of(
                Component.text("送信先: " + session.getPlayerBName(), NamedTextColor.WHITE),
                Component.text("アイテムと設定した金額を送信します。", NamedTextColor.GRAY),
                Component.text("相手の承認は不要です。", NamedTextColor.YELLOW))));
        inventory.setItem(TradeGuiLayout.BACK_SLOT,
            session.getReturnAction() == null ? GuiItems.closeButton() : GuiItems.backButton());
        inventory.setItem(TradeGuiLayout.CLOSE_SLOT, GuiItems.closeButton());
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

    /** 現在の送金予定額と共通金額設定画面への導線を生成します。 */
    private @NotNull ItemStack goldItem(long amount) {
        return actionItem(Material.GOLD_INGOT,
            Component.text("送金額", NamedTextColor.GOLD, TextDecoration.BOLD), List.of(
                Component.text("送金額: " + amount + " ゴールド", NamedTextColor.YELLOW),
                Component.text("クリックで金額を設定します。", NamedTextColor.GREEN)));
    }

    private @NotNull ItemStack actionItem(@NotNull Material material, @NotNull Component name, @NotNull List<Component> lore) {
        return GuiItems.create(material, name, lore);
    }

    public record TradeHolder(
        @NotNull UUID sessionId,
        @NotNull UUID viewerUuid
    ) implements InventoryHolder, HotbarShortcutGuiHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, TradeGuiLayout.SIZE);
        }
    }
}
