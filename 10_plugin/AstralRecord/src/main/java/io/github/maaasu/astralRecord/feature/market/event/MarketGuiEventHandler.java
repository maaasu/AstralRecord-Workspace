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
import io.github.maaasu.astralRecord.feature.market.model.MarketListingSource;
import io.github.maaasu.astralRecord.feature.market.model.MarketProceedsClaim;
import io.github.maaasu.astralRecord.feature.market.model.MarketProceedsClaimRequest;
import io.github.maaasu.astralRecord.feature.market.model.MarketPurchaseRequest;
import io.github.maaasu.astralRecord.feature.market.model.MarketTransaction;
import io.github.maaasu.astralRecord.feature.market.repository.MarketTransportException;
import io.github.maaasu.astralRecord.feature.market.service.MarketService;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.gold.GoldAmountSettingGui;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutClickSupport;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** マーケット GUI の操作と API 確定処理を扱います。 */
public final class MarketGuiEventHandler extends AbstractEventHandler {
    public static final String GOLD_AMOUNT_SOURCE_KEY = "market-listing-price";
    private static final String MARKET_CURRENCY_ID = "gold";
    private static final int PAGE_SIZE = MarketGui.CONTENT_SLOT_COUNT;
    private static final int QUERY_PAGE_SIZE = PAGE_SIZE + 1;

    private final AstralRecord plugin;
    private final MarketGui marketGui;
    private final MarketService marketService;
    private final ItemService itemService;
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
        this(
            plugin,
            itemService,
            new MarketGui(itemService, itemStackFactory),
            marketService,
            inventoryService,
            inventorySaveCoordinator,
            currencyService,
            messageService,
            goldAmountSettingGui
        );
    }

    /** GUI を差し替えてイベント callback を検証するための package-private 構築子です。 */
    MarketGuiEventHandler(
        @NotNull AstralRecord plugin,
        @NotNull ItemService itemService,
        @NotNull MarketGui marketGui,
        @NotNull MarketService marketService,
        @NotNull InventoryService inventoryService,
        @NotNull InventorySaveCoordinator inventorySaveCoordinator,
        @NotNull CurrencyService currencyService,
        @NotNull PlayerMessageService messageService,
        @NotNull GoldAmountSettingGui goldAmountSettingGui
    ) {
        this.plugin = plugin;
        this.marketGui = marketGui;
        this.marketService = marketService;
        this.itemService = itemService;
        this.inventoryService = inventoryService;
        this.inventorySaveCoordinator = inventorySaveCoordinator;
        this.currencyService = currencyService;
        this.messageService = messageService;
        this.goldAmountSettingGui = goldAmountSettingGui;
    }

    /** 管理コマンドからマーケットを開きます。 */
    public void openFromCommand(@NotNull Player player) {
        openBrowse(player, 1, true);
    }

    /** マーケット NPC からマーケットを開きます。 */
    public void openFromNpc(@NotNull Player player) {
        openBrowse(player, 1, true);
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
        if (session.ownListings && event.getClickedInventory() instanceof PlayerInventory) {
            // 下部所持品は出品枠ではないため取り下げ対象にしない。共通ホットバー操作を先に処理し、
            // それ以外は出品候補選択と同じ処理へ委譲する。
            handleSellSelectClick(event, player, session);
            return;
        }
        if (handleHotbarShortcutClick(event, player)) {
            return;
        }
        int rawSlot = event.getRawSlot();
        if (rawSlot == MarketGui.HEADER_ACTION_SLOT) {
            if (session.ownListings) {
                session.screen = MarketScreen.SELL_SELECT;
                session.draft = null;
                marketGui.openSellSelect(player, session.sessionId, session.summary, goldAmount(player));
            } else {
                openListings(player, false, session.page);
            }
            GuiSound.SELECT.play(player);
            return;
        }
        if (rawSlot >= MarketGui.CONTENT_START_SLOT
            && rawSlot < MarketGui.CONTENT_START_SLOT + PAGE_SIZE) {
            int listingIndex = rawSlot - MarketGui.CONTENT_START_SLOT;
            if (listingIndex >= session.listings.size()) {
                GuiSound.DENY.play(player);
                return;
            }
            MarketListing listing = session.listings.get(listingIndex);
            if (session.ownListings) {
                openOwnListingAction(player, session, listing);
                return;
            }
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer != null && listing.sellerAccountId().equals(astPlayer.getAccount().getUuid())) {
                messageService.send(player, PlayerMsgId.P_6309);
                GuiSound.DENY.play(player);
                return;
            }
            session.selectedListing = listing;
            session.purchaseQuantity = 1L;
            session.screen = MarketScreen.PURCHASE_CONFIRM;
            marketGui.openPurchaseConfirm(player, session.sessionId, listing, session.purchaseQuantity, goldAmount(player));
            GuiSound.SELECT.play(player);
            return;
        }

        switch (rawSlot) {
            case MarketGui.PREVIOUS_SLOT -> {
                if (session.page <= 1) {
                    GuiSound.DENY.play(player);
                    return;
                }
                GuiSound.PAGE.play(player);
                openListings(player, session.ownListings, session.page - 1);
            }
            case MarketGui.NEXT_SLOT -> {
                if (!session.hasNextPage) {
                    GuiSound.DENY.play(player);
                    return;
                }
                GuiSound.PAGE.play(player);
                openListings(player, session.ownListings, session.page + 1);
            }
            case MarketGui.BROWSE_SLOT -> {
                GuiSound.SELECT.play(player);
                openListings(player, false, 1);
            }
            case MarketGui.MY_LISTINGS_SLOT -> {
                GuiSound.SELECT.play(player);
                openListings(player, true, 1);
            }
            default -> GuiSound.DENY.play(player);
        }
    }

    private void openOwnListingAction(
        @NotNull Player player,
        @NotNull MarketSession session,
        @NotNull MarketListing listing
    ) {
        if (isClaimable(listing)) {
            claimProceeds(player, session, listing);
            return;
        }
        if (!isCancelable(listing)) {
            GuiSound.DENY.play(player);
            return;
        }
        session.selectedListing = listing;
        session.screen = MarketScreen.CANCEL_CONFIRM;
        marketGui.openCancelConfirm(player, session.sessionId, listing);
        GuiSound.SELECT.play(player);
    }

    private void handleSellSelectClick(
        @NotNull InventoryClickEvent event,
        @NotNull Player player,
        @NotNull MarketSession session
    ) {
        if (event.getRawSlot() == MarketGui.SELL_SELECT_BACK_SLOT) {
            openListings(player, true, session.page);
            GuiSound.SELECT.play(player);
            return;
        }
        if (HotbarShortcutClickSupport.handleInventoryControlClick(event, player, inventoryService)) {
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
        if (entry == null || item == null) {
            GuiSound.DENY.play(player);
            return;
        }
        if (!MarketListingEligibility.isEligible(entry, item)) {
            messageService.send(player, PlayerMsgId.P_6304);
            GuiSound.DENY.play(player);
            return;
        }

        boolean instanceListing = entry.getInstanceId() != null;
        List<MarketListingSource> sourceEntries = new ArrayList<>();
        long maxQuantity;
        if (instanceListing) {
            sourceEntries.add(new MarketListingSource(entry.getInventoryEntryId(), 1L));
            maxQuantity = 1L;
        } else {
            List<InventoryEntryModel> matchingEntries = inventoryService.getOwnedStackEntries(
                astPlayer,
                entry.getItemCategory(),
                entry.getItemId()
            );
            if (matchingEntries.stream().noneMatch(sourceEntry ->
                sourceEntry.getInventoryEntryId().equals(entry.getInventoryEntryId()))) {
                GuiSound.DENY.play(player);
                return;
            }
            long total = 0L;
            for (InventoryEntryModel sourceEntry : matchingEntries) {
                try {
                    total = Math.addExact(total, sourceEntry.getQuantity());
                } catch (ArithmeticException overflow) {
                    messageService.send(player, PlayerMsgId.P_6304);
                    GuiSound.DENY.play(player);
                    return;
                }
                sourceEntries.add(new MarketListingSource(
                    sourceEntry.getInventoryEntryId(),
                    sourceEntry.getQuantity()
                ));
            }
            maxQuantity = total;
        }
        long minimumUnitPrice = minimumListingUnitPrice(item);
        if (maxQuantity < 1L || sourceEntries.isEmpty() || minimumUnitPrice < 1L) {
            GuiSound.DENY.play(player);
            return;
        }
        session.draft = new MarketListingDraft(
            UUID.randomUUID(),
            sourceEntries,
            entry.getItemCategory(),
            entry.getItemId(),
            entry.getInstanceType(),
            entry.getInstanceId(),
            maxQuantity,
            minimumUnitPrice
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
        if (handleHotbarShortcutClick(event, player)) {
            return;
        }
        MarketListingDraft draft = session.draft;
        if (draft == null) {
            openListings(player, true, 1);
            return;
        }
        switch (event.getRawSlot()) {
            case MarketGui.BACK_SLOT -> {
                session.screen = MarketScreen.SELL_SELECT;
                marketGui.openSellSelect(player, session.sessionId, session.summary, goldAmount(player));
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
        if (handleHotbarShortcutClick(event, player)) {
            return;
        }
        if (event.getRawSlot() == MarketGui.BACK_SLOT) {
            openListings(player, false, session.page);
            return;
        }
        if (session.selectedListing == null) {
            GuiSound.DENY.play(player);
            return;
        }
        MarketListing listing = session.selectedListing;
        if (event.getRawSlot() == MarketGui.QUANTITY_DOWN_SLOT) {
            adjustPurchaseQuantity(session, listing, -(event.isShiftClick() ? 16L : 1L));
            marketGui.openPurchaseConfirm(player, session.sessionId, listing, session.purchaseQuantity, goldAmount(player));
            GuiSound.SELECT.play(player);
            return;
        }
        if (event.getRawSlot() == MarketGui.QUANTITY_UP_SLOT) {
            adjustPurchaseQuantity(session, listing, event.isShiftClick() ? 16L : 1L);
            marketGui.openPurchaseConfirm(player, session.sessionId, listing, session.purchaseQuantity, goldAmount(player));
            GuiSound.SELECT.play(player);
            return;
        }
        if (event.getRawSlot() != MarketGui.CONFIRM_SLOT) {
            GuiSound.DENY.play(player);
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }
        if (listing.sellerAccountId().equals(astPlayer.getAccount().getUuid())) {
            messageService.send(player, PlayerMsgId.P_6309);
            GuiSound.DENY.play(player);
            return;
        }
        long totalPrice = selectedPurchaseTotal(listing, session.purchaseQuantity);
        if (goldAmount(player) < totalPrice) {
            messageService.send(player, PlayerMsgId.P_6307);
            GuiSound.DENY.play(player);
            return;
        }
        purchaseListing(player, session, listing, session.purchaseQuantity);
    }

    private void handleCancelConfirmClick(
        @NotNull InventoryClickEvent event,
        @NotNull Player player,
        @NotNull MarketSession session
    ) {
        if (handleHotbarShortcutClick(event, player)) {
            return;
        }
        if (event.getRawSlot() == MarketGui.BACK_SLOT) {
            openListings(player, true, session.page);
            return;
        }
        if (event.getRawSlot() != MarketGui.CONFIRM_SLOT || session.selectedListing == null) {
            GuiSound.DENY.play(player);
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !canReturnListingToInventory(astPlayer, session.selectedListing)) {
            messageService.send(player, PlayerMsgId.P_6308);
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
            List<MarketListingSource> selectedSources = resolveListingSources(astPlayer, draft);
            MarketListing listing = marketService.createListing(new MarketListingCreateRequest(
                accountId,
                selectedSources,
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
            inventoryService.reconcileExternalInventoryEntries(
                accountId,
                selectedSources.stream().map(MarketListingSource::inventoryEntryId).toList(),
                baseline
            );
            return listing;
        }).whenComplete((listing, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
            refreshInventoryUiAfterMarketMutation(player, throwable);
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

    /**
     * 出品確定時点の正本 state から、通常アイテム共通消費順の source を再解決します。
     * 個体品だけは選択した instance の entry ID を保持し、スタック品はクリック元を消費元に固定しません。
     *
     * @param astPlayer 出品操作中のプレイヤー
     * @param draft 出品設定
     * @return API へ送信する source entry 一覧
     */
    private @NotNull List<MarketListingSource> resolveListingSources(
        @NotNull AstPlayer astPlayer,
        @NotNull MarketListingDraft draft
    ) {
        if (draft.instanceId() != null) {
            return draft.selectedSources();
        }
        List<MarketListingSource> currentSources = inventoryService.getOwnedStackEntries(
            astPlayer,
            draft.itemCategory(),
            draft.itemId()
        ).stream()
            .map(entry -> new MarketListingSource(entry.getInventoryEntryId(), entry.getQuantity()))
            .toList();
        return draft.selectedSources(currentSources);
    }

    private void purchaseListing(
        @NotNull Player player,
        @NotNull MarketSession session,
        @NotNull MarketListing listing,
        long purchaseQuantity
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
            PlayerMsgId preflightRejection = purchasePreflightRejection(astPlayer, listing, purchaseQuantity);
            if (preflightRejection != null) {
                return PurchaseListingResult.rejected(preflightRejection);
            }
            MarketPurchaseRequest request = new MarketPurchaseRequest(
                accountId,
                purchaseQuantity,
                UUID.randomUUID().toString(),
                accountId
            );
            MarketTransaction transaction = purchaseWithReplay(listing.listingId(), request);
            inventoryService.reconcileExternalInventoryEntriesToOwnedInventory(
                astPlayer,
                transaction.affectedInventoryEntryIds(),
                baseline
            );
            return PurchaseListingResult.completed(transaction);
        }).whenComplete((result, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
            refreshInventoryUiAfterMarketMutation(player, throwable);
            if (!isCurrentSession(player, session)) {
                return;
            }
            session.busy = false;
            if (throwable != null) {
                sendMarketFailure(player, throwable);
                openListings(player, false, session.page);
                return;
            }
            if (result == null) {
                sendMarketFailure(player, new IllegalStateException("Market purchase result was empty"));
                openListings(player, false, session.page);
                return;
            }
            if (result.rejectionMessage() != null) {
                messageService.send(player, result.rejectionMessage());
                GuiSound.DENY.play(player);
                openListings(player, false, session.page);
                return;
            }
            MarketTransaction transaction = result.transaction();
            if (transaction == null) {
                sendMarketFailure(player, new IllegalStateException("Market purchase result was empty"));
                openListings(player, false, session.page);
                return;
            }
            messageService.send(player, PlayerMsgId.P_6302, transaction.totalPrice());
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
        if (astPlayer == null || session.busy) {
            GuiSound.DENY.play(player);
            return;
        }
        UUID accountId = astPlayer.getAccount().getUuid();
        session.busy = true;
        session.screen = MarketScreen.LOADING;
        marketGui.openLoading(player, session.sessionId);
        inventorySaveCoordinator.executeExclusiveAfterSave(accountId, baseline -> {
            if (!canReturnListingToInventory(astPlayer, listing)) {
                return CancelListingResult.capacityFailure();
            }
            MarketListing canceled = marketService.cancel(listing.listingId(), new MarketCancelRequest(
                accountId,
                "player_cancel",
                accountId
            ));
            inventoryService.reconcileExternalInventoryEntriesToOwnedInventory(
                astPlayer,
                canceled.sourceInventoryEntryIds().isEmpty()
                    ? legacySourceEntryIds(listing)
                    : canceled.sourceInventoryEntryIds(),
                baseline
            );
            return CancelListingResult.completed();
        }).whenComplete((result, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
            refreshInventoryUiAfterMarketMutation(player, throwable);
            if (!isCurrentSession(player, session)) {
                return;
            }
            session.busy = false;
            if (throwable != null) {
                sendMarketFailure(player, throwable);
                openListings(player, true, session.page);
                return;
            }
            if (result.inventoryCapacityInsufficient()) {
                messageService.send(player, PlayerMsgId.P_6308);
                GuiSound.DENY.play(player);
                openListings(player, true, session.page);
                return;
            }
            messageService.send(player, PlayerMsgId.P_6301);
            GuiSound.SUCCESS.play(player);
            openListings(player, true, session.page);
        }));
    }

    /** 売却済み出品をクリックして、売上を受け取り出品枠を解放します。 */
    private void claimProceeds(
        @NotNull Player player,
        @NotNull MarketSession session,
        @NotNull MarketListing listing
    ) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || session.busy || !isClaimable(listing)) {
            GuiSound.DENY.play(player);
            return;
        }
        UUID accountId = astPlayer.getAccount().getUuid();
        MarketProceedsClaimRequest request = new MarketProceedsClaimRequest(
            accountId,
            proceedsClaimIdempotencyKey(listing.listingId()),
            accountId
        );
        session.busy = true;
        session.screen = MarketScreen.LOADING;
        marketGui.openLoading(player, session.sessionId);
        inventorySaveCoordinator.executeExclusiveAfterSave(accountId, baseline -> {
            MarketProceedsClaim claim = claimProceedsWithReplay(listing.listingId(), request);
            inventoryService.reconcileExternalInventoryEntries(
                accountId,
                claim.affectedInventoryEntryIds(),
                baseline
            );
            return claim;
        }).whenComplete((claim, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
            refreshInventoryUiAfterMarketMutation(player, throwable);
            if (!isCurrentSession(player, session)) {
                return;
            }
            session.busy = false;
            if (throwable != null) {
                sendMarketFailure(player, throwable);
                openListings(player, true, session.page);
                return;
            }
            messageService.send(player, PlayerMsgId.P_6303);
            GuiSound.SUCCESS.play(player);
            openListings(player, true, session.page);
        }));
    }

    private void openBrowse(@NotNull Player player, int page, boolean playOpenSound) {
        openListings(player, false, page, playOpenSound);
    }

    private void openListings(@NotNull Player player, boolean ownListings, int page) {
        openListings(player, ownListings, page, false);
    }

    private void openListings(
        @NotNull Player player,
        boolean ownListings,
        int page,
        boolean playOpenSound
    ) {
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
        session.purchaseQuantity = 1L;
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
                    QUERY_PAGE_SIZE
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
            boolean hasNextPage = listings.size() > PAGE_SIZE;
            List<MarketListing> pageListings = hasNextPage
                ? List.copyOf(listings.subList(0, PAGE_SIZE))
                : List.copyOf(listings);
            if (requestedPage > 1 && pageListings.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (isCurrentSession(player, session)) {
                        openListings(player, ownListings, requestedPage - 1, false);
                    }
                });
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!isCurrentSession(player, session)) {
                    return;
                }
                session.busy = false;
                session.listings = pageListings;
                session.hasNextPage = hasNextPage;
                session.summary = summary;
                session.screen = ownListings ? MarketScreen.MY_LISTINGS : MarketScreen.BROWSE;
                marketGui.openListings(
                    player,
                    session.sessionId,
                    session.screen,
                    pageListings,
                    summary,
                    requestedPage,
                    goldAmount(player),
                    hasNextPage
                );
                if (playOpenSound) {
                    GuiSound.OPEN.play(player);
                }
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

    /**
     * 共通ホットバー操作をマーケットの一覧・確認画面へ委譲します。
     * 出品対象選択画面だけは、バッグ／ホットバーのアイテムを選択するため個別処理します。
     *
     * @param event クリックイベント
     * @param player 操作プレイヤー
     * @return 共通操作を処理した場合は {@code true}
     */
    private boolean handleHotbarShortcutClick(
        @NotNull InventoryClickEvent event,
        @NotNull Player player
    ) {
        return HotbarShortcutClickSupport.handle(event, player, inventoryService);
    }

    /**
     * API 正本の再同期に成功したマーケット確定操作後、メインスレッドでプレイヤー所持品の表示を更新します。
     *
     * @param player 操作プレイヤー
     * @param throwable 非 null の場合は同期まで完了していないため表示を変更しない
     */
    private void refreshInventoryUiAfterMarketMutation(
        @NotNull Player player,
        @Nullable Throwable throwable
    ) {
        if (throwable != null || !player.isOnline()) {
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer != null) {
            inventoryService.refreshManagedInventoryUi(astPlayer);
        }
    }

    private boolean isCancelable(@NotNull MarketListing listing) {
        return listing.status().equalsIgnoreCase("ACTIVE") || listing.status().equalsIgnoreCase("SUSPENDED");
    }

    private boolean isClaimable(@NotNull MarketListing listing) {
        return listing.status().equalsIgnoreCase("SOLD") && listing.pendingProceeds() > 0L;
    }

    private @NotNull List<UUID> legacySourceEntryIds(@NotNull MarketListing listing) {
        return listing.sourceInventoryEntryId() == null ? List.of() : List.of(listing.sourceInventoryEntryId());
    }

    private boolean canReturnListingToInventory(
        @NotNull AstPlayer astPlayer,
        @NotNull MarketListing listing
    ) {
        if (listing.remainingQuantity() < 1L || listing.remainingQuantity() > Integer.MAX_VALUE) {
            return false;
        }
        ItemModel model = itemService.findLoadedById(listing.itemId());
        return model != null && inventoryService.canAddItemToNormalInventory(
            astPlayer,
            model,
            (int) listing.remainingQuantity()
        );
    }

    /** 購入 API の直前に数量・item 解決・所持容量を同一保存 lane で検証します。 */
    private @Nullable PlayerMsgId purchasePreflightRejection(
        @NotNull AstPlayer astPlayer,
        @NotNull MarketListing listing,
        long purchaseQuantity
    ) {
        if (purchaseQuantity < 1L
            || listing.remainingQuantity() < 1L
            || purchaseQuantity > listing.remainingQuantity()
            || purchaseQuantity > Integer.MAX_VALUE) {
            return PlayerMsgId.P_6305;
        }
        ItemModel model = itemService.findLoadedById(listing.itemId());
        if (model == null) {
            return PlayerMsgId.P_6305;
        }
        return inventoryService.canAddItemToNormalInventory(astPlayer, model, (int) purchaseQuantity)
            ? null
            : PlayerMsgId.P_5241;
    }

    private long minimumListingUnitPrice(@NotNull ItemModel item) {
        if (item.getSaleValue() == Long.MAX_VALUE) {
            return 0L;
        }
        return Math.max(1L, item.getSaleValue() + 1L);
    }

    /**
     * 応答喪失の可能性がある売上受取を、同じ冪等キーで一度だけ再送します。
     * <p>
     * API は受取済み出品の receipt を同じキーで再生するため、最初の要求が確定済みでも
     * Gold を二重加算せず、通貨 entry の正本再同期を完了できます。
     *
     * @param listingId 売上受取対象の出品 ID
     * @param request 再送時にも同じキーを使うリクエスト
     * @return API が確定した売上受取結果
     */
    private @NotNull MarketProceedsClaim claimProceedsWithReplay(
        @NotNull UUID listingId,
        @NotNull MarketProceedsClaimRequest request
    ) {
        try {
            return marketService.claimProceeds(listingId, request);
        } catch (MarketTransportException firstFailure) {
            try {
                return marketService.claimProceeds(listingId, request);
            } catch (RuntimeException retryFailure) {
                retryFailure.addSuppressed(firstFailure);
                throw retryFailure;
            }
        }
    }

    /**
     * 応答喪失の可能性がある購入確定を、同じ冪等キーで一度だけ再送します。
     * <p>
     * API は確定済み取引の receipt を同じキーで再生するため、二重購入せず
     * Gold と購入品の再同期対象を取得できます。
     *
     * @param listingId 購入対象の出品 ID
     * @param request 再送時にも同じキーを使う購入リクエスト
     * @return API が確定した購入結果
     */
    private @NotNull MarketTransaction purchaseWithReplay(
        @NotNull UUID listingId,
        @NotNull MarketPurchaseRequest request
    ) {
        try {
            return marketService.purchase(listingId, request);
        } catch (MarketTransportException firstFailure) {
            try {
                return marketService.purchase(listingId, request);
            } catch (RuntimeException retryFailure) {
                retryFailure.addSuppressed(firstFailure);
                throw retryFailure;
            }
        }
    }

    private @NotNull String proceedsClaimIdempotencyKey(@NotNull UUID listingId) {
        return "market-proceeds-claim-" + listingId;
    }

    private void adjustPurchaseQuantity(
        @NotNull MarketSession session,
        @NotNull MarketListing listing,
        long delta
    ) {
        long current = Math.max(1L, Math.min(session.purchaseQuantity, listing.remainingQuantity()));
        long next;
        try {
            next = Math.addExact(current, delta);
        } catch (ArithmeticException overflow) {
            next = delta < 0L ? 1L : listing.remainingQuantity();
        }
        session.purchaseQuantity = Math.max(1L, Math.min(next, listing.remainingQuantity()));
    }

    private long selectedPurchaseTotal(@NotNull MarketListing listing, long quantity) {
        long safeQuantity = Math.max(1L, Math.min(quantity, listing.remainingQuantity()));
        if (listing.unitPrice() < 1L || listing.unitPrice() > Long.MAX_VALUE / safeQuantity) {
            return Long.MAX_VALUE;
        }
        return listing.unitPrice() * safeQuantity;
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

    private void sendMarketFailure(@NotNull Player player, @NotNull Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message != null && message.contains("market.insufficient_gold")) {
            messageService.send(player, PlayerMsgId.P_6307);
        } else if (message != null && message.contains("market.self_purchase")) {
            messageService.send(player, PlayerMsgId.P_6309);
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
        private boolean hasNextPage;
        private List<MarketListing> listings = List.of();
        private @Nullable MarketAccountSummary summary;
        private @Nullable MarketListing selectedListing;
        private @Nullable MarketListingDraft draft;
        private long purchaseQuantity = 1L;
    }

    /** 取消の保存 lane で確認した容量不足を、外部 API 未呼出の正常完了として返します。 */
    private record CancelListingResult(boolean inventoryCapacityInsufficient) {
        private static @NotNull CancelListingResult capacityFailure() {
            return new CancelListingResult(true);
        }

        private static @NotNull CancelListingResult completed() {
            return new CancelListingResult(false);
        }
    }

    /** 購入 API 前の拒否結果、または API 確定済み transaction を保持します。 */
    private record PurchaseListingResult(
        @Nullable MarketTransaction transaction,
        @Nullable PlayerMsgId rejectionMessage
    ) {
        private static @NotNull PurchaseListingResult completed(@NotNull MarketTransaction transaction) {
            return new PurchaseListingResult(transaction, null);
        }

        private static @NotNull PurchaseListingResult rejected(@NotNull PlayerMsgId rejectionMessage) {
            return new PurchaseListingResult(null, rejectionMessage);
        }
    }
}
