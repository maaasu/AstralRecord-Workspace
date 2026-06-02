package io.github.maaasu.astralRecord.feature.inventory.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentType;
import io.github.maaasu.astralRecord.feature.inventory.service.HotbarLayout;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryClickGuard;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.menu.event.MenuOpenEventHandler;
import io.github.maaasu.astralRecord.feature.menu.model.MenuScreen;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class InventoryEquipmentGuiEventHandler extends AbstractEventHandler {

    private final MenuView menuView;
    private final InventoryService inventoryService;
    private final CurrencyService currencyService;
    private final StatusService statusService;

    /**
     * 装備 GUI とプレイヤーインベントリ上の装備操作を処理するイベントハンドラーを生成します。
     *
     * @param menuView 装備メニューの表示・スロット判定に使用するビュー
     * @param inventoryService 装備状態とインベントリ保存を担当するサービス
     * @param currencyService 通貨表示を担当するサービス
     */
    public InventoryEquipmentGuiEventHandler(
        @NotNull MenuView menuView,
        @NotNull InventoryService inventoryService,
        @NotNull CurrencyService currencyService,
        @NotNull StatusService statusService
    ) {
        this.menuView = menuView;
        this.inventoryService = inventoryService;
        this.currencyService = currencyService;
        this.statusService = statusService;
    }

    /**
     * インベントリクリック時に、装備メニュー内の操作または通常インベントリ上の装備操作へ振り分けます。
     *
     * @param event Bukkit のクリックイベント
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        runSafely(() -> {
            var topInventory = event.getView().getTopInventory();
            if (isEquipmentMenu(topInventory)) {
                handleEquipmentMenuClick(event, topInventory);
                return;
            }
            handlePlayerInventoryClick(event);
        }, LogId.E_5600, event.getWhoClicked().getName());
    }

    /**
     * 装備メニューを閉じたとき、GUI 上の装備スナップショットを保存します。
     *
     * @param event Bukkit のインベントリクローズイベント
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent event) {
        runSafely(() -> {
            if (!(event.getPlayer() instanceof Player player)) {
                return;
            }
            if (!isEquipmentMenu(event.getInventory())) {
                return;
            }
            saveEquipmentMenuSnapshot(player, event.getInventory());
        }, LogId.E_5600, event.getPlayer().getName());
    }

    private boolean isEquipmentMenu(@NotNull Inventory inventory) {
        return menuView.isMenuInventory(inventory)
            && menuView.getMenuScreen(inventory) == MenuScreen.EQUIPMENT_GUI;
    }

    private void handleEquipmentMenuClick(@NotNull InventoryClickEvent event, @NotNull Inventory topInventory) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);

        if (event.getRawSlot() >= topInventory.getSize()) {
            handleEquipmentMenuPlayerInventoryClick(event, topInventory, player);
            return;
        }

        if (handleEquipmentMenuNavigationClick(event, topInventory, player)) {
            return;
        }
        if (!menuView.isEquipmentItemSlot(event.getRawSlot())) {
            GuiSound.DENY.play(player);
            return;
        }
        if (event.getClick().isShiftClick()) {
            GuiSound.DENY.play(player);
            return;
        }

        handleEquipmentMenuSlotClick(event, topInventory, player);
    }

    private boolean handleEquipmentMenuNavigationClick(
        @NotNull InventoryClickEvent event,
        @NotNull Inventory topInventory,
        @NotNull Player player
    ) {
        int rawSlot = event.getRawSlot();
        if (rawSlot == MenuView.BACK_SLOT) {
            saveEquipmentMenuSnapshot(player, topInventory);
            GuiSound.SELECT.play(player);
            MenuOpenEventHandler.suppressNextCloseSound(player);
            menuView.open(player);
            return true;
        }
        return false;
    }

    private void handleEquipmentMenuSlotClick(
        @NotNull InventoryClickEvent event,
        @NotNull Inventory topInventory,
        @NotNull Player player
    ) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer != null
            && !inventoryService.getClickGuard().tryAcquire(
                astPlayer.getAccount().getUuid(), InventoryClickGuard.ClickAction.EQUIPMENT_GUI_SLOT)) {
            return;
        }

        ItemStack cursor = event.getCursor();
        int rawSlot = event.getRawSlot();
        EquipmentType equipmentType = menuView.getEquipmentTypeAtSlot(rawSlot);
        boolean extendedAccessory = menuView.isExtendedAccessorySlot(rawSlot);
        if (!inventoryService.canPlaceInEquipmentGuiSlot(cursor, equipmentType, extendedAccessory)) {
            GuiSound.DENY.play(player);
            return;
        }

        ItemStack current = menuView.getEquipmentGuiItem(topInventory, rawSlot);
        boolean hasCursor = cursor.getType() != Material.AIR;
        boolean hasCurrent = current != null;

        if (!hasCursor && !hasCurrent) {
            GuiSound.DENY.play(player);
            return;
        }

        if (!hasCursor) {
            removeEquipmentMenuItem(event, topInventory, player, current);
            return;
        }

        replaceEquipmentMenuItem(event, topInventory, player, current, hasCurrent);
    }

    private void removeEquipmentMenuItem(
        @NotNull InventoryClickEvent event,
        @NotNull Inventory topInventory,
        @NotNull Player player,
        @NotNull ItemStack current
    ) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }
        if (inventoryService.returnItemToOwnedInventory(astPlayer, current.clone()) == null) {
            GuiSound.DENY.play(player);
            return;
        }
        int rawSlot = event.getRawSlot();
        ItemStack placeholder = menuView.getEquipmentSlotPlaceholder(rawSlot);
        topInventory.setItem(rawSlot, placeholder);
        saveEquipmentMenuSnapshot(player, topInventory);
        player.updateInventory();
        GuiSound.UNEQUIP.play(player);
    }

    private void replaceEquipmentMenuItem(
        @NotNull InventoryClickEvent event,
        @NotNull Inventory topInventory,
        @NotNull Player player,
        @Nullable ItemStack current,
        boolean hasCurrent
    ) {
        topInventory.setItem(event.getRawSlot(), event.getCursor().clone());
        player.setItemOnCursor(hasCurrent ? current : new ItemStack(Material.AIR));
        GuiSound.EQUIP.play(player);
    }

    private void handleEquipmentMenuPlayerInventoryClick(
        @NotNull InventoryClickEvent event,
        @NotNull Inventory topInventory,
        @NotNull Player player
    ) {
        if (!(event.getClickedInventory() instanceof PlayerInventory)) {
            GuiSound.DENY.play(player);
            return;
        }

        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer != null
            && inventoryService.isHotbarShortcutMode(astPlayer)
            && event.getSlot() >= 0
            && event.getSlot() <= 8) {
            handleHotbarShortcutClick(astPlayer, player, event.getSlot());
            return;
        }

        if (astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }

        if (!inventoryService.getClickGuard().tryAcquire(
                astPlayer.getAccount().getUuid(), InventoryClickGuard.ClickAction.DISPLAYED_ITEM)) {
            return;
        }

        ItemStack cursor = event.getCursor();
        if (cursor.getType() != Material.AIR) {
            GuiSound.DENY.play(player);
            return;
        }

        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType() == Material.AIR) {
            GuiSound.DENY.play(player);
            return;
        }

        var sourceEntry = inventoryService.getDisplayedEntryAtBukkitSlot(astPlayer, event.getSlot());
        if (sourceEntry == null) {
            GuiSound.DENY.play(player);
            return;
        }

        EquipmentType equipmentType = inventoryService.getEquipmentTypeForEntry(sourceEntry);
        int targetSlot = equipmentType == EquipmentType.OFF_HAND
            ? menuView.firstEmptyAccessorySlot(topInventory)
            : menuView.getSlotForEquipmentType(equipmentType);
        if (targetSlot < 0) {
            GuiSound.DENY.play(player);
            return;
        }

        boolean extendedAccessory = menuView.isExtendedAccessorySlot(targetSlot);
        EquipmentType targetEquipmentType = menuView.getEquipmentTypeAtSlot(targetSlot);
        if (!inventoryService.canPlaceInEquipmentGuiSlot(sourceEntry, targetEquipmentType, extendedAccessory)) {
            GuiSound.DENY.play(player);
            return;
        }

        ItemStack previous = menuView.getEquipmentGuiItem(topInventory, targetSlot);
        if (!inventoryService.moveDisplayedItemToEquipmentGui(astPlayer, event.getSlot(), previous)) {
            GuiSound.DENY.play(player);
            return;
        }
        topInventory.setItem(targetSlot, clickedItem.clone());
        GuiSound.SELECT.play(player);
    }

    private void saveEquipmentMenuSnapshot(@NotNull Player player, @NotNull Inventory inventory) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return;
        }
        ItemStack[] accessories = menuView.getAccessoryItems(inventory);
        inventoryService.saveEquipmentGui(
            astPlayer,
            menuView.getEquipmentGuiItem(inventory, MenuView.EQUIPMENT_HEAD_SLOT),
            menuView.getEquipmentGuiItem(inventory, MenuView.EQUIPMENT_CHEST_SLOT),
            menuView.getEquipmentGuiItem(inventory, MenuView.EQUIPMENT_LEGS_SLOT),
            menuView.getEquipmentGuiItem(inventory, MenuView.EQUIPMENT_FEET_SLOT),
            accessories[1],
            accessories[2],
            accessories[3],
            accessories[4],
            accessories[5],
            accessories[6],
            accessories[7]
        );
        statusService.refreshStatus(astPlayer);
    }

    private void handlePlayerInventoryClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (event.getView().getType() != InventoryType.CRAFTING) {
            return;
        }

        if (!(event.getClickedInventory() instanceof PlayerInventory)) {
            return;
        }

        var astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !astPlayer.getAccount().getMode().shouldReflectInventoryToGui()) {
            return;
        }

        int slot = event.getSlot();
        if (slot >= 0 && slot <= 8) {
            handleHotbarClick(event, astPlayer, player, slot);
            return;
        }

        if (slot == 40) {
            handleOffhandHotbarClick(event, astPlayer, player);
            return;
        }

        if (isArmorSlot(slot)) {
            handleArmorSlotClick(event, astPlayer, player, slot);
            return;
        }

        handleDisplayedInventoryItemClick(event, astPlayer, player, slot);
    }

    private void handleHotbarClick(
        @NotNull InventoryClickEvent event,
        @NotNull AstPlayer astPlayer,
        @NotNull Player player,
        int slot
    ) {
        if (slot == 8) {
            event.setCancelled(true);
            handleHotbarShortcutClick(astPlayer, player, slot);
            return;
        }
        if (inventoryService.isHotbarShortcutMode(astPlayer)) {
            event.setCancelled(true);
            handleHotbarShortcutClick(astPlayer, player, slot);
            return;
        }
        if (!inventoryService.getClickGuard().tryAcquire(
                astPlayer.getAccount().getUuid(), InventoryClickGuard.ClickAction.HOTBAR_SLOT)) {
            event.setCancelled(true);
            return;
        }
        boolean equipAction = inventoryService.hasHotbarEntry(astPlayer, slot + 1);
        boolean handled = inventoryService.handleHotbarSlotClick(astPlayer, slot + 1);
        if (handled) {
            statusService.refreshStatus(astPlayer);
        }
        playResultSound(player, handled, equipAction);
    }

    private void handleHotbarShortcutClick(
        @NotNull AstPlayer astPlayer,
        @NotNull Player player,
        int slot
    ) {
        if (!inventoryService.getClickGuard().tryAcquire(
                astPlayer.getAccount().getUuid(), InventoryClickGuard.ClickAction.HOTBAR_SHORTCUT)) {
            return;
        }
        boolean handled = inventoryService.handleHotbarShortcutClick(astPlayer, slot);
        if (handled) {
            if (slot != 4) {
                GuiSound.SELECT.play(player);
            }
            return;
        }
        GuiSound.DENY.play(player);
    }

    private void handleHotbarShortcutClick(
        @NotNull AstPlayer astPlayer,
        @NotNull Player player,
        @NotNull Inventory topInventory,
        int slot
    ) {
        // 装備GUIを閉じずに切り替える場合でも、GUI上の最新装備状態を保存してから遷移する。
        saveEquipmentMenuSnapshot(player, topInventory);
        handleHotbarShortcutClick(astPlayer, player, slot);
    }

    private void handleOffhandHotbarClick(
        @NotNull InventoryClickEvent event,
        @NotNull AstPlayer astPlayer,
        @NotNull Player player
    ) {
        event.setCancelled(true);
        if (!inventoryService.getClickGuard().tryAcquire(
                astPlayer.getAccount().getUuid(), InventoryClickGuard.ClickAction.HOTBAR_SLOT)) {
            return;
        }
        boolean equipAction = inventoryService.hasHotbarEntry(astPlayer, HotbarLayout.DB_SLOT_OFFHAND);
        boolean handled = inventoryService.handleHotbarSlotClick(astPlayer, HotbarLayout.DB_SLOT_OFFHAND);
        if (handled) {
            statusService.refreshStatus(astPlayer);
        }
        playResultSound(player, handled, equipAction);
    }

    private void handleArmorSlotClick(
        @NotNull InventoryClickEvent event,
        @NotNull AstPlayer astPlayer,
        @NotNull Player player,
        int slot
    ) {
        event.setCancelled(true);
        if (!inventoryService.getClickGuard().tryAcquire(
                astPlayer.getAccount().getUuid(), InventoryClickGuard.ClickAction.ARMOR_SLOT)) {
            return;
        }
        boolean handled = swapArmorSlotItem(event, astPlayer, slot);
        if (handled) {
            statusService.refreshStatus(astPlayer);
        }
        playResultSound(player, handled, true);
    }

    private void handleDisplayedInventoryItemClick(
        @NotNull InventoryClickEvent event,
        @NotNull AstPlayer astPlayer,
        @NotNull Player player,
        int slot
    ) {
        if (slot < 9 || slot > 35) {
            return;
        }

        var clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType() == Material.AIR) {
            return;
        }
        if (inventoryService.getDisplayedEntryAtBukkitSlot(astPlayer, slot) == null) {
            return;
        }

        event.setCancelled(true);
        if (!inventoryService.getClickGuard().tryAcquire(
                astPlayer.getAccount().getUuid(), InventoryClickGuard.ClickAction.DISPLAYED_ITEM)) {
            return;
        }
        boolean handled = inventoryService.equipOrAssignClickedItem(astPlayer, slot);
        if (handled) {
            statusService.refreshStatus(astPlayer);
        }
        playResultSound(player, handled, true);
    }

    private void playResultSound(@NotNull Player player, boolean handled, boolean equipAction) {
        if (handled) {
            if (equipAction) {
                GuiSound.EQUIP.play(player);
            } else {
                GuiSound.SELECT.play(player);
            }
            return;
        }
        GuiSound.DENY.play(player);
    }

    /**
     * Bukkit 防具スロットのクリック操作（装着・解除・入れ替え）を処理します。
     *
     * @param event クリックイベント
     * @param astPlayer 対象プレイヤー
     * @param slot クリックスロット
     * @return 変更が反映された場合 true
     */
    private boolean swapArmorSlotItem(
        @NotNull InventoryClickEvent event,
        @NotNull AstPlayer astPlayer,
        int slot
    ) {
        if (!(event.getClickedInventory() instanceof PlayerInventory inventory)) {
            return false;
        }

        EquipmentType equipmentType = equipmentTypeFromPlayerSlot(slot);
        if (equipmentType == EquipmentType.UNSUPPORTED) {
            return false;
        }

        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        boolean hasCurrent = current != null && current.getType() != Material.AIR;
        boolean hasCursor = cursor.getType() != Material.AIR;

        if (!hasCurrent && !hasCursor) {
            return false;
        }

        if (!hasCursor) {
            // カーソル空クリック = 装備解除。
            // 共通メソッドで EQUIPMENT/RUNE インベントリへ entry を再生成し、
            // スロット詰め＋表示インベントリ自動切替を実施する。
            if (inventoryService.returnItemToOwnedInventory(astPlayer, current.clone()) == null) {
                return false;
            }
            inventory.setItem(slot, new ItemStack(Material.AIR));
            inventoryService.saveEquipSlotSnapshot(astPlayer);
            inventoryService.saveAccessorySlotSnapshot(astPlayer);
            inventoryService.syncCurrentEquipmentState(astPlayer);
            astPlayer.getBukkit().updateInventory();
            GuiSound.UNEQUIP.play(astPlayer.getBukkit());
            return true;
        }

        if (!inventoryService.canPlaceInEquipmentGuiSlot(cursor, equipmentType, false)) {
            return false;
        }

        inventory.setItem(slot, cursor.clone());
        event.getView().setCursor(hasCurrent ? current.clone() : new ItemStack(Material.AIR));
        inventoryService.saveEquipSlotSnapshot(astPlayer);
        inventoryService.saveAccessorySlotSnapshot(astPlayer);
        inventoryService.syncCurrentEquipmentState(astPlayer);
        astPlayer.getBukkit().updateInventory();
        GuiSound.EQUIP.play(astPlayer.getBukkit());
        return true;
    }

    private boolean isArmorSlot(int slot) {
        return slot >= 36 && slot <= 39;
    }

    private @NotNull EquipmentType equipmentTypeFromPlayerSlot(int slot) {
        return switch (slot) {
            case 36 -> EquipmentType.FEET;
            case 37 -> EquipmentType.LEGS;
            case 38 -> EquipmentType.CHEST;
            case 39 -> EquipmentType.HEAD;
            default -> EquipmentType.UNSUPPORTED;
        };
    }
}
