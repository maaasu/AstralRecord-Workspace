package io.github.maaasu.astralRecord.feature.player.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.loginbonus.service.LoginBonusService;
import io.github.maaasu.astralRecord.feature.mail.service.MailService;
import io.github.maaasu.astralRecord.feature.menu.service.MenuToolJoinGrantService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.guide.service.GuideService;
import io.github.maaasu.astralRecord.feature.guide.model.GuideConditionType;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerService;
import io.github.maaasu.astralRecord.feature.quest.service.QuestService;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.service.SkillBindPresetService;
import io.github.maaasu.astralRecord.feature.skill.service.LearnedSkillService;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

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
    private final LearnedSkillService learnedSkillService;
    private final LoginBonusService loginBonusService;
    private final MailService mailService;
    private final @Nullable GuideService guideService;
    private final @Nullable MenuToolJoinGrantService menuToolJoinGrantService;
    private final AstralRecord plugin;
    private final Map<UUID, JoinAttempt> joinAttempts = new ConcurrentHashMap<>();
    private final Map<UUID, LoadingControl> loadingControls = new ConcurrentHashMap<>();
    private final AtomicLong joinAttemptSequence = new AtomicLong();
    private final AtomicLong nextJoinStartNanos = new AtomicLong();
    private Consumer<AstPlayer> playerLoadedListener = ignored -> { };
    private Consumer<AstPlayer> playerQuitListener = ignored -> { };

    /**
     * ガイド進行連携を使用しないテスト・互換用途のコンストラクタです。
     */
    public PlayerJoinEventHandler(
        AstralRecord plugin,
        PlayerService playerService,
        SkillTreeService skillTreeService,
        QuestService questService,
        SkillBindPresetService skillBindPresetService,
        LearnedSkillService learnedSkillService,
        LoginBonusService loginBonusService,
        MailService mailService
    ) {
        this(plugin, playerService, skillTreeService, questService, skillBindPresetService, learnedSkillService,
            loginBonusService, mailService, null, null);
    }

    /**
     * 参加時メニュー導線を使用しないテスト・互換用途のコンストラクタです。
     */
    public PlayerJoinEventHandler(
        AstralRecord plugin,
        PlayerService playerService,
        SkillTreeService skillTreeService,
        QuestService questService,
        SkillBindPresetService skillBindPresetService,
        LearnedSkillService learnedSkillService,
        LoginBonusService loginBonusService,
        MailService mailService,
        @Nullable GuideService guideService
    ) {
        this(plugin, playerService, skillTreeService, questService, skillBindPresetService, learnedSkillService,
            loginBonusService, mailService, guideService, null);
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
     * @param menuToolJoinGrantService 参加時メニュー導線付与サービス
     */
    public PlayerJoinEventHandler(
        AstralRecord plugin,
        PlayerService playerService,
        SkillTreeService skillTreeService,
        QuestService questService,
        SkillBindPresetService skillBindPresetService,
        LearnedSkillService learnedSkillService,
        LoginBonusService loginBonusService,
        MailService mailService,
        @Nullable GuideService guideService,
        @Nullable MenuToolJoinGrantService menuToolJoinGrantService
    ) {
        this.plugin = plugin;
        this.playerService = playerService;
        this.skillTreeService = skillTreeService;
        this.questService = questService;
        this.skillBindPresetService = skillBindPresetService;
        this.learnedSkillService = learnedSkillService;
        this.loginBonusService = loginBonusService;
        this.mailService = mailService;
        this.guideService = guideService;
        this.menuToolJoinGrantService = menuToolJoinGrantService;
    }

    /**
     * プレイヤーデータ反映後の通知先を設定します。
     *
     * @param listener プレイヤーデータ反映後に呼び出す通知先
     */
    public void setPlayerLoadedListener(@NotNull Consumer<AstPlayer> listener) {
        this.playerLoadedListener = listener;
    }

    /**
     * プレイヤー退出またはアカウント切替時、キャッシュ削除前の通知先を設定します。
     *
     * @param listener キャッシュ削除前に呼び出すセッション終了通知先
     */
    public void setPlayerQuitListener(@NotNull Consumer<AstPlayer> listener) {
        this.playerQuitListener = listener;
    }

    /**
     * オンライン中のアカウント切替に備え、現在のセッションを保存可能な状態へ移します。
     * <p>
     * Bukkit のプレイヤー状態を参照するため、メインスレッドから呼び出してください。
     * 保存完了の待機は呼び出し元が {@link PlayerService#awaitQueuedSavesForAccountSwitch(UUID)}
     * を非同期で行います。
     *
     * @param player アカウントを切り替えるプレイヤー
     * @return 切替前のアカウント UUID と保存結果。現在のセッションがない場合は {@code null}
     */
    public @Nullable AccountSwitchPreparation prepareAccountSwitch(@NotNull Player player) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("prepareAccountSwitch must run on the Bukkit main thread");
        }

        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return null;
        }

        UUID accountId = astPlayer.getAccount().getUuid();
        String playerName = player.getName();
        runSafely(() -> playerQuitListener.accept(astPlayer), LogId.E_5070, playerName);
        questService.releaseState(accountId);
        skillBindPresetService.invalidate(accountId);
        learnedSkillService.invalidate(accountId);
        if (guideService != null) {
            guideService.releaseProgress(accountId);
        }
        CompletableFuture<Boolean> logoutSave = playerService.onPlayerQuit(player);
        return new AccountSwitchPreparation(accountId, logoutSave);
    }

    /** アカウント切替前に切り離した旧セッションと、その保存結果です。 */
    public record AccountSwitchPreparation(
        @NotNull UUID accountId,
        @NotNull CompletableFuture<Boolean> logoutSave
    ) {
    }

    /**
     * オンラインプレイヤーへ指定アカウントの参加時データを再ロードします。
     * ログインボーナス・ログイン履歴は発生させず、アカウント単位の runtime state だけを再構築します。
     *
     * @param player 再ロード対象プレイヤー
     * @param account 切替後のアカウント
     * @param completionListener 再ロード結果の通知先。通知はメインスレッドで行います
     */
    public void reloadAccount(
        @NotNull Player player,
        @NotNull AccountModel account,
        @NotNull Consumer<Boolean> completionListener
    ) {
        if (!Bukkit.isPrimaryThread()) {
            plugin.getServer().getScheduler().runTask(
                plugin,
                () -> reloadAccount(player, account, completionListener)
            );
            return;
        }
        if (!player.isOnline() || AstPlayerCache.contains(player.getUniqueId())) {
            completionListener.accept(false);
            return;
        }

        JoinAttempt attempt = startJoinLoading(player);
        String playerName = player.getName();
        scheduleAsync(
            () -> loadAccountSwitchStep(attempt, playerName, account, completionListener),
            0L
        );
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();

        event.joinMessage(
            PlayerMessageService.getInstance().formatInteractivePlayerMessage(
                PlayerMsgId.P_5076,
                playerName
            )
        );

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

        event.quitMessage(
            PlayerMessageService.getInstance().formatInteractivePlayerMessage(
                PlayerMsgId.P_5077,
                playerName
            )
        );

        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer != null) {
            runSafely(() -> playerQuitListener.accept(astPlayer), LogId.E_5070, playerName);
            UUID accountId = astPlayer.getAccount().getUuid();
            questService.releaseState(accountId);
            skillBindPresetService.invalidate(accountId);
            learnedSkillService.invalidate(accountId);
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

            scheduleAsync(() -> loadInventoryStep(attempt, playerName, user, account, null), JOIN_STEP_DELAY_TICKS);
        });
    }

    private void loadAccountSwitchStep(
        JoinAttempt attempt,
        String playerName,
        AccountModel account,
        Consumer<Boolean> completionListener
    ) {
        runJoinStep(attempt, playerName, () -> {
            if (!isJoinLoading(attempt)) {
                return;
            }

            UserModel user = playerService.loadPlayerJoinUser(attempt.playerUuid(), playerName);
            if (!isJoinLoading(attempt)) {
                return;
            }
            if (user == null || !user.getUuid().equals(account.getUserId())) {
                finishAccountLoad(attempt, false, completionListener);
                return;
            }

            loadInventoryStep(attempt, playerName, user, account, completionListener);
        }, completionListener);
    }

    private void loadInventoryStep(
        JoinAttempt attempt,
        String playerName,
        UserModel user,
        AccountModel account,
        @Nullable Consumer<Boolean> completionListener
    ) {
        runJoinStep(attempt, playerName, () -> {
            if (!isJoinLoading(attempt)) {
                return;
            }

            PlayerService.PlayerJoinInventoryState inventoryState = null;
            MenuToolJoinGrantService.PreparedGrant preparedMenuGrant = null;
            boolean handedOffToMain = false;
            QuestService.InitialState questStateToDiscard = null;
            try {
                // 習得スキルロード時に削除済みジェム・不正シジルをAPIが整合するため、
                // インベントリはその後にロードして整合後のentryを取得する。
                List<LearnedSkillInstance> learnedSkills = learnedSkillService.loadInitialSkills(account.getUuid());
                if (!isJoinLoading(attempt)) {
                    return;
                }
                inventoryState = playerService.loadPlayerJoinInventoryState(account);
                if (!isJoinLoading(attempt)) {
                    return;
                }
                SkillTreePlayerState skillTreeState = loadInitialSkillTreeState(
                    attempt,
                    playerName,
                    account.getUuid(),
                    user.getUuid()
                );
                if (!isJoinLoading(attempt)) {
                    return;
                }
                if (skillTreeState == null) {
                    finishAccountLoad(attempt, false, completionListener);
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
                preparedMenuGrant = menuToolJoinGrantService == null
                    ? null
                    : menuToolJoinGrantService.prepareIfMissing(inventoryState.state());
                if (!isJoinLoading(attempt)) {
                    return;
                }
                PlayerService.PlayerJoinData joinData = new PlayerService.PlayerJoinData(
                    user,
                    account,
                    inventoryState
                );
                MenuToolJoinGrantService.PreparedGrant grantForMain = preparedMenuGrant;
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    applyJoinData(
                        attempt,
                        playerName,
                        joinData,
                        skillTreeState,
                        questState,
                        skillBindPresets,
                        learnedSkills,
                        grantForMain,
                        completionListener
                    )
                );
                handedOffToMain = true;
                questStateToDiscard = null;
            } finally {
                if (!handedOffToMain) {
                    if (questStateToDiscard != null) {
                        questService.discardInitialState(questStateToDiscard);
                    }
                    if (inventoryState != null) {
                        playerService.discardPlayerJoinInventoryState(inventoryState);
                    }
                    if (preparedMenuGrant != null) {
                        menuToolJoinGrantService.cleanupPreparedGrant(preparedMenuGrant);
                    }
                }
            }
        }, completionListener);
    }

    private void applyJoinData(
        JoinAttempt attempt,
        String playerName,
        PlayerService.PlayerJoinData joinData,
        @Nullable SkillTreePlayerState skillTreeState,
        QuestService.InitialState questState,
        List<SkillBindPreset> skillBindPresets,
        List<LearnedSkillInstance> learnedSkills,
        @Nullable MenuToolJoinGrantService.PreparedGrant preparedMenuGrant,
        @Nullable Consumer<Boolean> completionListener
    ) {
        boolean questApplied = false;
        boolean skillTreeApplied = false;
        boolean skillBindPresetsApplied = false;
        boolean preparedMenuGrantCleanupScheduled = false;
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
                cleanupPreparedGrantAsync(preparedMenuGrant);
                finishAccountLoad(attempt, false, completionListener);
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
                cleanupPreparedGrantAsync(preparedMenuGrant);
                finishAccountLoad(attempt, false, completionListener);
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
                cleanupPreparedGrantAsync(preparedMenuGrant);
                finishAccountLoad(attempt, false, completionListener);
                return;
            }
            questApplied = true;
            skillTreeApplied = true;
            skillTreeService.applyInitialPlayerState(skillTreeState);
            skillBindPresetsApplied = true;
            skillBindPresetService.applyInitialPresets(joinData.account().getUuid(), skillBindPresets);
            learnedSkillService.applyInitialSkills(joinData.account().getUuid(), learnedSkills);
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
                cleanupPreparedGrantAsync(preparedMenuGrant);
                finishAccountLoad(attempt, false, completionListener);
                return;
            }
            AstPlayer appliedPlayer = AstPlayerCache.get(player);
            if (menuToolJoinGrantService != null && preparedMenuGrant != null) {
                if (appliedPlayer == null) {
                    throw new IllegalStateException("AstPlayer was not published after join application");
                }
                if (!menuToolJoinGrantService.grantPreparedIfMissing(appliedPlayer, preparedMenuGrant)) {
                    cleanupPreparedGrantAsync(preparedMenuGrant);
                    preparedMenuGrantCleanupScheduled = true;
                }
            }
            if (completionListener == null) {
                loginBonusService.openAfterDataLoaded(player);
            }
            playerService.commitPlayerJoin(playerJoinApplication);
            if (appliedPlayer != null) {
                runSafely(() -> playerLoadedListener.accept(appliedPlayer), LogId.E_5070, playerName);
                plugin.getPlayerClassService().updatePlayerListName(appliedPlayer);
            }
            if (guideService != null) {
                guideService.loadProgressAsync(joinData.account().getUuid());
                if (appliedPlayer != null && completionListener == null) {
                    guideService.recordCondition(appliedPlayer, GuideConditionType.PLAYER_LOGGED_IN, null);
                }
            }
            if (appliedPlayer != null) {
                mailService.notifyPendingMailReceived(appliedPlayer);
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
            if (!preparedMenuGrantCleanupScheduled) {
                cleanupPreparedGrantAsync(preparedMenuGrant);
            }
            Logger.log(LogId.E_5070, exception, playerName);
            finishAccountLoad(attempt, false, completionListener);
            return;
        }

        finishAccountLoad(attempt, true, completionListener);
        notifyUnreadMailAsync(attempt, playerName);
        if (completionListener == null) {
            scheduleAsync(
                () -> runSafely(
                    () -> playerService.recordLoginHistory(attempt.playerUuid(), playerName),
                    LogId.E_5070,
                    playerName
                ),
                JOIN_STEP_DELAY_TICKS
            );
        }
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
        learnedSkillService.invalidate(joinData.account().getUuid());
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

    private void cleanupPreparedGrantAsync(
        @Nullable MenuToolJoinGrantService.PreparedGrant preparedMenuGrant
    ) {
        MenuToolJoinGrantService grantService = menuToolJoinGrantService;
        if (grantService == null || preparedMenuGrant == null) {
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(
            plugin,
            () -> grantService.cleanupPreparedGrant(preparedMenuGrant)
        );
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
        restoreLoadingControl(player, previous, true);
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
        finishJoinLoading(attempt, notifyComplete, notifyComplete);
    }

    private void finishJoinLoading(
        JoinAttempt attempt,
        boolean notifyComplete,
        boolean notifyLoadCompleteMessage
    ) {
        if (!Bukkit.isPrimaryThread()) {
            plugin.getServer().getScheduler().runTask(
                plugin,
                () -> finishJoinLoading(attempt, notifyComplete, notifyLoadCompleteMessage)
            );
            return;
        }

        if (!joinAttempts.remove(attempt.playerUuid(), attempt)) {
            return;
        }
        LoadingControl loadingControl = loadingControls.remove(attempt.playerUuid());

        Player player = plugin.getServer().getPlayer(attempt.playerUuid());
        if (player == attempt.player() && player.isOnline()) {
            // 成功時は参加反映中の StatusService.refreshStatus が設定した MOVEMENT_SPEED を保持する。
            // 失敗時だけログイン前の値へ戻し、ロード中の一時ロックが残らないようにする。
            restoreLoadingControl(player, loadingControl, !notifyComplete);
            player.clearTitle();
            if (notifyLoadCompleteMessage) {
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
        UUID accountId,
        UUID userId
    ) {
        boolean loggedFailure = false;
        while (isJoinLoading(attempt)) {
            try {
                return skillTreeService.loadInitialPlayerState(accountId, userId);
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
        runJoinStep(attempt, playerName, action, null);
    }

    private void runJoinStep(
        JoinAttempt attempt,
        String playerName,
        Runnable action,
        @Nullable Consumer<Boolean> completionListener
    ) {
        if (!isJoinLoading(attempt)) {
            return;
        }
        try {
            action.run();
        } catch (Exception e) {
            Logger.log(LogId.E_5070, e, playerName);
            plugin.getServer().getScheduler().runTask(plugin, () -> finishJoinLoading(attempt, false));
            notifyAccountLoadCompletion(completionListener, false);
        }
    }

    private void finishAccountLoad(
        JoinAttempt attempt,
        boolean succeeded,
        @Nullable Consumer<Boolean> completionListener
    ) {
        finishJoinLoading(attempt, succeeded, completionListener == null);
        notifyAccountLoadCompletion(completionListener, succeeded);
    }

    private void notifyAccountLoadCompletion(
        @Nullable Consumer<Boolean> completionListener,
        boolean succeeded
    ) {
        if (completionListener == null) {
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            plugin.getServer().getScheduler().runTask(
                plugin,
                () -> notifyAccountLoadCompletion(completionListener, succeeded)
            );
            return;
        }
        completionListener.accept(succeeded);
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

    private void restoreLoadingControl(
        Player player,
        @Nullable LoadingControl loadingControl,
        boolean restoreMovementSpeed
    ) {
        if (loadingControl == null) {
            return;
        }
        if (loadingControl.titleTask() != null) {
            loadingControl.titleTask().cancel();
        }
        if (restoreMovementSpeed) {
            restoreAttributeBaseValue(player, Attribute.MOVEMENT_SPEED, loadingControl.movementSpeed());
        }
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
