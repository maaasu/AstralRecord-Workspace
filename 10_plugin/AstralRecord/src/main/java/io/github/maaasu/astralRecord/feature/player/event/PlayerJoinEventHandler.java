package io.github.maaasu.astralRecord.feature.player.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.loginbonus.service.LoginBonusService;
import io.github.maaasu.astralRecord.feature.mail.service.MailService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.guide.service.GuideService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerService;
import io.github.maaasu.astralRecord.feature.quest.service.QuestService;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.service.SkillBindPresetService;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
    private final QuestService questService;
    private final SkillBindPresetService skillBindPresetService;
    private final LoginBonusService loginBonusService;
    private final MailService mailService;
    private final @Nullable GuideService guideService;
    private final AstralRecord plugin;
    private final Map<UUID, JoinAttempt> joinAttempts = new ConcurrentHashMap<>();
    private final Map<UUID, LoadingControl> loadingControls = new ConcurrentHashMap<>();
    private final AtomicLong joinAttemptSequence = new AtomicLong();
    private final AtomicLong nextJoinStartNanos = new AtomicLong();

    /**
     * ガイド進行連携を使用しないテスト・互換用途のコンストラクタです。
     */
    public PlayerJoinEventHandler(
        AstralRecord plugin,
        PlayerService playerService,
        SkillTreeService skillTreeService,
        QuestService questService,
        SkillBindPresetService skillBindPresetService,
        LoginBonusService loginBonusService,
        MailService mailService
    ) {
        this(plugin, playerService, skillTreeService, questService, skillBindPresetService,
            loginBonusService, mailService, null);
    }

    /**
     * ログイン処理と各機能の初期状態読込を構成します。
     *
     * @param plugin Plugin本体
     * @param playerService プレイヤーサービス
     * @param skillTreeService スキルツリーサービス
     * @param questService クエストサービス
     * @param skillBindPresetService スキルバインドサービス
     * @param loginBonusService ログインボーナスサービス
     * @param mailService メールサービス
     * @param guideService ガイド進行サービス
     */
    public PlayerJoinEventHandler(
        AstralRecord plugin,
        PlayerService playerService,
        SkillTreeService skillTreeService,
        QuestService questService,
        SkillBindPresetService skillBindPresetService,
        LoginBonusService loginBonusService,
        MailService mailService,
        @Nullable GuideService guideService
    ) {
        this.plugin = plugin;
        this.playerService = playerService;
        this.skillTreeService = skillTreeService;
        this.questService = questService;
        this.skillBindPresetService = skillBindPresetService;
        this.loginBonusService = loginBonusService;
        this.mailService = mailService;
        this.guideService = guideService;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();

        JoinAttempt attempt = startJoinLoading(player);
        scheduleAsync(() -> loadUserStep(attempt, playerName), reserveJoinStartDelayTicks());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        if (!isJoinLoading(player)) {
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
        if (event.getDamager() instanceof Player player && isJoinLoading(player)) {
            event.setDamage(0.0D);
            event.setCancelled(true);
            return;
        }
        if (event.getEntity() instanceof Player player && isJoinLoading(player)) {
            event.setDamage(0.0D);
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        var player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();
        JoinAttempt attempt = currentJoinAttempt(player);

        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer != null) {
            UUID accountId = astPlayer.getAccount().getUuid();
            questService.releaseState(accountId);
            skillBindPresetService.invalidate(accountId);
            if (guideService != null) {
                guideService.releaseProgress(accountId);
            }
        }
        runSafely(() -> playerService.onPlayerQuit(player), LogId.E_5070, playerName);
        if (attempt != null) {
            finishJoinLoading(attempt, false);
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
            runSafely(() -> playerService.recordLogoutHistory(playerUuid, playerName), LogId.E_5070, playerName)
        );
    }

    private void loadUserStep(JoinAttempt attempt, String playerName) {
        runJoinStep(attempt, playerName, () -> {
            if (!isJoinLoading(attempt)) {
                return;
            }

            UserModel user = playerService.loadPlayerJoinUser(attempt.playerUuid(), playerName);
            if (!isJoinLoading(attempt)) {
                return;
            }
            if (user == null) {
                finishJoinLoading(attempt, false);
                return;
            }

            scheduleAsync(() -> loadAccountStep(attempt, playerName, user), JOIN_STEP_DELAY_TICKS);
        });
    }

    private void loadAccountStep(JoinAttempt attempt, String playerName, UserModel user) {
        runJoinStep(attempt, playerName, () -> {
            if (!isJoinLoading(attempt)) {
                return;
            }

            AccountModel account = playerService.loadPlayerJoinAccount(user, playerName);
            if (!isJoinLoading(attempt)) {
                return;
            }
            if (account == null) {
                finishJoinLoading(attempt, false);
                return;
            }

            scheduleAsync(() -> loadInventoryStep(attempt, playerName, user, account), JOIN_STEP_DELAY_TICKS);
        });
    }

    private void loadInventoryStep(JoinAttempt attempt, String playerName, UserModel user, AccountModel account) {
        runJoinStep(attempt, playerName, () -> {
            if (!isJoinLoading(attempt)) {
                return;
            }

            PlayerService.PlayerJoinInventoryState inventoryState =
                playerService.loadPlayerJoinInventoryState(account);
            boolean handedOffToMain = false;
            QuestService.InitialState questStateToDiscard = null;
            try {
                if (!isJoinLoading(attempt)) {
                    return;
                }
                SkillTreePlayerState skillTreeState = loadInitialSkillTreeState(
                    attempt,
                    playerName,
                    account.getUuid()
                );
                if (!isJoinLoading(attempt)) {
                    return;
                }
                if (skillTreeState == null) {
                    finishJoinLoading(attempt, false);
                    return;
                }
                QuestService.InitialState questState = questService.loadInitialState(account.getUuid());
                questStateToDiscard = questState;
                if (!isJoinLoading(attempt)) {
                    return;
                }
                List<SkillBindPreset> skillBindPresets = skillBindPresetService.loadInitialPresets(account.getUuid());
                if (!isJoinLoading(attempt)) {
                    return;
                }
                PlayerService.PlayerJoinData joinData = new PlayerService.PlayerJoinData(
                    user,
                    account,
                    inventoryState
                );
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    applyJoinData(attempt, playerName, joinData, skillTreeState, questState, skillBindPresets)
                );
                handedOffToMain = true;
                questStateToDiscard = null;
            } finally {
                if (!handedOffToMain) {
                    if (questStateToDiscard != null) {
                        questService.discardInitialState(questStateToDiscard);
                    }
                    playerService.discardPlayerJoinInventoryState(inventoryState);
                }
            }
        });
    }

    private void applyJoinData(
        JoinAttempt attempt,
        String playerName,
        PlayerService.PlayerJoinData joinData,
        @Nullable SkillTreePlayerState skillTreeState,
        QuestService.InitialState questState,
        List<SkillBindPreset> skillBindPresets
    ) {
        boolean questApplied = false;
        boolean skillTreeApplied = false;
        boolean skillBindPresetsApplied = false;
        PlayerService.PlayerJoinApplication playerJoinApplication = null;
        try {
            Player player = plugin.getServer().getPlayer(attempt.playerUuid());
            if (player == null
                || player != attempt.player()
                || !player.isOnline()
                || !isJoinLoading(attempt)) {
                rollbackJoinApplication(
                    playerName,
                    joinData,
                    skillTreeState,
                    questState,
                    false,
                    false,
                    false,
                    null
                );
                finishJoinLoading(attempt, false);
                return;
            }
            if (skillTreeState == null) {
                rollbackJoinApplication(
                    playerName,
                    joinData,
                    null,
                    questState,
                    false,
                    false,
                    false,
                    null
                );
                finishJoinLoading(attempt, false);
                return;
            }

            if (!questService.applyInitialState(questState)) {
                rollbackJoinApplication(
                    playerName,
                    joinData,
                    skillTreeState,
                    questState,
                    false,
                    false,
                    false,
                    null
                );
                finishJoinLoading(attempt, false);
                return;
            }
            questApplied = true;
            skillTreeApplied = true;
            skillTreeService.applyInitialPlayerState(skillTreeState);
            skillBindPresetsApplied = true;
            skillBindPresetService.applyInitialPresets(joinData.account().getUuid(), skillBindPresets);
            playerJoinApplication = playerService.applyPlayerJoinTransactional(player, joinData);
            if (playerJoinApplication == null) {
                rollbackJoinApplication(
                    playerName,
                    joinData,
                    skillTreeState,
                    questState,
                    questApplied,
                    skillTreeApplied,
                    skillBindPresetsApplied,
                    null
                );
                finishJoinLoading(attempt, false);
                return;
            }
            loginBonusService.openAfterDataLoaded(player);
            playerService.commitPlayerJoin(playerJoinApplication);
            if (guideService != null) {
                guideService.loadProgressAsync(joinData.account().getUuid());
            }
        } catch (Exception exception) {
            rollbackJoinApplication(
                playerName,
                joinData,
                skillTreeState,
                questState,
                questApplied,
                skillTreeApplied,
                skillBindPresetsApplied,
                playerJoinApplication
            );
            Logger.log(LogId.E_5070, exception, playerName);
            finishJoinLoading(attempt, false);
            return;
        }

        finishJoinLoading(attempt, true);
        notifyUnreadMailAsync(attempt, playerName);
        scheduleAsync(
            () -> runSafely(
                () -> playerService.recordLoginHistory(attempt.playerUuid(), playerName),
                LogId.E_5070,
                playerName
            ),
            JOIN_STEP_DELAY_TICKS
        );
    }

    private void rollbackJoinApplication(
        String playerName,
        PlayerService.PlayerJoinData joinData,
        @Nullable SkillTreePlayerState skillTreeState,
        QuestService.InitialState questState,
        boolean questApplied,
        boolean skillTreeApplied,
        boolean skillBindPresetsApplied,
        @Nullable PlayerService.PlayerJoinApplication playerJoinApplication
    ) {
        if (playerJoinApplication != null) {
            runJoinRollbackStep(playerName, () -> playerService.rollbackPlayerJoin(playerJoinApplication));
        }
        if (skillBindPresetsApplied) {
            runJoinRollbackStep(
                playerName,
                () -> skillBindPresetService.invalidate(joinData.account().getUuid())
            );
        }
        if (skillTreeApplied && skillTreeState != null) {
            runJoinRollbackStep(playerName, () -> skillTreeService.discardInitialPlayerState(skillTreeState));
        }
        if (questApplied) {
            runJoinRollbackStep(playerName, () -> questService.releaseState(questState.accountId()));
        } else {
            runJoinRollbackStep(playerName, () -> questService.discardInitialState(questState));
        }
        if (playerJoinApplication == null) {
            runJoinRollbackStep(
                playerName,
                () -> playerService.discardPlayerJoinInventoryState(joinData.inventoryState())
            );
        }
    }

    private void runJoinRollbackStep(String playerName, Runnable rollbackStep) {
        try {
            rollbackStep.run();
        } catch (RuntimeException rollbackFailure) {
            Logger.log(LogId.E_5070, rollbackFailure, playerName);
        }
    }

    private void notifyUnreadMailAsync(JoinAttempt attempt, String playerName) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
            runSafely(() -> {
                int unreadCount = mailService.countUnread(attempt.playerUuid());
                if (unreadCount <= 0) {
                    return;
                }
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    Player player = plugin.getServer().getPlayer(attempt.playerUuid());
                    if (player == attempt.player() && player.isOnline()) {
                        PlayerMessageService.getInstance().sendClickable(
                            player,
                            PlayerMsgId.P_5624,
                            "/menu mail",
                            unreadCount
                        );
                    }
                });
            }, LogId.E_5070, playerName)
        );
    }

    private JoinAttempt startJoinLoading(Player player) {
        UUID playerUuid = player.getUniqueId();
        JoinAttempt attempt = new JoinAttempt(
            playerUuid,
            joinAttemptSequence.incrementAndGet(),
            player,
            System.nanoTime()
        );
        joinAttempts.put(playerUuid, attempt);
        LoadingControl previous = loadingControls.remove(playerUuid);
        restoreLoadingControl(player, previous);
        BukkitTask titleTask = plugin.getServer().getScheduler().runTaskTimer(
            plugin,
            () -> showJoinLoadingTitle(attempt),
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
        return attempt;
    }

    private void finishJoinLoading(JoinAttempt attempt, boolean notifyComplete) {
        if (!Bukkit.isPrimaryThread()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> finishJoinLoading(attempt, notifyComplete));
            return;
        }

        if (!joinAttempts.remove(attempt.playerUuid(), attempt)) {
            return;
        }
        LoadingControl loadingControl = loadingControls.remove(attempt.playerUuid());

        Player player = plugin.getServer().getPlayer(attempt.playerUuid());
        if (player == attempt.player() && player.isOnline()) {
            restoreLoadingControl(player, loadingControl);
            player.clearTitle();
            if (notifyComplete) {
                PlayerMessageService.getInstance().send(
                    player,
                    PlayerMsgId.P_5072,
                    elapsedMillisSince(attempt.startedAtNanos())
                );
            }
        } else if (loadingControl != null && loadingControl.titleTask() != null) {
            loadingControl.titleTask().cancel();
        }
    }

    @Nullable
    private SkillTreePlayerState loadInitialSkillTreeState(
        JoinAttempt attempt,
        String playerName,
        UUID accountId
    ) {
        boolean loggedFailure = false;
        while (isJoinLoading(attempt)) {
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
                    Logger.error(LogId.E_5073, interrupted, playerName);
                    return null;
                }
            }
        }
        return null;
    }

    private void runJoinStep(JoinAttempt attempt, String playerName, Runnable action) {
        if (!isJoinLoading(attempt)) {
            return;
        }
        try {
            action.run();
        } catch (Exception e) {
            Logger.log(LogId.E_5070, e, playerName);
            plugin.getServer().getScheduler().runTask(plugin, () -> finishJoinLoading(attempt, false));
        }
    }

    private void scheduleAsync(Runnable task, long delayTicks) {
        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, task, Math.max(0L, delayTicks));
    }

    private boolean isJoinLoading(JoinAttempt attempt) {
        return joinAttempts.get(attempt.playerUuid()) == attempt;
    }

    /**
     * プレイヤーがログインデータ読込中で、ゲーム入力を受け付けない状態か判定します。
     *
     * @param player 判定対象プレイヤー
     * @return 読込中なら true
     */
    public boolean isLoading(Player player) {
        return isJoinLoading(player);
    }

    private boolean isJoinLoading(Player player) {
        JoinAttempt attempt = joinAttempts.get(player.getUniqueId());
        return attempt != null && attempt.player() == player;
    }

    @Nullable
    private JoinAttempt currentJoinAttempt(Player player) {
        JoinAttempt attempt = joinAttempts.get(player.getUniqueId());
        return attempt != null && attempt.player() == player ? attempt : null;
    }

    private void showJoinLoadingTitle(JoinAttempt attempt) {
        Player player = attempt.player();
        if (!player.isOnline() || !isJoinLoading(attempt)) {
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

    /**
     * 指定した単調時計の開始時刻から現在までの経過時間をミリ秒で返します。
     *
     * @param startedAtNanos {@link System#nanoTime()} で取得した開始時刻
     * @return 0 以上の経過ミリ秒
     */
    private long elapsedMillisSince(long startedAtNanos) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos));
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

    private record JoinAttempt(UUID playerUuid, long generation, Player player, long startedAtNanos) {
    }
}

