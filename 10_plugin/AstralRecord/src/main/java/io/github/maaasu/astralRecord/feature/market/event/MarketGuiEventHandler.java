package io.github.maaasu.astralRecord.feature.market.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.market.gui.MarketGui;
import io.github.maaasu.astralRecord.feature.market.gui.MarketScreen;
import io.github.maaasu.astralRecord.feature.market.model.MarketAccountSummary;
import io.github.maaasu.astralRecord.feature.market.model.MarketCancelRequest;
import io.github.maaasu.astralRecord.feature.market.model.MarketListing;
import io.github.maaasu.astralRecord.feature.market.model.MarketListingCreateRequest;
import io.github.maaasu.astralRecord.feature.market.model.MarketListingDraft;
import io.github.maaasu.astralRecord.feature.market.model.MarketListingQuery;
import io.github.maaasu.astralRecord.feature.market.model.MarketPurchaseRequest;
import io.github.maaasu.astralRecord.feature.market.model.MarketTransaction;
import io.github.maaasu.astralRecord.feature.market.service.MarketService;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.gold.GoldAmountSettingGui;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** マーケット GUI の操作と API 確定処理を扱います。 */
public final class MarketGuiEventHandler extends AbstractEventHandler {
    public static final String GOLD_AMOUNT_SOURCE_KEY = "market-listing-price";
    private static final String MARKET_CURRENCY_ID = "gold";
    private static final int PAGE_SIZE = 45;

    private final AstralRecord plugin;
    private final MarketGui marketGui;
    private final MarketService marketService;
    private final InventoryService inventoryService;
    private final InventorySaveCoordinator inventorySaveCoordinator;
    private final CurrencyService currencyService;
    private final PlayerMessageService messageService;
    private final GoldAmountSettingGui goldAmountSettingGui;
    private final Map<UUID, MarketSession> sessions = new ConcurrentHashMap<>();

    public MarketGuiEventHandler(
        @NotNull AstralRecord plugin,
        @NotNull ItemService itemService,
        @NotNull ItemStackFactory itemStackFactory,
        @NotNull MarketService marketService,
        @NotNull InventoryService inventoryService,
        @NotNull InventorySaveCoordinator inventorySaveCoordinator,
        @NotNull CurrencyService currencyService,
        @NotNull PlayerMessageService messageService,
        @NotNull GoldAmountSettingGui goldAmountSettingGui
    ) {
        this.plugin = plugin;
        this.marketGui = new MarketGui(itemService, itemStackFactory);
        this.marketService = marketService;
        this.inventoryService = inventoryService;
        this.inventorySaveCoordinator = inventorySaveCoordinator;
        this.currencyService = currencyService;
        this.messageService = messageService;
        this.goldAmountSettingGui = goldAmountSettingGui;
    }

    /** 管理コマンドからマーケットを開きます。 */
    public void openFromCommand(@NotNull Player player) {
        openBrowse(player, 1);
    }

    /** マーケット NPC からマーケットを開きます。 */
    public void openFromNpc(@NotNull Player player) {
        openBrowse(player, 1);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        runSafely(() -> {
            Inventory top = event.getView().getTopInventory();
            if (marketGui.isMarketInventory(top)) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player player) {
                    handleMarketClick(event, player, top);
                }
                return;
            }
            if (isMarketGoldAmountInventory(top)) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player player) {
                    handleGoldAmountClick(event, player, top);
                }
            }
        }, LogId.E_6320, event.getWhoClicked().getName(), "market_click");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        runSafely(() -> {
            Inventory top = event.getView().getTopInventory();
            if (marketGui.isMarketInventory(top) || isMarketGoldAmountInventory(top)) {
                event.setCancelled(true);
            }
        }, LogId.E_6320, event.getWhoClicked().getName(), "market_drag");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    private void handleMarketClick(
        @NotNull InventoryClickEvent event,
        @NotNull Player player,
        @NotNull Inventory top
    ) {
        if (!AccountModeGuard.isGameplayPlayer(player)) {
            player.closeInventory();
            return;
        }
        MarketGui.MarketHolder holder = marketGui.getHolder(top);
        MarketSession session = sessions.get(player.getUniqueId());
        if (holder == null || !isCurrentSession(holder, player, session)) {
            GuiSound.DENY.play(player);
            return;
        }

        switch (holder.screen()) {
            case LOADING -> GuiSound.DENY.play(player);
            case BROWSE, MY_LISTINGS -> handleListingsClick(event, player, session);
            case SELL_SELECT -> handleSellSelectClick(event, player, session);
            case SELL_CONFIG -> handleSellConfigClick(event, player, session);
            case PURCHASE_CONFIRM -> handlePurchaseConfirmClick(event, player, session);
            case CANCEL_CONFIRM -> handleCancelConfirmClick(event, player, session);
        }
    }

    private void handleListingsClick(
        @NotNull InventoryClickEvent event,
        @NotNull Player player,
        @NotNull MarketSession session
    ) {
        int rawSlot = event.getRawSlot();
        if (rawSlot >= 0 && rawSlot < PAGE_SIZE) {
            if (rawSlot >= session.listings.size()) {
                GuiSound.DENY.play(player);
                return;
            }
            MarketListing listing = session.listings.get(rawSlot);
            if (session.ownListings) {
                if (!isCancelable(listing)) {
                    GuiSound.DENY.play(player);
                    return;
                }
                session.selectedListing = listing;
                session.screen = MarketScreen.CANCEL_CONFIRM;
                marketGui.openCancelConfirm(player, session.sessionId, listing);
                GuiSound.SELECT.play(player);
                return;
            }
            session.selectedListing = listing;
            session.screen = MarketScreen.PURCHASE_CONFIRM;
            marketGui.openPurchaseConfirm(player, session.sessionId, listing, goldAmount(player));
            GuiSound.SELECT.play(player);
            return;
        }

        switch (rawSlot) {
            case MarketGui.PREVIOUS_SLOT -> openListings(player, session.ownListings, Math.max(1, session.page - 1));
            case MarketGui.NEXT_SLOT -> openListings(player, session.ownListings, session.page + 1);
            case MarketGui.BROWSE_SLOT -> openListings(player, false, 1);
            case MarketGui.MY_LISTINGS_SLOT -> openListings(player, true, 1);
            case MarketGui.SELL_SLOT -> {
                session.screen = MarketScreen.SELL_SELECT;
                session.draft = null;
                marketGui.openSellSelect(player, session.sessionId, session.summary);
                GuiSound.OPEN.play(player);
            }
            case MarketGui.REFRESH_SLOT -> openListings(player, session.ownListings, session.page);
            case MarketGui.CLOSE_SLOT -> player.closeInventory();
            default -> GuiSound.DENY.play(player);
        }
    }

    private void handleSellSelectClick(
        @NotNull InventoryClickEvent event,
        @NotNull Player player,
        @NotNull MarketSession session
    ) {
        if (event.getRawSlot() == MarketGui.CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (!(event.getClickedInventory() instanceof PlayerInventory)) {
            GuiSound.DENY.play(player);
            return;
        }

        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }
        InventoryEntryModel entry = inventoryService.getOwnedEntryAtBukkitSlot(astPlayer, event.getSlot());
        ItemModel item = inventoryService.getOwnedItemModelAtBukkitSlot(astPlayer, event.getSlot());
        if (!isMarketable(entry, item)) {
            messageService.send(player, PlayerMsgId.P_6304);
            GuiSound.DENY.play(player);
            return;
        }

        boolean instanceListing = entry.getInstanceId() != null;
        long maxQuantity = instanceListing ? 1L : entry.getQuantity();
        if (maxQuantity < 1L) {
            GuiSound.DENY.play(player);
            return;
        }
        session.draft = new MarketListingDraft(
            UUID.randomUUID(),
            entry.getInventoryEntryId(),
            entry.getItemCategory(),
            entry.getItemId(),
            entry.getInstanceType(),
            entry.getInstanceId(),
            maxQuantity,
            Math.max(1L, item.getSaleValue())
        );
        session.screen = MarketScreen.SELL_CONFIG;
        marketGui.openSellConfig(player, session.sessionId, session.draft);
        GuiSound.SELECT.play(player);
    }

    private void handleSellConfigClick(
        @NotNull InventoryClickEvent event,
        @NotNull Player player,
        @NotNull MarketSession session
    ) {
        MarketListingDraft draft = session.draft;
        if (draft == null) {
            openListings(player, false, 1);
            return;
        }
        switch (event.getRawSlot()) {
            case MarketGui.BACK_SLOT -> {
                session.screen = MarketScreen.SELL_SELECT;
                marketGui.openSellSelect(player, session.sessionId, session.summary);
                GuiSound.SELECT.play(player);
            }
            case MarketGui.QUANTITY_DOWN_SLOT -> {
                adjustDraftQuantity(draft, -(event.isShiftClick() ? 16L : 1L));
                marketGui.openSellConfig(player, session.sessionId, draft);
                GuiSound.SELECT.play(player);
            }
            case MarketGui.QUANTITY_UP_SLOT -> {
                adjustDraftQuantity(draft, event.isShiftClick() ? 16L : 1L);
                marketGui.openSellConfig(player, session.sessionId, draft);
                GuiSound.SELECT.play(player);
            }
            case MarketGui.PRICE_SLOT -> {
                long maxUnitPrice = Math.max(1L, Long.MAX_VALUE / draft.quantity());
                goldAmountSettingGui.open(
                    player,
                    GOLD_AMOUNT_SOURCE_KEY,
                    draft.contextId(),
                    draft.unitPrice(),
                    maxUnitPrice
                );
                GuiSound.SELECT.play(player);
            }
            case MarketGui.CONFIRM_SLOT -> submitListing(player, session, draft);
            default -> GuiSound.DENY.play(player);
        }
    }

    private void handlePurchaseConfirmClick(
        @NotNull InventoryClickEvent event,
        @NotNull Player player,
        @NotNull MarketSession session
    ) {
        if (event.getRawSlot() == MarketGui.BACK_SLOT) {
            openListings(player, false, session.page);
            return;
        }
        if (event.getRawSlot() != MarketGui.CONFIRM_SLOT || session.selectedListing == null) {
            GuiSound.DENY.play(player);
            return;
        }
        MarketListing listing = session.selectedListing;
        if (goldAmount(player) < listing.totalPrice()) {
            messageService.send(player, PlayerMsgId.P_6307);
            GuiSound.DENY.play(player);
            return;
        }
        purchaseListing(player, session, listing);
    }

    private void handleCancelConfirmClick(
        @NotNull InventoryClickEvent event,
        @NotNull Player player,
        @NotNull MarketSession session
    ) {
        if (event.getRawSlot() == MarketGui.BACK_SLOT) {
            openListings(player, true, session.page);
            return;
        }
        if (event.getRawSlot() != MarketGui.CONFIRM_SLOT || session.selectedListing == null) {
            GuiSound.DENY.play(player);
            return;
        }
        cancelListing(player, session, session.selectedListing);
    }

    private void handleGoldAmountClick(
        @NotNull InventoryClickEvent event,
        @NotNull Player player,
        @NotNull Inventory top
    ) {
        if (!AccountModeGuard.isGameplayPlayer(player)) {
            player.closeInventory();
            return;
        }
        GoldAmountSettingGui.GoldAmountHolder holder = goldAmountSettingGui.getHolder(top);
        MarketSession session = sessions.get(player.getUniqueId());
        if (holder == null
            || session == null
            || !holder.viewerUuid().equals(player.getUniqueId())
            || !GOLD_AMOUNT_SOURCE_KEY.equals(holder.sourceKey())
            || session.draft == null
            || !session.draft.contextId().equals(holder.contextId())) {
            GuiSound.DENY.play(player);
            return;
        }

        int rawSlot = event.getRawSlot();
        if (rawSlot == GoldAmountSettingGui.BACK_SLOT) {
            session.screen = MarketScreen.SELL_CONFIG;
            marketGui.openSellConfig(player, session.sessionId, session.draft);
            GuiSound.SELECT.play(player);
            return;
        }
        if (rawSlot == GoldAmountSettingGui.CONFIRM_SLOT) {
            if (holder.amount() < 1L) {
                GuiSound.DENY.play(player);
                return;
            }
            session.draft.setUnitPrice(holder.amount());
            session.screen = MarketScreen.SELL_CONFIG;
            marketGui.openSellConfig(player, session.sessionId, session.draft);
            GuiSound.SELECT.play(player);
            return;
        }
        if (rawSlot == GoldAmountSettingGui.STEP_DOWN_SLOT || rawSlot == GoldAmountSettingGui.STEP_UP_SLOT) {
            long before = holder.step();
            goldAmountSettingGui.shiftStep(holder, (rawSlot == GoldAmountSettingGui.STEP_DOWN_SLOT ? -1 : 1)
                * (event.isShiftClick() ? 3 : 1));
            goldAmountSettingGui.rerender(top, holder);
            playChanged(player, before != holder.step());
            return;
        }

        long before = holder.amount();
        long amount = switch (rawSlot) {
            case GoldAmountSettingGui.CLEAR_SLOT -> 0L;
            case GoldAmountSettingGui.MINUS_SLOT -> goldAmountSettingGui.applyStepDelta(holder, -1, goldMultiplier(event));
            case GoldAmountSettingGui.HALF_SLOT -> holder.amount() / 2L;
            case GoldAmountSettingGui.DOUBLE_SLOT -> goldAmountSettingGui.applyDelta(holder, holder.amount());
            case GoldAmountSettingGui.PLUS_SLOT -> goldAmountSettingGui.applyStepDelta(holder, 1, goldMultiplier(event));
            case GoldAmountSettingGui.MAX_SLOT -> holder.maxAmount();
            default -> before;
        };
        if (amount != before) {
            holder.setAmount(amount);
            goldAmountSettingGui.rerender(top, holder);
            GuiSound.SELECT.play(player);
            return;
        }
        GuiSound.DENY.play(player);
    }

    private void submitListing(
        @NotNull Player player,
        @NotNull MarketSession session,
        @NotNull MarketListingDraft draft
    ) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || session.busy) {
            GuiSound.DENY.play(player);
            return;
        }
        UUID accountId = astPlayer.getAccount().getUuid();
        session.busy = true;
        session.screen = MarketScreen.LOADING;
        marketGui.openLoading(player, session.sessionId);
        inventorySaveCoordinator.executeExclusiveAfterSave(accountId, baseline -> {
            MarketListing listing = marketService.createListing(new MarketListingCreateRequest(
                accountId,
                draft.sourceInventoryEntryId(),
                draft.itemCategory(),
                draft.itemId(),
                draft.instanceType(),
                draft.instanceId(),
                draft.quantity(),
                MARKET_CURRENCY_ID,
                draft.unitPrice(),
                null,
                accountId
            ));
            inventoryService.reconcileExternalInventoryEntries(accountId, List.of(draft.sourceInventoryEntryId()), baseline);
            return listing;
        }).whenComplete((listing, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!isCurrentSession(player, session)) {
                return;
            }
            session.busy = false;
            if (throwable != null) {
                sendMarketFailure(player, throwable);
                session.screen = MarketScreen.SELL_CONFIG;
                marketGui.openSellConfig(player, session.sessionId, draft);
                return;
            }
            session.draft = null;
            messageService.send(player, PlayerMsgId.P_6300);
            GuiSound.SUCCESS.play(player);
            openListings(player, true, 1);
        }));
    }

    private void purchaseListing(
        @NotNull Player player,
        @NotNull MarketSession session,
        @NotNull MarketListing listing
    ) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || session.busy) {
            GuiSound.DENY.play(player);
            return;
        }
        UUID accountId = astPlayer.getAccount().getUuid();
        session.busy = true;
        session.screen = MarketScreen.LOADING;
        marketGui.openLoading(player, session.sessionId);
        inventorySaveCoordinator.executeExclusiveAfterSave(accountId, baseline -> {
            MarketTransaction transaction = marketService.purchase(listing.listingId(), new MarketPurchaseRequest(
                accountId,
                UUID.randomUUID().toString(),
                accountId
            ));
            inventoryService.reconcileExternalInventoryEntries(
                accountId,
                transaction.affectedInventoryEntryIds(),
                baseline
            );
            return transaction;
        }).whenComplete((transaction, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!isCurrentSession(player, session)) {
                return;
            }
            session.busy = false;
            if (throwable != null) {
                sendMarketFailure(player, throwable);
                openListings(player, false, session.page);
                return;
            }
            applyOnlineSellerProceeds(transaction);
            messageService.send(player, PlayerMsgId.P_6302);
            GuiSound.SUCCESS.play(player);
            openListings(player, false, session.page);
        }));
    }

    private void cancelListing(
        @NotNull Player player,
        @NotNull MarketSession session,
        @NotNull MarketListing listing
    ) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || session.busy || listing.sourceInventoryEntryId() == null) {
            GuiSound.DENY.play(player);
            return;
        }
        UUID accountId = astPlayer.getAccount().getUuid();
        session.busy = true;
        session.screen = MarketScreen.LOADING;
        marketGui.openLoading(player, session.sessionId);
        inventorySaveCoordinator.executeExclusiveAfterSave(accountId, baseline -> {
            MarketListing canceled = marketService.cancel(listing.listingId(), new MarketCancelRequest(
                accountId,
                "player_cancel",
                accountId
            ));
            inventoryService.reconcileExternalInventoryEntries(
                accountId,
                List.of(listing.sourceInventoryEntryId()),
                baseline
            );
            return canceled;
        }).whenComplete((canceled, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!isCurrentSession(player, session)) {
                return;
            }
            session.busy = false;
            if (throwable != null) {
                sendMarketFailure(player, throwable);
                openListings(player, true, session.page);
                return;
            }
            messageService.send(player, PlayerMsgId.P_6301);
            GuiSound.SUCCESS.play(player);
            openListings(player, true, session.page);
        }));
    }

    private void openBrowse(@NotNull Player player, int page) {
        openListings(player, false, page);
    }

    private void openListings(@NotNull Player player, boolean ownListings, int page) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !AccountModeGuard.isGameplayPlayer(player)) {
            GuiSound.DENY.play(player);
            return;
        }
        MarketSession session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new MarketSession());
        session.screen = MarketScreen.LOADING;
        session.busy = true;
        session.ownListings = ownListings;
        session.page = Math.max(1, page);
        session.selectedListing = null;
        marketGui.openLoading(player, session.sessionId);

        UUID accountId = astPlayer.getAccount().getUuid();
        int requestedPage = session.page;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<MarketListing> listings;
            MarketAccountSummary summary;
            try {
                listings = marketService.findListings(new MarketListingQuery(
                    ownListings ? accountId : null,
                    null,
                    null,
                    ownListings ? "ALL" : "ACTIVE",
                    null,
                    null,
                    ownListings ? "listed_desc" : "price_asc",
                    requestedPage,
                    PAGE_SIZE
                ));
                summary = marketService.findAccountSummary(accountId).orElse(null);
            } catch (RuntimeException failure) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (isCurrentSession(player, session)) {
                        session.busy = false;
                        sendMarketFailure(player, failure);
                        player.closeInventory();
                    }
                });
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!isCurrentSession(player, session)) {
                    return;
                }
                session.busy = false;
                session.listings = List.copyOf(listings);
                session.summary = summary;
                session.screen = ownListings ? MarketScreen.MY_LISTINGS : MarketScreen.BROWSE;
                marketGui.openListings(
                    player,
                    session.sessionId,
                    session.screen,
                    session.listings,
                    summary,
                    requestedPage,
                    goldAmount(player)
                );
                GuiSound.OPEN.play(player);
            });
        });
    }

    private boolean isCurrentSession(
        @NotNull MarketGui.MarketHolder holder,
        @NotNull Player player,
        @Nullable MarketSession session
    ) {
        return session != null
            && holder.viewerUuid().equals(player.getUniqueId())
            && holder.sessionId().equals(session.sessionId)
            && holder.screen() == session.screen;
    }

    private boolean isCurrentSession(@NotNull Player player, @NotNull MarketSession session) {
        return player.isOnline() && sessions.get(player.getUniqueId()) == session;
    }

    private boolean isMarketGoldAmountInventory(@Nullable Inventory inventory) {
        GoldAmountSettingGui.GoldAmountHolder holder = goldAmountSettingGui.getHolder(inventory);
        return holder != null && GOLD_AMOUNT_SOURCE_KEY.equals(holder.sourceKey());
    }

    private boolean isMarketable(@Nullable InventoryEntryModel entry, @Nullable ItemModel item) {
        if (entry == null || item == null || entry.getItemId() == null || item.getUnTradeable()) {
            return false;
        }
        String category = entry.getItemCategory().trim().toUpperCase(Locale.ROOT);
        if (!category.equals("MATERIAL")
            && !category.equals("CONSUMABLE")
            && !category.equals("EQUIPMENT")
            && !category.equals("RUNE")) {
            return false;
        }
        if (entry.getInstanceId() == null) {
            return entry.getInstanceType() == null && entry.getQuantity() > 0L;
        }
        return entry.getQuantity() == 1L
            && entry.getInstanceType() != null
            && (entry.getInstanceType().equalsIgnoreCase("EQUIPMENT")
                || entry.getInstanceType().equalsIgnoreCase("RUNE"));
    }

    private boolean isCancelable(@NotNull MarketListing listing) {
        return listing.status().equalsIgnoreCase("ACTIVE") || listing.status().equalsIgnoreCase("SUSPENDED");
    }

    private void adjustDraftQuantity(@NotNull MarketListingDraft draft, long delta) {
        long next;
        try {
            next = Math.addExact(draft.quantity(), delta);
        } catch (ArithmeticException ignored) {
            next = delta < 0L ? 1L : draft.maxQuantity();
        }
        draft.setQuantity(next);
        draft.setUnitPrice(draft.unitPrice());
    }

    private int goldMultiplier(@NotNull InventoryClickEvent event) {
        if (event.isShiftClick()) {
            return 10;
        }
        return event.isRightClick() ? 5 : 1;
    }

    private void playChanged(@NotNull Player player, boolean changed) {
        if (changed) {
            GuiSound.SELECT.play(player);
        } else {
            GuiSound.DENY.play(player);
        }
    }

    private long goldAmount(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        return astPlayer == null ? 0L : currencyService.getGoldAmount(astPlayer.getAccount().getUuid());
    }

    private void applyOnlineSellerProceeds(@NotNull MarketTransaction transaction) {
        for (AstPlayer seller : AstPlayerCache.getAll()) {
            if (!seller.getAccount().getUuid().equals(transaction.sellerAccountId())) {
                continue;
            }
            if (inventoryService.addGold(seller, transaction.sellerProceeds())) {
                messageService.send(seller, PlayerMsgId.P_6303);
            }
            return;
        }
    }

    private void sendMarketFailure(@NotNull Player player, @NotNull Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message != null && message.contains("market.insufficient_gold")) {
            messageService.send(player, PlayerMsgId.P_6307);
        } else if (message != null && message.contains("listing_slot_limit")) {
            messageService.send(player, PlayerMsgId.P_6306);
        } else {
            messageService.send(player, PlayerMsgId.P_6305);
        }
        GuiSound.DENY.play(player);
    }

    private static final class MarketSession {
        private final UUID sessionId = UUID.randomUUID();
        private MarketScreen screen = MarketScreen.LOADING;
        private boolean ownListings;
        private boolean busy;
        private int page = 1;
        private List<MarketListing> listings = List.of();
        private @Nullable MarketAccountSummary summary;
        private @Nullable MarketListing selectedListing;
        private @Nullable MarketListingDraft draft;
    }
}
