package io.github.maaasu.astralRecord.feature.trade.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.account.service.AccountDisplayNameFormatter;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemReferenceResolver;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemTransferSupport;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.feature.trade.gui.TradeCancelConfirmGui;
import io.github.maaasu.astralRecord.feature.trade.gui.TradeGui;
import io.github.maaasu.astralRecord.feature.trade.gui.TradeGuiLayout;
import io.github.maaasu.astralRecord.feature.trade.model.TradeCommitItem;
import io.github.maaasu.astralRecord.feature.trade.model.TradeRequest;
import io.github.maaasu.astralRecord.feature.trade.model.TradeRequestStatus;
import io.github.maaasu.astralRecord.feature.trade.model.TradeCommitRequest;
import io.github.maaasu.astralRecord.feature.trade.model.TradeCommitResult;
import io.github.maaasu.astralRecord.feature.trade.model.TradeSession;
import io.github.maaasu.astralRecord.feature.trade.model.TradeSessionStatus;
import io.github.maaasu.astralRecord.feature.trade.repository.TradeRepository;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.gui.gold.GoldAmountSettingGui;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

public final class TradeService {
    private static final Duration REQUEST_TTL = Duration.ofSeconds(60);
    public static final String GOLD_AMOUNT_SOURCE_KEY = "trade";

    private final AstralRecord plugin;
    private final TradeGui tradeGui;
    private final TradeCancelConfirmGui cancelConfirmGui;
    private final GoldAmountSettingGui goldAmountSettingGui;
    private final InventoryService inventoryService;
    private final CurrencyService currencyService;
    private final PlayerMessageService messageService;
    private final ItemReferenceResolver itemReferenceResolver;
    private final @Nullable InventorySaveCoordinator inventorySaveCoordinator;
    private final @Nullable TradeRepository tradeRepository;
    private final Map<UUID, TradeRequest> requests = new HashMap<>();
    private final Map<UUID, TradeSession> sessions = new HashMap<>();
    private final Map<UUID, UUID> activeSessionByPlayer = new HashMap<>();
    private final Map<UUID, TradeCommitRecovery> tradeCommitRecoveries = new ConcurrentHashMap<>();
    private final Set<UUID> suppressedClosePlayers = new HashSet<>();

    public TradeService(
        @NotNull AstralRecord plugin,
        @NotNull TradeGui tradeGui,
        @NotNull TradeCancelConfirmGui cancelConfirmGui,
        @NotNull GoldAmountSettingGui goldAmountSettingGui,
        @NotNull InventoryService inventoryService,
        @NotNull CurrencyService currencyService,
        @NotNull PlayerMessageService messageService,
        @NotNull ItemService itemService,
        @NotNull InventorySaveCoordinator inventorySaveCoordinator
    ) {
        this(
            plugin,
            tradeGui,
            cancelConfirmGui,
            goldAmountSettingGui,
            inventoryService,
            currencyService,
            messageService,
            new ItemReferenceResolver(itemService),
            inventorySaveCoordinator,
            new TradeRepository()
        );
    }

    TradeService(
        @NotNull AstralRecord plugin,
        @NotNull TradeGui tradeGui,
        @NotNull TradeCancelConfirmGui cancelConfirmGui,
        @NotNull GoldAmountSettingGui goldAmountSettingGui,
        @NotNull InventoryService inventoryService,
        @NotNull CurrencyService currencyService,
        @NotNull PlayerMessageService messageService,
        @NotNull ItemReferenceResolver itemReferenceResolver
    ) {
        this(
            plugin,
            tradeGui,
            cancelConfirmGui,
            goldAmountSettingGui,
            inventoryService,
            currencyService,
            messageService,
            itemReferenceResolver,
            null,
            null
        );
    }

    TradeService(
        @NotNull AstralRecord plugin,
        @NotNull TradeGui tradeGui,
        @NotNull TradeCancelConfirmGui cancelConfirmGui,
        @NotNull GoldAmountSettingGui goldAmountSettingGui,
        @NotNull InventoryService inventoryService,
        @NotNull CurrencyService currencyService,
        @NotNull PlayerMessageService messageService,
        @NotNull ItemReferenceResolver itemReferenceResolver,
        @Nullable InventorySaveCoordinator inventorySaveCoordinator,
        @Nullable TradeRepository tradeRepository
    ) {
        this.plugin = plugin;
        this.tradeGui = tradeGui;
        this.cancelConfirmGui = cancelConfirmGui;
        this.goldAmountSettingGui = goldAmountSettingGui;
        this.inventoryService = inventoryService;
        this.currencyService = currencyService;
        this.messageService = messageService;
        this.itemReferenceResolver = itemReferenceResolver;
        this.inventorySaveCoordinator = inventorySaveCoordinator;
        this.tradeRepository = tradeRepository;
    }

    /**
     * トレード申請を作成し、相手プレイヤーへクリック可能な承諾メッセージを送信する。
     *
     * @param sender 申請者
     * @param target 相手プレイヤー
     */
    public void requestTrade(@NotNull Player sender, @NotNull Player target) {
        expireRequests();
        if (!AccountModeGuard.isGameplayPlayer(sender) || !AccountModeGuard.isGameplayPlayer(target)) {
            messageService.send(sender, PlayerMsgId.P_5065);
            return;
        }
        if (!isTradeAllowedWorld(sender) || !isTradeAllowedWorld(target)) {
            messageService.send(sender, PlayerMsgId.P_6210);
            return;
        }
        if (sender.getUniqueId().equals(target.getUniqueId()) || !target.isOnline()
            || isTrading(sender.getUniqueId()) || isTrading(target.getUniqueId())) {
            messageService.send(sender, PlayerMsgId.P_6203);
            return;
        }
        TradeRequest existing = findPendingRequest(sender.getUniqueId(), target.getUniqueId());
        if (existing != null) {
            messageService.send(sender, PlayerMsgId.P_6203);
            return;
        }

        Instant now = Instant.now();
        TradeRequest request = new TradeRequest(
            UUID.randomUUID(),
            sender.getUniqueId(),
            sender.getName(),
            target.getUniqueId(),
            target.getName(),
            now,
            now.plus(REQUEST_TTL)
        );
        requests.put(request.getRequestId(), request);
        messageService.send(sender, PlayerMsgId.P_6200, target.getName());
        messageService.sendComponent(
            target,
            messageService.decorateAccountPlayerArguments(
                PlayerMsgResource.formatComponent(PlayerMsgId.P_6201.getId(), sender.getName()),
                sender.getName()
            )
                .clickEvent(ClickEvent.runCommand("/trade accept"))
                .hoverEvent(HoverEvent.showText(net.kyori.adventure.text.Component.text("/trade accept")))
        );
    }

    /**
     * 最新の有効な受信トレード申請を承諾し、双方の取引 GUI を開く。
     *
     * @param accepter 承諾者
     */
    public void acceptTrade(@NotNull Player accepter) {
        expireRequests();
        if (!AccountModeGuard.isGameplayPlayer(accepter)) {
            messageService.send(accepter, PlayerMsgId.P_5065);
            return;
        }
        if (!isTradeAllowedWorld(accepter)) {
            messageService.send(accepter, PlayerMsgId.P_6210);
            return;
        }
        TradeRequest request = findLatestIncoming(accepter.getUniqueId());
        if (request == null) {
            messageService.send(accepter, PlayerMsgId.P_6202);
            return;
        }
        Player sender = Bukkit.getPlayer(request.getSenderUuid());
        if (sender != null && !AccountModeGuard.isGameplayPlayer(sender)) {
            finishRequest(request, TradeRequestStatus.CANCELLED);
            messageService.send(accepter, PlayerMsgId.P_5065);
            return;
        }
        if (sender == null || !sender.isOnline() || isTrading(sender.getUniqueId()) || isTrading(accepter.getUniqueId())) {
            finishRequest(request, TradeRequestStatus.CANCELLED);
            messageService.send(accepter, PlayerMsgId.P_6203);
            return;
        }
        if (!isTradeAllowedWorld(sender)) {
            finishRequest(request, TradeRequestStatus.CANCELLED);
            messageService.send(accepter, PlayerMsgId.P_6210);
            return;
        }
        AstPlayer senderAstPlayer = AstPlayerCache.get(sender);
        AstPlayer accepterAstPlayer = AstPlayerCache.get(accepter);
        if (senderAstPlayer == null || accepterAstPlayer == null) {
            finishRequest(request, TradeRequestStatus.CANCELLED);
            messageService.send(accepter, PlayerMsgId.P_6203);
            return;
        }
        finishRequest(request, TradeRequestStatus.ACCEPTED);
        TradeSession session = new TradeSession(
            UUID.randomUUID(),
            sender.getUniqueId(),
            senderAstPlayer.getAccount().getUuid(),
            AccountDisplayNameFormatter.toPlain(senderAstPlayer.getAccount()),
            accepter.getUniqueId(),
            accepterAstPlayer.getAccount().getUuid(),
            AccountDisplayNameFormatter.toPlain(accepterAstPlayer.getAccount()),
            Instant.now()
        );
        sessions.put(session.getSessionId(), session);
        activeSessionByPlayer.put(sender.getUniqueId(), session.getSessionId());
        activeSessionByPlayer.put(accepter.getUniqueId(), session.getSessionId());
        openTrade(sender, session);
        openTrade(accepter, session);
    }

    /**
     * 指定プレイヤーの取引準備状態を切り替え、条件が揃えば取引成立を実行する。
     *
     * @param player 操作したプレイヤー
     */
    public void toggleReady(@NotNull Player player) {
        if (!AccountModeGuard.isGameplayPlayer(player)) {
            messageService.send(player, PlayerMsgId.P_5065);
            return;
        }
        if (!isTradeAllowedWorld(player)) {
            messageService.send(player, PlayerMsgId.P_6210);
            return;
        }
        TradeSession session = getOpenSession(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (session.isReady(player.getUniqueId())) {
            session.setReady(player.getUniqueId(), false);
            messageService.send(player, PlayerMsgId.P_6206);
            refreshBoth(session);
            return;
        }
        if (!session.isPartnerReady(player.getUniqueId())) {
            session.setReady(player.getUniqueId(), true);
            messageService.send(player, PlayerMsgId.P_6205);
            refreshBoth(session);
            return;
        }
        session.setReady(player.getUniqueId(), true);
        completeTrade(session);
    }

    /**
     * 対象プレイヤーの取引中止確認 GUI を開く。
     *
     * @param player 表示対象プレイヤー
     */
    public void openCancelConfirm(@NotNull Player player) {
        openCancelConfirm(player, true);
    }

    /**
     * トレード GUI が手動で閉じられたあとに中止確認 GUI を開きます。
     *
     * @param player 表示対象プレイヤー
     */
    public void openCancelConfirmAfterClose(@NotNull Player player) {
        openCancelConfirm(player, false);
    }

    /**
     * Gold 金額設定 GUI を開きます。
     *
     * @param player 表示対象プレイヤー
     */
    public void openGoldAmountSetting(@NotNull Player player) {
        if (!AccountModeGuard.isGameplayPlayer(player)) {
            messageService.send(player, PlayerMsgId.P_5065);
            return;
        }
        if (!isTradeAllowedWorld(player)) {
            messageService.send(player, PlayerMsgId.P_6210);
            return;
        }
        TradeSession session = getOpenSession(player.getUniqueId());
        if (session == null) {
            return;
        }
        suppressNextClose(player);
        clearTopInventory(player);
        long ownedGold = currencyService.getGoldAmount(player);
        long currentAmount = session.getGoldAmount(player.getUniqueId());
        goldAmountSettingGui.open(
            player,
            GOLD_AMOUNT_SOURCE_KEY,
            session.getSessionId(),
            currentAmount,
            Math.max(ownedGold, currentAmount)
        );
    }

    /**
     * Gold 金額設定 GUI の確定結果をトレードセッションへ反映します。
     *
     * @param player 操作プレイヤー
     * @param sessionId 対象セッション ID
     * @param amount 確定金額
     */
    public void applyGoldAmount(@NotNull Player player, @NotNull UUID sessionId, long amount) {
        if (!AccountModeGuard.isGameplayPlayer(player)) {
            messageService.send(player, PlayerMsgId.P_5065);
            return;
        }
        if (!isTradeAllowedWorld(player)) {
            messageService.send(player, PlayerMsgId.P_6210);
            return;
        }
        TradeSession session = getOpenSession(player.getUniqueId());
        if (session == null || !session.getSessionId().equals(sessionId)) {
            return;
        }
        long maxAmount = currencyService.getGoldAmount(player);
        session.setGoldAmount(player.getUniqueId(), Math.min(Math.max(0L, amount), maxAmount));
        reopenTrade(player);
        Player partner = Bukkit.getPlayer(session.getPartnerUuid(player.getUniqueId()));
        if (partner != null && partner.isOnline()) {
            tradeGui.refreshIfOpen(partner, session);
        }
    }

    /**
     * 取引 GUI を再表示する。
     *
     * @param player 表示対象プレイヤー
     */
    public void reopenTrade(@NotNull Player player) {
        if (!AccountModeGuard.isGameplayPlayer(player)) {
            messageService.send(player, PlayerMsgId.P_5065);
            return;
        }
        TradeSession session = getOpenSession(player.getUniqueId());
        if (session == null) {
            return;
        }
        openTrade(player, session);
    }

    /**
     * サブ GUI が手動で閉じられたあとに、suppress フラグを残さず取引 GUI を再表示します。
     *
     * @param player 表示対象プレイヤー
     */
    public void reopenTradeAfterClose(@NotNull Player player) {
        if (!AccountModeGuard.isGameplayPlayer(player)) {
            messageService.send(player, PlayerMsgId.P_5065);
            return;
        }
        TradeSession session = getOpenSession(player.getUniqueId());
        if (session == null) {
            return;
        }
        openTrade(player, session, false);
    }

    /**
     * 取引を中止し、提示アイテムを元の所有者へ返却する。
     *
     * @param player 中止操作または異常終了を発生させたプレイヤー
     */
    public void cancelTrade(@NotNull Player player) {
        TradeSession session = getOpenSession(player.getUniqueId());
        if (session != null) {
            cancelTrade(session);
        }
    }

    public void cancelTrade(@NotNull TradeSession session) {
        if (session.getStatus() != TradeSessionStatus.OPEN) {
            return;
        }
        restoreOfferedItemsToOwner(session, session.getPlayerAUuid());
        restoreOfferedItemsToOwner(session, session.getPlayerBUuid());
        session.setStatus(TradeSessionStatus.CANCELLED);
        closeParticipants(session);
        clearSession(session);
        sendIfOnline(session.getPlayerAUuid(), PlayerMsgId.P_6208);
        sendIfOnline(session.getPlayerBUuid(), PlayerMsgId.P_6208);
    }

    public void cancelAll() {
        for (TradeSession session : List.copyOf(sessions.values())) {
            if (session.getStatus() == TradeSessionStatus.OPEN) {
                cancelTrade(session);
            }
        }
    }

    public boolean consumeSuppressedClose(@NotNull UUID playerUuid) {
        return suppressedClosePlayers.remove(playerUuid);
    }

    public boolean isTradeable(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType().isAir()) {
            return false;
        }
        ItemModel model = itemReferenceResolver.resolveItemModel(itemStack);
        return model != null && !model.getUnTradeable();
    }

    public @Nullable TradeSession getOpenSession(@NotNull UUID playerUuid) {
        UUID sessionId = activeSessionByPlayer.get(playerUuid);
        TradeSession session = sessionId == null ? null : sessions.get(sessionId);
        return session != null && session.getStatus() == TradeSessionStatus.OPEN ? session : null;
    }

    /**
     * Bukkit main thread で呼び出し、指定した owned item をトレード提示へ予約します。
     * <p>
     * NORMAL inventory の正本 state は変更せず、提示用 clone と起点 entry ID・数量の予約だけを
     * session と GUI 表示へ反映します。session／account／entry が一致しない場合、対象 item が
     * untradeable の場合、または提示可能数量がない場合は {@code false} を返します。
     *
     * @param player 提示する online player
     * @param sourceBukkitSlot 起点 item がある Bukkit inventory slot
     * @param clickType 提示数量を決めるクリック種別
     * @param displayedItem GUI に表示中の起点 item
     * @return 予約表示を更新できた場合は {@code true}
     * @throws NullPointerException {@code player} または {@code clickType} が {@code null} の場合
     */
    public boolean offerOwnedItem(
        @NotNull Player player,
        int sourceBukkitSlot,
        @NotNull ClickType clickType,
        @Nullable ItemStack displayedItem
    ) {
        if (!isTradeAllowedWorld(player)) {
            messageService.send(player, PlayerMsgId.P_6210);
            return false;
        }
        TradeSession session = getOpenSession(player.getUniqueId());
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (session == null || astPlayer == null
            || !session.getAccountId(player.getUniqueId()).equals(astPlayer.getAccount().getUuid())
            || displayedItem == null || displayedItem.getType().isAir()) {
            return false;
        }
        InventoryEntryModel sourceEntry = inventoryService.getOwnedEntryAtBukkitSlot(astPlayer, sourceBukkitSlot);
        ItemModel model = inventoryService.getOwnedItemModelAtBukkitSlot(astPlayer, sourceBukkitSlot);
        if (model == null || model.getUnTradeable()) {
            return false;
        }
        if (sourceEntry == null || sourceEntry.getInventoryEntryId() == null) {
            return false;
        }
        int requested = ItemTransferSupport.resolveTransferAmount(
            clickType,
            displayedItem.getAmount(),
            displayedItem.getMaxStackSize()
        );
        int capacity = offerCapacity(session.getItems(player.getUniqueId()), displayedItem, requested);
        if (capacity <= 0) {
            return false;
        }
        List<ItemStack> originalItems = session.getItems(player.getUniqueId());
        try {
            ItemStack moved = displayedItem.clone();
            moved.setAmount(capacity);
            List<ItemStack> updated = appendEscrowItems(originalItems, moved);
            if (updated.size() > TradeGuiLayout.OWN_SLOT_LIST.size()) {
                return false;
            }
            List<UUID> sourceEntryIds = sourceEntryIds(session, player.getUniqueId());
            sourceEntryIds.add(sourceEntry.getInventoryEntryId());
            session.setItems(player.getUniqueId(), updated, sourceEntryIds);
            inventoryService.hideOwnedEntryQuantityFromGui(astPlayer, sourceEntry.getInventoryEntryId(), capacity);
            refreshBoth(session);
            return true;
        } catch (RuntimeException e) {
            session.setItems(player.getUniqueId(), originalItems);
            Logger.log(LogId.E_6201, e, "offer:" + session.getSessionId());
            return false;
        }
    }

    /**
     * Bukkit main thread で呼び出し、提示済み item の予約数量を取り下げます。
     * <p>
     * NORMAL inventory の正本 state は変更せず、session の clone・起点 entry ID・数量予約と
     * GUI の予約非表示だけを更新します。session／account／提示 index が一致しない場合、起点 entry を
     * 解決できない場合、または取り下げ数量がない場合は {@code false} を返します。
     *
     * @param player 取り下げる online player
     * @param offerIndex 自分側 offer list の index
     * @param clickType 取り下げ数量を決めるクリック種別
     * @return 予約表示を更新できた場合は {@code true}
     * @throws NullPointerException {@code player} または {@code clickType} が {@code null} の場合
     */
    public boolean withdrawOfferedItem(
        @NotNull Player player,
        int offerIndex,
        @NotNull ClickType clickType
    ) {
        if (!isTradeAllowedWorld(player)) {
            messageService.send(player, PlayerMsgId.P_6210);
            return false;
        }
        TradeSession session = getOpenSession(player.getUniqueId());
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (session == null || astPlayer == null
            || !session.getAccountId(player.getUniqueId()).equals(astPlayer.getAccount().getUuid())) {
            return false;
        }
        List<ItemStack> originalItems = session.getItems(player.getUniqueId());
        if (offerIndex < 0 || offerIndex >= originalItems.size()) {
            return false;
        }
        ItemStack offered = originalItems.get(offerIndex);
        int requested = ItemTransferSupport.resolveTransferAmount(
            clickType,
            offered.getAmount(),
            offered.getMaxStackSize()
        );
        UUID sourceEntryId = session.getItemSourceEntryId(player.getUniqueId(), offerIndex);
        if (requested <= 0 || sourceEntryId == null) {
            return false;
        }
        try {
            List<ItemStack> updated = new ArrayList<>(originalItems);
            List<UUID> sourceEntryIds = sourceEntryIds(session, player.getUniqueId());
            int remaining = offered.getAmount() - requested;
            if (remaining <= 0) {
                updated.remove(offerIndex);
                sourceEntryIds.remove(offerIndex);
            } else {
                ItemStack remainder = offered.clone();
                remainder.setAmount(remaining);
                updated.set(offerIndex, remainder);
            }
            session.setItems(player.getUniqueId(), updated, sourceEntryIds);
            inventoryService.restoreHiddenEntryQuantityToGui(astPlayer, sourceEntryId, requested);
            refreshBoth(session);
            return true;
        } catch (RuntimeException e) {
            session.setItems(player.getUniqueId(), originalItems);
            Logger.log(LogId.E_6201, e, "withdraw:" + session.getSessionId());
            return false;
        }
    }

    private void completeTrade(@NotNull TradeSession session) {
        try {
            Player playerA = Bukkit.getPlayer(session.getPlayerAUuid());
            Player playerB = Bukkit.getPlayer(session.getPlayerBUuid());
            if (!AccountModeGuard.isGameplayPlayer(playerA) || !AccountModeGuard.isGameplayPlayer(playerB)) {
                cancelTrade(session);
                return;
            }
            if (!isTradeAllowedWorld(playerA) || !isTradeAllowedWorld(playerB)) {
                sendIfOnline(session.getPlayerAUuid(), PlayerMsgId.P_6210);
                sendIfOnline(session.getPlayerBUuid(), PlayerMsgId.P_6210);
                cancelTrade(session);
                return;
            }
            if (!hasAnyOffer(session)) {
                cancelTrade(session);
                return;
            }
            if (!session.isPlayerAReady() || !session.isPlayerBReady()
                || !allTradeable(session.getItems(session.getPlayerAUuid()))
                || !allTradeable(session.getItems(session.getPlayerBUuid()))) {
                session.resetReady();
                refreshBoth(session);
                return;
            }
            if (!hasGold(session.getPlayerAUuid(), session.getGoldAmount(session.getPlayerAUuid()))
                || !hasGold(session.getPlayerBUuid(), session.getGoldAmount(session.getPlayerBUuid()))) {
                session.resetReady();
                sendIfOnline(session.getPlayerAUuid(), PlayerMsgId.P_6203);
                sendIfOnline(session.getPlayerBUuid(), PlayerMsgId.P_6203);
                refreshBoth(session);
                return;
            }
            List<TradeCommitItem> playerACommitItems = session.getCommitItems(session.getPlayerAUuid());
            List<TradeCommitItem> playerBCommitItems = session.getCommitItems(session.getPlayerBUuid());
            if (inventorySaveCoordinator == null || tradeRepository == null
                || playerACommitItems.size() != session.getItems(session.getPlayerAUuid()).size()
                || playerBCommitItems.size() != session.getItems(session.getPlayerBUuid()).size()) {
                session.resetReady();
                sendIfOnline(session.getPlayerAUuid(), PlayerMsgId.P_6209);
                sendIfOnline(session.getPlayerBUuid(), PlayerMsgId.P_6209);
                refreshBoth(session);
                return;
            }
            session.setStatus(TradeSessionStatus.COMMITTING);
            commitTradeWithInventoryLocks(session).whenComplete((result, throwable) ->
                Bukkit.getScheduler().runTask(plugin, () -> finishTradeCommit(session, throwable))
            );
        } catch (Exception e) {
            Logger.log(LogId.E_6201, e, session.getSessionId());
            session.setStatus(TradeSessionStatus.OPEN);
            session.resetReady();
            refreshBoth(session);
        }
    }

    /**
     * 両 account の事前保存を完了してから API transaction を実行し、各 state を再同期・保存します。
     * <p>
     * lane worker 間で future を同期待機しないため、二 account が同じ single-thread executor を共有しても
     * 停止しません。API 成功後は未解決境界を保持し、片方の再同期失敗時も {@code COMMITTING} のまま
     * 同じ operation ID の結果で復旧します。
     *
     * @param session 確定対象セッション
     * @return 両方の state を再同期・保存し終えた future
     */
    private @NotNull CompletableFuture<TradeCommitResult> commitTradeWithInventoryLocks(@NotNull TradeSession session) {
        InventorySaveCoordinator coordinator = java.util.Objects.requireNonNull(inventorySaveCoordinator);
        TradeRepository repository = java.util.Objects.requireNonNull(tradeRepository);
        CompletableFuture<InventorySaveCoordinator.PreparedExternalOperation> playerAPrepared =
            coordinator.prepareExternalOperationAfterSave(session.getPlayerAAccountId());
        CompletableFuture<InventorySaveCoordinator.PreparedExternalOperation> playerBPrepared =
            coordinator.prepareExternalOperationAfterSave(session.getPlayerBAccountId());

        CompletableFuture<TradeCommitResult> commit = playerAPrepared.thenCombine(
            playerBPrepared,
            (preparedA, preparedB) -> {
                TradeCommitRequest request = new TradeCommitRequest(
                    session.getSessionId(),
                    session.getPlayerAAccountId(),
                    session.getPlayerBAccountId(),
                    session.getCommitItems(session.getPlayerAUuid()),
                    session.getCommitItems(session.getPlayerBUuid()),
                    session.getGoldAmount(session.getPlayerAUuid()),
                    session.getGoldAmount(session.getPlayerBUuid()),
                    session.getPlayerAAccountId()
                );
                TradeCommitRecovery recovery = new TradeCommitRecovery(preparedA, preparedB, request);
                tradeCommitRecoveries.put(session.getSessionId(), recovery);
                try {
                    recovery.replaceResult(repository.commit(request));
                    return recovery;
                } catch (TradeRepository.TradeCommitRejectedException rejected) {
                    tradeCommitRecoveries.remove(session.getSessionId(), recovery);
                    throw rejected;
                }
            }
        ).thenCompose(recovery -> reconcileUnfinishedTradeCommit(session, recovery));
        commit.whenComplete((ignored, throwable) -> {
            if (tradeCommitRecoveries.containsKey(session.getSessionId())) {
                return;
            }
            playerAPrepared.thenAccept(coordinator::abandonPreparedExternalOperation);
            playerBPrepared.thenAccept(coordinator::abandonPreparedExternalOperation);
        });
        return commit;
    }

    /**
     * API 確定済みトレードについて、未完了 account だけを各 save lane で再同期・保存します。
     * lane worker は他 account の future を待機しないため、single-thread executor でも進行します。
     */
    private @NotNull CompletableFuture<TradeCommitResult> reconcileUnfinishedTradeCommit(
        @NotNull TradeSession session,
        @NotNull TradeCommitRecovery recovery
    ) {
        InventorySaveCoordinator coordinator = java.util.Objects.requireNonNull(inventorySaveCoordinator);
        List<CompletableFuture<?>> reconciliations = new ArrayList<>();
        if (!recovery.playerAReconciled()) {
            reconciliations.add(coordinator.completePreparedExternalOperation(
                recovery.playerAPrepared(),
                baseline -> {
                    inventoryService.reconcileTradeInventoryEntries(
                        session.getPlayerAAccountId(),
                        recovery.result().playerAAffectedInventoryEntryIds(),
                        baseline
                    );
                    return recovery.result();
                }
            ).thenAccept(ignored -> recovery.markPlayerAReconciled()));
        }
        if (!recovery.playerBReconciled()) {
            reconciliations.add(coordinator.completePreparedExternalOperation(
                recovery.playerBPrepared(),
                baseline -> {
                    inventoryService.reconcileTradeInventoryEntries(
                        session.getPlayerBAccountId(),
                        recovery.result().playerBAffectedInventoryEntryIds(),
                        baseline
                    );
                    return recovery.result();
                }
            ).thenAccept(ignored -> recovery.markPlayerBReconciled()));
        }
        return CompletableFuture.allOf(reconciliations.toArray(CompletableFuture[]::new))
            .thenApply(ignored -> recovery.result());
    }

    /** API 確定結果を Bukkit main thread で反映します。 */
    private void finishTradeCommit(@NotNull TradeSession session, @Nullable Throwable throwable) {
        if (throwable != null) {
            Logger.log(LogId.E_6201, unwrapCompletionFailure(throwable), "commit:" + session.getSessionId());
            if (tradeCommitRecoveries.containsKey(session.getSessionId())) {
                session.setStatus(TradeSessionStatus.COMMITTING);
                scheduleTradeCommitRecovery(session);
                return;
            }
            session.setStatus(TradeSessionStatus.OPEN);
            session.resetReady();
            sendIfOnline(session.getPlayerAUuid(), PlayerMsgId.P_6209);
            sendIfOnline(session.getPlayerBUuid(), PlayerMsgId.P_6209);
            refreshBoth(session);
            return;
        }
        tradeCommitRecoveries.remove(session.getSessionId());
        session.setStatus(TradeSessionStatus.COMPLETED);
        clearHiddenOfferReservations(session);
        refreshManagedInventoryUi(session.getPlayerAUuid());
        refreshManagedInventoryUi(session.getPlayerBUuid());
        closeParticipants(session);
        clearSession(session);
        sendIfOnline(session.getPlayerAUuid(), PlayerMsgId.P_6207);
        sendIfOnline(session.getPlayerBUuid(), PlayerMsgId.P_6207);
        playCompletionSound(session.getPlayerAUuid());
        playCompletionSound(session.getPlayerBUuid());
    }

    /**
     * API 応答未達または確定後に失敗した account 再同期を、同じ operation ID の replay で再試行します。
     * 明示的な 4xx 拒否だけは未確定として保存境界を解除し、その他の失敗は確定不明として維持します。
     */
    private void scheduleTradeCommitRecovery(@NotNull TradeSession session) {
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            TradeCommitRecovery recovery = tradeCommitRecoveries.get(session.getSessionId());
            if (recovery == null || session.getStatus() != TradeSessionStatus.COMMITTING) {
                return;
            }
            TradeRepository repository = java.util.Objects.requireNonNull(tradeRepository);
            try {
                recovery.replaceResult(repository.commit(recovery.request()));
            } catch (TradeRepository.TradeCommitRejectedException rejected) {
                if (tradeCommitRecoveries.remove(session.getSessionId(), recovery)) {
                    InventorySaveCoordinator coordinator = java.util.Objects.requireNonNull(inventorySaveCoordinator);
                    coordinator.abandonPreparedExternalOperation(recovery.playerAPrepared());
                    coordinator.abandonPreparedExternalOperation(recovery.playerBPrepared());
                }
                Bukkit.getScheduler().runTask(plugin, () -> finishTradeCommit(session, rejected));
                return;
            } catch (RuntimeException replayFailure) {
                Bukkit.getScheduler().runTask(plugin, () -> finishTradeCommit(session, replayFailure));
                return;
            }
            reconcileUnfinishedTradeCommit(session, recovery).whenComplete((result, throwable) ->
                Bukkit.getScheduler().runTask(plugin, () -> finishTradeCommit(session, throwable))
            );
        }, 20L);
    }

    private void playCompletionSound(@NotNull UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            GuiSound.TRADE.play(player);
        }
    }

    private void refreshBoth(@NotNull TradeSession session) {
        Player playerA = Bukkit.getPlayer(session.getPlayerAUuid());
        Player playerB = Bukkit.getPlayer(session.getPlayerBUuid());
        if (playerA != null && playerA.isOnline()) {
            tradeGui.refreshIfOpen(playerA, session);
        }
        if (playerB != null && playerB.isOnline()) {
            tradeGui.refreshIfOpen(playerB, session);
        }
    }

    private void openTrade(@NotNull Player player, @NotNull TradeSession session) {
        openTrade(player, session, true);
    }

    private void openTrade(@NotNull Player player, @NotNull TradeSession session, boolean suppressCurrentClose) {
        if (!AccountModeGuard.isGameplayPlayer(player)) {
            messageService.send(player, PlayerMsgId.P_5065);
            return;
        }
        if (!isTradeAllowedWorld(player)) {
            messageService.send(player, PlayerMsgId.P_6210);
            return;
        }
        if (suppressCurrentClose) {
            suppressNextClose(player);
        }
        clearTopInventory(player);
        tradeGui.open(player, session);
    }

    private int offerCapacity(
        @NotNull List<ItemStack> escrowItems,
        @NotNull ItemStack template,
        int requested
    ) {
        if (requested <= 0) {
            return 0;
        }
        int maxStackSize = Math.max(1, template.getMaxStackSize());
        int freeSlots = Math.max(0, TradeGuiLayout.OWN_SLOT_LIST.size() - escrowItems.size());
        long capacity = (long) freeSlots * maxStackSize;
        return (int) Math.min(requested, Math.min(Integer.MAX_VALUE, capacity));
    }

    private @NotNull List<ItemStack> appendEscrowItems(
        @NotNull List<ItemStack> escrowItems,
        @NotNull ItemStack moved
    ) {
        List<ItemStack> updated = new ArrayList<>();
        for (ItemStack item : escrowItems) {
            updated.add(item.clone());
        }
        int remaining = moved.getAmount();
        while (remaining > 0 && updated.size() < TradeGuiLayout.OWN_SLOT_LIST.size()) {
            ItemStack split = moved.clone();
            int transfer = Math.min(remaining, split.getMaxStackSize());
            split.setAmount(transfer);
            updated.add(split);
            remaining -= transfer;
        }
        if (remaining > 0) {
            throw new IllegalStateException("提示アイテムの移動中に取引保管領域の容量が変化しました");
        }
        return updated;
    }

    private @NotNull List<UUID> sourceEntryIds(@NotNull TradeSession session, @NotNull UUID playerUuid) {
        List<ItemStack> items = session.getItems(playerUuid);
        List<UUID> sourceIds = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            sourceIds.add(session.getItemSourceEntryId(playerUuid, index));
        }
        return sourceIds;
    }

    private void restoreOfferedItemsToOwner(@NotNull TradeSession session, @NotNull UUID playerUuid) {
        Player player = Bukkit.getPlayer(playerUuid);
        AstPlayer astPlayer = player == null ? null : AstPlayerCache.get(player);
        if (astPlayer == null) {
            return;
        }
        List<ItemStack> items = session.getItems(playerUuid);
        for (int index = 0; index < items.size(); index++) {
            UUID sourceEntryId = session.getItemSourceEntryId(playerUuid, index);
            if (sourceEntryId != null) {
                inventoryService.restoreHiddenEntryQuantityToGui(astPlayer, sourceEntryId, items.get(index).getAmount());
            }
        }
    }

    private void clearHiddenOfferReservations(@NotNull TradeSession session) {
        restoreOfferedItemsToOwner(session, session.getPlayerAUuid());
        restoreOfferedItemsToOwner(session, session.getPlayerBUuid());
    }

    private void refreshManagedInventoryUi(@NotNull UUID playerUuid) {
        Player player = Bukkit.getPlayer(playerUuid);
        AstPlayer astPlayer = player == null ? null : AstPlayerCache.get(player);
        if (astPlayer != null) {
            inventoryService.refreshManagedInventoryUi(astPlayer);
        }
    }

    private @NotNull Throwable unwrapCompletionFailure(@NotNull Throwable throwable) {
        return throwable instanceof CompletionException completion && completion.getCause() != null
            ? completion.getCause()
            : throwable;
    }

    private static final class TradeCommitRecovery {
        private final InventorySaveCoordinator.PreparedExternalOperation playerAPrepared;
        private final InventorySaveCoordinator.PreparedExternalOperation playerBPrepared;
        private final TradeCommitRequest request;
        private volatile @Nullable TradeCommitResult result;
        private volatile boolean playerAReconciled;
        private volatile boolean playerBReconciled;

        private TradeCommitRecovery(
            @NotNull InventorySaveCoordinator.PreparedExternalOperation playerAPrepared,
            @NotNull InventorySaveCoordinator.PreparedExternalOperation playerBPrepared,
            @NotNull TradeCommitRequest request
        ) {
            this.playerAPrepared = playerAPrepared;
            this.playerBPrepared = playerBPrepared;
            this.request = request;
        }

        private @NotNull InventorySaveCoordinator.PreparedExternalOperation playerAPrepared() {
            return playerAPrepared;
        }

        private @NotNull InventorySaveCoordinator.PreparedExternalOperation playerBPrepared() {
            return playerBPrepared;
        }

        private @NotNull TradeCommitResult result() {
            return java.util.Objects.requireNonNull(result, "Trade commit response has not been confirmed");
        }

        private @NotNull TradeCommitRequest request() {
            return request;
        }

        private void replaceResult(@NotNull TradeCommitResult replayedResult) {
            if (!request.operationId().equals(replayedResult.operationId())) {
                throw new IllegalStateException("Trade replay returned a different operation ID");
            }
            result = replayedResult;
        }

        private boolean playerAReconciled() {
            return playerAReconciled;
        }

        private boolean playerBReconciled() {
            return playerBReconciled;
        }

        private void markPlayerAReconciled() {
            playerAReconciled = true;
        }

        private void markPlayerBReconciled() {
            playerBReconciled = true;
        }
    }

    private void clearTopInventory(@NotNull Player player) {
        Inventory top = player.getOpenInventory().getTopInventory();
        if (tradeGui.isTradeInventory(top)) {
            tradeGui.clearTradeInventory(top);
        }
    }

    private boolean allTradeable(@NotNull List<ItemStack> items) {
        return items.stream().allMatch(this::isTradeable);
    }

    private boolean hasAnyOffer(@NotNull TradeSession session) {
        return !session.getItems(session.getPlayerAUuid()).isEmpty()
            || !session.getItems(session.getPlayerBUuid()).isEmpty()
            || session.getGoldAmount(session.getPlayerAUuid()) > 0L
            || session.getGoldAmount(session.getPlayerBUuid()) > 0L;
    }

    private boolean hasGold(@NotNull UUID playerUuid, long amount) {
        if (amount <= 0L) {
            return true;
        }
        UUID accountId = resolveAccountId(playerUuid);
        return accountId != null && currencyService.getGoldAmount(accountId) >= amount;
    }

    private @Nullable UUID resolveAccountId(@NotNull UUID playerUuid) {
        UUID sessionId = activeSessionByPlayer.get(playerUuid);
        TradeSession session = sessionId == null ? null : sessions.get(sessionId);
        if (session != null && session.contains(playerUuid)) {
            return session.getAccountId(playerUuid);
        }
        Player player = Bukkit.getPlayer(playerUuid);
        AstPlayer astPlayer = player == null ? null : AstPlayerCache.get(player);
        return astPlayer == null ? null : astPlayer.getAccount().getUuid();
    }

    /**
     * トレードの開始・操作を許可するワールドか判定します。
     * <p>
     * 通常の拠点ワールド、およびスキルツリーサービスが解決する専用ワールドだけを許可します。
     * 未登録ワールドやサービス未初期化時は許可しません。
     *
     * @param player 判定対象プレイヤー
     * @return トレードを開始または操作できる場合は {@code true}
     */
    public boolean isTradeAllowedWorld(@NotNull Player player) {
        WorldService worldService = plugin.getWorldService();
        WorldMasterData worldData = worldService == null ? null : worldService.findByBukkitWorld(player.getWorld());
        if (worldData != null && worldData.worldType() == WorldType.BASE) {
            return true;
        }
        SkillTreeService skillTreeService = plugin.getSkillTreeService();
        return skillTreeService != null && skillTreeService.isSkillTreeWorld(player.getWorld());
    }

    private void closeParticipants(@NotNull TradeSession session) {
        closeIfOnline(session.getPlayerAUuid());
        closeIfOnline(session.getPlayerBUuid());
    }

    private void closeIfOnline(@NotNull UUID playerUuid) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null || !player.isOnline()) {
            return;
        }
        suppressNextClose(player);
        clearTopInventory(player);
        player.closeInventory();
    }

    private void clearSession(@NotNull TradeSession session) {
        activeSessionByPlayer.remove(session.getPlayerAUuid());
        activeSessionByPlayer.remove(session.getPlayerBUuid());
        sessions.remove(session.getSessionId());
    }

    private void sendIfOnline(@NotNull UUID playerUuid, @NotNull PlayerMsgId msgId) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player != null && player.isOnline()) {
            messageService.send(player, msgId);
        }
    }

    private boolean isTrading(@NotNull UUID playerUuid) {
        return getOpenSession(playerUuid) != null;
    }

    private @Nullable TradeRequest findPendingRequest(@NotNull UUID senderUuid, @NotNull UUID targetUuid) {
        return requests.values().stream()
            .filter(request -> request.getStatus() == TradeRequestStatus.PENDING)
            .filter(request -> request.getSenderUuid().equals(senderUuid) && request.getTargetUuid().equals(targetUuid))
            .findFirst()
            .orElse(null);
    }

    private @Nullable TradeRequest findLatestIncoming(@NotNull UUID targetUuid) {
        return requests.values().stream()
            .filter(request -> request.getStatus() == TradeRequestStatus.PENDING)
            .filter(request -> request.getTargetUuid().equals(targetUuid))
            .max((left, right) -> left.getCreatedAt().compareTo(right.getCreatedAt()))
            .orElse(null);
    }

    /**
     * 期限切れの pending トレード申請を終端状態へ遷移させ、管理対象から除去します。
     *
     * @implNote iterator を通じて削除し、同一走査中の構造変更例外を防ぎます。
     */
    private void expireRequests() {
        Instant now = Instant.now();
        Iterator<TradeRequest> iterator = requests.values().iterator();
        while (iterator.hasNext()) {
            TradeRequest request = iterator.next();
            if (request.getStatus() == TradeRequestStatus.PENDING && request.isExpired(now)) {
                request.setStatus(TradeRequestStatus.EXPIRED);
                iterator.remove();
            }
        }
    }

    /**
     * トレード申請を終端状態へ遷移させ、pending request 管理から除去します。
     *
     * @param request 終端化するトレード申請
     * @param status 設定する終端状態
     */
    private void finishRequest(@NotNull TradeRequest request, @NotNull TradeRequestStatus status) {
        request.setStatus(status);
        requests.remove(request.getRequestId());
    }

    private void suppressNextClose(@NotNull Player player) {
        suppressedClosePlayers.add(player.getUniqueId());
    }

    private void openCancelConfirm(@NotNull Player player, boolean suppressCurrentClose) {
        TradeSession session = getOpenSession(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (suppressCurrentClose) {
            suppressNextClose(player);
        }
        clearTopInventory(player);
        cancelConfirmGui.open(player, session.getSessionId());
    }

}
