package io.github.maaasu.astralRecord.feature.menu.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryClickGuard;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.menu.model.MenuScreen;
import io.github.maaasu.astralRecord.feature.menu.model.MenuShortcutAction;
import io.github.maaasu.astralRecord.feature.menu.model.MenuShortcutSettings;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.TrashScreenView;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.gui.PlayerSettingGui;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MenuOpenEventHandler extends AbstractEventHandler {

    private final AstralRecord plugin;
    private final MenuView menuView;
    private final InventoryService inventoryService;
    private final CurrencyService currencyService;
    private final StatusService statusService;
    private final Set<UUID> craftRenderSuppressed = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, List<ItemStack>> trashItemsByPlayer = new ConcurrentHashMap<>();
    private final Set<UUID> suppressTrashConfirmOnClose = ConcurrentHashMap.newKeySet();

    public MenuOpenEventHandler(
        @NotNull AstralRecord plugin,
        @NotNull MenuView menuView,
        @NotNull InventoryService inventoryService,
        @NotNull CurrencyService currencyService,
        @NotNull StatusService statusService
    ) {
        this.plugin = plugin;
        this.menuView = menuView;
        this.inventoryService = inventoryService;
        this.currencyService = currencyService;
        this.statusService = statusService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrepareItemCraft(PrepareItemCraftEvent event) {
        String playerName = event.getView().getPlayer() instanceof Player p ? p.getName() : "unknown";
        runSafely(() -> {
            if (!isPlayerCraftingInventory(event.getInventory())) {
                return;
            }
            if (!(event.getView().getPlayer() instanceof Player player)) {
                return;
            }
            if (craftRenderSuppressed.contains(player.getUniqueId())) {
                return;
            }
            scheduleCraftShortcutRender(player);
        }, LogId.E_5600, playerName);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        runSafely(() -> {
            if (event.getPlayer() instanceof Player player) {
                scheduleCraftShortcutRender(player);
                applyHotbarShortcutMode(player, event.getInventory(), event.getView().getType());
            }
        }, LogId.E_5600, event.getPlayer().getName());
    }

    private void applyHotbarShortcutMode(
        @NotNull Player player,
        @NotNull Inventory openedInventory,
        @NotNull org.bukkit.event.inventory.InventoryType viewType
    ) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !astPlayer.getAccount().getMode().shouldReflectInventoryToGui()) {
            return;
        }
        PlayerSettingGui playerSettingGui = plugin.getPlayerSettingGui();
        if (menuView.isMenuInventory(openedInventory)
            || playerSettingGui != null && playerSettingGui.isInventory(openedInventory)
            || viewType == org.bukkit.event.inventory.InventoryType.CRAFTING) {
            inventoryService.setHotbarShortcutMode(astPlayer, true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        runSafely(() -> {
            if (event.getInventory() instanceof CraftingInventory inventory) {
                menuView.clearCraftShortcuts(inventory);
                if (event.getPlayer() instanceof Player player) {
                    menuView.removeCraftShortcutItems(player);
                }
            }
            if (event.getPlayer() instanceof Player player) {
                handleTrashClose(event.getInventory(), player);
                AstPlayer astPlayer = AstPlayerCache.get(player);
                if (astPlayer != null) {
                    inventoryService.setHotbarShortcutMode(astPlayer, false);
                }
                scheduleCraftShortcutRender(player);
            }
        }, LogId.E_5600, event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        runSafely(() -> plugin.getServer().getScheduler().runTaskLater(
            plugin,
            () -> scheduleCraftShortcutRender(event.getPlayer()),
            2L
        ), LogId.E_5600, event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        runSafely(() -> {
            if (menuView.isMenuInventory(event.getView().getTopInventory())) {
                MenuScreen menuScreen = menuView.getMenuScreen(event.getView().getTopInventory());
                if (menuScreen == MenuScreen.TRASH) {
                    handleTrashClick(event);
                    return;
                }
                if (menuScreen == MenuScreen.TRASH_CONFIRM) {
                    handleTrashConfirmClick(event);
                    return;
                }
                if (event.getWhoClicked() instanceof Player player
                    && handleMenuHotbarShortcutClick(event, player)) {
                    return;
                }
                if (menuScreen == MenuScreen.EQUIPMENT_GUI) {
                    return;
                }
                handleMenuClick(event);
                return;
            }

            if (!(event.getWhoClicked() instanceof Player player) || !isPlayerMode(player)) {
                return;
            }

            if (isCraftSlotSwapAttempt(event)) {
                GuiSound.DENY.play(player);
                return;
            }

            if (event.getView().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING) {
                return;
            }

            if (!isCraftMenuClick(event)) {
                scheduleCraftShortcutRender(player);
                return;
            }

            event.setCancelled(true);
            if (event.getRawSlot() == MenuView.CRAFT_RESULT_RAW_SLOT) {
                GuiSound.OPEN.play(player);
                openMainMenu(player);
                return;
            }

            int shortcutIndex = menuView.getCraftShortcutIndex(event.getRawSlot());
            if (shortcutIndex >= 0) {
                MenuShortcutAction action = MenuShortcutAction.defaultForSlot(shortcutIndex);
                executeShortcutAction(player, action, shortcutIndex);
            }
        }, LogId.E_5600, event.getWhoClicked().getName());
    }

    private boolean isCraftSlotSwapAttempt(@NotNull InventoryClickEvent event) {
        if (event.getView().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING) {
            return false;
        }

        int rawSlot = event.getRawSlot();
        if (rawSlot < MenuView.CRAFT_RESULT_RAW_SLOT
            || rawSlot >= MenuView.CRAFT_RESULT_RAW_SLOT + craftMenuSlotCount()) {
            return false;
        }

        return switch (event.getClick()) {
            case NUMBER_KEY, SWAP_OFFHAND -> true;
            default -> false;
        };
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        runSafely(() -> {
            if (menuView.isMenuInventory(event.getView().getTopInventory())) {
                MenuScreen menuScreen = menuView.getMenuScreen(event.getView().getTopInventory());
                if (menuScreen == MenuScreen.TRASH) {
                    if (event.getRawSlots().stream().anyMatch(this::isTrashControlSlot)) {
                        event.setCancelled(true);
                        if (event.getWhoClicked() instanceof Player player) {
                            GuiSound.DENY.play(player);
                        }
                    }
                    return;
                }
                if (menuScreen == MenuScreen.TRASH_CONFIRM) {
                    event.setCancelled(true);
                    if (event.getWhoClicked() instanceof Player player) {
                        GuiSound.DENY.play(player);
                    }
                    return;
                }
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player player) {
                    GuiSound.DENY.play(player);
                }
                return;
            }

            if (!(event.getWhoClicked() instanceof Player player) || !isPlayerMode(player)) {
                return;
            }
            for (int rawSlot : event.getRawSlots()) {
                if (rawSlot >= MenuView.CRAFT_RESULT_RAW_SLOT
                    && rawSlot < MenuView.CRAFT_RESULT_RAW_SLOT + craftMenuSlotCount()) {
                    scheduleCraftShortcutRender(player);
                    return;
                }
            }
        }, LogId.E_5600, event.getWhoClicked().getName());
    }

    private void handleMenuClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);

        if (event.getRawSlot() == MenuView.CLOSE_SLOT) {
            GuiSound.CLOSE.play(player);
            player.closeInventory();
            return;
        }

        MenuScreen screen = menuView.getMenuScreen(event.getView().getTopInventory());
        if (screen == null) {
            return;
        }

        switch (screen) {
            case MAIN -> handleMainMenuClick(player, event.getRawSlot());
            case STATUS -> handleStatusClick(player, event.getRawSlot());
            case INVENTORY_SELECTOR -> handleInventorySelectorClick(player, event.getRawSlot());
            case EQUIPMENT_GUI -> {
            }
            case CURRENCY -> handleCurrencyClick(event, player);
            case GUIDE -> handleGuideClick(player, event.getRawSlot());
            case TRASH, TRASH_CONFIRM -> GuiSound.DENY.play(player);
            default -> GuiSound.DENY.play(player);
        }
    }

    private boolean handleMenuHotbarShortcutClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        if (!(event.getClickedInventory() instanceof PlayerInventory)) {
            return false;
        }
        int slot = event.getSlot();
        if (slot < 0 || slot > 8) {
            return false;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !inventoryService.isHotbarShortcutMode(astPlayer)) {
            return false;
        }

        event.setCancelled(true);
        if (!inventoryService.getClickGuard().tryAcquire(
                astPlayer.getAccount().getUuid(), InventoryClickGuard.ClickAction.HOTBAR_SHORTCUT)) {
            return true;
        }
        boolean handled = inventoryService.handleHotbarShortcutClick(astPlayer, slot);
        if (handled) {
            if (slot == 4) {
                GuiSound.CLOSE.play(player);
            } else {
                GuiSound.SELECT.play(player);
            }
        } else {
            GuiSound.DENY.play(player);
        }
        return true;
    }

    private void handleMainMenuClick(@NotNull Player player, int rawSlot) {
        if (rawSlot == MenuView.STATUS_SLOT) {
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer == null) {
                GuiSound.DENY.play(player);
                return;
            }
            GuiSound.SELECT.play(player);
            menuView.openStatus(player, astPlayer, statusService.refreshStatus(astPlayer));
            return;
        }
        if (rawSlot == MenuView.PLAYER_SETTING_SLOT) {
            PlayerSettingGui playerSettingGui = plugin.getPlayerSettingGui();
            if (playerSettingGui == null) {
                GuiSound.DENY.play(player);
                return;
            }
            GuiSound.SELECT.play(player);
            playerSettingGui.open(player);
            return;
        }
        if (rawSlot == MenuView.EQUIPMENT_GUI_SLOT) {
            openEquipmentGui(player);
            return;
        }
        if (rawSlot == MenuView.TRASH_SLOT) {
            GuiSound.OPEN.play(player);
            openTrash(player, 0);
            return;
        }
        if (rawSlot == MenuView.GUIDE_SLOT) {
            GuiSound.SELECT.play(player);
            menuView.openGuide(player);
            return;
        }
        GuiSound.DENY.play(player);
    }

    private void handleGuideClick(@NotNull Player player, int rawSlot) {
        if (rawSlot == MenuView.BACK_SLOT) {
            GuiSound.SELECT.play(player);
            menuView.open(player);
            return;
        }
        GuiSound.DENY.play(player);
    }

    private void handleInventorySelectorClick(@NotNull Player player, int rawSlot) {
        if (rawSlot == MenuView.BACK_SLOT) {
            GuiSound.SELECT.play(player);
            menuView.open(player);
            return;
        }
        if (rawSlot == MenuView.INVENTORY_SELECTOR_TRASH_SLOT) {
            GuiSound.OPEN.play(player);
            openTrash(player, 0);
            return;
        }

        InventoryType inventoryType = menuView.getInventoryTypeAtSlot(rawSlot);
        if (inventoryType == null) {
            GuiSound.DENY.play(player);
            return;
        }

        applyInventoryShortcut(player, inventoryType);
    }

    private void handleCurrencyClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        int rawSlot = event.getRawSlot();
        if (rawSlot == MenuView.PAGING_CLOSE_SLOT) {
            GuiSound.CLOSE.play(player);
            player.closeInventory();
            return;
        }
        if (rawSlot == MenuView.PAGING_BACK_SLOT) {
            GuiSound.SELECT.play(player);
            menuView.open(player);
            return;
        }

        int pageIndex = menuView.getPageIndex(event.getView().getTopInventory());
        List<ItemStack> currencyItems = currencyItems(player);
        if (rawSlot == MenuView.PAGING_PREVIOUS_SLOT && menuView.hasPreviousCurrencyPage(pageIndex)) {
            GuiSound.SELECT.play(player);
            menuView.openCurrency(player, currencyItems, pageIndex - 1);
            return;
        }
        if (rawSlot == MenuView.PAGING_NEXT_SLOT && menuView.hasNextCurrencyPage(currencyItems, pageIndex)) {
            GuiSound.SELECT.play(player);
            menuView.openCurrency(player, currencyItems, pageIndex + 1);
            return;
        }

        GuiSound.DENY.play(player);
    }

    private void handleStatusClick(@NotNull Player player, int rawSlot) {
        if (rawSlot == MenuView.BACK_SLOT) {
            GuiSound.SELECT.play(player);
            menuView.open(player);
            return;
        }
        GuiSound.DENY.play(player);
    }

    private void handleTrashClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        int rawSlot = event.getRawSlot();
        Inventory topInventory = event.getView().getTopInventory();
        List<ItemStack> currentTrashItems = snapshotTrashItems(topInventory);

        if (rawSlot >= topInventory.getSize()) {
            handleTrashPlayerInventoryClick(event, player, topInventory);
            return;
        }

        if (rawSlot == MenuView.BACK_SLOT) {
            suppressTrashConfirmOnClose.add(player.getUniqueId());
            GuiSound.SELECT.play(player);
            menuView.openInventorySelector(player);
            return;
        }
        if (rawSlot == MenuView.TRASH_CLOSE_SLOT) {
            event.setCancelled(true);
            GuiSound.CLOSE.play(player);
            if (currentTrashItems.isEmpty()) {
                discardTrash(player);
                suppressTrashConfirmOnClose.add(player.getUniqueId());
                player.closeInventory();
                return;
            }
            openTrashConfirm(player, currentTrashItems, 0);
            return;
        }
        if (rawSlot == MenuView.TRASH_PREVIOUS_SLOT) {
            int pageIndex = menuView.getPageIndex(topInventory);
            if (menuView.hasPreviousTrashPage(pageIndex)) {
                GuiSound.SELECT.play(player);
                openTrash(player, pageIndex - 1);
            } else {
                GuiSound.DENY.play(player);
            }
            return;
        }
        if (rawSlot == MenuView.TRASH_NEXT_SLOT) {
            int pageIndex = menuView.getPageIndex(topInventory);
            if (menuView.hasNextTrashPage(currentTrashItems, pageIndex)) {
                GuiSound.SELECT.play(player);
                openTrash(player, pageIndex + 1);
            } else {
                GuiSound.DENY.play(player);
            }
            return;
        }
        if (isTrashContentSlot(rawSlot)) {
            handleTrashContentClick(event, player, topInventory, rawSlot);
            return;
        }
        if (isTrashControlSlot(rawSlot)) {
            GuiSound.DENY.play(player);
        }
    }

    private void handleTrashConfirmClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        int rawSlot = event.getRawSlot();
        Inventory topInventory = event.getView().getTopInventory();
        List<ItemStack> currentTrashItems = snapshotTrashItems(topInventory);
        int pageIndex = menuView.getPageIndex(topInventory);

        if (rawSlot == MenuView.TRASH_CONFIRM_DISPOSE_SLOT) {
            GuiSound.CLOSE.play(player);
            discardTrash(player);
            player.closeInventory();
            return;
        }
        if (rawSlot == MenuView.TRASH_CONFIRM_RETURN_SLOT) {
            GuiSound.SELECT.play(player);
            suppressTrashConfirmOnClose.add(player.getUniqueId());
            menuView.openTrash(player, currentTrashItems, pageIndex);
            return;
        }
        if (rawSlot == MenuView.TRASH_CONFIRM_PREVIOUS_SLOT && menuView.hasPreviousTrashConfirmPage(pageIndex)) {
            GuiSound.SELECT.play(player);
            menuView.openTrashConfirm(player, currentTrashItems, pageIndex - 1);
            return;
        }
        if (rawSlot == MenuView.TRASH_CONFIRM_NEXT_SLOT && menuView.hasNextTrashConfirmPage(currentTrashItems, pageIndex)) {
            GuiSound.SELECT.play(player);
            menuView.openTrashConfirm(player, currentTrashItems, pageIndex + 1);
            return;
        }
        GuiSound.DENY.play(player);
    }

    private void executeShortcutAction(@NotNull Player player, @NotNull MenuShortcutAction action, int shortcutIndex) {
        if (action == MenuShortcutAction.MAIN_MENU) {
            GuiSound.OPEN.play(player);
            openMainMenu(player);
            return;
        }
        if (action == MenuShortcutAction.STATUS) {
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer == null) {
                GuiSound.DENY.play(player);
                return;
            }
            GuiSound.SELECT.play(player);
            menuView.openStatus(player, astPlayer, statusService.refreshStatus(astPlayer));
            return;
        }
        if (action == MenuShortcutAction.INVENTORY_CYCLE) {
            var next = nextInventoryType(player);
            if (next == null) {
                GuiSound.DENY.play(player);
                return;
            }
            applyInventoryShortcut(player, next);
            return;
        }
        if (action == MenuShortcutAction.EQUIPMENT_GUI) {
            GuiSound.SELECT.play(player);
            openEquipmentGui(player);
            return;
        }
        if (action.isCurrencyAction()) {
            GuiSound.OPEN.play(player);
            openCurrency(player, 0);
            return;
        }
        if (action.getInventoryType() != null) {
            applyInventoryShortcut(player, action.getInventoryType());
            return;
        }
        GuiSound.DENY.play(player);
    }

    private @Nullable InventoryType nextInventoryType(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !astPlayer.getAccount().getMode().shouldReflectInventoryToGui()) {
            return null;
        }

        InventoryType current = inventoryService.getDisplayedInventoryType(astPlayer.getAccount().getUuid());
        return switch (current) {
            case NORMAL -> InventoryType.EQUIPMENT;
            case EQUIPMENT -> InventoryType.RUNE;
            default -> InventoryType.NORMAL;
        };
    }

    private void applyInventoryShortcut(@NotNull Player player, @NotNull InventoryType inventoryType) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !astPlayer.getAccount().getMode().shouldReflectInventoryToGui()) {
            GuiSound.DENY.play(player);
            return;
        }
        if (inventoryService.getDisplayedInventoryType(astPlayer.getAccount().getUuid()) == inventoryType) {
            GuiSound.SELECT.play(player);
            return;
        }
        if (!inventoryService.getClickGuard().tryAcquire(
                astPlayer.getAccount().getUuid(), InventoryClickGuard.ClickAction.INVENTORY_SWITCH)) {
            return;
        }

        GuiSound.SELECT.play(player);
        suppressCraftRendering(player);
        menuView.clearCraftShortcuts(player);
        inventoryService.applyInventoryToGui(astPlayer, inventoryType);
        plugin.getServer().getScheduler().runTask(plugin, () -> resumeCraftRendering(player));
    }

    private void openMainMenu(@NotNull Player player) {
        suppressCraftRendering(player);
        menuView.clearCraftShortcuts(player);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            menuView.open(player);
            resumeCraftRendering(player);
        });
    }

    private void openCurrency(@NotNull Player player, int pageIndex) {
        suppressCraftRendering(player);
        menuView.clearCraftShortcuts(player);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            menuView.openCurrency(player, currencyItems(player), pageIndex);
            resumeCraftRendering(player);
        });
    }

    private void openEquipmentGui(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }
        menuView.openEquipmentGui(
            player,
            new ItemStack[] {
                null,
                inventoryService.getAccessorySnapshotItem(astPlayer, 1),
                inventoryService.getAccessorySnapshotItem(astPlayer, 2),
                inventoryService.getAccessorySnapshotItem(astPlayer, 3),
                inventoryService.getAccessorySnapshotItem(astPlayer, 4),
                inventoryService.getAccessorySnapshotItem(astPlayer, 5),
                inventoryService.getAccessorySnapshotItem(astPlayer, 6),
                inventoryService.getAccessorySnapshotItem(astPlayer, 7)
            }
        );
    }

    private @NotNull List<ItemStack> currencyItems(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return List.of();
        }
        return currencyService.getCurrencyItemStacks(astPlayer.getAccount().getUuid());
    }

    private void scheduleCraftShortcutRender(@NotNull Player player) {
        plugin.getServer().getScheduler().runTask(plugin, () -> renderCraftShortcuts(player));
    }

    private void renderCraftShortcuts(@NotNull Player player) {
        UUID playerId = player.getUniqueId();
        if (craftRenderSuppressed.contains(playerId)) {
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || astPlayer.getAccount().getMode() != AccountMode.PLAYER) {
            return;
        }
        craftRenderSuppressed.add(playerId);
        try {
            InventoryType displayedType = astPlayer.getAccount().getMode().shouldReflectInventoryToGui()
                ? inventoryService.getDisplayedInventoryType(astPlayer.getAccount().getUuid())
                : null;
            var snapshot = statusService.getStatus(astPlayer);
            menuView.renderCraftShortcuts(player, MenuShortcutSettings.defaults(), displayedType, snapshot);
        } finally {
            craftRenderSuppressed.remove(playerId);
        }
    }

    private void suppressCraftRendering(@NotNull Player player) {
        craftRenderSuppressed.add(player.getUniqueId());
    }

    private void resumeCraftRendering(@NotNull Player player) {
        craftRenderSuppressed.remove(player.getUniqueId());
        scheduleCraftShortcutRender(player);
    }

    private boolean isCraftMenuClick(@NotNull InventoryClickEvent event) {
        return event.getView().getType() == org.bukkit.event.inventory.InventoryType.CRAFTING
            && event.getRawSlot() >= MenuView.CRAFT_RESULT_RAW_SLOT
            && event.getRawSlot() < MenuView.CRAFT_RESULT_RAW_SLOT + craftMenuSlotCount();
    }

    private int craftMenuSlotCount() {
        return MenuShortcutSettings.SLOT_COUNT + 1;
    }

    private boolean isPlayerCraftingInventory(@NotNull CraftingInventory inventory) {
        return inventory.getType() == org.bukkit.event.inventory.InventoryType.CRAFTING;
    }

    private boolean isPlayerMode(@Nullable Player player) {
        if (player == null) {
            return false;
        }
        var astPlayer = AstPlayerCache.get(player);
        return astPlayer != null && astPlayer.getAccount().getMode() == AccountMode.PLAYER;
    }

    private void openTrash(@NotNull Player player, int pageIndex) {
        menuView.openTrash(player, trashItemsByPlayer.getOrDefault(player.getUniqueId(), List.of()), pageIndex);
    }

    private void openTrashConfirm(@NotNull Player player, @NotNull List<ItemStack> currentItems, int pageIndex) {
        if (currentItems.isEmpty()) {
            discardTrash(player);
            return;
        }
        trashItemsByPlayer.put(player.getUniqueId(), currentItems);
        suppressTrashConfirmOnClose.add(player.getUniqueId());
        menuView.openTrashConfirm(player, currentItems, pageIndex);
    }

    private void handleTrashClose(@NotNull Inventory inventory, @NotNull Player player) {
        MenuScreen screen = menuView.getMenuScreen(inventory);
        if (screen == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        if (screen == MenuScreen.TRASH) {
            if (suppressTrashConfirmOnClose.remove(playerId)) {
                List<ItemStack> items = snapshotTrashItems(inventory);
                trashItemsByPlayer.put(playerId, items);
                return;
            }
            List<ItemStack> allItems = collectAllTrashItems(inventory, playerId);
            returnTrashItemsToInventory(player, allItems);
            discardTrash(player);
            return;
        }
        if (screen == MenuScreen.TRASH_CONFIRM) {
            if (suppressTrashConfirmOnClose.remove(playerId)) {
                return;
            }
            discardTrash(player);
        }
    }

    private @NotNull List<ItemStack> collectAllTrashItems(@NotNull Inventory inventory, @NotNull UUID playerId) {
        int pageIndex = menuView.getPageIndex(inventory);
        int pageStart = pageIndex * TrashScreenView.CONTENT_SLOT_COUNT;
        int pageEnd = pageStart + TrashScreenView.CONTENT_SLOT_COUNT;
        List<ItemStack> currentPage = snapshotTrashItems(inventory);
        List<ItemStack> existing = trashItemsByPlayer.getOrDefault(playerId, List.of());
        List<ItemStack> merged = new java.util.ArrayList<>();
        for (int i = 0; i < Math.min(pageStart, existing.size()); i++) {
            merged.add(existing.get(i));
        }
        merged.addAll(currentPage);
        for (int i = pageEnd; i < existing.size(); i++) {
            merged.add(existing.get(i));
        }
        return merged;
    }

    private void returnTrashItemsToInventory(@NotNull Player player, @NotNull List<ItemStack> items) {
        if (items.isEmpty()) {
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return;
        }
        for (ItemStack itemStack : items) {
            if (itemStack == null || itemStack.getType() == Material.AIR) {
                continue;
            }
            inventoryService.returnItemToOwnedInventory(astPlayer, itemStack.clone());
        }
    }

    private @NotNull List<ItemStack> snapshotTrashItems(@NotNull Inventory inventory) {
        List<ItemStack> items = new java.util.ArrayList<>();
        for (int slot = 0; slot < 45; slot++) {
            ItemStack itemStack = inventory.getItem(slot);
            if (itemStack == null || itemStack.getType() == Material.AIR) {
                continue;
            }
            items.add(itemStack.clone());
        }
        return items;
    }

    private void handleTrashPlayerInventoryClick(
        @NotNull InventoryClickEvent event,
        @NotNull Player player,
        @NotNull Inventory topInventory
    ) {
        if (!(event.getClickedInventory() instanceof PlayerInventory)) {
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) {
            GuiSound.DENY.play(player);
            return;
        }
        int requested = resolveTrashTransferAmount(event.getClick(), clicked.getAmount());
        if (requested <= 0) {
            GuiSound.DENY.play(player);
            return;
        }
        int capacity = countTrashPlacementCapacity(topInventory, clicked, requested);
        if (capacity <= 0) {
            GuiSound.DENY.play(player);
            player.updateInventory();
            return;
        }
        ItemStack moved = inventoryService.takeDisplayedItemAmount(astPlayer, event.getSlot(), capacity);
        if (moved == null || moved.getType() == Material.AIR) {
            GuiSound.DENY.play(player);
            player.updateInventory();
            return;
        }
        placeItemsIntoTrash(topInventory, moved);
        GuiSound.SELECT.play(player);
        player.updateInventory();
    }

    private void handleTrashContentClick(
        @NotNull InventoryClickEvent event,
        @NotNull Player player,
        @NotNull Inventory topInventory,
        int rawSlot
    ) {
        ItemStack current = topInventory.getItem(rawSlot);
        ItemStack cursor = event.getCursor();
        boolean hasCurrent = current != null && current.getType() != Material.AIR;
        boolean hasCursor = cursor != null && cursor.getType() != Material.AIR;

        if (hasCursor) {
            GuiSound.DENY.play(player);
            player.updateInventory();
            return;
        }
        if (!hasCurrent) {
            GuiSound.DENY.play(player);
            return;
        }

        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }
        int requested = resolveTrashTransferAmount(event.getClick(), current.getAmount());
        if (requested <= 0) {
            GuiSound.DENY.play(player);
            return;
        }
        ItemStack partial = current.clone();
        partial.setAmount(requested);
        if (inventoryService.returnItemToOwnedInventory(astPlayer, partial) == null) {
            GuiSound.DENY.play(player);
            player.updateInventory();
            return;
        }
        int remaining = current.getAmount() - requested;
        if (remaining <= 0) {
            topInventory.setItem(rawSlot, new ItemStack(Material.AIR));
        } else {
            ItemStack updated = current.clone();
            updated.setAmount(remaining);
            topInventory.setItem(rawSlot, updated);
        }
        GuiSound.SELECT.play(player);
        player.updateInventory();
    }

    private int resolveTrashTransferAmount(@NotNull ClickType clickType, int sourceAmount) {
        if (sourceAmount <= 0) {
            return 0;
        }
        return switch (clickType) {
            case LEFT -> 1;
            case SHIFT_LEFT -> sourceAmount;
            case RIGHT -> Math.max(1, (sourceAmount + 1) / 2);
            default -> 0;
        };
    }

    private int countTrashPlacementCapacity(
        @NotNull Inventory topInventory,
        @NotNull ItemStack template,
        int desired
    ) {
        int capacity = 0;
        int maxStack = template.getMaxStackSize();
        for (int slot = 0; slot < TrashScreenView.CONTENT_SLOT_COUNT && capacity < desired; slot++) {
            ItemStack existing = topInventory.getItem(slot);
            if (existing == null || existing.getType() == Material.AIR) {
                capacity += maxStack;
                continue;
            }
            if (existing.isSimilar(template)) {
                capacity += Math.max(0, maxStack - existing.getAmount());
            }
        }
        return Math.min(capacity, desired);
    }

    private void placeItemsIntoTrash(@NotNull Inventory topInventory, @NotNull ItemStack moved) {
        int remaining = moved.getAmount();
        int maxStack = moved.getMaxStackSize();
        for (int slot = 0; slot < TrashScreenView.CONTENT_SLOT_COUNT && remaining > 0; slot++) {
            ItemStack existing = topInventory.getItem(slot);
            if (existing == null || existing.getType() == Material.AIR) {
                continue;
            }
            if (!existing.isSimilar(moved)) {
                continue;
            }
            int canAdd = Math.min(remaining, maxStack - existing.getAmount());
            if (canAdd <= 0) {
                continue;
            }
            ItemStack updated = existing.clone();
            updated.setAmount(existing.getAmount() + canAdd);
            topInventory.setItem(slot, updated);
            remaining -= canAdd;
        }
        for (int slot = 0; slot < TrashScreenView.CONTENT_SLOT_COUNT && remaining > 0; slot++) {
            ItemStack existing = topInventory.getItem(slot);
            if (existing != null && existing.getType() != Material.AIR) {
                continue;
            }
            int canAdd = Math.min(remaining, maxStack);
            ItemStack newStack = moved.clone();
            newStack.setAmount(canAdd);
            topInventory.setItem(slot, newStack);
            remaining -= canAdd;
        }
    }

    private boolean isTrashContentSlot(int rawSlot) {
        return rawSlot >= 0 && rawSlot < TrashScreenView.CONTENT_SLOT_COUNT;
    }

    private boolean isTrashControlSlot(int rawSlot) {
        return rawSlot == MenuView.TRASH_PREVIOUS_SLOT
            || rawSlot == MenuView.TRASH_GUIDE_SLOT
            || rawSlot == MenuView.BACK_SLOT
            || rawSlot == MenuView.TRASH_CLOSE_SLOT
            || rawSlot == MenuView.TRASH_NEXT_SLOT;
    }

    private void discardTrash(@NotNull Player player) {
        trashItemsByPlayer.remove(player.getUniqueId());
    }
}
