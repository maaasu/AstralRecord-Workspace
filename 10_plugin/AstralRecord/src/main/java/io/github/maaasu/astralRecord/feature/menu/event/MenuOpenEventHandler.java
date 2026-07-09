package io.github.maaasu.astralRecord.feature.menu.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryClickGuard;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.menu.model.MenuScreen;
import io.github.maaasu.astralRecord.feature.menu.model.MenuShortcutAction;
import io.github.maaasu.astralRecord.feature.menu.model.MenuShortcutSettings;
import io.github.maaasu.astralRecord.feature.menu.player.PlayerBrowserGuiEventHandler;
import io.github.maaasu.astralRecord.feature.menu.service.MenuGuiTransitionService;
import io.github.maaasu.astralRecord.feature.menu.service.TrashService;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.playersetting.gui.PlayerSettingGui;
import io.github.maaasu.astralRecord.feature.sell.service.SellService;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.feature.storage.service.StorageService;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.feature.world.service.ReturnToBaseService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutClickSupport;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiSupport;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MenuOpenEventHandler extends AbstractEventHandler {
    private static final long BUFF_GUI_REFRESH_INTERVAL_TICKS = 20L;
    private static final long CRAFT_SHORTCUT_DROP_CLEANUP_INTERVAL_TICKS = 20L * 60L;
    private static final long ACCOUNTS_CACHE_TTL_MILLIS = 5_000L;

    private final AstralRecord plugin;
    private final MenuView menuView;
    private final InventoryService inventoryService;
    private final CurrencyService currencyService;
    private final StatusService statusService;
    private final MenuGuiTransitionService menuGuiTransitionService;
    private final TrashService trashService;
    private final SellService sellService;
    private final StorageService storageService;
    private final SkillTreeService skillTreeService;
    private final ReturnToBaseService returnToBaseService;
    private final Set<UUID> craftRenderSuppressed = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, CachedAccounts> cachedAccountsByUserId = new ConcurrentHashMap<>();
    private final BukkitTask buffGuiRefreshTask;
    private final BukkitTask craftShortcutDropCleanupTask;

    /**
     * メニュー GUI 全体のイベント振り分けを行うハンドラを初期化します。
     *
     * @param plugin プラグイン本体
     * @param menuView メニュー GUI 表示
     * @param inventoryService インベントリサービス
     * @param currencyService 通貨サービス
     * @param statusService ステータスサービス
     * @param menuGuiTransitionService GUI 切替サービス
     * @param trashService ゴミ箱 GUI サービス
     * @param sellService 売却 GUI サービス
     * @param storageService ストレージ GUI サービス
     * @param returnToBaseService 拠点帰還サービス
     */
    public MenuOpenEventHandler(
        @NotNull AstralRecord plugin,
        @NotNull MenuView menuView,
        @NotNull InventoryService inventoryService,
        @NotNull CurrencyService currencyService,
        @NotNull StatusService statusService,
        @NotNull MenuGuiTransitionService menuGuiTransitionService,
        @NotNull TrashService trashService,
        @NotNull SellService sellService,
        @NotNull StorageService storageService,
        @NotNull SkillTreeService skillTreeService,
        @NotNull ReturnToBaseService returnToBaseService
    ) {
        this.plugin = plugin;
        this.menuView = menuView;
        this.inventoryService = inventoryService;
        this.currencyService = currencyService;
        this.statusService = statusService;
        this.menuGuiTransitionService = menuGuiTransitionService;
        this.trashService = trashService;
        this.sellService = sellService;
        this.storageService = storageService;
        this.skillTreeService = skillTreeService;
        this.returnToBaseService = returnToBaseService;
        this.buffGuiRefreshTask = plugin.getServer().getScheduler().runTaskTimer(
            plugin,
            (Runnable) this::refreshOpenBuffMenus,
            BUFF_GUI_REFRESH_INTERVAL_TICKS,
            BUFF_GUI_REFRESH_INTERVAL_TICKS
        );
        this.craftShortcutDropCleanupTask = plugin.getServer().getScheduler().runTaskTimer(
            plugin,
            (Runnable) this::removeDroppedCraftShortcutItems,
            CRAFT_SHORTCUT_DROP_CLEANUP_INTERVAL_TICKS,
            CRAFT_SHORTCUT_DROP_CLEANUP_INTERVAL_TICKS
        );
    }

    @Override
    public void cleanup() {
        super.cleanup();
        buffGuiRefreshTask.cancel();
        craftShortcutDropCleanupTask.cancel();
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
                menuGuiTransitionService.applyPlayerInventoryDummy(player, event.getInventory(), event.getView().getType());
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
                    && menuGuiTransitionService.shouldPlayCloseSoundOnClose(player, event.getInventory());
                trashService.handleClose(event.getInventory(), player);
                sellService.handleClose(event.getInventory(), player);
                storageService.handleClose(event);
                if (menuGuiTransitionService.consumePlayerInventoryDummyApplied(player)
                    && !menuGuiTransitionService.consumeSuppressedPlayerInventoryRestore(player)) {
                    menuGuiTransitionService.restorePlayerInventory(player);
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
        runSafely(() -> {
            cleanupCraftShortcuts(event.getPlayer(), true);
            plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> {
                    cleanupCraftShortcuts(event.getPlayer(), true);
                    scheduleCraftShortcutRender(event.getPlayer());
                },
                2L
            );
        }, LogId.E_5600, event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        runSafely(() -> cleanupCraftShortcuts(event.getPlayer(), false), LogId.E_5600, event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        runSafely(() -> cleanupCraftShortcuts(event.getPlayer(), true), LogId.E_5600, event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        runSafely(() -> {
            if (!menuView.isCraftShortcutItem(event.getItemDrop().getItemStack())) {
                return;
            }
            event.setCancelled(true);
            event.getItemDrop().remove();
            cleanupCraftShortcuts(event.getPlayer(), true);
        }, LogId.E_5600, event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        runSafely(() -> {
            if (!menuView.isCraftShortcutItem(event.getEntity().getItemStack())) {
                return;
            }
            event.setCancelled(true);
        }, LogId.E_5600, event.getEntity().getWorld().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        runSafely(() -> {
            if (!menuView.isCraftShortcutItem(event.getItem().getItemStack())) {
                return;
            }
            event.setCancelled(true);
            event.getItem().remove();
        }, LogId.E_5600, event.getItem().getWorld().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        runSafely(() -> {
            if (menuView.isMenuInventory(event.getView().getTopInventory())) {
                MenuScreen menuScreen = menuView.getMenuScreen(event.getView().getTopInventory());
                if (event.getWhoClicked() instanceof Player player
                    && !AccountModeGuard.isGameplayPlayer(player)) {
                    event.setCancelled(true);
                    player.closeInventory();
                    return;
                }
                if (event.getWhoClicked() instanceof Player player
                    && handleMenuHotbarShortcutClick(event, player)) {
                    return;
                }
                if (menuScreen == MenuScreen.TRASH || menuScreen == MenuScreen.TRASH_CONFIRM) {
                    trashService.handleClick(event);
                    return;
                }
                if (menuScreen == MenuScreen.SELL || menuScreen == MenuScreen.SELL_CONFIRM) {
                    sellService.handleClick(event);
                    return;
                }
                if (menuScreen == MenuScreen.STORAGE) {
                    storageService.handleClick(event);
                    return;
                }
                if (menuScreen == MenuScreen.EQUIPMENT_GUI
                    || menuScreen == MenuScreen.EQUIPMENT_ENHANCE
                    || menuScreen == MenuScreen.EQUIPMENT_REPAIR) {
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
                if (event.getWhoClicked() instanceof Player player
                    && !AccountModeGuard.isGameplayPlayer(player)) {
                    event.setCancelled(true);
                    player.closeInventory();
                    return;
                }
                if (menuScreen == MenuScreen.TRASH || menuScreen == MenuScreen.TRASH_CONFIRM) {
                    trashService.handleDrag(event);
                    return;
                }
                if (menuScreen == MenuScreen.SELL || menuScreen == MenuScreen.SELL_CONFIRM) {
                    sellService.handleDrag(event);
                    return;
                }
                if (menuScreen == MenuScreen.STORAGE) {
                    storageService.handleDrag(event);
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
            case EQUIPMENT_ENHANCE -> {
            }
            case EQUIPMENT_REPAIR -> {
            }
            case CURRENCY -> handleCurrencyClick(event, player);
            case GUIDE -> handleGuideClick(event, player);
            case TRASH, TRASH_CONFIRM -> GuiSound.DENY.play(player);
            default -> GuiSound.DENY.play(player);
        }
    }

    private boolean handleMenuHotbarShortcutClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        return HotbarShortcutClickSupport.handle(event, player, inventoryService);
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
            trashService.open(player, 0, true);
            return;
        }
        if (rawSlot == MenuView.GUIDE_SLOT) {
            GuiSound.SELECT.play(player);
            switchGuiWithoutInventoryReload(player, () -> menuView.openGuide(player));
            return;
        }
        if (rawSlot == MenuView.RETURN_TO_BASE_SLOT) {
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer == null) {
                GuiSound.DENY.play(player);
                return;
            }
            if (!returnToBaseService.beginReturn(astPlayer)) {
                GuiSound.DENY.play(player);
                return;
            }
            GuiSound.SELECT.play(player);
            player.closeInventory();
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

    private void handleGuideClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        int rawSlot = event.getRawSlot();
        Inventory inventory = event.getView().getTopInventory();
        int pageIndex = menuView.getPageIndex(inventory);
        String contentId = menuView.getContentId(inventory);

        if (rawSlot == MenuView.PAGING_BACK_SLOT || rawSlot == MenuView.BACK_SLOT) {
            GuiSound.SELECT.play(player);
            switchGuiWithoutInventoryReload(player, () -> {
                if (contentId == null) {
                    menuView.open(player);
                } else {
                    menuView.openGuide(player, pageIndex);
                }
            });
            return;
        }

        if (contentId != null) {
            GuiSound.DENY.play(player);
            return;
        }

        if (rawSlot == MenuView.PAGING_PREVIOUS_SLOT && menuView.hasPreviousGuidePage(pageIndex)) {
            GuiSound.SELECT.play(player);
            switchGuiWithoutInventoryReload(player, () -> menuView.openGuide(player, pageIndex - 1));
            return;
        }
        if (rawSlot == MenuView.PAGING_NEXT_SLOT && menuView.hasNextGuidePage(pageIndex)) {
            GuiSound.SELECT.play(player);
            switchGuiWithoutInventoryReload(player, () -> menuView.openGuide(player, pageIndex + 1));
            return;
        }

        var guide = menuView.getGuideAtSlot(rawSlot, pageIndex);
        if (guide != null) {
            GuiSound.SELECT.play(player);
            switchGuiWithoutInventoryReload(player, () -> menuView.openGuideDetail(player, guide, pageIndex));
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
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }
        var classService = plugin.getPlayerClassService();
        if (classService == null) {
            GuiSound.DENY.play(player);
            return;
        }
        String classId = menuView.getClassId(clickedItem);
        if (classId == null || classId.isBlank() || classId.equalsIgnoreCase(astPlayer.getClassId())) {
            GuiSound.DENY.play(player);
            return;
        }
        if (!classService.canChangeClass(astPlayer, classId)) {
            return;
        }

        String oldDisplayName = classService.getDisplayName(astPlayer.getClassId());
        classService.changeClass(astPlayer, classId);
        String newDisplayName = classService.getDisplayName(classId);
        PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5812, oldDisplayName, newDisplayName);
        GuiSound.SELECT.play(player);
        player.closeInventory();
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
            int classPoints = skillTreeService.availableClassPoints(astPlayer);
            int passivePoints = skillTreeService.availablePassivePoints(astPlayer);
            List<AccountModel> accounts = getCachedAccounts(astPlayer.getUser().getUuid());
            menuView.renderCraftShortcuts(
                player,
                MenuShortcutSettings.defaults(),
                displayedType,
                snapshot,
                astPlayer.getAccount(),
                classPoints,
                passivePoints,
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

    private void cleanupCraftShortcuts(@NotNull Player player, boolean updateInventory) {
        craftRenderSuppressed.add(player.getUniqueId());
        try {
            menuView.clearCraftShortcuts(player);
            menuView.removeCraftShortcutItems(player);
            removeDroppedCraftShortcutItems(player.getWorld());
            if (updateInventory) {
                player.updateInventory();
            }
        } finally {
            craftRenderSuppressed.remove(player.getUniqueId());
        }
    }

    private void removeDroppedCraftShortcutItems() {
        for (World world : plugin.getServer().getWorlds()) {
            removeDroppedCraftShortcutItems(world);
        }
    }

    private void removeDroppedCraftShortcutItems(@NotNull World world) {
        for (org.bukkit.entity.Item item : world.getEntitiesByClass(org.bukkit.entity.Item.class)) {
            if (menuView.isCraftShortcutItem(item.getItemStack())) {
                item.remove();
            }
        }
    }

    private @NotNull List<AccountModel> getCachedAccounts(@NotNull UUID userId) {
        long now = System.currentTimeMillis();
        CachedAccounts cached = cachedAccountsByUserId.get(userId);
        if (cached != null && now - cached.loadedAtMillis() <= ACCOUNTS_CACHE_TTL_MILLIS) {
            return cached.accounts();
        }
        List<AccountModel> accounts = plugin.getAccountService().getAccounts(userId);
        cachedAccountsByUserId.put(userId, new CachedAccounts(accounts, now));
        return accounts;
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

    private boolean isHotbarShortcutGui(@NotNull Inventory openedInventory) {
        return HotbarShortcutGuiSupport.isManagedGui(openedInventory);
    }

    private void switchGuiWithoutInventoryReload(@NotNull Player player, @NotNull Runnable opener) {
        menuGuiTransitionService.switchGuiWithoutInventoryReload(player, opener);
    }

    private void switchGuiWithInventoryRestore(@NotNull Player player, @NotNull Runnable opener) {
        menuGuiTransitionService.switchGuiWithInventoryRestore(player, opener);
    }

    public static void suppressNextCloseSound(@NotNull Player player) {
        MenuGuiTransitionService.suppressNextCloseSound(player);
    }

    private record CachedAccounts(@NotNull List<AccountModel> accounts, long loadedAtMillis) {
    }
}
