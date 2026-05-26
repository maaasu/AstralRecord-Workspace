package io.github.maaasu.astralRecord.feature.menu.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.menu.model.MenuScreen;
import io.github.maaasu.astralRecord.feature.menu.model.MenuShortcutAction;
import io.github.maaasu.astralRecord.feature.menu.model.MenuShortcutSettings;
import io.github.maaasu.astralRecord.feature.menu.repository.MenuShortcutRepository;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * プレイヤーインベントリのクラフト欄からメニューとショートカットを開くイベントハンドラ。
 */
public class MenuOpenEventHandler extends AbstractEventHandler {

    private final AstralRecord plugin;
    private final MenuView menuView;
    private final MenuShortcutRepository shortcutRepository;
    private final InventoryService inventoryService;
    private final CurrencyService currencyService;
    private final StatusService statusService;
    private final Set<UUID> craftRenderSuppressed = ConcurrentHashMap.newKeySet();

    /**
     * メニュー起動イベントハンドラを生成します。
     *
     * @param plugin プラグインインスタンス
     * @param menuView メニュー表示ビュー
     * @param shortcutRepository ショートカット設定リポジトリ
     * @param inventoryService インベントリ表示サービス
     * @param currencyService 通貨表示サービス
     */
    public MenuOpenEventHandler(
        @NotNull AstralRecord plugin,
        @NotNull MenuView menuView,
        @NotNull MenuShortcutRepository shortcutRepository,
        @NotNull InventoryService inventoryService,
        @NotNull CurrencyService currencyService,
        @NotNull StatusService statusService
    ) {
        this.plugin = plugin;
        this.menuView = menuView;
        this.shortcutRepository = shortcutRepository;
        this.inventoryService = inventoryService;
        this.currencyService = currencyService;
        this.statusService = statusService;
    }

    /**
     * プレイヤーインベントリのクラフト欄にメニューショートカットを表示します。
     *
     * @param event クラフト準備イベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrepareItemCraft(PrepareItemCraftEvent event) {
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
        }, LogId.E_5600, "prepare");
    }

    /**
     * インベントリを開いた直後にクラフト欄ショートカットを再描画します。
     *
     * @param event インベントリオープンイベント
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        runSafely(() -> {
            if (event.getPlayer() instanceof Player player) {
                scheduleCraftShortcutRender(player);
                applyHotbarShortcutMode(player, event.getInventory(), event.getView().getType());
            }
        }, LogId.E_5600, event.getPlayer().getName());
    }

    /**
     * GUI 表示中はホットバーをショートカット表示モードへ切り替えます。
     *
     * @param player 対象プレイヤー
     * @param openedInventory 開かれたインベントリ
     * @param viewType 開いたビューの種別
     */
    private void applyHotbarShortcutMode(
        @NotNull Player player,
        @NotNull Inventory openedInventory,
        @NotNull org.bukkit.event.inventory.InventoryType viewType
    ) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !astPlayer.getAccount().getMode().shouldReflectInventoryToGui())
            return;

        if (menuView.isMenuInventory(openedInventory)
            || viewType == org.bukkit.event.inventory.InventoryType.CRAFTING) {
            inventoryService.setHotbarShortcutMode(astPlayer, true);
        }
    }

    /**
     * クラフト欄を閉じるときにショートカット表示アイテムを消去します。
     *
     * @param event インベントリクローズイベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        runSafely(() -> {
            if (event.getInventory() instanceof CraftingInventory inventory) {
                menuView.clearCraftShortcuts(inventory);
                if (event.getPlayer() instanceof Player player)
                    menuView.removeCraftShortcutItems(player);

            }
            if (event.getPlayer() instanceof Player player) {
                AstPlayer astPlayer = AstPlayerCache.get(player);
                if (astPlayer != null)
                    inventoryService.setHotbarShortcutMode(astPlayer, false);
                scheduleCraftShortcutRender(player);
            }
        }, LogId.E_5600, event.getPlayer().getName());
    }

    /**
     * 参加直後にクラフト欄ショートカットを描画します。
     *
     * @param event プレイヤー参加イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        runSafely(() -> plugin.getServer().getScheduler().runTaskLater(
            plugin,
            () -> scheduleCraftShortcutRender(event.getPlayer()),
            2L
        ), LogId.E_5600, event.getPlayer().getName());
    }

    /**
     * クラフト成果物スロット、ショートカット、メニュー画面内のクリックを処理します。
     *
     * @param event インベントリクリックイベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        runSafely(() -> {
            if (menuView.isMenuInventory(event.getView().getTopInventory())) {
                if (event.getWhoClicked() instanceof Player player
                    && handleMenuHotbarShortcutClick(event, player)) {
                    return;
                }
                if (menuView.getMenuScreen(event.getView().getTopInventory()) == MenuScreen.EQUIPMENT_GUI) {
                    return;
                }
                handleMenuClick(event);
                return;
            }

            if (!(event.getWhoClicked() instanceof Player player) || !isPlayerMode(player)) {
                return;
            }

            // クラフト枠ショートカットがホットバー入れ替え（NUMBER_KEY）やオフハンド入れ替えで
            // プレイヤーインベントリの実アイテムと交換されないよう、対象スロットへのスワップ系操作は抑止する。
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
                MenuShortcutAction action = settings(player).getAction(shortcutIndex);
                executeShortcutAction(player, action);
            }
        }, LogId.E_5600, event.getWhoClicked().getName());
    }

    /**
     * クラフト枠（成果物・ショートカット枠）に対するホットバー入れ替え系操作かを判定します。
     * NUMBER_KEY・SWAP_OFFHAND など、対象スロットの中身を別スロットへ移動する操作を対象とします。
     *
     * @param event インベントリクリックイベント
     * @return クラフト枠への入れ替え系操作なら true
     */
    private boolean isCraftSlotSwapAttempt(@NotNull InventoryClickEvent event) {
        if (event.getView().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING)
            return false;

        int rawSlot = event.getRawSlot();
        if (rawSlot < MenuView.CRAFT_RESULT_RAW_SLOT
            || rawSlot >= MenuView.CRAFT_RESULT_RAW_SLOT + craftMenuSlotCount())
            return false;

        return switch (event.getClick()) {
            case NUMBER_KEY, SWAP_OFFHAND -> true;
            default -> false;
        };
    }

    /**
     * メニュー画面内やショートカットクラフト欄へのドラッグ操作を抑止します。
     *
     * @param event インベントリドラッグイベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        runSafely(() -> {
            if (menuView.isMenuInventory(event.getView().getTopInventory())) {
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

    /**
     * メニュー GUI 内のクリック内容に応じて画面遷移または操作を実行します。
     *
     * @param event インベントリクリックイベント
     */
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
            case SHORTCUT_SLOT_SELECTOR -> handleShortcutSlotSelectorClick(player, event.getRawSlot());
            case SHORTCUT_ACTION_SELECTOR -> handleShortcutActionSelectorClick(player, event.getRawSlot());
        }
    }

    /**
     * メニュー GUI 表示中にホットバーへ描画されたショートカットを処理します。
     *
     * @param event インベントリクリックイベント
     * @param player 操作プレイヤー
     * @return ホットバーショートカット領域のクリックとして処理した場合 true
     */
    private boolean handleMenuHotbarShortcutClick(
        @NotNull InventoryClickEvent event,
        @NotNull Player player
    ) {
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
        boolean handled = inventoryService.handleHotbarShortcutClick(astPlayer, slot);
        if (handled) {
            if (slot == 8) {
                GuiSound.CLOSE.play(player);
            } else {
                GuiSound.SELECT.play(player);
            }
        } else {
            GuiSound.DENY.play(player);
        }
        return true;
    }

    /**
     * メインメニューのクリックを処理します。
     *
     * @param player 操作プレイヤー
     * @param rawSlot クリックされた raw slot
     */
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
        if (rawSlot == MenuView.EQUIPMENT_GUI_SLOT) {
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer == null) {
                GuiSound.DENY.play(player);
                return;
            }
            GuiSound.SELECT.play(player);
            menuView.openEquipmentGui(
                player,
                new org.bukkit.inventory.ItemStack[] {
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
            return;
        }
        if (rawSlot == MenuView.SHORTCUT_SETTINGS_SLOT) {
            GuiSound.SELECT.play(player);
            menuView.openShortcutSlotSelector(player, settings(player));
            return;
        }
        GuiSound.DENY.play(player);
    }

    /**
     * インベントリ選択メニューのクリックを処理します。
     *
     * @param player 操作プレイヤー
     * @param rawSlot クリックされた raw slot
     */
    private void handleInventorySelectorClick(@NotNull Player player, int rawSlot) {
        if (rawSlot == MenuView.BACK_SLOT) {
            GuiSound.SELECT.play(player);
            menuView.open(player);
            return;
        }

        InventoryType inventoryType = menuView.getInventoryTypeAtSlot(rawSlot);
        if (inventoryType == null) {
            GuiSound.DENY.play(player);
            return;
        }

        applyInventoryShortcut(player, inventoryType);
    }

    /**
     * 通貨 GUI のページング操作を処理します。
     *
     * @param event インベントリクリックイベント
     * @param player 操作プレイヤー
     */
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

    /**
     * ショートカットスロット選択メニューのクリックを処理します。
     *
     * @param player 操作プレイヤー
     * @param rawSlot クリックされた raw slot
     */
    private void handleShortcutSlotSelectorClick(@NotNull Player player, int rawSlot) {
        if (rawSlot == MenuView.BACK_SLOT) {
            GuiSound.SELECT.play(player);
            menuView.open(player);
            return;
        }

        int shortcutSlot = menuView.getShortcutSettingSlotAtSlot(rawSlot);
        if (shortcutSlot < 0) {
            GuiSound.DENY.play(player);
            return;
        }

        GuiSound.SELECT.play(player);
        menuView.openShortcutActionSelector(player, shortcutSlot, settings(player).getAction(shortcutSlot));
    }

    /**
     * ショートカット項目選択メニューのクリックを処理します。
     *
     * @param player 操作プレイヤー
     * @param rawSlot クリックされた raw slot
     */
    private void handleShortcutActionSelectorClick(@NotNull Player player, int rawSlot) {
        if (rawSlot == MenuView.BACK_SLOT) {
            GuiSound.SELECT.play(player);
            menuView.openShortcutSlotSelector(player, settings(player));
            return;
        }

        MenuShortcutAction action = menuView.getShortcutActionAtSlot(rawSlot);
        if (action == null) {
            GuiSound.DENY.play(player);
            return;
        }

        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }

        int shortcutSlot = menuView.getShortcutSlotIndex(player.getOpenInventory().getTopInventory());
        shortcutRepository.updateSlot(astPlayer.getAccount().getUuid(), shortcutSlot, action);
        GuiSound.SELECT.play(player);
        scheduleCraftShortcutRender(player);
        menuView.openShortcutSlotSelector(player, settings(player));
    }

    /**
     * ショートカット項目を実行します。
     *
     * @param player 操作プレイヤー
     * @param action 実行するショートカット項目
     */
    private void executeShortcutAction(@NotNull Player player, @NotNull MenuShortcutAction action) {
        if (action == MenuShortcutAction.NONE) {
            GuiSound.SELECT.play(player);
            menuView.openShortcutSlotSelector(player, settings(player));
            return;
        }
        if (action == MenuShortcutAction.MAIN_MENU) {
            GuiSound.OPEN.play(player);
            openMainMenu(player);
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

    /**
     * 指定インベントリタイプをプレイヤー GUI へ反映します。
     *
     * @param player 操作プレイヤー
     * @param inventoryType 表示するインベントリタイプ
     */
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

        GuiSound.SELECT.play(player);
        suppressCraftRendering(player);
        menuView.clearCraftShortcuts(player);
        inventoryService.applyInventoryToGui(astPlayer, inventoryType);
        plugin.getServer().getScheduler().runTask(plugin, () -> resumeCraftRendering(player));
    }

    /**
     * クラフト欄表示アイテムを消去してメインメニューを開きます。
     *
     * @param player 操作プレイヤー
     */
    private void openMainMenu(@NotNull Player player) {
        suppressCraftRendering(player);
        menuView.clearCraftShortcuts(player);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            menuView.open(player);
            resumeCraftRendering(player);
        });
    }

    /**
     * クラフト欄表示アイテムを消去して通貨 GUI を開きます。
     *
     * @param player 操作プレイヤー
     * @param pageIndex 0 始まりのページ番号
     */
    private void openCurrency(@NotNull Player player, int pageIndex) {
        suppressCraftRendering(player);
        menuView.clearCraftShortcuts(player);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            menuView.openCurrency(player, currencyItems(player), pageIndex);
            resumeCraftRendering(player);
        });
    }

    /**
     * プレイヤーの通貨表示アイテム一覧を取得します。
     *
     * @param player 対象プレイヤー
     * @return 通貨 GUI 表示用 ItemStack 一覧
     */
    private @NotNull List<ItemStack> currencyItems(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return List.of();
        }
        return currencyService.getCurrencyItemStacks(astPlayer.getAccount().getUuid());
    }

    /**
     * 次 tick にクラフトショートカットを再描画します。
     *
     * @param player 表示対象プレイヤー
     */
    private void scheduleCraftShortcutRender(@NotNull Player player) {
        plugin.getServer().getScheduler().runTask(plugin, () -> renderCraftShortcuts(player));
    }

    /**
     * クラフトショートカットを現在の設定で描画します。
     *
     * @param player 表示対象プレイヤー
     */
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
            UUID accountId = astPlayer.getAccount().getUuid();
            InventoryType displayedType = astPlayer.getAccount().getMode().shouldReflectInventoryToGui()
                ? inventoryService.getDisplayedInventoryType(accountId)
                : null;
            menuView.renderCraftShortcuts(
                player,
                shortcutRepository.findByAccountId(accountId),
                displayedType
            );
        } finally {
            craftRenderSuppressed.remove(playerId);
        }
    }

    /**
     * 指定プレイヤーのクラフト欄ショートカット再描画を一時停止します。
     *
     * @param player 対象プレイヤー
     */
    private void suppressCraftRendering(@NotNull Player player) {
        craftRenderSuppressed.add(player.getUniqueId());
    }

    /**
     * 指定プレイヤーのクラフト欄ショートカット再描画を再開します。
     *
     * @param player 対象プレイヤー
     */
    private void resumeCraftRendering(@NotNull Player player) {
        craftRenderSuppressed.remove(player.getUniqueId());
        scheduleCraftShortcutRender(player);
    }

    /**
     * 指定プレイヤーのショートカット設定を取得します。
     *
     * @param player 対象プレイヤー
     * @return ショートカット設定
     */
    private @NotNull MenuShortcutSettings settings(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return MenuShortcutSettings.defaults();
        }
        return shortcutRepository.findByAccountId(astPlayer.getAccount().getUuid());
    }

    /**
     * プレイヤーインベントリのクラフト欄クリックか判定します。
     *
     * @param event インベントリクリックイベント
     * @return メニュー起動またはショートカット実行対象なら true
     */
    private boolean isCraftMenuClick(@NotNull InventoryClickEvent event) {
        return event.getView().getType() == org.bukkit.event.inventory.InventoryType.CRAFTING
            && event.getRawSlot() >= MenuView.CRAFT_RESULT_RAW_SLOT
            && event.getRawSlot() < MenuView.CRAFT_RESULT_RAW_SLOT + craftMenuSlotCount();
    }

    /**
     * 成果物スロット1つと 2x2 クラフトスロット4つを合わせた数を返します。
     *
     * @return raw slot 判定対象数
     */
    private int craftMenuSlotCount() {
        return MenuShortcutSettings.SLOT_COUNT + 1;
    }

    /**
     * プレイヤー自身のクラフトインベントリか判定します。
     *
     * @param inventory 判定対象インベントリ
     * @return プレイヤークラフトインベントリなら true
     */
    private boolean isPlayerCraftingInventory(@NotNull CraftingInventory inventory) {
        return inventory.getType() == org.bukkit.event.inventory.InventoryType.CRAFTING;
    }

    /**
     * 通常プレイヤーモードか判定します。
     *
     * @param player 判定対象プレイヤー
     * @return 通常プレイヤーモードなら true
     */
    private boolean isPlayerMode(@Nullable Player player) {
        if (player == null) {
            return false;
        }
        var astPlayer = AstPlayerCache.get(player);
        return astPlayer != null && astPlayer.getAccount().getMode() == AccountMode.PLAYER;
    }

}