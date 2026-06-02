package io.github.maaasu.astralRecord.feature.menu.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryClickGuard;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.menu.model.MenuScreen;
import io.github.maaasu.astralRecord.feature.menu.model.MenuShortcutAction;
import io.github.maaasu.astralRecord.feature.menu.model.MenuShortcutSettings;
import io.github.maaasu.astralRecord.feature.menu.player.PlayerBrowserGuiEventHandler;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.BaseMenuScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.SellScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.TrashScreenView;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.gui.PlayerSettingGui;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.feature.storage.model.StorageViewEntry;
import io.github.maaasu.astralRecord.feature.storage.model.StorageViewOptions;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MenuOpenEventHandler extends AbstractEventHandler {
    private static final long BUFF_GUI_REFRESH_INTERVAL_TICKS = 20L;
    private static final Set<UUID> SUPPRESS_CLOSE_SOUND_ON_CLOSE = ConcurrentHashMap.newKeySet();

    private final AstralRecord plugin;
    private final MenuView menuView;
    private final InventoryService inventoryService;
    private final CurrencyService currencyService;
    private final StatusService statusService;
    private final Set<UUID> craftRenderSuppressed = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, List<ItemStack>> trashItemsByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, List<ItemStack>> sellItemsByPlayer = new ConcurrentHashMap<>();
    private final Set<UUID> suppressTrashConfirmOnClose = ConcurrentHashMap.newKeySet();
    private final Set<UUID> suppressSellConfirmOnClose = ConcurrentHashMap.newKeySet();
    private final Set<UUID> suppressPlayerInventoryRestoreOnClose = ConcurrentHashMap.newKeySet();
    private final Set<UUID> suppressTrashConfirmRestoreOnClose = ConcurrentHashMap.newKeySet();
    private final Set<UUID> suppressSellConfirmRestoreOnClose = ConcurrentHashMap.newKeySet();
    private final Set<UUID> playerInventoryDummyApplied = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, StorageViewOptions> storageOptionsByPlayer = new ConcurrentHashMap<>();

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
        plugin.getServer().getScheduler().runTaskTimer(
            plugin,
            (Runnable) this::refreshOpenBuffMenus,
            BUFF_GUI_REFRESH_INTERVAL_TICKS,
            BUFF_GUI_REFRESH_INTERVAL_TICKS
        );
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
                applyPlayerInventoryDummy(player, event.getInventory(), event.getView().getType());
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
        if (isHotbarShortcutGui(openedInventory)
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
                boolean shouldPlayCloseSound = isHotbarShortcutGui(event.getInventory())
                    && !consumeSuppressedCloseSound(player);
                handleTrashClose(event.getInventory(), player);
                handleSellClose(event.getInventory(), player);
                if (playerInventoryDummyApplied.remove(player.getUniqueId())) {
                    if (!suppressPlayerInventoryRestoreOnClose.remove(player.getUniqueId())) {
                        restorePlayerInventory(player);
                    }
                }
                AstPlayer astPlayer = AstPlayerCache.get(player);
                if (astPlayer != null) {
                    inventoryService.setHotbarShortcutMode(astPlayer, false);
                }
                if (shouldPlayCloseSound) {
                    GuiSound.CLOSE.play(player);
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
                if (menuScreen == MenuScreen.SELL) {
                    handleSellClick(event);
                    return;
                }
                if (menuScreen == MenuScreen.SELL_CONFIRM) {
                    handleSellConfirmClick(event);
                    return;
                }
                if (menuScreen == MenuScreen.STORAGE) {
                    handleStorageClick(event);
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
                if (menuScreen == MenuScreen.SELL) {
                    if (event.getRawSlots().stream().anyMatch(this::isSellControlSlot)) {
                        event.setCancelled(true);
                        if (event.getWhoClicked() instanceof Player player) {
                            GuiSound.DENY.play(player);
                        }
                    }
                    return;
                }
                if (menuScreen == MenuScreen.SELL_CONFIRM) {
                    event.setCancelled(true);
                    if (event.getWhoClicked() instanceof Player player) {
                        GuiSound.DENY.play(player);
                    }
                    return;
                }
                if (menuScreen == MenuScreen.STORAGE) {
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

        MenuScreen screen = menuView.getMenuScreen(event.getView().getTopInventory());
        if (screen == null) {
            return;
        }

        switch (screen) {
            case MAIN -> handleMainMenuClick(player, event.getRawSlot());
            case STATUS -> handleStatusClick(player, event.getRawSlot());
            case BUFF -> handleBuffClick(player, event.getRawSlot());
            case CLASS -> handleClassClick(player, event.getCurrentItem(), event.getRawSlot());
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
            if (slot != 4) {
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
            switchGuiWithoutInventoryReload(
                player,
                () -> menuView.openStatus(player, astPlayer, statusService.refreshStatus(astPlayer))
            );
            return;
        }
        if (rawSlot == MenuView.PLAYER_SETTING_SLOT) {
            PlayerSettingGui playerSettingGui = plugin.getPlayerSettingGui();
            if (playerSettingGui == null) {
                GuiSound.DENY.play(player);
                return;
            }
            GuiSound.SELECT.play(player);
            switchGuiWithoutInventoryReload(player, () -> playerSettingGui.open(player));
            return;
        }
        if (rawSlot == MenuView.EQUIPMENT_GUI_SLOT) {
            GuiSound.SELECT.play(player);
            openEquipmentGui(player);
            return;
        }
        if (rawSlot == MenuView.TRASH_SLOT) {
            GuiSound.SELECT.play(player);
            openTrash(player, 0, true);
            return;
        }
        if (rawSlot == MenuView.GUIDE_SLOT) {
            GuiSound.SELECT.play(player);
            switchGuiWithoutInventoryReload(player, () -> menuView.openGuide(player));
            return;
        }
        if (rawSlot == MenuView.STORAGE_SLOT) {
            GuiSound.SELECT.play(player);
            openStorage(player, 0);
            return;
        }
        if (rawSlot == MenuView.BUFF_SLOT) {
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer == null) {
                GuiSound.DENY.play(player);
                return;
            }
            GuiSound.SELECT.play(player);
            switchGuiWithoutInventoryReload(
                player,
                () -> menuView.openBuff(player, statusService.getActiveBuffs(astPlayer))
            );
            return;
        }
        if (rawSlot == MenuView.SKILL_BIND_SLOT) {
            var skillBindHandler = plugin.getSkillBindGuiEventHandler();
            if (skillBindHandler == null) {
                GuiSound.DENY.play(player);
                return;
            }
            GuiSound.SELECT.play(player);
            switchGuiWithoutInventoryReload(player, () -> skillBindHandler.open(player));
            return;
        }
        if (rawSlot == MenuView.MAIL_SLOT) {
            var mailGuiEventHandler = plugin.getMailGuiEventHandler();
            if (mailGuiEventHandler == null) {
                GuiSound.DENY.play(player);
                return;
            }
            GuiSound.SELECT.play(player);
            switchGuiWithoutInventoryReload(player, () -> mailGuiEventHandler.open(player));
            return;
        }
        if (rawSlot == MenuView.ADVENTURE_RECORD_SLOT) {
            var adventureRecordGuiEventHandler = plugin.getAdventureRecordGuiEventHandler();
            if (adventureRecordGuiEventHandler == null) {
                GuiSound.DENY.play(player);
                return;
            }
            GuiSound.SELECT.play(player);
            switchGuiWithoutInventoryReload(player, () -> adventureRecordGuiEventHandler.open(player));
            return;
        }
        if (rawSlot == MenuView.CURRENCY_SLOT) {
            GuiSound.SELECT.play(player);
            openCurrency(player, 0);
            return;
        }
        if (rawSlot == MenuView.PARTY_SLOT) {
            var partyGui = plugin.getPartyGui();
            if (partyGui == null) {
                GuiSound.DENY.play(player);
                return;
            }
            GuiSound.SELECT.play(player);
            switchGuiWithoutInventoryReload(player, () -> partyGui.open(player));
            return;
        }
        if (rawSlot == MenuView.PLAYER_INFO_SLOT) {
            PlayerBrowserGuiEventHandler playerBrowserGuiEventHandler = plugin.getPlayerBrowserGuiEventHandler();
            if (playerBrowserGuiEventHandler == null) {
                GuiSound.DENY.play(player);
                return;
            }
            GuiSound.SELECT.play(player);
            switchGuiWithoutInventoryReload(player, () -> playerBrowserGuiEventHandler.openInfoList(player, 0));
            return;
        }
        GuiSound.DENY.play(player);
    }

    private void handleGuideClick(@NotNull Player player, int rawSlot) {
        if (rawSlot == MenuView.BACK_SLOT) {
            GuiSound.SELECT.play(player);
            switchGuiWithoutInventoryReload(player, () -> menuView.open(player));
            return;
        }
        GuiSound.DENY.play(player);
    }

    private void handleCurrencyClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        int rawSlot = event.getRawSlot();
        if (rawSlot == MenuView.PAGING_BACK_SLOT) {
            GuiSound.SELECT.play(player);
            switchGuiWithoutInventoryReload(player, () -> menuView.open(player));
            return;
        }

        int pageIndex = menuView.getPageIndex(event.getView().getTopInventory());
        List<ItemStack> currencyItems = currencyItems(player);
        if (rawSlot == MenuView.PAGING_PREVIOUS_SLOT && menuView.hasPreviousCurrencyPage(pageIndex)) {
            GuiSound.SELECT.play(player);
            switchGuiWithoutInventoryReload(player, () -> menuView.openCurrency(player, currencyItems, pageIndex - 1));
            return;
        }
        if (rawSlot == MenuView.PAGING_NEXT_SLOT && menuView.hasNextCurrencyPage(currencyItems, pageIndex)) {
            GuiSound.SELECT.play(player);
            switchGuiWithoutInventoryReload(player, () -> menuView.openCurrency(player, currencyItems, pageIndex + 1));
            return;
        }

        GuiSound.DENY.play(player);
    }

    private void handleStatusClick(@NotNull Player player, int rawSlot) {
        if (rawSlot == MenuView.BACK_SLOT) {
            GuiSound.SELECT.play(player);
            switchGuiWithoutInventoryReload(player, () -> menuView.open(player));
            return;
        }
        GuiSound.DENY.play(player);
    }

    private void handleBuffClick(@NotNull Player player, int rawSlot) {
        if (rawSlot == MenuView.BACK_SLOT) {
            GuiSound.SELECT.play(player);
            switchGuiWithoutInventoryReload(player, () -> menuView.open(player));
            return;
        }
        GuiSound.DENY.play(player);
    }

    private void refreshOpenBuffMenus() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Inventory inventory = player.getOpenInventory().getTopInventory();
            if (!menuView.isMenuInventory(inventory) || menuView.getMenuScreen(inventory) != MenuScreen.BUFF) {
                continue;
            }

            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer == null) {
                continue;
            }
            menuView.renderBuff(inventory, statusService.getActiveBuffs(astPlayer));
        }
    }

    private void handleClassClick(@NotNull Player player, @Nullable ItemStack clickedItem, int rawSlot) {
        if (rawSlot == MenuView.BACK_SLOT) {
            GuiSound.SELECT.play(player);
            menuView.open(player);
            return;
        }
        if (!hasAdminPermission(player)) {
            GuiSound.DENY.play(player);
            player.closeInventory();
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || plugin.getPlayerClassService() == null) {
            GuiSound.DENY.play(player);
            return;
        }
        String classId = menuView.getClassId(clickedItem);
        if (classId == null || classId.isBlank() || classId.equalsIgnoreCase(astPlayer.getClassId())) {
            GuiSound.DENY.play(player);
            return;
        }

        String oldDisplayName = plugin.getPlayerClassService().getDisplayName(astPlayer.getClassId());
        astPlayer.setClassId(classId);
        astPlayer.setClassLevel(Math.max(1, astPlayer.getClassLevel()));
        String newDisplayName = plugin.getPlayerClassService().getDisplayName(classId);
        astPlayer.sendMessage(PlayerMsgId.P_5812, oldDisplayName, newDisplayName);
        GuiSound.SELECT.play(player);
        switchGuiWithoutInventoryReload(
            player,
            () -> menuView.openClass(player, astPlayer, plugin.getPlayerClassService().getClassViewEntries())
        );
    }

    private boolean hasAdminPermission(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        return astPlayer != null && astPlayer.getUser().getPermission() >= UserPermission.ADMIN.getValue();
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
            menuView.open(player);
            return;
        }
        if (rawSlot == MenuView.TRASH_CLOSE_SLOT) {
            event.setCancelled(true);
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
        List<ItemStack> currentTrashItems = trashItemsByPlayer.getOrDefault(player.getUniqueId(), List.of());

        if (rawSlot >= topInventory.getSize()) {
            GuiSound.DENY.play(player);
            return;
        }

        if (rawSlot == MenuView.TRASH_CONFIRM_DISPOSE_SLOT) {
            List<ItemStack> disposedItems = normalizeTrashItems(currentTrashItems);
            GuiSound.TRASH_DISPOSE.play(player);
            discardTrash(player);
            notifyTrashDisposed(player, disposedItems);
            restorePlayerInventory(player);
            suppressTrashConfirmOnClose.add(player.getUniqueId());
            player.closeInventory();
            return;
        }
        if (rawSlot == MenuView.TRASH_CONFIRM_RETURN_SLOT) {
            GuiSound.SELECT.play(player);
            suppressTrashConfirmOnClose.add(player.getUniqueId());
            suppressTrashConfirmRestoreOnClose.add(player.getUniqueId());
            menuView.openTrash(player, currentTrashItems, 0);
            restorePlayerInventory(player);
            return;
        }
        GuiSound.DENY.play(player);
    }

    private void handleSellClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        int rawSlot = event.getRawSlot();
        Inventory topInventory = event.getView().getTopInventory();
        List<ItemStack> currentSellItems = snapshotSellItems(topInventory);

        if (rawSlot >= topInventory.getSize()) {
            handleSellPlayerInventoryClick(event, player, topInventory);
            return;
        }

        if (rawSlot == MenuView.BACK_SLOT) {
            GuiSound.SELECT.play(player);
            player.closeInventory();
            return;
        }
        if (rawSlot == MenuView.TRASH_CLOSE_SLOT) {
            if (currentSellItems.isEmpty()) {
                discardSell(player);
                suppressSellConfirmOnClose.add(player.getUniqueId());
                player.closeInventory();
                return;
            }
            openSellConfirm(player, currentSellItems, 0);
            return;
        }
        if (rawSlot == MenuView.TRASH_PREVIOUS_SLOT) {
            int pageIndex = menuView.getPageIndex(topInventory);
            if (menuView.hasPreviousSellPage(pageIndex)) {
                GuiSound.SELECT.play(player);
                openSell(player, pageIndex - 1);
            } else {
                GuiSound.DENY.play(player);
            }
            return;
        }
        if (rawSlot == MenuView.TRASH_NEXT_SLOT) {
            int pageIndex = menuView.getPageIndex(topInventory);
            if (menuView.hasNextSellPage(currentSellItems, pageIndex)) {
                GuiSound.SELECT.play(player);
                openSell(player, pageIndex + 1);
            } else {
                GuiSound.DENY.play(player);
            }
            return;
        }
        if (isSellContentSlot(rawSlot)) {
            handleSellContentClick(event, player, topInventory, rawSlot);
            return;
        }
        if (isSellControlSlot(rawSlot)) {
            GuiSound.DENY.play(player);
        }
    }

    private void handleSellConfirmClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        int rawSlot = event.getRawSlot();
        Inventory topInventory = event.getView().getTopInventory();
        List<ItemStack> currentSellItems = sellItemsByPlayer.getOrDefault(player.getUniqueId(), List.of());

        if (rawSlot >= topInventory.getSize()) {
            GuiSound.DENY.play(player);
            return;
        }

        if (rawSlot == MenuView.TRASH_CONFIRM_DISPOSE_SLOT) {
            List<ItemStack> soldItems = normalizeSellItems(currentSellItems);
            long totalSaleValue = totalSaleValue(soldItems);
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer == null || !creditGold(astPlayer, totalSaleValue)) {
                GuiSound.DENY.play(player);
                player.updateInventory();
                return;
            }
            GuiSound.SELECT.play(player);
            discardSell(player);
            notifySellCompleted(player, soldItems, totalSaleValue);
            restorePlayerInventory(player);
            suppressSellConfirmOnClose.add(player.getUniqueId());
            player.closeInventory();
            return;
        }
        if (rawSlot == MenuView.TRASH_CONFIRM_RETURN_SLOT) {
            GuiSound.SELECT.play(player);
            suppressSellConfirmOnClose.add(player.getUniqueId());
            suppressSellConfirmRestoreOnClose.add(player.getUniqueId());
            menuView.openSell(player, currentSellItems, 0);
            restorePlayerInventory(player);
            return;
        }
        GuiSound.DENY.play(player);
    }

    private void handleStorageClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        int rawSlot = event.getRawSlot();
        Inventory topInventory = event.getView().getTopInventory();

        if (rawSlot >= topInventory.getSize()) {
            handleStoragePlayerInventoryClick(event, player);
            return;
        }

        if (rawSlot == MenuView.BACK_SLOT) {
            GuiSound.SELECT.play(player);
            switchGuiWithInventoryRestore(player, () -> menuView.open(player));
            return;
        }

        int pageIndex = menuView.getPageIndex(topInventory);
        StorageViewOptions options = storageOptions(player);
        List<StorageViewEntry> entries = storageEntries(player, options);
        if (rawSlot == MenuView.STORAGE_PREVIOUS_SLOT) {
            if (menuView.hasPreviousStoragePage(pageIndex)) {
                GuiSound.SELECT.play(player);
                openStorage(player, pageIndex - 1);
            } else {
                GuiSound.DENY.play(player);
            }
            return;
        }
        if (rawSlot == MenuView.STORAGE_NEXT_SLOT) {
            if (menuView.hasNextStoragePage(entries, pageIndex)) {
                GuiSound.SELECT.play(player);
                openStorage(player, pageIndex + 1);
            } else {
                GuiSound.DENY.play(player);
            }
            return;
        }
        if (rawSlot == MenuView.STORAGE_CATEGORY_FILTER_SLOT) {
            GuiSound.SELECT.play(player);
            storageOptionsByPlayer.put(player.getUniqueId(), options.withCategoryFilter(nextStorageCategory(options.categoryFilter())));
            openStorage(player, 0);
            return;
        }
        if (rawSlot == MenuView.STORAGE_RARITY_FILTER_SLOT) {
            GuiSound.SELECT.play(player);
            storageOptionsByPlayer.put(player.getUniqueId(), options.withRarityFilter(nextStorageRarity(options.rarityFilter())));
            openStorage(player, 0);
            return;
        }
        if (rawSlot == MenuView.STORAGE_SORT_KEY_SLOT) {
            GuiSound.SELECT.play(player);
            storageOptionsByPlayer.put(player.getUniqueId(), options.withSortKey(options.sortKey().next()));
            openStorage(player, 0);
            return;
        }
        if (rawSlot == MenuView.STORAGE_SORT_DIRECTION_SLOT) {
            GuiSound.SELECT.play(player);
            storageOptionsByPlayer.put(player.getUniqueId(), options.withSortDirection(options.sortDirection().next()));
            openStorage(player, 0);
            return;
        }
        if (isStorageControlSlot(rawSlot)) {
            GuiSound.DENY.play(player);
            return;
        }
        if (!isStorageContentSlot(rawSlot)) {
            GuiSound.DENY.play(player);
            return;
        }
        handleStorageContentClick(event, player, topInventory, rawSlot);
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
            switchGuiWithoutInventoryReload(
                player,
                () -> menuView.openStatus(player, astPlayer, statusService.refreshStatus(astPlayer))
            );
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
            GuiSound.OPEN.play(player);
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
        return inventoryService.findNextSwitchableInventoryType(astPlayer.getAccount().getUuid());
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
        if (!inventoryService.canSwitchToInventory(astPlayer.getAccount().getUuid(), inventoryType)) {
            GuiSound.DENY.play(player);
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
            switchGuiWithoutInventoryReload(player, () -> menuView.open(player));
            resumeCraftRendering(player);
        });
    }

    private void openCurrency(@NotNull Player player, int pageIndex) {
        suppressCraftRendering(player);
        menuView.clearCraftShortcuts(player);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            switchGuiWithoutInventoryReload(player, () -> menuView.openCurrency(player, currencyItems(player), pageIndex));
            resumeCraftRendering(player);
        });
    }

    private void openEquipmentGui(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }
        switchGuiWithInventoryRestore(
            player,
            () -> menuView.openEquipmentGui(
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
            )
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
            List<AccountModel> accounts = plugin.getAccountService().getAccounts(astPlayer.getUser().getUuid());
            menuView.renderCraftShortcuts(
                player,
                MenuShortcutSettings.defaults(),
                displayedType,
                snapshot,
                astPlayer.getAccount(),
                accounts
            );
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
        openTrash(player, pageIndex, false);
    }

    private void openTrash(@NotNull Player player, int pageIndex, boolean restoreAfterOpen) {
        List<ItemStack> normalized = normalizeTrashItems(
            trashItemsByPlayer.getOrDefault(player.getUniqueId(), List.of())
        );
        trashItemsByPlayer.put(player.getUniqueId(), normalized);
        if (restoreAfterOpen) {
            switchGuiWithInventoryRestore(player, () -> menuView.openTrash(player, normalized, pageIndex));
            return;
        }
        menuView.openTrash(player, normalized, pageIndex);
    }

    private void openTrashConfirm(@NotNull Player player, @NotNull List<ItemStack> currentItems, int pageIndex) {
        List<ItemStack> normalized = normalizeTrashItems(currentItems);
        if (normalized.isEmpty()) {
            discardTrash(player);
            return;
        }
        trashItemsByPlayer.put(player.getUniqueId(), normalized);
        suppressTrashConfirmOnClose.add(player.getUniqueId());
        menuView.openTrashConfirm(player, normalized, pageIndex);
        fillPlayerInventoryDummy(player);
    }

    private void openSell(@NotNull Player player, int pageIndex) {
        openSell(player, pageIndex, false);
    }

    private void openSell(@NotNull Player player, int pageIndex, boolean restoreAfterOpen) {
        List<ItemStack> normalized = normalizeSellItems(
            sellItemsByPlayer.getOrDefault(player.getUniqueId(), List.of())
        );
        sellItemsByPlayer.put(player.getUniqueId(), normalized);
        if (restoreAfterOpen) {
            switchGuiWithInventoryRestore(player, () -> menuView.openSell(player, normalized, pageIndex));
            return;
        }
        menuView.openSell(player, normalized, pageIndex);
    }

    private void openSellConfirm(@NotNull Player player, @NotNull List<ItemStack> currentItems, int pageIndex) {
        List<ItemStack> normalized = normalizeSellItems(currentItems);
        if (normalized.isEmpty()) {
            discardSell(player);
            return;
        }
        sellItemsByPlayer.put(player.getUniqueId(), normalized);
        suppressSellConfirmOnClose.add(player.getUniqueId());
        menuView.openSellConfirm(player, normalized, pageIndex);
        fillPlayerInventoryDummy(player);
    }

    public void openStorage(@NotNull Player player, int pageIndex) {
        StorageViewOptions options = storageOptions(player);
        switchGuiWithInventoryRestore(
            player,
            () -> menuView.openStorage(player, storageEntries(player, options), options, pageIndex)
        );
    }

    private @NotNull StorageViewOptions storageOptions(@NotNull Player player) {
        return storageOptionsByPlayer.computeIfAbsent(player.getUniqueId(), ignored -> StorageViewOptions.defaults());
    }

    private @NotNull List<StorageViewEntry> storageEntries(
        @NotNull Player player,
        @NotNull StorageViewOptions options
    ) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return List.of();
        }
        return inventoryService.getStorageViewEntries(astPlayer.getAccount().getUuid(), options);
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
            boolean returned = returnTrashItemsToInventory(player, allItems);
            discardTrash(player);
            if (returned) {
                notifyTrashReturned(player, allItems);
            }
            return;
        }
        if (screen == MenuScreen.TRASH_CONFIRM) {
            if (suppressTrashConfirmOnClose.remove(playerId)) {
                if (!suppressTrashConfirmRestoreOnClose.remove(playerId)) {
                    restorePlayerInventory(player);
                }
                return;
            }
            restorePlayerInventory(player);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                GuiSound.SELECT.play(player);
                openTrash(player, 0);
            });
        }
    }

    private void handleSellClose(@NotNull Inventory inventory, @NotNull Player player) {
        MenuScreen screen = menuView.getMenuScreen(inventory);
        if (screen == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        if (screen == MenuScreen.SELL) {
            if (suppressSellConfirmOnClose.remove(playerId)) {
                List<ItemStack> items = snapshotSellItems(inventory);
                sellItemsByPlayer.put(playerId, items);
                return;
            }
            List<ItemStack> allItems = collectAllSellItems(inventory, playerId);
            boolean returned = returnTrashItemsToInventory(player, allItems);
            discardSell(player);
            if (returned) {
                notifySellReturned(player, allItems);
            }
            return;
        }
        if (screen == MenuScreen.SELL_CONFIRM) {
            if (suppressSellConfirmOnClose.remove(playerId)) {
                if (!suppressSellConfirmRestoreOnClose.remove(playerId)) {
                    restorePlayerInventory(player);
                }
                return;
            }
            restorePlayerInventory(player);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                GuiSound.SELECT.play(player);
                openSell(player, 0);
            });
        }
    }

    private void fillPlayerInventoryDummy(@NotNull Player player) {
        ItemStack dummy = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = dummy.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" "));
            dummy.setItemMeta(meta);
        }
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            inventory.setItem(slot, dummy.clone());
        }
        player.updateInventory();
    }

    private void applyPlayerInventoryDummy(
        @NotNull Player player,
        @NotNull Inventory openedInventory,
        @NotNull org.bukkit.event.inventory.InventoryType viewType
    ) {
        if (!shouldFillPlayerInventoryDummy(player, openedInventory, viewType)) {
            return;
        }
        playerInventoryDummyApplied.add(player.getUniqueId());
        fillPlayerInventoryDummy(player);
    }

    private boolean shouldFillPlayerInventoryDummy(
        @NotNull Player player,
        @NotNull Inventory openedInventory,
        @NotNull org.bukkit.event.inventory.InventoryType viewType
    ) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !astPlayer.getAccount().getMode().shouldReflectInventoryToGui()) {
            return false;
        }
        if (viewType == org.bukkit.event.inventory.InventoryType.CRAFTING) {
            return false;
        }

        MenuScreen menuScreen = menuView.getMenuScreen(openedInventory);
        if (menuScreen != null) {
            return false;
        }

        PlayerSettingGui playerSettingGui = plugin.getPlayerSettingGui();
        if (playerSettingGui != null && playerSettingGui.isInventory(openedInventory)) {
            return false;
        }

        var partyGui = plugin.getPartyGui();
        if (partyGui != null && partyGui.isInventory(openedInventory)) {
            return false;
        }
        var playerListGui = plugin.getPlayerListGui();
        if (playerListGui != null && playerListGui.isInventory(openedInventory)) {
            return false;
        }
        var playerDetailGui = plugin.getPlayerDetailGui();
        if (playerDetailGui != null && playerDetailGui.isInventory(openedInventory)) {
            return false;
        }
        var mailGuiEventHandler = plugin.getMailGuiEventHandler();
        if (mailGuiEventHandler != null && mailGuiEventHandler.isInventory(openedInventory)) {
            return false;
        }
        return false;
    }

    private boolean isHotbarShortcutGui(@NotNull Inventory openedInventory) {
        if (menuView.isMenuInventory(openedInventory)) {
            return true;
        }
        PlayerSettingGui playerSettingGui = plugin.getPlayerSettingGui();
        if (playerSettingGui != null && playerSettingGui.isInventory(openedInventory)) {
            return true;
        }
        var partyGui = plugin.getPartyGui();
        if (partyGui != null && partyGui.isInventory(openedInventory)) {
            return true;
        }
        var playerListGui = plugin.getPlayerListGui();
        if (playerListGui != null && playerListGui.isInventory(openedInventory)) {
            return true;
        }
        var playerDetailGui = plugin.getPlayerDetailGui();
        if (playerDetailGui != null && playerDetailGui.isInventory(openedInventory)) {
            return true;
        }
        var mailGuiEventHandler = plugin.getMailGuiEventHandler();
        if (mailGuiEventHandler != null && mailGuiEventHandler.isInventory(openedInventory)) {
            return true;
        }
        var loginBonusService = plugin.getLoginBonusService();
        return loginBonusService != null && loginBonusService.getGui().isLoginBonusInventory(openedInventory);
    }

    private void restorePlayerInventory(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer != null) {
            inventoryService.applyInventoriesToGui(astPlayer);
            player.updateInventory();
        }
    }

    private void switchGuiWithoutInventoryReload(@NotNull Player player, @NotNull Runnable opener) {
        suppressPlayerInventoryRestoreForGuiSwitch(player);
        suppressCloseSoundForGuiSwitch(player);
        opener.run();
    }

    private void switchGuiWithInventoryRestore(@NotNull Player player, @NotNull Runnable opener) {
        suppressPlayerInventoryRestoreForGuiSwitch(player);
        suppressCloseSoundForGuiSwitch(player);
        opener.run();
        restorePlayerInventory(player);
    }

    private void suppressPlayerInventoryRestoreForGuiSwitch(@NotNull Player player) {
        if (playerInventoryDummyApplied.contains(player.getUniqueId())) {
            suppressPlayerInventoryRestoreOnClose.add(player.getUniqueId());
        }
    }

    private void suppressCloseSoundForGuiSwitch(@NotNull Player player) {
        suppressNextCloseSound(player);
    }

    public static void suppressNextCloseSound(@NotNull Player player) {
        SUPPRESS_CLOSE_SOUND_ON_CLOSE.add(player.getUniqueId());
    }

    public static boolean consumeSuppressedCloseSound(@NotNull Player player) {
        return SUPPRESS_CLOSE_SOUND_ON_CLOSE.remove(player.getUniqueId());
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
        return normalizeTrashItems(merged);
    }

    private @NotNull List<ItemStack> collectAllSellItems(@NotNull Inventory inventory, @NotNull UUID playerId) {
        int pageIndex = menuView.getPageIndex(inventory);
        int pageStart = pageIndex * SellScreenView.CONTENT_SLOT_COUNT;
        int pageEnd = pageStart + SellScreenView.CONTENT_SLOT_COUNT;
        List<ItemStack> currentPage = snapshotSellItems(inventory);
        List<ItemStack> existing = sellItemsByPlayer.getOrDefault(playerId, List.of());
        List<ItemStack> merged = new java.util.ArrayList<>();
        for (int i = 0; i < Math.min(pageStart, existing.size()); i++) {
            merged.add(existing.get(i));
        }
        merged.addAll(currentPage);
        for (int i = pageEnd; i < existing.size(); i++) {
            merged.add(existing.get(i));
        }
        return normalizeSellItems(merged);
    }

    private boolean returnTrashItemsToInventory(@NotNull Player player, @NotNull List<ItemStack> items) {
        if (items.isEmpty()) {
            return false;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return false;
        }
        for (ItemStack itemStack : items) {
            if (itemStack == null || itemStack.getType() == Material.AIR) {
                continue;
            }
            inventoryService.returnItemToOwnedInventory(astPlayer, itemStack.clone());
        }
        return true;
    }

    private void notifyTrashDisposed(@NotNull Player player, @NotNull List<ItemStack> items) {
        int disposedCount = countTrashItemStacks(items);
        if (disposedCount <= 0) {
            return;
        }
        sendTrashMessage(player, PlayerMsgId.P_5601, disposedCount);
    }

    private void notifyTrashReturned(@NotNull Player player, @NotNull List<ItemStack> items) {
        int returnedCount = countTrashItemStacks(items);
        if (returnedCount <= 0) {
            return;
        }
        GuiSound.SELECT.play(player);
        sendTrashMessage(player, PlayerMsgId.P_5602, returnedCount);
    }

    private void notifySellCompleted(@NotNull Player player, @NotNull List<ItemStack> items, long totalSaleValue) {
        int soldCount = countTrashItemStacks(items);
        if (soldCount <= 0) {
            return;
        }
        sendTrashMessage(player, PlayerMsgId.P_5604, soldCount, totalSaleValue);
    }

    private void notifySellReturned(@NotNull Player player, @NotNull List<ItemStack> items) {
        int returnedCount = countTrashItemStacks(items);
        if (returnedCount <= 0) {
            return;
        }
        GuiSound.SELECT.play(player);
        sendTrashMessage(player, PlayerMsgId.P_5606, returnedCount);
    }

    private int countTrashItemStacks(@NotNull List<ItemStack> items) {
        int count = 0;
        for (ItemStack itemStack : items) {
            if (isTrashEmptyItem(null, itemStack)) {
                continue;
            }
            count++;
        }
        return count;
    }

    private void sendTrashMessage(@NotNull Player player, @NotNull PlayerMsgId msgId, Object... args) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer != null) {
            astPlayer.sendMessage(msgId, args);
            return;
        }
        player.sendMessage(PlayerMsgResource.format(msgId.getId(), args));
    }

    private @NotNull List<ItemStack> snapshotTrashItems(@NotNull Inventory inventory) {
        List<ItemStack> items = new java.util.ArrayList<>();
        int maxSlot = Math.min(45, inventory.getSize());
        for (int slot = 0; slot < maxSlot; slot++) {
            ItemStack itemStack = inventory.getItem(slot);
            if (isTrashEmptyItem(inventory, itemStack)) {
                continue;
            }
            items.add(stripTrashDisplayAmountLore(itemStack));
        }
        return normalizeTrashItems(items);
    }

    private @NotNull List<ItemStack> snapshotSellItems(@NotNull Inventory inventory) {
        List<ItemStack> items = new java.util.ArrayList<>();
        int maxSlot = Math.min(SellScreenView.CONTENT_SLOT_COUNT, inventory.getSize());
        for (int slot = 0; slot < maxSlot; slot++) {
            ItemStack itemStack = inventory.getItem(slot);
            if (isSellEmptyItem(inventory, itemStack)) {
                continue;
            }
            items.add(stripTransferDisplayLore(itemStack));
        }
        return normalizeSellItems(items);
    }

    private void handleTrashPlayerInventoryClick(
        @NotNull InventoryClickEvent event,
        @NotNull Player player,
        @NotNull Inventory topInventory
    ) {
        if (!(event.getClickedInventory() instanceof PlayerInventory)) {
            return;
        }
        if (handleMenuHotbarShortcutClick(event, player)) {
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
        rerenderTrashInventory(player, topInventory);
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
        boolean hasCurrent = !isTrashEmptyItem(topInventory, current);
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
        ItemStack partial = stripTrashDisplayAmountLore(current);
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
        rerenderTrashInventory(player, topInventory);
        player.updateInventory();
    }

    private void handleSellPlayerInventoryClick(
        @NotNull InventoryClickEvent event,
        @NotNull Player player,
        @NotNull Inventory topInventory
    ) {
        if (!(event.getClickedInventory() instanceof PlayerInventory)) {
            return;
        }
        if (handleMenuHotbarShortcutClick(event, player)) {
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
        if (!isSellableItem(clicked)) {
            GuiSound.DENY.play(player);
            sendTrashMessage(player, PlayerMsgId.P_5605);
            return;
        }
        int requested = resolveTrashTransferAmount(event.getClick(), clicked.getAmount());
        if (requested <= 0) {
            GuiSound.DENY.play(player);
            return;
        }
        int capacity = countSellPlacementCapacity(topInventory, clicked, requested);
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
        placeItemsIntoSell(topInventory, moved);
        GuiSound.SELECT.play(player);
        rerenderSellInventory(player, topInventory);
        player.updateInventory();
    }

    private void handleSellContentClick(
        @NotNull InventoryClickEvent event,
        @NotNull Player player,
        @NotNull Inventory topInventory,
        int rawSlot
    ) {
        ItemStack current = topInventory.getItem(rawSlot);
        ItemStack cursor = event.getCursor();
        boolean hasCurrent = !isSellEmptyItem(topInventory, current);
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
        ItemStack partial = stripTransferDisplayLore(current);
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
            ItemStack updated = stripTransferDisplayLore(current);
            updated.setAmount(remaining);
            topInventory.setItem(rawSlot, updated);
        }
        GuiSound.SELECT.play(player);
        rerenderSellInventory(player, topInventory);
        player.updateInventory();
    }

    private void handleStoragePlayerInventoryClick(
        @NotNull InventoryClickEvent event,
        @NotNull Player player
    ) {
        if (!(event.getClickedInventory() instanceof PlayerInventory)) {
            return;
        }
        if (handleMenuHotbarShortcutClick(event, player)) {
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
        int moved = inventoryService.moveDisplayedItemToStorage(astPlayer, event.getSlot(), requested);
        if (moved <= 0) {
            GuiSound.DENY.play(player);
            player.updateInventory();
            return;
        }
        GuiSound.SELECT.play(player);
        sendTrashMessage(player, PlayerMsgId.P_5607, moved);
        openStorage(player, menuView.getPageIndex(event.getView().getTopInventory()));
        player.updateInventory();
    }

    private void handleStorageContentClick(
        @NotNull InventoryClickEvent event,
        @NotNull Player player,
        @NotNull Inventory topInventory,
        int rawSlot
    ) {
        ItemStack current = topInventory.getItem(rawSlot);
        if (current == null || current.getType() == Material.AIR || menuView.isStorageContentPlaceholder(current)) {
            GuiSound.DENY.play(player);
            return;
        }
        UUID storageEntryId = menuView.getStorageEntryId(current);
        if (storageEntryId == null) {
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
        int moved = inventoryService.withdrawStorageEntry(astPlayer, storageEntryId, requested);
        if (moved <= 0) {
            GuiSound.DENY.play(player);
            player.updateInventory();
            return;
        }
        GuiSound.SELECT.play(player);
        sendTrashMessage(player, PlayerMsgId.P_5608, moved);
        openStorage(player, menuView.getPageIndex(topInventory));
        player.updateInventory();
    }

    private void rerenderTrashInventory(@NotNull Player player, @NotNull Inventory topInventory) {
        int pageIndex = menuView.getPageIndex(topInventory);
        List<ItemStack> currentItems = snapshotTrashItems(topInventory);
        trashItemsByPlayer.put(player.getUniqueId(), currentItems);
        suppressTrashConfirmOnClose.add(player.getUniqueId());
        menuView.openTrash(player, currentItems, pageIndex);
    }

    private void rerenderSellInventory(@NotNull Player player, @NotNull Inventory topInventory) {
        int pageIndex = menuView.getPageIndex(topInventory);
        List<ItemStack> currentItems = snapshotSellItems(topInventory);
        sellItemsByPlayer.put(player.getUniqueId(), currentItems);
        suppressSellConfirmOnClose.add(player.getUniqueId());
        menuView.openSell(player, currentItems, pageIndex);
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
        ItemStack cleanTemplate = stripTransferDisplayLore(template);
        int capacity = 0;
        for (int slot = 0; slot < TrashScreenView.CONTENT_SLOT_COUNT; slot++) {
            ItemStack existing = topInventory.getItem(slot);
            if (isTrashEmptyItem(topInventory, existing)) {
                capacity += cleanTemplate.getMaxStackSize();
            } else if (existing != null) {
                ItemStack comparableExisting = stripTransferDisplayLore(existing);
                if (comparableExisting.isSimilar(cleanTemplate)) {
                    capacity += Math.max(0, comparableExisting.getMaxStackSize() - comparableExisting.getAmount());
                }
            }
            if (capacity >= desired) {
                return desired;
            }
        }
        return Math.max(0, Math.min(desired, capacity));
    }

    private void placeItemsIntoTrash(@NotNull Inventory topInventory, @NotNull ItemStack moved) {
        ItemStack cleanMoved = stripTransferDisplayLore(moved);
        int remaining = cleanMoved.getAmount();
        for (int slot = 0; slot < TrashScreenView.CONTENT_SLOT_COUNT && remaining > 0; slot++) {
            ItemStack existing = topInventory.getItem(slot);
            if (isTrashEmptyItem(topInventory, existing)) {
                continue;
            }
            ItemStack cleanExisting = stripTransferDisplayLore(existing);
            if (!cleanExisting.isSimilar(cleanMoved)) {
                continue;
            }
            int available = Math.max(0, cleanExisting.getMaxStackSize() - cleanExisting.getAmount());
            if (available <= 0) {
                continue;
            }
            int transfer = Math.min(remaining, available);
            ItemStack updated = cleanExisting.clone();
            updated.setAmount(cleanExisting.getAmount() + transfer);
            topInventory.setItem(slot, updated);
            remaining -= transfer;
        }
        for (int slot = 0; slot < TrashScreenView.CONTENT_SLOT_COUNT && remaining > 0; slot++) {
            ItemStack existing = topInventory.getItem(slot);
            if (!isTrashEmptyItem(topInventory, existing)) {
                continue;
            }
            ItemStack newStack = cleanMoved.clone();
            int transfer = Math.min(remaining, newStack.getMaxStackSize());
            newStack.setAmount(transfer);
            topInventory.setItem(slot, newStack);
            remaining -= transfer;
        }
    }

    private int countSellPlacementCapacity(
        @NotNull Inventory topInventory,
        @NotNull ItemStack template,
        int desired
    ) {
        ItemStack cleanTemplate = stripTransferDisplayLore(template);
        int capacity = 0;
        for (int slot = 0; slot < SellScreenView.CONTENT_SLOT_COUNT; slot++) {
            ItemStack existing = topInventory.getItem(slot);
            if (isSellEmptyItem(topInventory, existing)) {
                capacity += cleanTemplate.getMaxStackSize();
            } else if (existing != null) {
                ItemStack comparableExisting = stripTransferDisplayLore(existing);
                if (comparableExisting.isSimilar(cleanTemplate)) {
                    capacity += Math.max(0, comparableExisting.getMaxStackSize() - comparableExisting.getAmount());
                }
            }
            if (capacity >= desired) {
                return desired;
            }
        }
        return Math.max(0, Math.min(desired, capacity));
    }

    private void placeItemsIntoSell(@NotNull Inventory topInventory, @NotNull ItemStack moved) {
        ItemStack cleanMoved = stripTransferDisplayLore(moved);
        int remaining = cleanMoved.getAmount();
        for (int slot = 0; slot < SellScreenView.CONTENT_SLOT_COUNT && remaining > 0; slot++) {
            ItemStack existing = topInventory.getItem(slot);
            if (isSellEmptyItem(topInventory, existing)) {
                continue;
            }
            ItemStack cleanExisting = stripTransferDisplayLore(existing);
            if (!cleanExisting.isSimilar(cleanMoved)) {
                continue;
            }
            int available = Math.max(0, cleanExisting.getMaxStackSize() - cleanExisting.getAmount());
            if (available <= 0) {
                continue;
            }
            int transfer = Math.min(remaining, available);
            ItemStack updated = cleanExisting.clone();
            updated.setAmount(cleanExisting.getAmount() + transfer);
            topInventory.setItem(slot, updated);
            remaining -= transfer;
        }
        for (int slot = 0; slot < SellScreenView.CONTENT_SLOT_COUNT && remaining > 0; slot++) {
            ItemStack existing = topInventory.getItem(slot);
            if (!isSellEmptyItem(topInventory, existing)) {
                continue;
            }
            ItemStack newStack = cleanMoved.clone();
            int transfer = Math.min(remaining, newStack.getMaxStackSize());
            newStack.setAmount(transfer);
            topInventory.setItem(slot, newStack);
            remaining -= transfer;
        }
    }

    private @NotNull List<ItemStack> normalizeTrashItems(@NotNull List<ItemStack> items) {
        List<ItemStack> normalized = new java.util.ArrayList<>();
        for (ItemStack itemStack : items) {
            if (isTrashEmptyItem(null, itemStack)) {
                continue;
            }
            ItemStack candidate = stripTransferDisplayLore(itemStack);
            if (candidate.getMaxStackSize() <= 1) {
                normalized.add(candidate);
                continue;
            }

            boolean merged = false;
            for (int index = 0; index < normalized.size(); index++) {
                ItemStack existing = normalized.get(index);
                if (existing.getMaxStackSize() <= 1 || !existing.isSimilar(candidate)) {
                    continue;
                }
                int available = Math.max(0, existing.getMaxStackSize() - existing.getAmount());
                if (available <= 0) {
                    continue;
                }
                int transfer = Math.min(candidate.getAmount(), available);
                ItemStack updated = existing.clone();
                updated.setAmount(existing.getAmount() + transfer);
                normalized.set(index, updated);
                candidate.setAmount(candidate.getAmount() - transfer);
                if (candidate.getAmount() <= 0) {
                    merged = true;
                    break;
                }
            }
            while (!merged && candidate.getAmount() > 0) {
                ItemStack split = candidate.clone();
                int transfer = Math.min(candidate.getAmount(), split.getMaxStackSize());
                split.setAmount(transfer);
                normalized.add(split);
                candidate.setAmount(candidate.getAmount() - transfer);
            }
        }
        return normalized;
    }

    private @NotNull List<ItemStack> normalizeSellItems(@NotNull List<ItemStack> items) {
        return normalizeTrashItems(items);
    }

    private @NotNull ItemStack stripTrashDisplayAmountLore(@NotNull ItemStack itemStack) {
        return stripTransferDisplayLore(itemStack);
    }

    private @NotNull ItemStack stripTransferDisplayLore(@NotNull ItemStack itemStack) {
        ItemStack cleaned = itemStack.clone();
        ItemMeta meta = cleaned.getItemMeta();
        if (meta == null || !meta.hasLore() || meta.lore() == null) {
            return cleaned;
        }

        List<Component> lore = new java.util.ArrayList<>(meta.lore());
        boolean removed = lore.removeIf(line ->
            isTransferDisplayLoreLine(PlainTextComponentSerializer.plainText().serialize(line))
        );
        if (!removed) {
            return cleaned;
        }
        meta.lore(lore);
        cleaned.setItemMeta(meta);
        return cleaned;
    }

    private boolean isTransferDisplayLoreLine(@NotNull String line) {
        return line.startsWith(BaseMenuScreenView.DISPLAY_AMOUNT_LORE_PREFIX)
            || line.startsWith(SellScreenView.UNIT_PRICE_LORE_PREFIX)
            || line.startsWith(SellScreenView.TOTAL_PRICE_LORE_PREFIX);
    }

    private boolean isTrashEmptyItem(@Nullable Inventory inventory, @Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return true;
        }
        return isTrashPlaceholderItem(inventory, itemStack);
    }

    private boolean isSellEmptyItem(@Nullable Inventory inventory, @Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return true;
        }
        return isSellPlaceholderItem(inventory, itemStack);
    }

    private boolean isTrashPlaceholderItem(@Nullable Inventory inventory, @Nullable ItemStack itemStack) {
        if (itemStack == null) {
            return false;
        }
        MenuScreen screen = inventory == null ? null : menuView.getMenuScreen(inventory);
        if (screen == MenuScreen.TRASH) {
            return menuView.isTrashContentPlaceholder(itemStack);
        }
        if (screen == MenuScreen.TRASH_CONFIRM) {
            return menuView.isTrashConfirmContentPlaceholder(itemStack);
        }
        return menuView.isTrashContentPlaceholder(itemStack)
            || menuView.isTrashConfirmContentPlaceholder(itemStack);
    }

    private boolean isSellPlaceholderItem(@Nullable Inventory inventory, @Nullable ItemStack itemStack) {
        if (itemStack == null) {
            return false;
        }
        MenuScreen screen = inventory == null ? null : menuView.getMenuScreen(inventory);
        if (screen == MenuScreen.SELL) {
            return menuView.isSellContentPlaceholder(itemStack);
        }
        if (screen == MenuScreen.SELL_CONFIRM) {
            return menuView.isSellConfirmContentPlaceholder(itemStack);
        }
        return menuView.isSellContentPlaceholder(itemStack)
            || menuView.isSellConfirmContentPlaceholder(itemStack);
    }

    private boolean isTrashContentSlot(int rawSlot) {
        return rawSlot >= 0 && rawSlot < TrashScreenView.CONTENT_SLOT_COUNT;
    }

    private boolean isSellContentSlot(int rawSlot) {
        return rawSlot >= 0 && rawSlot < SellScreenView.CONTENT_SLOT_COUNT;
    }

    private boolean isStorageContentSlot(int rawSlot) {
        return rawSlot >= 0 && rawSlot < io.github.maaasu.astralRecord.feature.menu.view.screen.StorageScreenView.CONTENT_SLOT_COUNT;
    }

    private boolean isTrashControlSlot(int rawSlot) {
        return rawSlot == MenuView.TRASH_PREVIOUS_SLOT
            || rawSlot == MenuView.TRASH_GUIDE_SLOT
            || rawSlot == MenuView.BACK_SLOT
            || rawSlot == MenuView.TRASH_CLOSE_SLOT
            || rawSlot == MenuView.TRASH_NEXT_SLOT;
    }

    private boolean isSellControlSlot(int rawSlot) {
        return rawSlot == MenuView.TRASH_PREVIOUS_SLOT
            || rawSlot == MenuView.TRASH_GUIDE_SLOT
            || rawSlot == MenuView.BACK_SLOT
            || rawSlot == MenuView.TRASH_CLOSE_SLOT
            || rawSlot == MenuView.TRASH_NEXT_SLOT;
    }

    private boolean isStorageControlSlot(int rawSlot) {
        return rawSlot == MenuView.STORAGE_PREVIOUS_SLOT
            || rawSlot == MenuView.STORAGE_CATEGORY_FILTER_SLOT
            || rawSlot == MenuView.STORAGE_RARITY_FILTER_SLOT
            || rawSlot == MenuView.STORAGE_SORT_KEY_SLOT
            || rawSlot == MenuView.BACK_SLOT
            || rawSlot == MenuView.STORAGE_SORT_DIRECTION_SLOT
            || rawSlot == MenuView.STORAGE_GUIDE_SLOT
            || rawSlot == MenuView.STORAGE_NEXT_SLOT;
    }

    private @Nullable String nextStorageCategory(@Nullable String current) {
        List<String> values = ItemCategory.supportedApiValues();
        return nextNullableCycle(current, values);
    }

    private @Nullable String nextStorageRarity(@Nullable String current) {
        return nextNullableCycle(current, List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC"));
    }

    private @Nullable String nextNullableCycle(@Nullable String current, @NotNull List<String> values) {
        if (values.isEmpty()) {
            return null;
        }
        if (current == null || current.isBlank()) {
            return values.get(0);
        }
        for (int index = 0; index < values.size(); index++) {
            if (!values.get(index).equalsIgnoreCase(current)) {
                continue;
            }
            int nextIndex = index + 1;
            return nextIndex >= values.size() ? null : values.get(nextIndex);
        }
        return null;
    }

    private boolean isSellableItem(@NotNull ItemStack itemStack) {
        String itemId = ItemStackFactory.getAstralItemId(stripTransferDisplayLore(itemStack));
        if (itemId == null || itemId.isBlank()) {
            return false;
        }
        ItemModel model = plugin.getItemService().findLoadedById(itemId);
        if (model == null) {
            model = plugin.getItemService().loadItem(itemId);
        }
        return model != null && !model.getUnSellable();
    }

    private long totalSaleValue(@NotNull List<ItemStack> items) {
        long total = 0L;
        for (ItemStack itemStack : items) {
            if (isSellEmptyItem(null, itemStack)) {
                continue;
            }
            String itemId = ItemStackFactory.getAstralItemId(stripTransferDisplayLore(itemStack));
            if (itemId == null || itemId.isBlank()) {
                continue;
            }
            ItemModel model = plugin.getItemService().findLoadedById(itemId);
            if (model == null) {
                model = plugin.getItemService().loadItem(itemId);
            }
            if (model == null || model.getUnSellable()) {
                continue;
            }
            total += (long) Math.max(0, model.getSaleValue()) * Math.max(1, itemStack.getAmount());
        }
        return total;
    }

    private boolean creditGold(@NotNull AstPlayer astPlayer, long amount) {
        if (amount <= 0L) {
            return true;
        }
        ItemModel gold = plugin.getItemService().loadItem(ItemService.DEFAULT_CURRENCY_ITEM_ID);
        if (gold == null) {
            return false;
        }
        long remaining = amount;
        while (remaining > 0L) {
            int chunk = (int) Math.min(Integer.MAX_VALUE, remaining);
            ItemStack goldStack = plugin.getItemStackFactory().create(gold);
            goldStack.setAmount(chunk);
            if (inventoryService.returnItemToOwnedInventory(astPlayer, goldStack) == null) {
                return false;
            }
            remaining -= chunk;
        }
        return true;
    }

    private void discardTrash(@NotNull Player player) {
        trashItemsByPlayer.remove(player.getUniqueId());
    }

    private void discardSell(@NotNull Player player) {
        sellItemsByPlayer.remove(player.getUniqueId());
    }
}

