package io.github.maaasu.astralRecord.feature.player.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.loginbonus.service.LoginBonusService;
import io.github.maaasu.astralRecord.feature.mail.service.MailService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerService;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePlayerState;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import net.kyori.adventure.title.Title;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
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
    private static final long JOIN_LOADING_TITLE_INTERVAL_TICKS = 100L;
    private static final long JOIN_LOADING_RETRY_MILLIS = 500L;

    private final PlayerService playerService;
    private final SkillTreeService skillTreeService;
    private final LoginBonusService loginBonusService;
    private final MailService mailService;
    private final AstralRecord plugin;
    private final Set<UUID> loadingPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, LoadingControl> loadingControls = new ConcurrentHashMap<>();
    private final AtomicLong nextJoinStartNanos = new AtomicLong();

    public PlayerJoinEventHandler(
        AstralRecord plugin,
        PlayerService playerService,
        SkillTreeService skillTreeService,
        LoginBonusService loginBonusService,
        MailService mailService
    ) {
        this.plugin = plugin;
        this.playerService = playerService;
        this.skillTreeService = skillTreeService;
        this.loginBonusService = loginBonusService;
        this.mailService = mailService;
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

        LoadingControl loadingControl = loadingControls.get(playerUuid);
        Location lockLocation = loadingControl == null ? null : loadingControl.lockLocation();
        Location to = event.getTo();
        if (lockLocation == null || to == null) {
            return;
        }
        if (event instanceof PlayerTeleportEvent) {
            loadingControls.put(playerUuid, loadingControl.withLockLocation(to.clone()));
            return;
        }
        if (!hasPositionChanged(lockLocation, to) && !hasViewChanged(lockLocation, to)) {
            return;
        }

        event.setTo(lockLocation.clone());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && loadingPlayers.contains(player.getUniqueId())) {
            event.setDamage(0.0D);
            event.setCancelled(true);
            return;
        }
        if (event.getEntity() instanceof Player player && loadingPlayers.contains(player.getUniqueId())) {
            event.setDamage(0.0D);
            event.setCancelled(true);
        }
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
            SkillTreePlayerState skillTreeState = loadInitialSkillTreeState(playerUuid, playerName, account.getUuid());
            plugin.getServer().getScheduler().runTask(plugin, () ->
                applyJoinData(playerUuid, playerName, new PlayerService.PlayerJoinData(user, account), skillTreeState)
            );
        });
    }

    private void applyJoinData(
        UUID playerUuid,
        String playerName,
        PlayerService.PlayerJoinData joinData,
        @Nullable SkillTreePlayerState skillTreeState
    ) {
        runSafely(() -> {
            Player player = plugin.getServer().getPlayer(playerUuid);
            if (player == null || !player.isOnline() || !isJoinLoading(playerUuid)) {
                finishJoinLoading(playerUuid, false);
                return;
            }
            if (skillTreeState == null) {
                return;
            }

            skillTreeService.applyInitialPlayerState(skillTreeState);
            playerService.applyPlayerJoin(player, joinData);
            loginBonusService.openAfterDataLoaded(player);
            finishJoinLoading(playerUuid, true);
            notifyUnreadMailAsync(playerUuid, playerName);
            scheduleAsync(
                () -> runSafely(() -> playerService.recordLoginHistory(playerUuid, playerName), LogId.E_5070, playerName),
                JOIN_STEP_DELAY_TICKS
            );
        }, LogId.E_5070, playerName);
    }

    private void notifyUnreadMailAsync(UUID playerUuid, String playerName) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
            runSafely(() -> {
                int unreadCount = mailService.countUnread(playerUuid);
                if (unreadCount <= 0) {
                    return;
                }
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    Player player = plugin.getServer().getPlayer(playerUuid);
                    if (player != null && player.isOnline()) {
                        PlayerMessageService.getInstance().sendClickable(
                            player,
                            PlayerMsgId.P_5624,
                            "/menu mail",
                            unreadCount
                        );
                    }
                });
            }, LogId.E_5600, playerName)
        );
    }

    private void startJoinLoading(Player player) {
        UUID playerUuid = player.getUniqueId();
        loadingPlayers.add(playerUuid);
        LoadingControl previous = loadingControls.remove(playerUuid);
        restoreLoadingControl(player, previous);
        BukkitTask titleTask = plugin.getServer().getScheduler().runTaskTimer(
            plugin,
            () -> showJoinLoadingTitle(player),
            0L,
            JOIN_LOADING_TITLE_INTERVAL_TICKS
        );
        loadingControls.put(
            playerUuid,
            new LoadingControl(
                player.getLocation().clone(),
                setAttributeBaseValue(player, Attribute.MOVEMENT_SPEED, 0.0D),
                setAttributeBaseValue(player, Attribute.JUMP_STRENGTH, 0.0D),
                titleTask
            )
        );
        PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5071);
    }

    private void finishJoinLoading(UUID playerUuid, boolean notifyComplete) {
        if (!Bukkit.isPrimaryThread()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> finishJoinLoading(playerUuid, notifyComplete));
            return;
        }

        loadingPlayers.remove(playerUuid);
        LoadingControl loadingControl = loadingControls.remove(playerUuid);

        Player player = plugin.getServer().getPlayer(playerUuid);
        if (player != null && player.isOnline()) {
            restoreLoadingControl(player, loadingControl);
            player.clearTitle();
            if (notifyComplete) {
                PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5072);
            }
        } else if (loadingControl != null && loadingControl.titleTask() != null) {
            loadingControl.titleTask().cancel();
        }
    }

    @Nullable
    private SkillTreePlayerState loadInitialSkillTreeState(UUID playerUuid, String playerName, UUID accountId) {
        boolean loggedFailure = false;
        while (isJoinLoading(playerUuid)) {
            try {
                return skillTreeService.loadInitialPlayerState(accountId);
            } catch (RuntimeException e) {
                if (!loggedFailure) {
                    Logger.log(LogId.W_9002, accountId, e.getMessage());
                    loggedFailure = true;
                }
                try {
                    Thread.sleep(JOIN_LOADING_RETRY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    Logger.log(LogId.E_5070, interrupted, playerName);
                    return null;
                }
            }
        }
        return null;
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

    /**
     * プレイヤーがログインデータ読込中で、ゲーム入力を受け付けない状態か判定します。
     *
     * @param player 判定対象プレイヤー
     * @return 読込中なら true
     */
    public boolean isLoading(Player player) {
        return loadingPlayers.contains(player.getUniqueId());
    }

    private void showJoinLoadingTitle(Player player) {
        if (!player.isOnline() || !loadingPlayers.contains(player.getUniqueId())) {
            return;
        }
        player.showTitle(Title.title(
            PlayerMsgResource.formatComponent(PlayerMsgId.P_5073.getId()),
            PlayerMsgResource.formatComponent(PlayerMsgId.P_5071.getId()),
            Title.Times.times(Duration.ZERO, Duration.ofSeconds(6), Duration.ofMillis(500))
        ));
    }

    @Nullable
    private Double setAttributeBaseValue(Player player, Attribute attribute, double value) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return null;
        }
        double previousValue = instance.getBaseValue();
        instance.setBaseValue(value);
        return previousValue;
    }

    private void restoreAttributeBaseValue(Player player, Attribute attribute, @Nullable Double value) {
        if (value == null) {
            return;
        }
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    private void restoreLoadingControl(Player player, @Nullable LoadingControl loadingControl) {
        if (loadingControl == null) {
            return;
        }
        if (loadingControl.titleTask() != null) {
            loadingControl.titleTask().cancel();
        }
        restoreAttributeBaseValue(player, Attribute.MOVEMENT_SPEED, loadingControl.movementSpeed());
        restoreAttributeBaseValue(player, Attribute.JUMP_STRENGTH, loadingControl.jumpStrength());
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

    private boolean hasViewChanged(Location from, Location to) {
        return from.getYaw() != to.getYaw()
            || from.getPitch() != to.getPitch();
    }

    private record LoadingControl(
        Location lockLocation,
        @Nullable Double movementSpeed,
        @Nullable Double jumpStrength,
        @Nullable BukkitTask titleTask
    ) {
        private LoadingControl withLockLocation(Location updatedLockLocation) {
            return new LoadingControl(updatedLockLocation, movementSpeed, jumpStrength, titleTask);
        }
    }
}


