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
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerService;
import io.github.maaasu.astralRecord.feature.user.model.SystemUser;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.AsyncTaskUtil;
import org.bukkit.Bukkit;
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

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** /account switch の実行と、対象プレイヤーの安全な再ロードを扱います。 */
public final class AccountSwitchCommand extends AstCommand implements EventHandler {
    private final Set<String> pendingTargets = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<UUID> frozenPlayers = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public AccountSwitchCommand() {
        super(
            "accountswitch",
            "対象プレイヤーのアカウントを切り替えます。",
            "/account switch <player> <slot>",
            false,
            UserPermission.ADMIN.getValue()
        );
    }

    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!hasAdminPermission(sender)) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5061.getId()));
            return;
        }
        if (args.length != 2) {
            sendUsage(sender);
            return;
        }

        Integer slotIndex = parseSlotIndex(args[1]);
        if (slotIndex == null) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5337.getId()));
            return;
        }

        String targetName = args[0];
        AstralRecord plugin = AstralRecord.getInstance();
        AccountService accountService = plugin.getAccountService();
        PlayerService playerService = plugin.getPlayerService();
        PlayerJoinEventHandler playerJoinEventHandler = plugin.getPlayerJoinEventHandler();
        UserService userService = plugin.getUserService();
        Player target = Bukkit.getPlayerExact(targetName);
        AstPlayer targetAstPlayer = target == null ? null : AstPlayerCache.get(target);

        if (target != null && targetAstPlayer == null) {
            sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_5814.getId(), targetName));
            return;
        }
        if (target != null && hasCursorItem(target)) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5344.getId()));
            return;
        }
        if (accountService == null
            || (target != null && (playerService == null || playerJoinEventHandler == null))
            || (target == null && userService == null)) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5340.getId()));
            return;
        }

        String pendingKey = targetName.toLowerCase(Locale.ROOT);
        if (!pendingTargets.add(pendingKey)) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5341.getId()));
            return;
        }

        UUID updatedBy = getUpdatedBy(sender);
        TargetRequest request = new TargetRequest(
            targetName,
            targetAstPlayer == null ? null : targetAstPlayer.getUser().getUuid(),
            targetAstPlayer == null ? null : targetAstPlayer.getAccount().getUuid(),
            target
        );
        AsyncTaskUtil.supplyAsync(plugin, () -> resolveOrCreateAccount(
            request,
            slotIndex,
            updatedBy,
            accountService,
            userService
        )).whenComplete((resolved, failure) -> AsyncTaskUtil.runSync(plugin, () -> {
            if (failure != null || resolved == null) {
                fail(sender, pendingKey, target, targetName, failure);
                return;
            }

            Player onlineTarget = resolved.onlinePlayer();
            if (onlineTarget != null && onlineTarget.isOnline() && hasCursorItem(onlineTarget)) {
                pendingTargets.remove(pendingKey);
                frozenPlayers.remove(onlineTarget.getUniqueId());
                sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5344.getId()));
                return;
            }

            UUID currentAccountId = resolveCurrentAccountId(resolved);
            if (resolved.account().getUuid().equals(currentAccountId)) {
                completeSuccess(sender, pendingKey, resolved);
                return;
            }

            if (onlineTarget != null && onlineTarget.isOnline()) {
                freeze(onlineTarget);
                PlayerJoinEventHandler.AccountSwitchPreparation preparation =
                    playerJoinEventHandler.prepareAccountSwitch(onlineTarget);
                if (preparation == null) {
                    frozenPlayers.remove(onlineTarget.getUniqueId());
                    failWithCleanup(
                        sender,
                        pendingKey,
                        resolved,
                        updatedBy,
                        accountService,
                        null
                    );
                    return;
                }
                switchAfterSessionSave(
                    sender,
                    pendingKey,
                    resolved,
                    updatedBy,
                    preparation,
                    accountService,
                    playerService,
                    playerJoinEventHandler
                );
                return;
            }

            switchAfterSessionSave(
                sender,
                pendingKey,
                resolved,
                updatedBy,
                null,
                accountService,
                playerService,
                playerJoinEventHandler
            );
        }));
    }

    private @Nullable ResolvedAccount resolveOrCreateAccount(
        @NotNull TargetRequest request,
        int slotIndex,
        @NotNull UUID updatedBy,
        @NotNull AccountService accountService,
        @Nullable UserService userService
    ) {
        UUID userId = request.knownUserId();
        UUID currentAccountId = request.knownCurrentAccountId();
        String accountName = request.targetName();
        if (userId == null) {
            if (userService == null) {
                return null;
            }
            UserModel user = userService.getUserByMcid(request.targetName());
            if (user == null) {
                return null;
            }
            userId = user.getUuid();
            currentAccountId = user.getAccountId();
            accountName = user.getMcid();
        }

        UUID resolvedUserId = userId;
        UUID resolvedCurrentAccountId = currentAccountId;
        List<AccountModel> accounts = accountService.getAccounts(resolvedUserId);
        AccountModel current = accounts.stream()
            .filter(account -> resolvedCurrentAccountId != null
                && account.getUuid().equals(resolvedCurrentAccountId))
            .findFirst()
            .orElseGet(() -> accounts.stream().filter(AccountModel::isActive).findFirst().orElse(null));
        AccountModel target = accounts.stream()
            .filter(account -> account.getSlotIndex() == slotIndex)
            .findFirst()
            .orElse(null);
        boolean created = false;
        if (target == null) {
            target = accountService.createAccount(resolvedUserId, accountName, slotIndex, updatedBy);
            created = true;
        }
        return new ResolvedAccount(
            resolvedUserId,
            request.targetName(),
            request.onlinePlayer(),
            current == null ? resolvedCurrentAccountId : current.getUuid(),
            target,
            created
        );
    }

    private void switchAfterSessionSave(
        @NotNull CommandSender sender,
        @NotNull String pendingKey,
        @NotNull ResolvedAccount resolved,
        @NotNull UUID updatedBy,
        @Nullable PlayerJoinEventHandler.AccountSwitchPreparation preparation,
        @NotNull AccountService accountService,
        @Nullable PlayerService playerService,
        @Nullable PlayerJoinEventHandler playerJoinEventHandler
    ) {
        AstralRecord plugin = AstralRecord.getInstance();
        UUID previousAccountId = preparation == null
            ? resolved.currentAccountId()
            : preparation.accountId();
        AsyncTaskUtil.supplyAsync(plugin, () -> {
            if (preparation != null && playerService != null) {
                playerService.awaitQueuedSavesForAccountSwitch(
                    preparation.accountId(),
                    preparation.logoutSave()
                );
            }
            return accountService.switchAccount(
                resolved.userId(),
                resolved.account().getUuid(),
                updatedBy
            );
        }).whenComplete((switched, failure) -> AsyncTaskUtil.runSync(plugin, () -> {
            Player onlineTarget = resolved.onlinePlayer();
            if (failure != null || switched == null) {
                if (previousAccountId != null) {
                    recoverPreviousAccount(
                        sender,
                        pendingKey,
                        resolved,
                        previousAccountId,
                        updatedBy,
                        accountService,
                        playerJoinEventHandler,
                        failure
                    );
                } else {
                    failWithCleanup(
                        sender,
                        pendingKey,
                        resolved,
                        updatedBy,
                        accountService,
                        failure
                    );
                }
                return;
            }

            if (preparation != null && onlineTarget != null && onlineTarget.isOnline()
                && playerJoinEventHandler != null) {
                playerJoinEventHandler.reloadAccount(onlineTarget, switched, reloaded -> {
                    if (reloaded) {
                        completeSuccess(sender, pendingKey, resolved);
                        return;
                    }
                    if (previousAccountId == null) {
                        failAndKick(sender, pendingKey, resolved, null);
                        return;
                    }
                    recoverPreviousAccount(
                        sender,
                        pendingKey,
                        resolved,
                        previousAccountId,
                        updatedBy,
                        accountService,
                        playerJoinEventHandler,
                        null
                    );
                });
                return;
            }

            completeSuccess(sender, pendingKey, resolved);
        }));
    }

    private void recoverPreviousAccount(
        @NotNull CommandSender sender,
        @NotNull String pendingKey,
        @NotNull ResolvedAccount resolved,
        @NotNull UUID previousAccountId,
        @NotNull UUID updatedBy,
        @NotNull AccountService accountService,
        @Nullable PlayerJoinEventHandler playerJoinEventHandler,
        @Nullable Throwable originalFailure
    ) {
        AstralRecord plugin = AstralRecord.getInstance();
        AsyncTaskUtil.supplyAsync(plugin, () -> accountService.switchAccount(
            resolved.userId(),
            previousAccountId,
            updatedBy
        )).whenComplete((previous, failure) -> AsyncTaskUtil.runSync(plugin, () -> {
            Player target = resolved.onlinePlayer();
            if (failure != null || previous == null) {
                failAndKick(sender, pendingKey, resolved, failure != null ? failure : originalFailure);
                return;
            }
            if (target != null && target.isOnline() && playerJoinEventHandler != null) {
                playerJoinEventHandler.reloadAccount(target, previous, recovered -> {
                    if (recovered) {
                        cleanupCreatedAccount(
                            resolved,
                            updatedBy,
                            accountService,
                            () -> finishRecovery(sender, pendingKey, target)
                        );
                    } else {
                        failAndKick(sender, pendingKey, resolved, null);
                    }
                });
                return;
            }
            if (target != null && target.isOnline()) {
                failAndKick(sender, pendingKey, resolved, null);
                return;
            }
            cleanupCreatedAccount(
                resolved,
                updatedBy,
                accountService,
                () -> finishRecovery(sender, pendingKey, null)
            );
        }));
    }

    private void failWithCleanup(
        @NotNull CommandSender sender,
        @NotNull String pendingKey,
        @NotNull ResolvedAccount resolved,
        @NotNull UUID updatedBy,
        @NotNull AccountService accountService,
        @Nullable Throwable failure
    ) {
        cleanupCreatedAccount(
            resolved,
            updatedBy,
            accountService,
            () -> fail(sender, pendingKey, resolved.onlinePlayer(), resolved.targetName(), failure)
        );
    }

    private void cleanupCreatedAccount(
        @NotNull ResolvedAccount resolved,
        @NotNull UUID deletedBy,
        @NotNull AccountService accountService,
        @NotNull Runnable completionListener
    ) {
        if (!resolved.created()) {
            completionListener.run();
            return;
        }

        AstralRecord plugin = AstralRecord.getInstance();
        AsyncTaskUtil.supplyAsync(plugin, () -> accountService.deleteAccount(
            resolved.account().getUuid(),
            deletedBy
        )).whenComplete((deleted, failure) -> AsyncTaskUtil.runSync(plugin, () -> {
            if (failure != null || deleted == null) {
                Throwable cleanupFailure = failure != null
                    ? failure
                    : new IllegalStateException("Created account cleanup returned no result");
                Logger.error(LogId.E_5160, cleanupFailure, resolved.account().getUuid());
                completionListener.run();
                return;
            }
            completionListener.run();
        }));
    }

    private void completeSuccess(
        @NotNull CommandSender sender,
        @NotNull String pendingKey,
        @NotNull ResolvedAccount resolved
    ) {
        pendingTargets.remove(pendingKey);
        Player target = resolved.onlinePlayer();
        if (target != null) {
            frozenPlayers.remove(target.getUniqueId());
        }
        String messageId = resolved.created() ? PlayerMsgId.P_5346.getId() : PlayerMsgId.P_5345.getId();
        sendSuccess(sender, PlayerMsgResource.format(
            messageId,
            resolved.targetName(),
            resolved.account().getSlotIndex(),
            resolved.account().getAccountName()
        ));
        if (target != null && target.isOnline() && sender != target) {
            AstPlayer targetAstPlayer = AstPlayerCache.get(target);
            if (targetAstPlayer != null) {
                PlayerMessageService.getInstance().send(
                    targetAstPlayer,
                    resolved.created() ? PlayerMsgId.P_5343 : PlayerMsgId.P_5342,
                    resolved.account().getSlotIndex(),
                    resolved.account().getAccountName()
                );
            }
        }
    }

    private void finishRecovery(
        @NotNull CommandSender sender,
        @NotNull String pendingKey,
        @Nullable Player target
    ) {
        pendingTargets.remove(pendingKey);
        if (target != null) {
            frozenPlayers.remove(target.getUniqueId());
        }
        sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5062.getId()));
    }

    private void fail(
        @NotNull CommandSender sender,
        @NotNull String pendingKey,
        @Nullable Player target,
        @NotNull String targetName,
        @Nullable Throwable failure
    ) {
        if (failure != null) {
            Logger.error(LogId.E_5153, failure, targetName);
        }
        pendingTargets.remove(pendingKey);
        if (target != null) {
            frozenPlayers.remove(target.getUniqueId());
        }
        sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5062.getId()));
    }

    private void failAndKick(
        @NotNull CommandSender sender,
        @NotNull String pendingKey,
        @NotNull ResolvedAccount resolved,
        @Nullable Throwable failure
    ) {
        if (failure != null) {
            Logger.error(LogId.E_5153, failure, resolved.targetName());
        }
        pendingTargets.remove(pendingKey);
        Player target = resolved.onlinePlayer();
        if (target != null) {
            frozenPlayers.remove(target.getUniqueId());
            if (target.isOnline()) {
                target.kick(PlayerMsgResource.getComponent(PlayerMsgId.P_5339.getId()));
                return;
            }
        }
        sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5062.getId()));
    }

    private boolean hasAdminPermission(@NotNull CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        return astPlayer != null && astPlayer.hasAdminPermission();
    }

    private @Nullable Integer parseSlotIndex(@NotNull String value) {
        try {
            int slotIndex = Integer.parseInt(value);
            return slotIndex < 0 ? null : slotIndex;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean hasCursorItem(@NotNull Player player) {
        var cursor = player.getItemOnCursor();
        return cursor != null && !cursor.getType().isAir();
    }

    private @Nullable UUID resolveCurrentAccountId(@NotNull ResolvedAccount resolved) {
        Player target = resolved.onlinePlayer();
        if (target != null && target.isOnline()) {
            AstPlayer astPlayer = AstPlayerCache.get(target);
            if (astPlayer != null) {
                return astPlayer.getAccount().getUuid();
            }
        }
        return resolved.currentAccountId();
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

    private UUID getUpdatedBy(@NotNull CommandSender sender) {
        if (sender instanceof Player player) {
            return player.getUniqueId();
        }
        return SystemUser.INSTANCE.getUuid();
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
        frozenPlayers.remove(event.getPlayer().getUniqueId());
    }

    private record TargetRequest(
        @NotNull String targetName,
        @Nullable UUID knownUserId,
        @Nullable UUID knownCurrentAccountId,
        @Nullable Player onlinePlayer
    ) {
    }

    private record ResolvedAccount(
        @NotNull UUID userId,
        @NotNull String targetName,
        @Nullable Player onlinePlayer,
        @Nullable UUID currentAccountId,
        @NotNull AccountModel account,
        boolean created
    ) {
    }
}
