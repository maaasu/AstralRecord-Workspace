package io.github.maaasu.astralRecord.feature.account.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.EventHandler;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.event.PlayerJoinEventHandler;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.AsyncTaskUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** /account のスロット指定によるアカウント切替を扱います。 */
public final class AccountSwitchCommand extends AstCommand implements EventHandler {
    private final Set<UUID> pendingPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> frozenPlayers = ConcurrentHashMap.newKeySet();

    public AccountSwitchCommand() {
        super("accountswitch", "アカウントを切り替えます。", "/account <slot>", false, PERMISSION_NONE);
    }

    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5060.getId()));
            return;
        }
        if (args.length != 1) {
            sendUsage(sender);
            return;
        }

        Integer slotIndex = parseSlotIndex(args[0]);
        if (slotIndex == null) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5337.getId()));
            return;
        }
        if (hasCursorItem(player)) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5344.getId()));
            return;
        }

        AstralRecord plugin = AstralRecord.getInstance();
        AccountService accountService = plugin.getAccountService();
        PlayerService playerService = plugin.getPlayerService();
        PlayerJoinEventHandler playerJoinEventHandler = plugin.getPlayerJoinEventHandler();
        AstPlayer currentPlayer = AstPlayerCache.get(player);
        if (accountService == null || playerService == null || playerJoinEventHandler == null || currentPlayer == null) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5340.getId()));
            return;
        }
        if (!pendingPlayers.add(player.getUniqueId())) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5341.getId()));
            return;
        }

        UUID userId = currentPlayer.getUser().getUuid();
        UUID previousAccountId = currentPlayer.getAccount().getUuid();
        String accountName = player.getName();
        AsyncTaskUtil.supplyAsync(plugin, () -> resolveOrCreateAccount(
            accountService,
            userId,
            accountName,
            slotIndex
        )).whenComplete((resolved, failure) -> AsyncTaskUtil.runSync(plugin, () -> {
            if (failure != null || resolved == null) {
                fail(sender, player, failure);
                return;
            }
            if (!player.isOnline()) {
                pendingPlayers.remove(player.getUniqueId());
                frozenPlayers.remove(player.getUniqueId());
                return;
            }
            if (resolved.account().getUuid().equals(previousAccountId)) {
                completeSuccess(sender, player, resolved);
                return;
            }

            freeze(player);
            PlayerJoinEventHandler.AccountSwitchPreparation preparation =
                playerJoinEventHandler.prepareAccountSwitch(player);
            if (preparation == null) {
                fail(sender, player, null);
                return;
            }
            switchAfterSessionSave(
                sender,
                player,
                userId,
                preparation.accountId(),
                preparation.logoutSave(),
                resolved,
                accountService,
                playerService,
                playerJoinEventHandler
            );
        }));
    }

    private @NotNull ResolvedAccount resolveOrCreateAccount(
        @NotNull AccountService accountService,
        @NotNull UUID userId,
        @NotNull String accountName,
        int slotIndex
    ) {
        AccountModel account = accountService.getAccounts(userId).stream()
            .filter(candidate -> candidate.getSlotIndex() == slotIndex)
            .findFirst()
            .orElse(null);
        if (account != null) {
            return new ResolvedAccount(account, false);
        }
        return new ResolvedAccount(
            accountService.createAccount(userId, accountName, slotIndex, userId),
            true
        );
    }

    private void switchAfterSessionSave(
        @NotNull CommandSender sender,
        @NotNull Player player,
        @NotNull UUID userId,
        @NotNull UUID previousAccountId,
        @NotNull CompletableFuture<Boolean> previousSessionSave,
        @NotNull ResolvedAccount resolved,
        @NotNull AccountService accountService,
        @NotNull PlayerService playerService,
        @NotNull PlayerJoinEventHandler playerJoinEventHandler
    ) {
        AstralRecord plugin = AstralRecord.getInstance();
        AsyncTaskUtil.supplyAsync(plugin, () -> {
            playerService.awaitQueuedSavesForAccountSwitch(previousAccountId, previousSessionSave);
            return accountService.switchAccount(userId, resolved.account().getUuid());
        }).whenComplete((switched, failure) -> AsyncTaskUtil.runSync(plugin, () -> {
            if (failure != null || switched == null) {
                recoverPreviousAccount(
                    sender,
                    player,
                    userId,
                    previousAccountId,
                    accountService,
                    playerJoinEventHandler
                );
                return;
            }
            playerJoinEventHandler.reloadAccount(player, switched, succeeded -> {
                if (succeeded) {
                    completeSuccess(sender, player, resolved);
                    return;
                }
                recoverPreviousAccount(
                    sender,
                    player,
                    userId,
                    previousAccountId,
                    accountService,
                    playerJoinEventHandler
                );
            });
        }));
    }

    private void recoverPreviousAccount(
        @NotNull CommandSender sender,
        @NotNull Player player,
        @NotNull UUID userId,
        @NotNull UUID previousAccountId,
        @NotNull AccountService accountService,
        @NotNull PlayerJoinEventHandler playerJoinEventHandler
    ) {
        AstralRecord plugin = AstralRecord.getInstance();
        AsyncTaskUtil.supplyAsync(plugin, () -> accountService.switchAccount(userId, previousAccountId))
            .whenComplete((previousAccount, failure) -> AsyncTaskUtil.runSync(plugin, () -> {
                if (failure != null || previousAccount == null) {
                    failAndKick(sender, player, failure);
                    return;
                }
                playerJoinEventHandler.reloadAccount(player, previousAccount, recovered -> {
                    if (!recovered) {
                        failAndKick(sender, player, null);
                        return;
                    }
                    pendingPlayers.remove(player.getUniqueId());
                    frozenPlayers.remove(player.getUniqueId());
                    sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5062.getId()));
                });
            }));
    }

    private void completeSuccess(
        @NotNull CommandSender sender,
        @NotNull Player player,
        @NotNull ResolvedAccount resolved
    ) {
        pendingPlayers.remove(player.getUniqueId());
        frozenPlayers.remove(player.getUniqueId());
        String messageId = resolved.created() ? PlayerMsgId.P_5343.getId() : PlayerMsgId.P_5342.getId();
        sendSuccess(sender, PlayerMsgResource.format(
            messageId,
            resolved.account().getSlotIndex(),
            resolved.account().getAccountName()
        ));
    }

    private void fail(
        @NotNull CommandSender sender,
        @NotNull Player player,
        @Nullable Throwable failure
    ) {
        if (failure != null) {
            Logger.error(LogId.E_5153, failure, player.getUniqueId());
        }
        pendingPlayers.remove(player.getUniqueId());
        frozenPlayers.remove(player.getUniqueId());
        sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5062.getId()));
    }

    private void failAndKick(
        @NotNull CommandSender sender,
        @NotNull Player player,
        @Nullable Throwable failure
    ) {
        if (failure != null) {
            Logger.error(LogId.E_5153, failure, player.getUniqueId());
        }
        pendingPlayers.remove(player.getUniqueId());
        frozenPlayers.remove(player.getUniqueId());
        if (player.isOnline()) {
            player.kick(PlayerMsgResource.getComponent(PlayerMsgId.P_5339.getId()));
        } else {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5062.getId()));
        }
    }

    private void freeze(@NotNull Player player) {
        frozenPlayers.add(player.getUniqueId());
        AstralRecord plugin = AstralRecord.getInstance();
        var skillBindGuiEventHandler = plugin.getSkillBindGuiEventHandler();
        if (skillBindGuiEventHandler != null) {
            skillBindGuiEventHandler.releaseForAccountSwitch(player);
        }
        var tradeService = plugin.getTradeService();
        if (tradeService != null) {
            tradeService.cancelTrade(player);
        }
        player.closeInventory();
    }

    private boolean hasCursorItem(@NotNull Player player) {
        var cursor = player.getItemOnCursor();
        return cursor != null && !cursor.getType().isAir();
    }

    private @Nullable Integer parseSlotIndex(@NotNull String value) {
        try {
            int slotIndex = Integer.parseInt(value);
            return slotIndex < 0 ? null : slotIndex;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void onPlayerMove(@NotNull PlayerMoveEvent event) {
        if (!frozenPlayers.contains(event.getPlayer().getUniqueId()) || event.getTo() == null) {
            return;
        }
        if (event.getFrom().getX() != event.getTo().getX()
            || event.getFrom().getY() != event.getTo().getY()
            || event.getFrom().getZ() != event.getTo().getZ()) {
            event.setCancelled(true);
        }
    }

    @org.bukkit.event.EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && frozenPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @org.bukkit.event.EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && frozenPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @org.bukkit.event.EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerDropItem(@NotNull PlayerDropItemEvent event) {
        if (frozenPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @org.bukkit.event.EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityPickupItem(@NotNull EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && frozenPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @org.bukkit.event.EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerInteract(@NotNull PlayerInteractEvent event) {
        if (frozenPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @org.bukkit.event.EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerInteractEntity(@NotNull PlayerInteractEntityEvent event) {
        if (frozenPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @org.bukkit.event.EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerSwapHandItems(@NotNull PlayerSwapHandItemsEvent event) {
        if (frozenPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @org.bukkit.event.EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerItemHeld(@NotNull PlayerItemHeldEvent event) {
        if (frozenPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @org.bukkit.event.EventHandler
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        pendingPlayers.remove(event.getPlayer().getUniqueId());
        frozenPlayers.remove(event.getPlayer().getUniqueId());
    }

    private record ResolvedAccount(@NotNull AccountModel account, boolean created) {
    }
}
