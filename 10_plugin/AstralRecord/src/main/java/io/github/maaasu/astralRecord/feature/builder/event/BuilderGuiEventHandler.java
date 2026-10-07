package io.github.maaasu.astralRecord.feature.builder.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.builder.gui.BuilderGui;
import io.github.maaasu.astralRecord.feature.builder.service.BuilderService;
import io.github.maaasu.astralRecord.feature.player.event.PlayerRuntimeDiscardEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** ビルダーGUIのアイテム移動を防ぎ、通常クリックだけを次tickで処理します。 */
public final class BuilderGuiEventHandler extends AbstractEventHandler {
    private final BuilderService service;
    private final BuilderGui gui;
    private final Set<UUID> pending = new HashSet<>();

    /**
     * サービスとGUIを指定します。
     * @param service 操作とプレイヤー設定を管理するサービス
     * @param gui 画面と固定クリック処理を持つGUI
     */
    public BuilderGuiEventHandler(@NotNull BuilderService service, @NotNull BuilderGui gui) {
        this.service = service;
        this.gui = gui;
    }

    /**
     * 全アイテム移動を取り消し、所有者の通常クリックだけを安全なtick境界に移します。
     * @param event 対象クリックイベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(@NotNull InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!gui.isInventory(top)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !gui.isOwnedBy(player, top)) return;
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= event.getView().countSlots()) return;
        Material clickedMaterial = null;
        if (rawSlot >= top.getSize()) {
            if (event.getClickedInventory() != player.getInventory()) return;
            ItemStack clicked = event.getCurrentItem();
            if (clicked != null) clickedMaterial = clicked.getType();
        }
        UUID playerId = player.getUniqueId();
        if (!pending.add(playerId)) return;
        Material selected = clickedMaterial;
        boolean fast = event.getClick() == ClickType.RIGHT;
        Bukkit.getScheduler().runTask(AstralRecord.getPlugin(AstralRecord.class), () -> {
            if (!pending.remove(playerId) || !player.isOnline() || player.getOpenInventory().getTopInventory() != top) return;
            gui.handleClick(player, top, rawSlot, selected, fast);
        });
    }

    /**
     * 上下インベントリのどちらを含むドラッグも取り消します。
     * @param event 対象ドラッグイベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(@NotNull InventoryDragEvent event) {
        if (gui.isInventory(event.getView().getTopInventory())) event.setCancelled(true);
    }

    /**
     * ログアウトで設定と遅延クリックを破棄します。
     * @param event ログアウトイベント
     */
    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) { discard(event.getPlayer()); }

    /**
     * 保存不能runtimeの破棄時にも設定と遅延クリックを消します。
     * @param event runtime破棄イベント
     */
    @EventHandler
    public void onRuntimeDiscard(@NotNull PlayerRuntimeDiscardEvent event) { discard(event.getPlayer()); }

    /** プレイヤーの遅延処理と設定を解除します。 */
    private void discard(Player player) {
        pending.remove(player.getUniqueId());
        service.clear(player.getUniqueId());
    }

    /** ハンドラ終了時に保留中クリックをすべて無効にします。 */
    @Override public void cleanup() {
        pending.clear();
        super.cleanup();
    }
}
