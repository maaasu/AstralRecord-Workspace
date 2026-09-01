package io.github.maaasu.astralRecord.feature.account.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountDeleteResult;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerService;
import io.github.maaasu.astralRecord.feature.user.model.SystemUser;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.util.AsyncTaskUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
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
import java.util.concurrent.ConcurrentHashMap;

/** /account delete の実行と、オンライン対象の安全な再ロードを扱います。 */
public final class AccountDeleteCommand extends AstCommand implements io.github.maaasu.astralRecord.core.event.EventHandler {
    private final Set<UUID> pendingAccountIds = ConcurrentHashMap.newKeySet();
    private final Set<UUID> frozenPlayers = ConcurrentHashMap.newKeySet();

    public AccountDeleteCommand() {
        super("accountdelete", "アカウントを削除します。", "/account delete (<player> <slot>|<accountUuid>)", false,
            UserPermission.ADMIN.getValue());
    }

    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (sender instanceof Player player) {
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer == null || !astPlayer.hasAdminPermission()) {
                sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5061.getId()));
                return;
            }
        }
        if (args.length != 1 && args.length != 2) {
            sendUsage(sender);
            return;
        }

        AccountService accountService = AstralRecord.getInstance().getAccountService();
        PlayerService playerService = AstralRecord.getInstance().getPlayerService();
        UserService userService = AstralRecord.getInstance().getUserService();
        if (accountService == null || playerService == null || userService == null) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5330.getId()));
            return;
        }

        TargetRequest request = resolveRequest(sender, args);
        if (request == null) {
            return;
        }

        AsyncTaskUtil.supplyAsync(AstralRecord.getInstance(), () -> resolveAccount(request, accountService, userService))
            .whenComplete((account, resolveFailure) -> AsyncTaskUtil.runSync(AstralRecord.getInstance(), () -> {
                if (resolveFailure != null) {
                    Logger.error(LogId.E_5150, resolveFailure, request.label());
                    sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5062.getId()));
                    return;
                }
                if (account == null) {
                    sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_5333.getId(), request.label()));
                    return;
                }
                if (!pendingAccountIds.add(account.getUuid())) {
                    sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5338.getId()));
                    return;
                }
                Player onlineTarget = findOnlineUser(account.getUserId());
                boolean reloadRequired = onlineTarget != null && isUsingAccount(onlineTarget, account.getUuid());
                if (reloadRequired) {
                    freezeAndDiscard(onlineTarget, playerService);
                }
                deleteAsync(sender, account, onlineTarget, reloadRequired, accountService, playerService);
            }));
    }

    private void deleteAsync(
        @NotNull CommandSender sender,
        @NotNull AccountModel account,
        @Nullable Player onlineTarget,
        boolean reloadRequired,
        @NotNull AccountService accountService,
        @NotNull PlayerService playerService
    ) {
        UUID deletedBy = sender instanceof Player player ? player.getUniqueId() : SystemUser.INSTANCE.getUuid();
        AsyncTaskUtil.supplyAsync(AstralRecord.getInstance(), () -> {
            if (reloadRequired) {
                playerService.awaitQueuedSavesForAccountDeletion(account.getUuid());
            }
            AccountDeleteResult result = accountService.deleteAccount(account.getUuid(), deletedBy);
            PlayerService.PlayerJoinData joinData = !reloadRequired || onlineTarget == null || !onlineTarget.isOnline()
                ? null
                : playerService.loadPlayerJoinData(onlineTarget.getUniqueId(), onlineTarget.getName());
            return new DeleteOperationResult(result, joinData, reloadRequired);
        }).whenComplete((result, failure) -> AsyncTaskUtil.runSync(AstralRecord.getInstance(), () -> {
            pendingAccountIds.remove(account.getUuid());
            if (failure != null || result == null || result.result() == null) {
                if (failure != null) {
                    Logger.error(LogId.E_5160, failure, account.getUuid());
                }
                if (result != null && result.reloadRequired()) {
                    recoverOrKick(onlineTarget, playerService);
                }
                sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5062.getId()));
                return;
            }
            if (onlineTarget != null && onlineTarget.isOnline()) {
                if (result.reloadRequired()) {
                    if (!applyReload(onlineTarget, result.joinData(), playerService)) {
                        onlineTarget.kick(PlayerMsgResource.getComponent(PlayerMsgId.P_5339.getId()));
                    } else {
                        sendDeletionMessage(onlineTarget, result.result().getDeletedSlotIndex());
                    }
                    frozenPlayers.remove(onlineTarget.getUniqueId());
                } else {
                    sendDeletionMessage(onlineTarget, result.result().getDeletedSlotIndex());
                }
            }
            sendSuccess(sender, PlayerMsgResource.format(
                PlayerMsgId.P_5336.getId(),
                result.result().getDeletedSlotIndex(),
                account.getAccountName()
            ));
        }));
    }

    private boolean applyReload(
        @NotNull Player player,
        @Nullable PlayerService.PlayerJoinData joinData,
        @NotNull PlayerService playerService
    ) {
        if (joinData == null) {
            return false;
        }
        try {
            return playerService.applyPlayerJoin(player, joinData);
        } catch (RuntimeException exception) {
            Logger.error(LogId.E_5161, exception, player.getUniqueId());
            playerService.discardPlayerJoinInventoryState(joinData.inventoryState());
            return false;
        }
    }

    private void recoverOrKick(@Nullable Player player, @NotNull PlayerService playerService) {
        if (player == null || !player.isOnline()) {
            return;
        }
        AsyncTaskUtil.supplyAsync(AstralRecord.getInstance(), () ->
            playerService.loadPlayerJoinData(player.getUniqueId(), player.getName())
        ).whenComplete((joinData, ignored) -> AsyncTaskUtil.runSync(AstralRecord.getInstance(), () -> {
            if (!player.isOnline() || !applyReload(player, joinData, playerService)) {
                player.kick(PlayerMsgResource.getComponent(PlayerMsgId.P_5339.getId()));
            }
            frozenPlayers.remove(player.getUniqueId());
        }));
    }

    private @Nullable AccountModel resolveAccount(
        @NotNull TargetRequest request,
        @NotNull AccountService accountService,
        @NotNull UserService userService
    ) {
        if (request.accountId() != null) {
            return accountService.getAccount(request.accountId());
        }
        String playerName = request.playerName();
        if (playerName == null) {
            return null;
        }
        UserModel user = userService.getUserByMcid(playerName);
        if (user == null) {
            return null;
        }
        return accountService.getAccounts(user.getUuid()).stream()
            .filter(account -> account.getSlotIndex() == request.slotIndex())
            .findFirst()
            .orElse(null);
    }

    private @Nullable TargetRequest resolveRequest(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 1) {
            try {
                return TargetRequest.forAccount(UUID.fromString(args[0]));
            } catch (IllegalArgumentException ignored) {
                sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_5333.getId(), args[0]));
                return null;
            }
        }
        try {
            int slot = Integer.parseInt(args[1]);
            if (slot < 0) {
                throw new NumberFormatException();
            }
            return TargetRequest.forSlot(args[0], slot);
        } catch (NumberFormatException ignored) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5337.getId()));
            return null;
        }
    }

    private @Nullable Player findOnlineUser(@NotNull UUID userId) {
        return Bukkit.getOnlinePlayers().stream()
            .filter(player -> player.getUniqueId().equals(userId))
            .findFirst()
            .orElse(null);
    }

    private boolean isUsingAccount(@NotNull Player player, @NotNull UUID accountId) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        return astPlayer != null && astPlayer.getAccount().getUuid().equals(accountId);
    }

    private void freezeAndDiscard(@NotNull Player player, @NotNull PlayerService playerService) {
        frozenPlayers.add(player.getUniqueId());
        var tradeService = AstralRecord.getInstance().getTradeService();
        if (tradeService != null) {
            tradeService.cancelTrade(player);
        }
        player.closeInventory();
        player.getInventory().clear();
        player.setItemOnCursor(null);
        playerService.discardOnlineSessionForAccountDeletion(player);
    }

    private void sendDeletionMessage(@NotNull Player player, int deletedSlotIndex) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer != null) {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5335, deletedSlotIndex);
        }
    }

    @EventHandler(ignoreCancelled = true)
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

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && frozenPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && frozenPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerDropItem(@NotNull PlayerDropItemEvent event) {
        if (frozenPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityPickupItem(@NotNull EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && frozenPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerInteract(@NotNull PlayerInteractEvent event) {
        if (frozenPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerInteractEntity(@NotNull PlayerInteractEntityEvent event) {
        if (frozenPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerSwapHandItems(@NotNull PlayerSwapHandItemsEvent event) {
        if (frozenPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerItemHeld(@NotNull PlayerItemHeldEvent event) {
        if (frozenPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        frozenPlayers.remove(event.getPlayer().getUniqueId());
    }

    private record TargetRequest(@Nullable UUID accountId, @Nullable String playerName, int slotIndex, @NotNull String label) {
        private static TargetRequest forAccount(@NotNull UUID accountId) {
            return new TargetRequest(accountId, null, -1, accountId.toString());
        }

        private static TargetRequest forSlot(@NotNull String playerName, int slotIndex) {
            return new TargetRequest(null, playerName, slotIndex, playerName + " slot " + slotIndex);
        }
    }

    private record DeleteOperationResult(
        @Nullable AccountDeleteResult result,
        @Nullable PlayerService.PlayerJoinData joinData,
        boolean reloadRequired
    ) {
    }
}
