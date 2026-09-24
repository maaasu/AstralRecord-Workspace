package io.github.maaasu.astralRecord.feature.account.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.EventHandler;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.repository.AccountManagementRepository;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.event.PlayerJoinEventHandler;
import io.github.maaasu.astralRecord.feature.player.service.PlayerSessionTransitionGuard;
import io.github.maaasu.astralRecord.feature.user.model.SystemUser;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.AsyncTaskUtil;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.jetbrains.annotations.NotNull;

/** 確認済みの複製操作と、ローカルで使用中のアカウントの保存・再読込を管理します。 */
public final class AccountCloneCommand extends AstCommand implements EventHandler {
    private final AccountManagementRepository repository;
    private final LongSupplier nanoTime;
    private final Map<CommandSender, Confirmation> confirmations = new HashMap<>();
    private final Set<UUID> busyUsers = new HashSet<>();
    private final Set<UUID> frozenPlayers = new HashSet<>();

    /** 管理者専用の複製コマンドを初期化します。 */
    public AccountCloneCommand() {
        this(new AccountManagementRepository(), System::nanoTime);
    }

    /**
     * 通信境界と単調時計を指定して初期化します。
     * @param repository アカウント管理APIの通信境界
     * @param nanoTime ナノ秒単位の単調時計。確認期限判定に使用
     */
    AccountCloneCommand(AccountManagementRepository repository, LongSupplier nanoTime) {
        super("accountclone", "アカウントのプレイヤーデータを複製します。",
            "/account clone <sourceUUID|sourceName> <targetPlayer> [slot] または /account clone <sourcePlayer> <sourceName|slot> <targetPlayer> [slot]",
            false, UserPermission.ADMIN.getValue());
        this.repository = repository;
        this.nanoTime = nanoTime;
    }

    /**
     * 複製元と複製先を照会し、既存先がある場合だけ期限付き確認を提示します。
     * @param sender 実行者
     * @param args 複製元、複製先ユーザー、任意の複製先スロット
     */
    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!authorized(sender)) return;
        confirmations.remove(sender);
        confirmations.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= nanoTime.getAsLong());
        boolean extendedWithoutSlot = args.length == 3 && !args[2].matches("[0-9]{1,2}");
        boolean extended = args.length == 4 || extendedWithoutSlot;
        boolean ambiguousNumericTarget = args.length == 3 && args[2].matches("[0-9]{1,2}");
        boolean hasSlot = args.length == 4 || (args.length == 3 && !extendedWithoutSlot);
        if (args.length != 2 && args.length != 3 && args.length != 4) { sendUsage(sender); return; }
        String slotText = hasSlot ? args[args.length - 1] : null;
        if (hasSlot && !slotText.matches("[0-9]{1,2}")) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_7427.getId()));
            return;
        }
        Integer requestedSlot = hasSlot ? Integer.parseInt(slotText) : null;
        String targetName = args[extended ? 2 : 1];
        String sourceOwner = extended ? args[0] : null;
        String selector = extended ? args[1] : args[0];
        if (sourceOwner == null && selector.matches("[0-9]{1,2}") && !(sender instanceof Player)) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5305.getId()));
            return;
        }
        if (sourceOwner == null && selector.matches("[0-9]{1,2}") && sender instanceof Player player)
            sourceOwner = player.getName();
        String owner = sourceOwner;
        AstralRecord plugin = AstralRecord.getInstance();
        AsyncTaskUtil.supplyAsync(plugin, () -> {
            try {
                String resolvedOwner = owner;
                String resolvedSelector = selector;
                String resolvedTargetName = targetName;
                Integer resolvedSlot = requestedSlot;
                AccountModel source = null;
                UserModel targetUser = null;
                if (ambiguousNumericTarget) {
                    AccountModel shortFormSource = repository.resolve(null, resolvedSelector);
                    if (shortFormSource == null) shortFormSource = repository.resolve(resolvedSelector, resolvedOwner);
                    UserModel shortFormTarget = plugin.getUserService().getUserByMcid(resolvedTargetName);
                    if (shortFormSource != null && shortFormTarget != null) {
                        source = shortFormSource;
                        targetUser = shortFormTarget;
                    } else {
                        AccountModel playerOwnedSource = repository.resolve(args[1], args[0]);
                        UserModel playerOwnedTarget = plugin.getUserService().getUserByMcid(args[2]);
                        if (playerOwnedSource == null || playerOwnedTarget == null) return null;
                        source = playerOwnedSource;
                        targetUser = playerOwnedTarget;
                        resolvedOwner = args[0];
                        resolvedSelector = args[1];
                        resolvedTargetName = args[2];
                        resolvedSlot = null;
                    }
                }
                if (!ambiguousNumericTarget) {
                    source = resolvedOwner == null ? repository.resolve(null, resolvedSelector) : null;
                    if (source == null) source = repository.resolve(resolvedSelector, resolvedOwner);
                    targetUser = plugin.getUserService().getUserByMcid(resolvedTargetName);
                }
                if (source == null || targetUser == null) return null;
                var targetAccounts = plugin.getAccountService().getAccounts(targetUser.getUuid());
                int slot = resolvedSlot == null
                    ? firstAvailableSlot(targetAccounts.stream().map(AccountModel::getSlotIndex).toList())
                    : resolvedSlot;
                if (slot < 0) return new CloneRequest(source, targetUser.getUuid(), resolvedTargetName, slot, null);
                AccountModel target = targetAccounts.stream()
                    .filter(account -> account.getSlotIndex() == slot).findFirst().orElse(null);
                return new CloneRequest(source, targetUser.getUuid(), resolvedTargetName, slot, target);
            } catch (IOException exception) { throw new UncheckedIOException(exception); }
        }).whenComplete((request, failure) -> AsyncTaskUtil.runSync(plugin, () -> {
            if (failure != null) { fail(sender, failure); return; }
            if (request == null) {
                sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_7426.getId()));
                return;
            }
            if (request.slot() < 0) {
                sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_7432.getId()));
                return;
            }
            if (request.target() != null && request.source().getUuid().equals(request.target().getUuid())) {
                sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_7425.getId()));
                return;
            }
            if (request.target() != null) {
                String token = UUID.randomUUID().toString();
                confirmations.put(sender, new Confirmation(request, token, nanoTime.getAsLong() + 120_000_000_000L));
                sendInfo(sender, PlayerMsgResource.format(PlayerMsgId.P_7421.getId(), request.targetName(),
                    request.slot(), request.target().getAccountName(), token));
                return;
            }
            start(sender, request);
        }));
    }

    /**
     * 0〜99の範囲から最小の未使用スロットを選びます。
     * @param usedSlots 複製先ユーザーが現在使用しているスロット
     * @return 最小空きスロット。すべて使用中なら -1
     */
    private static int firstAvailableSlot(@NotNull List<Integer> usedSlots) {
        Set<Integer> occupied = new HashSet<>(usedSlots);
        for (int slot = 0; slot <= 99; slot++) {
            if (!occupied.contains(slot)) return slot;
        }
        return -1;
    }

    /**
     * 実行者本人の未使用・未失効トークンだけを消費し、APIで既存先UUIDを再照合します。
     * @param sender 確認する実行者
     * @param args 提示された確認トークン一つ
     */
    public void confirm(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!authorized(sender)) return;
        Confirmation confirmation = confirmations.get(sender);
        if (args.length != 1 || confirmation == null || confirmation.expiresAt() <= nanoTime.getAsLong()
            || !confirmation.token().equals(args[0])) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_7422.getId()));
            return;
        }
        confirmations.remove(sender);
        start(sender, confirmation.request());
    }

    /** 権限を毎回確認します。 */
    private boolean authorized(CommandSender sender) {
        if (sender instanceof Player player) {
            var ast = AstPlayerCache.get(player);
            if (ast == null || !ast.hasAdminPermission()) {
                sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5061.getId()));
                return false;
            }
        }
        return true;
    }

    /** 両ユーザーの遷移を排他し、使用中アカウントを保存してからAPI複製を実行します。 */
    private void start(CommandSender sender, CloneRequest request) {
        if (!authorized(sender)) return;
        AstralRecord plugin = AstralRecord.getInstance();
        Set<UUID> users = new HashSet<>(List.of(request.source().getUserId(), request.targetUser()));
        if (users.stream().anyMatch(busyUsers::contains)) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5341.getId()));
            return;
        }
        List<Player> guarded = new ArrayList<>();
        for (UUID user : users) {
            Player online = Bukkit.getPlayer(user);
            if (online == null) continue;
            if (AstPlayerCache.get(online) == null || !online.getItemOnCursor().getType().isAir()
                || !plugin.getPlayerSessionTransitionGuard().tryBegin(user, PlayerSessionTransitionGuard.Transition.ACCOUNT_SWITCH)) {
                release(guarded, users);
                sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_7428.getId()));
                return;
            }
            guarded.add(online);
        }
        busyUsers.addAll(users);
        List<Session> sessions = new ArrayList<>();
        try {
            for (Player online : guarded) {
                var ast = AstPlayerCache.get(online);
                UUID current = ast.getAccount().getUuid();
                if (!current.equals(request.source().getUuid())
                    && (request.target() == null || !current.equals(request.target().getUuid()))) continue;
                frozenPlayers.add(online.getUniqueId());
                if (plugin.getTradeService() != null) plugin.getTradeService().cancelRelatedSessions(online);
                if (plugin.getSkillBindGuiEventHandler() != null) plugin.getSkillBindGuiEventHandler().releaseForAccountSwitch(online);
                online.closeInventory();
                AccountModel original = ast.getAccount();
                var preparation = plugin.getPlayerJoinEventHandler().prepareAccountSwitch(online);
                if (preparation == null) throw new IllegalStateException("Account session disappeared before clone");
                sessions.add(new Session(online, online.getName(), original, preparation));
            }
        } catch (RuntimeException exception) {
            restore(sender, request, null, exception, sessions, guarded, users);
            return;
        }
        UUID actor = sender instanceof Player player ? player.getUniqueId() : SystemUser.INSTANCE.getUuid();
        AsyncTaskUtil.supplyAsync(plugin, () -> {
            for (Session session : sessions) plugin.getPlayerService().awaitQueuedSavesForAccountSwitch(
                session.preparation().accountId(), session.preparation().logoutSave());
            try {
                AccountModel result = repository.cloneAccount(request.source().getUuid(), request.targetUser(), request.slot(),
                    request.target() == null ? null : request.target().getUuid(), actor);
                plugin.getAccountService().getAccounts(request.targetUser());
                return result;
            } catch (IOException exception) { throw new UncheckedIOException(exception); }
        }).whenComplete((result, failure) -> AsyncTaskUtil.runSync(plugin,
            () -> restore(sender, request, result, failure, sessions, guarded, users)));
    }

    /** 成否にかかわらず切り離したオンラインセッションをDBの現在の選択先から復旧します。 */
    private void restore(CommandSender sender, CloneRequest request, AccountModel result, Throwable failure,
                         List<Session> sessions, List<Player> guarded, Set<UUID> users) {
        AstralRecord plugin = AstralRecord.getInstance();
        List<CompletableFuture<Boolean>> reloads = new ArrayList<>();
        for (Session session : sessions) {
            CompletableFuture<Boolean> reload = new CompletableFuture<>();
            reloads.add(reload);
            AsyncTaskUtil.supplyAsync(plugin, () -> {
                try {
                    plugin.getPlayerService().awaitQueuedSavesForAccountSwitch(session.original().getUuid(), session.preparation().logoutSave());
                    return repository.resolve(null, session.playerName());
                } catch (IOException exception) { throw new UncheckedIOException(exception); }
            }).whenComplete((selected, loadFailure) -> AsyncTaskUtil.runSync(plugin, () -> {
                if (!session.player().isOnline()) { reload.complete(true); return; }
                if (loadFailure != null || selected == null) {
                    session.player().kick(PlayerMsgResource.getComponent(PlayerMsgId.P_5339.getId()));
                    reload.complete(false);
                    return;
                }
                plugin.getPlayerJoinEventHandler().reloadAccount(session.player(), selected, loaded -> {
                    if (!loaded && session.player().isOnline())
                        session.player().kick(PlayerMsgResource.getComponent(PlayerMsgId.P_5339.getId()));
                    reload.complete(loaded);
                });
            }));
        }
        CompletableFuture.allOf(reloads.toArray(CompletableFuture[]::new)).whenComplete((ignored, reloadFailure) ->
            AsyncTaskUtil.runSync(plugin, () -> {
                release(guarded, users);
                if (failure != null || result == null) { fail(sender, failure); return; }
                sendSuccess(sender, PlayerMsgResource.format(PlayerMsgId.P_7423.getId(), request.source().getAccountName(),
                    request.targetName(), request.slot(), result.getAccountName(), result.getUuid()));
                if (reloads.stream().anyMatch(reload -> !Boolean.TRUE.equals(reload.getNow(false))))
                    sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_7429.getId()));
            }));
    }

    /** 操作終了時にこの処理が確保した遷移と凍結を解除します。 */
    private void release(List<Player> guarded, Set<UUID> users) {
        busyUsers.removeAll(users);
        for (Player player : guarded) {
            frozenPlayers.remove(player.getUniqueId());
            AstralRecord.getInstance().getPlayerSessionTransitionGuard().end(player.getUniqueId(),
                PlayerSessionTransitionGuard.Transition.ACCOUNT_SWITCH);
        }
    }

    /** 複製失敗を記録します。応答不明時は再送せず利用者へ確認を促します。 */
    private void fail(CommandSender sender, Throwable failure) {
        if (failure != null) Logger.error(LogId.E_5163, failure, sender.getName());
        Throwable cause = failure;
        while (cause != null && cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof AccountManagementRepository.AccountOperationException response) {
            if (response.code().equals("SOURCE_ACCOUNT_SESSION_ACTIVE") || response.code().equals("TARGET_ACCOUNT_SESSION_ACTIVE")) {
                sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_7430.getId()));
                return;
            }
            if (response.code().equals("TARGET_CHANGED") || response.code().equals("TARGET_ACCOUNT_EXISTS")) {
                sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_7431.getId()));
                return;
            }
        }
        sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_7424.getId()));
    }

    /**
     * 凍結中の移動を止めます。
     * @param event プレイヤー移動イベント
     */
    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (frozenPlayers.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }
    /**
     * 凍結中のインベントリ操作を止めます。
     * @param event インベントリクリックイベント
     */
    @org.bukkit.event.EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (frozenPlayers.contains(event.getWhoClicked().getUniqueId())) event.setCancelled(true);
    }
    /**
     * 凍結中のドラッグ操作を止めます。
     * @param event インベントリドラッグイベント
     */
    @org.bukkit.event.EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (frozenPlayers.contains(event.getWhoClicked().getUniqueId())) event.setCancelled(true);
    }
    /**
     * 凍結中のドロップを止めます。
     * @param event アイテムドロップイベント
     */
    @org.bukkit.event.EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (frozenPlayers.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }
    /**
     * 凍結中の拾得を止めます。
     * @param event アイテム拾得イベント
     */
    @org.bukkit.event.EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent event) {
        if (frozenPlayers.contains(event.getEntity().getUniqueId())) event.setCancelled(true);
    }
    /**
     * 凍結中のアイテム使用を止めます。
     * @param event プレイヤー操作イベント
     */
    @org.bukkit.event.EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (frozenPlayers.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }
    /**
     * 凍結中のエンティティ操作を止めます。
     * @param event エンティティ操作イベント
     */
    @org.bukkit.event.EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (frozenPlayers.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }
    /**
     * 凍結中の持替えを止めます。
     * @param event 両手持替えイベント
     */
    @org.bukkit.event.EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (frozenPlayers.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }
    /**
     * 凍結中のホットバー選択を止めます。
     * @param event ホットバー選択イベント
     */
    @org.bukkit.event.EventHandler(priority = EventPriority.LOWEST)
    public void onHeld(PlayerItemHeldEvent event) {
        if (frozenPlayers.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }
    /**
     * セッションが切り離されている間の被ダメージを止めます。
     * @param event ダメージイベント
     */
    @org.bukkit.event.EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (frozenPlayers.contains(event.getEntity().getUniqueId())) event.setCancelled(true);
    }
    /**
     * 退出した実行者の確認トークンを破棄し、遷移所有権は処理完了時まで保持します。
     * @param event 退出イベント
     */
    @org.bukkit.event.EventHandler
    public void onQuit(PlayerQuitEvent event) { confirmations.remove(event.getPlayer()); }

    private record CloneRequest(AccountModel source, UUID targetUser, String targetName, int slot, AccountModel target) { }
    private record Confirmation(CloneRequest request, String token, long expiresAt) { }
    private record Session(Player player, String playerName, AccountModel original, PlayerJoinEventHandler.AccountSwitchPreparation preparation) { }
}
