package io.github.maaasu.astralRecord.feature.trade.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemReferenceResolver;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.trade.gui.TradeCancelConfirmGui;
import io.github.maaasu.astralRecord.feature.trade.gui.TradeGui;
import io.github.maaasu.astralRecord.feature.trade.model.TradeRequest;
import io.github.maaasu.astralRecord.feature.trade.model.TradeRequestStatus;
import io.github.maaasu.astralRecord.feature.trade.model.TradeSession;
import io.github.maaasu.astralRecord.feature.trade.model.TradeSessionStatus;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.gui.gold.GoldAmountSettingGui;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
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
        this.plugin = plugin;
        this.tradeGui = tradeGui;
        this.cancelConfirmGui = cancelConfirmGui;
        this.goldAmountSettingGui = goldAmountSettingGui;
        this.inventoryService = inventoryService;
        this.currencyService = currencyService;
        this.messageService = messageService;
        this.itemReferenceResolver = new ItemReferenceResolver(itemService);
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
            request.setStatus(TradeRequestStatus.CANCELLED);
            messageService.send(accepter, PlayerMsgId.P_5065);
            return;
        }
        if (sender == null || !sender.isOnline() || isTrading(sender.getUniqueId()) || isTrading(accepter.getUniqueId())) {
            request.setStatus(TradeRequestStatus.CANCELLED);
            messageService.send(accepter, PlayerMsgId.P_6203);
            return;
        }
        request.setStatus(TradeRequestStatus.ACCEPTED);
        TradeSession session = new TradeSession(
            UUID.randomUUID(),
            sender.getUniqueId(),
            sender.getName(),
            accepter.getUniqueId(),
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
        captureOpenInventory(player, session);
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
        captureOpenInventory(player, session);
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
            reopenTrade(partner);
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
        captureBoth(session);
        session.setStatus(TradeSessionStatus.CANCELLED);
        returnItems(session.getPlayerAUuid(), session.getItems(session.getPlayerAUuid()));
        returnItems(session.getPlayerBUuid(), session.getItems(session.getPlayerBUuid()));
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
            return true;
        }
        ItemModel model = itemReferenceResolver.resolveItemModel(itemStack);
        return model == null || !model.getUnTradeable();
    }

    public @Nullable TradeSession getOpenSession(@NotNull UUID playerUuid) {
        UUID sessionId = activeSessionByPlayer.get(playerUuid);
        TradeSession session = sessionId == null ? null : sessions.get(sessionId);
        return session != null && session.getStatus() == TradeSessionStatus.OPEN ? session : null;
    }

    public void captureOpenInventory(@NotNull Player player) {
        TradeSession session = getOpenSession(player.getUniqueId());
        if (session != null) {
            captureOpenInventory(player, session);
        }
    }

    public void captureInventory(@NotNull Player player, @NotNull Inventory inventory) {
        TradeSession session = getOpenSession(player.getUniqueId());
        TradeGui.TradeHolder holder = tradeGui.getTradeHolder(inventory);
        if (session == null || holder == null || !holder.sessionId().equals(session.getSessionId())) {
            return;
        }
        session.setItems(player.getUniqueId(), tradeGui.collectOwnItems(inventory));
    }

    private void completeTrade(@NotNull TradeSession session) {
        try {
            captureBoth(session);
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
            boolean deliveredA = returnItems(session.getPlayerBUuid(), session.getItems(session.getPlayerAUuid()));
            boolean deliveredB = returnItems(session.getPlayerAUuid(), session.getItems(session.getPlayerBUuid()));
            if (!deliveredA || !deliveredB) {
                Logger.log(LogId.E_6201, "inventory");
                session.resetReady();
                sendIfOnline(session.getPlayerAUuid(), PlayerMsgId.P_6209);
                sendIfOnline(session.getPlayerBUuid(), PlayerMsgId.P_6209);
                refreshBoth(session);
                return;
            }
            if (!transferGold(session)) {
                session.resetReady();
                sendIfOnline(session.getPlayerAUuid(), PlayerMsgId.P_6203);
                sendIfOnline(session.getPlayerBUuid(), PlayerMsgId.P_6203);
                refreshBoth(session);
                return;
            }
            session.setStatus(TradeSessionStatus.COMPLETED);
            closeParticipants(session);
            clearSession(session);
            sendIfOnline(session.getPlayerAUuid(), PlayerMsgId.P_6207);
            sendIfOnline(session.getPlayerBUuid(), PlayerMsgId.P_6207);
        } catch (Exception e) {
            Logger.log(LogId.E_6201, e, session.getSessionId());
            cancelTrade(session);
        }
    }

    private void refreshBoth(@NotNull TradeSession session) {
        Player playerA = Bukkit.getPlayer(session.getPlayerAUuid());
        Player playerB = Bukkit.getPlayer(session.getPlayerBUuid());
        if (playerA != null && playerA.isOnline()) {
            openTrade(playerA, session);
        }
        if (playerB != null && playerB.isOnline()) {
            openTrade(playerB, session);
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

    private void captureBoth(@NotNull TradeSession session) {
        Player playerA = Bukkit.getPlayer(session.getPlayerAUuid());
        Player playerB = Bukkit.getPlayer(session.getPlayerBUuid());
        if (playerA != null && playerA.isOnline()) {
            captureOpenInventory(playerA, session);
        }
        if (playerB != null && playerB.isOnline()) {
            captureOpenInventory(playerB, session);
        }
    }

    private void captureOpenInventory(@NotNull Player player, @NotNull TradeSession session) {
        Inventory top = player.getOpenInventory().getTopInventory();
        TradeGui.TradeHolder holder = tradeGui.getTradeHolder(top);
        if (holder == null || !holder.sessionId().equals(session.getSessionId())) {
            return;
        }
        session.setItems(player.getUniqueId(), tradeGui.collectOwnItems(top));
    }

    private void clearTopInventory(@NotNull Player player) {
        Inventory top = player.getOpenInventory().getTopInventory();
        if (tradeGui.isTradeInventory(top)) {
            tradeGui.clearTradeInventory(top);
        }
    }

    private boolean returnItems(@NotNull UUID ownerUuid, @NotNull List<ItemStack> items) {
        Player player = Bukkit.getPlayer(ownerUuid);
        if (player == null || !player.isOnline()) {
            return false;
        }
        boolean success = true;
        AstPlayer astPlayer = AstPlayerCache.get(player);
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            ItemStack clone = item.clone();
            if (itemReferenceResolver.resolve(clone) != null && astPlayer != null) {
                if (inventoryService.returnItemToOwnedInventory(astPlayer, clone) == null) {
                    Logger.log(LogId.W_6202, player.getName());
                    success = false;
                }
                continue;
            }
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(clone);
            if (!overflow.isEmpty()) {
                Logger.log(LogId.W_6202, player.getName());
                success = false;
            }
        }
        return success;
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
        return currencyService.getGoldAmount(playerUuid) >= amount;
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
            if (consumedA) {
                rollbackGold(session.getPlayerAUuid(), playerAGold);
            }
            if (consumedB) {
                rollbackGold(session.getPlayerBUuid(), playerBGold);
            }
            return false;
        }
        boolean addedToB = addGold(session.getPlayerBUuid(), playerAGold);
        boolean addedToA = addGold(session.getPlayerAUuid(), playerBGold);
        if (!addedToA || !addedToB) {
            rollbackGold(session.getPlayerAUuid(), playerAGold);
            rollbackGold(session.getPlayerBUuid(), playerBGold);
            return false;
        }
        return true;
    }

    private boolean consumeGold(@NotNull UUID playerUuid, long amount) {
        return amount <= 0L || inventoryService.consumeGold(playerUuid, amount);
    }

    private boolean addGold(@NotNull UUID playerUuid, long amount) {
        if (amount <= 0L) {
            return true;
        }
        Player player = Bukkit.getPlayer(playerUuid);
        AstPlayer astPlayer = player == null ? null : AstPlayerCache.get(player);
        return astPlayer != null && inventoryService.addGold(astPlayer, amount);
    }

    private void rollbackGold(@NotNull UUID playerUuid, long amount) {
        if (amount > 0L) {
            addGold(playerUuid, amount);
        }
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

    private void expireRequests() {
        Instant now = Instant.now();
        for (TradeRequest request : requests.values()) {
            if (request.getStatus() == TradeRequestStatus.PENDING && request.isExpired(now)) {
                request.setStatus(TradeRequestStatus.EXPIRED);
            }
        }
    }

    private void suppressNextClose(@NotNull Player player) {
        suppressedClosePlayers.add(player.getUniqueId());
    }

    private void openCancelConfirm(@NotNull Player player, boolean suppressCurrentClose) {
        TradeSession session = getOpenSession(player.getUniqueId());
        if (session == null) {
            return;
        }
        captureOpenInventory(player, session);
        if (suppressCurrentClose) {
            suppressNextClose(player);
        }
        clearTopInventory(player);
        cancelConfirmGui.open(player, session.getSessionId());
    }
}
