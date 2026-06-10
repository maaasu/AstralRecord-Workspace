package io.github.maaasu.astralRecord.feature.player.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.loginbonus.service.LoginBonusService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerService;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * プレイヤーのログイン・ログアウト処理を行うイベントハンドラー。
 * <p>
 * ログイン時は外部データ取得を非同期で行い、Bukkit API 操作だけをメインスレッドに戻して
 * {@link AstPlayer} をキャッシュに登録します。OP権限の付与・剥奪は
 * {@link AstPlayer#applyPermission(io.github.maaasu.astralRecord.feature.user.model.UserModel)} が行います。
 * ログアウト時は {@link PlayerService#onPlayerQuit(org.bukkit.entity.Player)} でキャッシュを削除します。
 */
public class PlayerJoinEventHandler extends AbstractEventHandler {

    private static final long NANOS_PER_TICK = 50_000_000L;
    private static final long JOIN_START_SPACING_TICKS = 20L;
    private static final long JOIN_STEP_DELAY_TICKS = 10L;

    private final PlayerService playerService;
    private final LoginBonusService loginBonusService;
    private final AstralRecord plugin;
    private final Set<UUID> loadingPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Location> movementLocks = new ConcurrentHashMap<>();
    private final AtomicLong nextJoinStartNanos = new AtomicLong();

    public PlayerJoinEventHandler(
        AstralRecord plugin,
        PlayerService playerService,
        LoginBonusService loginBonusService
    ) {
        this.plugin = plugin;
        this.playerService = playerService;
        this.loginBonusService = loginBonusService;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();

        startJoinLoading(player);
        scheduleAsync(() -> loadUserStep(playerUuid, playerName), reserveJoinStartDelayTicks());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        UUID playerUuid = event.getPlayer().getUniqueId();
        if (!loadingPlayers.contains(playerUuid)) {
            return;
        }

        Location lockLocation = movementLocks.get(playerUuid);
        Location to = event.getTo();
        if (lockLocation == null || to == null) {
            return;
        }
        if (event instanceof PlayerTeleportEvent) {
            movementLocks.put(playerUuid, to.clone());
            return;
        }
        if (!hasPositionChanged(lockLocation, to)) {
            return;
        }

        Location corrected = lockLocation.clone();
        corrected.setYaw(to.getYaw());
        corrected.setPitch(to.getPitch());
        event.setTo(corrected);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        var player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();

        runSafely(() -> playerService.onPlayerQuit(player), LogId.E_5070, playerName);
        finishJoinLoading(playerUuid, false);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
            runSafely(() -> playerService.recordLogoutHistory(playerUuid, playerName), LogId.E_5070, playerName)
        );
    }

    private void loadUserStep(UUID playerUuid, String playerName) {
        runJoinStep(playerUuid, playerName, () -> {
            if (!isJoinLoading(playerUuid)) {
                return;
            }

            UserModel user = playerService.loadPlayerJoinUser(playerUuid, playerName);
            if (user == null) {
                finishJoinLoading(playerUuid, false);
                return;
            }

            scheduleAsync(() -> loadAccountStep(playerUuid, playerName, user), JOIN_STEP_DELAY_TICKS);
        });
    }

    private void loadAccountStep(UUID playerUuid, String playerName, UserModel user) {
        runJoinStep(playerUuid, playerName, () -> {
            if (!isJoinLoading(playerUuid)) {
                return;
            }

            AccountModel account = playerService.loadPlayerJoinAccount(user, playerName);
            if (account == null) {
                finishJoinLoading(playerUuid, false);
                return;
            }

            scheduleAsync(() -> loadInventoryStep(playerUuid, playerName, user, account), JOIN_STEP_DELAY_TICKS);
        });
    }

    private void loadInventoryStep(UUID playerUuid, String playerName, UserModel user, AccountModel account) {
        runJoinStep(playerUuid, playerName, () -> {
            if (!isJoinLoading(playerUuid)) {
                return;
            }

            playerService.loadPlayerJoinInventoryState(account);
            plugin.getServer().getScheduler().runTask(plugin, () ->
                applyJoinData(playerUuid, playerName, new PlayerService.PlayerJoinData(user, account))
            );
        });
    }

    private void applyJoinData(UUID playerUuid, String playerName, PlayerService.PlayerJoinData joinData) {
        runSafely(() -> {
            Player player = plugin.getServer().getPlayer(playerUuid);
            if (player == null || !player.isOnline() || !isJoinLoading(playerUuid)) {
                finishJoinLoading(playerUuid, false);
                return;
            }

            playerService.applyPlayerJoin(player, joinData);
            loginBonusService.openAfterDataLoaded(player);
            finishJoinLoading(playerUuid, true);
            scheduleAsync(
                () -> runSafely(() -> playerService.recordLoginHistory(playerUuid, playerName), LogId.E_5070, playerName),
                JOIN_STEP_DELAY_TICKS
            );
        }, LogId.E_5070, playerName);
    }

    private void startJoinLoading(Player player) {
        UUID playerUuid = player.getUniqueId();
        loadingPlayers.add(playerUuid);
        movementLocks.put(playerUuid, player.getLocation().clone());
        PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5071);
    }

    private void finishJoinLoading(UUID playerUuid, boolean notifyComplete) {
        if (!Bukkit.isPrimaryThread()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> finishJoinLoading(playerUuid, notifyComplete));
            return;
        }

        loadingPlayers.remove(playerUuid);
        movementLocks.remove(playerUuid);

        Player player = plugin.getServer().getPlayer(playerUuid);
        if (notifyComplete && player != null && player.isOnline()) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5072);
        }
    }

    private void runJoinStep(UUID playerUuid, String playerName, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            Logger.log(LogId.E_5070, e, playerName);
            plugin.getServer().getScheduler().runTask(plugin, () -> finishJoinLoading(playerUuid, false));
        }
    }

    private void scheduleAsync(Runnable task, long delayTicks) {
        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, task, Math.max(0L, delayTicks));
    }

    private boolean isJoinLoading(UUID playerUuid) {
        return loadingPlayers.contains(playerUuid);
    }

    private long reserveJoinStartDelayTicks() {
        long now = System.nanoTime();
        long spacingNanos = JOIN_START_SPACING_TICKS * NANOS_PER_TICK;
        while (true) {
            long current = nextJoinStartNanos.get();
            long scheduled = Math.max(now, current);
            if (nextJoinStartNanos.compareAndSet(current, scheduled + spacingNanos)) {
                long delayNanos = scheduled - now;
                return (delayNanos + NANOS_PER_TICK - 1L) / NANOS_PER_TICK;
            }
        }
    }

    private boolean hasPositionChanged(Location from, Location to) {
        if (from.getWorld() != to.getWorld()) {
            return true;
        }
        return from.getX() != to.getX()
            || from.getY() != to.getY()
            || from.getZ() != to.getZ();
    }
}


