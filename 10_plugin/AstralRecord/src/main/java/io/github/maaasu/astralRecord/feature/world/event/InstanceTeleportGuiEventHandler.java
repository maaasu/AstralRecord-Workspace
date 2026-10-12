package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.world.gui.InstanceTeleportGui;
import io.github.maaasu.astralRecord.feature.world.model.InstanceTeleportFilter;
import io.github.maaasu.astralRecord.feature.world.service.InstanceTeleportService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.GuiPagination;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutClickSupport;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** 共通受付 GUI の種別切替・ページ移動・受付転送を処理します。 */
public final class InstanceTeleportGuiEventHandler extends AbstractEventHandler {
    private final InstanceTeleportGui gui;
    private final InstanceTeleportService service;
    private final InventoryService inventoryService;

    /**
     * 共通 GUI と既存のインベントリ制御を接続します。
     * @param gui 共通受付 GUI
     * @param service 受付一覧と転送サービス
     * @param inventoryService 共通ホットバー制御
     */
    public InstanceTeleportGuiEventHandler(
            @NotNull InstanceTeleportGui gui, @NotNull InstanceTeleportService service,
            @NotNull InventoryService inventoryService
    ) {
        this.gui = gui;
        this.service = service;
        this.inventoryService = inventoryService;
    }

    /**
     * コマンド入口から指定した種別を初期状態として一覧を開きます。
     * @param player 表示対象
     * @param filter 入口が指定する表示種別
     */
    public void open(@NotNull AstPlayer player, @NotNull InstanceTeleportFilter filter) {
        openList(player, filter, 0);
    }

    /**
     * GUI の移動をキャンセルし、通常クリックだけを受付操作として処理します。
     * @param event インベントリクリック
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        Inventory inventory = event.getView().getTopInventory();
        if (!gui.isInventory(inventory)) {
            return;
        }
        event.setCancelled(true);
        runSafely(() -> {
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            InstanceTeleportGui.Holder holder = gui.holder(inventory);
            InstanceTeleportGui.FilterHolder filterHolder = gui.filterHolder(inventory);
            UUID ownerId = holder != null ? holder.playerId() : filterHolder.playerId();
            UUID accountId = holder != null ? holder.accountId() : filterHolder.accountId();
            AstPlayer current = AstPlayerCache.get(player);
            if (current == null || !ownerId.equals(player.getUniqueId())
                    || !accountId.equals(current.getAccount().getUuid())
                    || !AccountModeGuard.isGameplayPlayer(current)) {
                GuiSound.DENY.play(player);
                player.closeInventory();
                return;
            }
            if (HotbarShortcutClickSupport.handle(event, player, inventoryService)) {
                return;
            }
            if (event.getRawSlot() < 0 || event.getRawSlot() >= inventory.getSize()
                    || (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT)) {
                return;
            }
            if (holder != null) {
                handleListClick(current, holder, event.getRawSlot());
            } else {
                handleFilterClick(current, filterHolder, event.getRawSlot());
            }
        }, LogId.E_5790, event.getWhoClicked().getName(), "click");
    }

    /**
     * 一覧とフィルター候補画面へのドラッグによるアイテム移動を防ぎます。
     * @param event インベントリドラッグ
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        if (gui.isInventory(event.getView().getTopInventory())) {
            event.setCancelled(true);
        }
    }

    /**
     * 一覧の移動・絞り込み・受付選択を処理します。
     * @param player 現在 account のプレイヤー
     * @param holder 操作した一覧の表示状態
     * @param slot 操作した上段スロット
     */
    private void handleListClick(@NotNull AstPlayer player, @NotNull InstanceTeleportGui.Holder holder, int slot) {
        if (slot == InstanceTeleportGui.FILTER_SLOT) {
            GuiSound.SELECT.play(player.getBukkit());
            gui.openFilter(player.getBukkit(), holder);
        } else if (slot == InstanceTeleportGui.RESET_SLOT) {
            GuiSound.SELECT.play(player.getBukkit());
            openList(player, InstanceTeleportFilter.ALL, 0);
        } else if (slot == InstanceTeleportGui.PREVIOUS_SLOT
                && GuiPagination.hasPreviousPage(holder.pageIndex())) {
            GuiSound.SELECT.play(player.getBukkit());
            openList(player, holder.filter(), holder.pageIndex() - 1);
        } else if (slot == InstanceTeleportGui.NEXT_SLOT
                && GuiPagination.hasNextPage(holder.pageIndex(), holder.entryCount(), InstanceTeleportGui.CONTENT_SIZE)) {
            GuiSound.SELECT.play(player.getBukkit());
            openList(player, holder.filter(), holder.pageIndex() + 1);
        } else if (slot >= 0 && slot < holder.visibleKeys().size()) {
            boolean accepted = service.teleportToEntry(player, holder.visibleKeys().get(slot));
            (accepted ? GuiSound.SELECT : GuiSound.DENY).play(player.getBukkit());
        }
    }

    /**
     * 候補選択は先頭ページへ、取消は元の種別とページへ戻します。
     * @param player 現在 account のプレイヤー
     * @param holder 候補画面の表示状態
     * @param slot 操作した上段スロット
     */
    private void handleFilterClick(@NotNull AstPlayer player, @NotNull InstanceTeleportGui.FilterHolder holder, int slot) {
        if (slot == InstanceTeleportGui.FILTER_BACK_SLOT) {
            GuiSound.SELECT.play(player.getBukkit());
            openList(player, holder.filter(), holder.listPageIndex());
        } else if (slot >= 0 && slot < InstanceTeleportFilter.values().length) {
            GuiSound.SELECT.play(player.getBukkit());
            openList(player, InstanceTeleportFilter.values()[slot], 0);
        }
    }

    /**
     * 最新の公開マスタで種別とページを適用して一覧を描画します。
     * @param player 表示対象
     * @param filter 現在種別
     * @param page 要求ページ
     */
    private void openList(@NotNull AstPlayer player, @NotNull InstanceTeleportFilter filter, int page) {
        gui.open(player.getBukkit(), player.getAccount().getUuid(), filter,
                service.listDestinations(filter), page, service::isAvailable);
    }
}
