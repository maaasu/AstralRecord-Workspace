package io.github.maaasu.astralRecord.feature.trade.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
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
import io.github.maaasu.astralRecord.feature.trade.gui.TradeCancelConfirmGui;
import io.github.maaasu.astralRecord.feature.trade.gui.TradeGui;
import io.github.maaasu.astralRecord.feature.trade.gui.TradeGuiLayout;
import io.github.maaasu.astralRecord.feature.trade.model.TradeRequest;
import io.github.maaasu.astralRecord.feature.trade.model.TradeRequestStatus;
import io.github.maaasu.astralRecord.feature.trade.model.TradeSession;
import io.github.maaasu.astralRecord.feature.trade.model.TradeSessionStatus;
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
    private final Map<UUID, TradeRequest> requests = new HashMap<>();
    private final Map<UUID, TradeSession> sessions = new HashMap<>();
    private final Map<UUID, UUID> activeSessionByPlayer = new HashMap<>();
    private final Set<UUID> suppressedClosePlayers = new HashSet<>();

    public TradeService(
        @NotNull AstralRecord plugin,
        @NotNull TradeGui tradeGui,
        @NotNull TradeCancelConfirmGui cancelConfirmGui,
        @NotNull GoldAmountSettingGui goldAmountSettingGui,
        @NotNull InventoryService inventoryService,
        @NotNull CurrencyService currencyService,
        @NotNull PlayerMessageService messageService,
        @NotNull ItemService itemService
    ) {
        this(
            plugin,
            tradeGui,
            cancelConfirmGui,
            goldAmountSettingGui,
            inventoryService,
            currencyService,
            messageService,
            new ItemReferenceResolver(itemService)
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
        this.plugin = plugin;
        this.tradeGui = tradeGui;
        this.cancelConfirmGui = cancelConfirmGui;
        this.goldAmountSettingGui = goldAmountSettingGui;
        this.inventoryService = inventoryService;
        this.currencyService = currencyService;
        this.messageService = messageService;
        this.itemReferenceResolver = itemReferenceResolver;
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
            PlayerMsgResource.formatComponent(PlayerMsgId.P_6201.getId(), sender.getName())
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
            sender.getName(),
            accepter.getUniqueId(),
            accepterAstPlayer.getAccount().getUuid(),
            accepter.getName(),
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
        TradeRollbackSnapshot rollbackSnapshot = captureRollbackSnapshot(session);
        if (rollbackSnapshot == null) {
            Logger.log(LogId.E_6201, "cancel_snapshot:" + session.getSessionId());
            return;
        }
        session.setStatus(TradeSessionStatus.COMMITTING);
        boolean returnedA = returnItems(session.getPlayerAUuid(), session.getItems(session.getPlayerAUuid()));
        boolean returnedB = returnItems(session.getPlayerBUuid(), session.getItems(session.getPlayerBUuid()));
        if (!returnedA || !returnedB) {
            Logger.log(LogId.E_6201, "cancel_inventory");
            rollbackCommittedTrade(session, rollbackSnapshot, PlayerMsgId.P_6209);
            return;
        }
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

    public boolean offerOwnedItem(
        @NotNull Player player,
        int sourceBukkitSlot,
        @NotNull ClickType clickType,
        @Nullable ItemStack displayedItem
    ) {
        TradeSession session = getOpenSession(player.getUniqueId());
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (session == null || astPlayer == null
            || !session.getAccountId(player.getUniqueId()).equals(astPlayer.getAccount().getUuid())
            || displayedItem == null || displayedItem.getType().isAir()) {
            return false;
        }
        ItemModel model = inventoryService.getOwnedItemModelAtBukkitSlot(astPlayer, sourceBukkitSlot);
        if (model == null || model.getUnTradeable()) {
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
        InventoryService.InventoryStateSnapshot stateSnapshot =
            inventoryService.snapshotState(astPlayer.getAccount().getUuid());
        if (stateSnapshot == null) {
            return false;
        }
        List<ItemStack> originalItems = session.getItems(player.getUniqueId());
        try {
            ItemStack moved = inventoryService.takeOwnedItemAmount(astPlayer, sourceBukkitSlot, capacity);
            if (!isTradeable(moved)) {
                inventoryService.restoreState(stateSnapshot);
                return false;
            }
            List<ItemStack> updated = appendEscrowItems(originalItems, moved);
            if (updated.size() > TradeGuiLayout.OWN_SLOT_LIST.size()) {
                inventoryService.restoreState(stateSnapshot);
                return false;
            }
            session.setItems(player.getUniqueId(), updated);
            refreshBoth(session);
            return true;
        } catch (RuntimeException e) {
            inventoryService.restoreState(stateSnapshot);
            session.setItems(player.getUniqueId(), originalItems);
            Logger.log(LogId.E_6201, e, "offer:" + session.getSessionId());
            return false;
        }
    }

    public boolean withdrawOfferedItem(
        @NotNull Player player,
        int offerIndex,
        @NotNull ClickType clickType
    ) {
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
        ItemModel model = itemReferenceResolver.resolveItemModel(offered);
        int requested = ItemTransferSupport.resolveTransferAmount(
            clickType,
            offered.getAmount(),
            offered.getMaxStackSize()
        );
        if (model == null || requested <= 0
            || !inventoryService.canAddItemToNormalInventory(astPlayer, model, requested)) {
            return false;
        }
        InventoryService.InventoryStateSnapshot stateSnapshot =
            inventoryService.snapshotState(astPlayer.getAccount().getUuid());
        if (stateSnapshot == null) {
            return false;
        }
        try {
            ItemStack returning = offered.clone();
            returning.setAmount(requested);
            if (inventoryService.returnItemToOwnedInventory(astPlayer, returning) == null) {
                inventoryService.restoreState(stateSnapshot);
                return false;
            }
            List<ItemStack> updated = new ArrayList<>(originalItems);
            int remaining = offered.getAmount() - requested;
            if (remaining <= 0) {
                updated.remove(offerIndex);
            } else {
                ItemStack remainder = offered.clone();
                remainder.setAmount(remaining);
                updated.set(offerIndex, remainder);
            }
            session.setItems(player.getUniqueId(), updated);
            refreshBoth(session);
            return true;
        } catch (RuntimeException e) {
            inventoryService.restoreState(stateSnapshot);
            session.setItems(player.getUniqueId(), originalItems);
            Logger.log(LogId.E_6201, e, "withdraw:" + session.getSessionId());
            return false;
        }
    }

    private void completeTrade(@NotNull TradeSession session) {
        TradeRollbackSnapshot rollbackSnapshot = null;
        try {
            Player playerA = Bukkit.getPlayer(session.getPlayerAUuid());
            Player playerB = Bukkit.getPlayer(session.getPlayerBUuid());
            if (!AccountModeGuard.isGameplayPlayer(playerA) || !AccountModeGuard.isGameplayPlayer(playerB)) {
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
            if (!canReceiveItems(session.getPlayerBUuid(), session.getItems(session.getPlayerAUuid()))
                || !canReceiveItems(session.getPlayerAUuid(), session.getItems(session.getPlayerBUuid()))) {
                session.resetReady();
                sendIfOnline(session.getPlayerAUuid(), PlayerMsgId.P_6209);
                sendIfOnline(session.getPlayerBUuid(), PlayerMsgId.P_6209);
                refreshBoth(session);
                return;
            }
            rollbackSnapshot = captureRollbackSnapshot(session);
            if (rollbackSnapshot == null) {
                session.resetReady();
                refreshBoth(session);
                return;
            }
            session.setStatus(TradeSessionStatus.COMMITTING);
            boolean deliveredA = returnItems(session.getPlayerBUuid(), session.getItems(session.getPlayerAUuid()));
            boolean deliveredB = returnItems(session.getPlayerAUuid(), session.getItems(session.getPlayerBUuid()));
            if (!deliveredA || !deliveredB) {
                Logger.log(LogId.E_6201, "inventory");
                rollbackCommittedTrade(session, rollbackSnapshot, PlayerMsgId.P_6209);
                return;
            }
            if (!transferGold(session)) {
                rollbackCommittedTrade(session, rollbackSnapshot, PlayerMsgId.P_6203);
                return;
            }
            session.setStatus(TradeSessionStatus.COMPLETED);
            closeParticipants(session);
            clearSession(session);
            sendIfOnline(session.getPlayerAUuid(), PlayerMsgId.P_6207);
            sendIfOnline(session.getPlayerBUuid(), PlayerMsgId.P_6207);
            playCompletionSound(session.getPlayerAUuid());
            playCompletionSound(session.getPlayerBUuid());
        } catch (Exception e) {
            Logger.log(LogId.E_6201, e, session.getSessionId());
            if (rollbackSnapshot != null) {
                rollbackCommittedTrade(session, rollbackSnapshot, PlayerMsgId.P_6209);
            } else {
                cancelTrade(session);
            }
        }
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
        long capacity = 0L;
        if (maxStackSize > 1) {
            for (ItemStack existing : escrowItems) {
                if (existing.isSimilar(template)) {
                    capacity += Math.max(0, maxStackSize - existing.getAmount());
                }
            }
        }
        int freeSlots = Math.max(0, TradeGuiLayout.OWN_SLOT_LIST.size() - escrowItems.size());
        capacity += (long) freeSlots * maxStackSize;
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
        if (moved.getMaxStackSize() > 1) {
            for (int index = 0; index < updated.size() && remaining > 0; index++) {
                ItemStack existing = updated.get(index);
                if (!existing.isSimilar(moved)) {
                    continue;
                }
                int available = Math.max(0, existing.getMaxStackSize() - existing.getAmount());
                if (available <= 0) {
                    continue;
                }
                int transfer = Math.min(remaining, available);
                ItemStack merged = existing.clone();
                merged.setAmount(existing.getAmount() + transfer);
                updated.set(index, merged);
                remaining -= transfer;
            }
        }
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

    private void clearTopInventory(@NotNull Player player) {
        Inventory top = player.getOpenInventory().getTopInventory();
        if (tradeGui.isTradeInventory(top)) {
            tradeGui.clearTradeInventory(top);
        }
    }

    private boolean returnItems(@NotNull UUID ownerUuid, @NotNull List<ItemStack> items) {
        return returnItems(ownerUuid, items, true);
    }

    private boolean returnItems(
        @NotNull UUID ownerUuid,
        @NotNull List<ItemStack> items,
        boolean logFailure
    ) {
        Player player = Bukkit.getPlayer(ownerUuid);
        AstPlayer astPlayer = player == null ? null : AstPlayerCache.get(player);
        if (player == null || astPlayer == null) {
            return false;
        }
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            ItemStack clone = item.clone();
            ItemModel model = itemReferenceResolver.resolveItemModel(clone);
            if (model == null
                || !inventoryService.canAddItemToNormalInventory(astPlayer, model, clone.getAmount())
                || inventoryService.returnItemToOwnedInventory(astPlayer, clone) == null) {
                if (logFailure) {
                    Logger.log(LogId.W_6202, player.getName());
                }
                return false;
            }
        }
        return true;
    }

    private boolean canReceiveItems(@NotNull UUID playerUuid, @NotNull List<ItemStack> items) {
        Player player = Bukkit.getPlayer(playerUuid);
        AstPlayer astPlayer = player == null ? null : AstPlayerCache.get(player);
        if (player == null || astPlayer == null) {
            return false;
        }
        InventoryService.InventoryStateSnapshot stateSnapshot =
            inventoryService.snapshotState(astPlayer.getAccount().getUuid());
        if (stateSnapshot == null) {
            return false;
        }
        boolean receivable;
        boolean restored;
        try {
            receivable = returnItems(playerUuid, items, false);
        } finally {
            restored = inventoryService.restoreState(stateSnapshot);
        }
        return restored && receivable;
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

    private boolean transferGold(@NotNull TradeSession session) {
        long playerAGold = session.getGoldAmount(session.getPlayerAUuid());
        long playerBGold = session.getGoldAmount(session.getPlayerBUuid());
        if (playerAGold <= 0L && playerBGold <= 0L) {
            return true;
        }
        boolean consumedA = consumeGold(session.getPlayerAUuid(), playerAGold);
        boolean consumedB = consumeGold(session.getPlayerBUuid(), playerBGold);
        if (!consumedA || !consumedB) {
            return false;
        }
        boolean addedToB = addGold(session.getPlayerBUuid(), playerAGold);
        boolean addedToA = addGold(session.getPlayerAUuid(), playerBGold);
        return addedToA && addedToB;
    }

    private boolean consumeGold(@NotNull UUID playerUuid, long amount) {
        UUID accountId = resolveAccountId(playerUuid);
        return amount <= 0L || accountId != null && inventoryService.consumeGold(accountId, amount);
    }

    private boolean addGold(@NotNull UUID playerUuid, long amount) {
        if (amount <= 0L) {
            return true;
        }
        Player player = Bukkit.getPlayer(playerUuid);
        AstPlayer astPlayer = player == null ? null : AstPlayerCache.get(player);
        return astPlayer != null && inventoryService.addGold(astPlayer, amount);
    }

    private @Nullable TradeRollbackSnapshot captureRollbackSnapshot(@NotNull TradeSession session) {
        InventoryService.InventoryStateSnapshot stateA =
            inventoryService.snapshotState(session.getPlayerAAccountId());
        InventoryService.InventoryStateSnapshot stateB =
            inventoryService.snapshotState(session.getPlayerBAccountId());
        if (stateA == null || stateB == null) {
            return null;
        }
        return new TradeRollbackSnapshot(stateA, stateB);
    }

    private void rollbackCommittedTrade(
        @NotNull TradeSession session,
        @NotNull TradeRollbackSnapshot snapshot,
        @NotNull PlayerMsgId messageId
    ) {
        boolean restoredA = inventoryService.restoreState(snapshot.playerAState());
        boolean restoredB = inventoryService.restoreState(snapshot.playerBState());
        boolean restored = restoredA && restoredB;
        sendIfOnline(session.getPlayerAUuid(), messageId);
        sendIfOnline(session.getPlayerBUuid(), messageId);
        if (!restored) {
            Logger.log(LogId.E_6201, "rollback:" + session.getSessionId());
            session.setStatus(TradeSessionStatus.CANCELLED);
            closeParticipants(session);
            clearSession(session);
            return;
        }
        session.setStatus(TradeSessionStatus.OPEN);
        session.resetReady();
        refreshBoth(session);
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

    private record TradeRollbackSnapshot(
        @NotNull InventoryService.InventoryStateSnapshot playerAState,
        @NotNull InventoryService.InventoryStateSnapshot playerBState
    ) {
    }
}
