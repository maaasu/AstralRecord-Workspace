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
import io.github.maaasu.astralRecord.feature.player.service.PlayerSessionTransitionGuard;
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
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
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
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private static final long JOIN_STEP_DELAY_TICKS = 10L;
    private static final long JOIN_LOADING_TITLE_INTERVAL_TICKS = 100L;
    private static final long INITIAL_GUIDE_TITLE_INTERVAL_TICKS = 100L;
    private static final NamespacedKey JOIN_LOADING_MODIFIER =
        new NamespacedKey("astralrecord", "join_loading_lock");

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
    private final Map<UUID, PendingPlayerStateRecovery> pendingPlayerStateRecoveries = new ConcurrentHashMap<>();
    private final Map<UUID, LoadingControl> loadingControls = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> initialGuideTitleTasks = new ConcurrentHashMap<>();
    private final Object joinLoadQueueLock = new Object();
    private final ArrayDeque<QueuedJoinLoad> queuedJoinLoads = new ArrayDeque<>();
    private final Set<JoinAttempt> activeJoinLoads = new HashSet<>();
    private boolean dispatchingJoinLoads;
    private final AtomicLong joinAttemptSequence = new AtomicLong();
    private Consumer<AstPlayer> playerLoadedListener = ignored -> { };
    private Consumer<AstPlayer> playerQuitListener = ignored -> { };
    private Consumer<AccountModel> accountLoadingListener = ignored -> { };
    private Consumer<UUID> playerStateRecoveryAccountDiscarder = ignored -> { };
    private Consumer<Player> playerStateRecoveryRuntimeClearer = ignored -> { };

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
     * account が確定し、インベントリ等の詳細ロードを始める直前の通知先を設定します。
     *
     * @param listener account と Minecraft UUID の対応を保持する通知先
     */
    public void setAccountLoadingListener(@NotNull Consumer<AccountModel> listener) {
        this.accountLoadingListener = listener;
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
     * 保存不能 state の旧 account runtime を強制破棄する処理を設定します。
     *
     * @param discarder account 単位の未保存 state を保存せずに破棄する処理
     */
    public void setPlayerStateRecoveryAccountDiscarder(@NotNull Consumer<UUID> discarder) {
        this.playerStateRecoveryAccountDiscarder = discarder;
    }

    /**
     * Bukkit の quit event でのみ通常解放される player runtime を、復旧時に明示解放する処理を設定します。
     *
     * @param clearer player 単位の runtime state を解放する処理
     */
    public void setPlayerStateRecoveryRuntimeClearer(@NotNull Consumer<Player> clearer) {
        this.playerStateRecoveryRuntimeClearer = clearer;
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
        enqueueJoinLoad(attempt, () -> loadAccountSwitchStep(attempt, playerName, account, completionListener));
    }

    /**
     * 保存不能になったオンラインプレイヤーの runtime state を保存せずに破棄し、同じ account を再ロードします。
     * <p>
     * 旧 state の API I/O または外部原子操作が進行中なら、その終了までログイン読込状態を維持します。
     * 終了後にのみ旧 state を破棄して参加時と同じ経路で再ロードするため、古い非同期完了処理が
     * 新しい session を上書きしません。
     *
     * @param player 復旧対象の Bukkit プレイヤー
     * @param completionListener 復旧完了通知先。通知は Bukkit メインスレッドで行います
     */
    public void recoverPlayerState(
        @NotNull Player player,
        @NotNull Consumer<Boolean> completionListener
    ) {
        if (!Bukkit.isPrimaryThread()) {
            plugin.getServer().getScheduler().runTask(
                plugin,
                () -> recoverPlayerState(player, completionListener)
            );
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (!player.isOnline() || astPlayer == null) {
            completionListener.accept(false);
            return;
        }
        UUID playerId = player.getUniqueId();
        if (!plugin.getPlayerSessionTransitionGuard().tryBegin(
            playerId,
            PlayerSessionTransitionGuard.Transition.PLAYER_STATE_RECOVERY
        )) {
            queuePlayerStateRecovery(player, astPlayer.getAccount().getUuid(), completionListener);
            return;
        }
        pendingPlayerStateRecoveries.remove(playerId);
        beginPlayerStateRecovery(player, astPlayer, completionListener);
    }

    /** RECOVERY 遷移所有権を取得済みの player の旧 session を破棄して再ロードを開始します。 */
    private void beginPlayerStateRecovery(
        @NotNull Player player,
        @NotNull AstPlayer astPlayer,
        @NotNull Consumer<Boolean> completionListener
    ) {
        UUID playerId = player.getUniqueId();
        UUID accountId = astPlayer.getAccount().getUuid();
        String playerName = player.getName();
        JoinAttempt attempt = startJoinLoading(player);

        playerService.discardOnlineSessionForRecovery(player).whenComplete((ignored, failure) ->
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (failure != null) {
                    finishPlayerStateRecovery(playerId, accountId, completionListener, false);
                    if (isJoinLoading(attempt)) {
                        finishJoinLoading(attempt, false);
                    }
                    return;
                }
                try {
                    playerQuitListener.accept(astPlayer);
                    playerStateRecoveryRuntimeClearer.accept(player);
                    questService.releaseState(accountId);
                    skillBindPresetService.invalidate(accountId);
                    learnedSkillService.invalidate(accountId);
                    if (guideService != null) {
                        guideService.releaseProgress(accountId);
                    }
                    playerStateRecoveryAccountDiscarder.accept(accountId);
                } catch (RuntimeException cleanupFailure) {
                    Logger.log(LogId.E_5070, cleanupFailure, playerName);
                    finishPlayerStateRecovery(playerId, accountId, completionListener, false);
                    finishJoinLoading(attempt, false);
                    return;
                }
                if (!player.isOnline() || !isJoinLoading(attempt)) {
                    finishPlayerStateRecovery(playerId, accountId, completionListener, false);
                    if (isJoinLoading(attempt)) {
                        finishJoinLoading(attempt, false);
                    }
                    return;
                }
                // 旧 state を破棄済みで旧 I/O も完了しているため、参加時の初期付与が
                // recovery block に拒否されないようここで通常操作の受付を再開する。
                playerService.finishAccountRecovery(accountId);
                enqueueJoinLoad(
                    attempt,
                    () -> loadRecoveryAccountStep(
                        attempt,
                        playerName,
                        accountId,
                        succeeded -> finishPlayerStateRecovery(
                            playerId,
                            accountId,
                            completionListener,
                            succeeded
                        )
                    )
                );
            })
        );
    }

    /**
     * 他の session 遷移が終了するまで、保存不能 account の復旧要求を一件だけ保留します。
     */
    private void queuePlayerStateRecovery(
        @NotNull Player player,
        @NotNull UUID accountId,
        @NotNull Consumer<Boolean> completionListener
    ) {
        UUID playerId = player.getUniqueId();
        PendingPlayerStateRecovery request = new PendingPlayerStateRecovery(player, accountId, completionListener);
        if (pendingPlayerStateRecoveries.putIfAbsent(playerId, request) == null) {
            schedulePendingPlayerStateRecovery(playerId, request);
        }
    }

    /** 保留した復旧を main thread で再試行し、離脱・account切替時は旧accountだけをoffline破棄します。 */
    private void schedulePendingPlayerStateRecovery(
        @NotNull UUID playerId,
        @NotNull PendingPlayerStateRecovery request
    ) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (pendingPlayerStateRecoveries.get(playerId) != request) {
                return;
            }
            if (!plugin.isEnabled()) {
                if (pendingPlayerStateRecoveries.remove(playerId, request)) {
                    playerService.discardAccountStateForRecovery(request.accountId()).whenComplete((ignored, failure) -> {
                        playerService.finishAccountRecovery(request.accountId());
                        request.completionListener().accept(failure == null);
                    });
                }
                return;
            }
            Player player = request.player();
            AstPlayer current = player.isOnline() ? AstPlayerCache.get(player) : null;
            if (current == null || !request.accountId().equals(current.getAccount().getUuid())) {
                if (pendingPlayerStateRecoveries.remove(playerId, request)) {
                    recoverPlayerState(request.accountId(), request.completionListener());
                }
                return;
            }
            if (!plugin.getPlayerSessionTransitionGuard().tryBegin(
                playerId,
                PlayerSessionTransitionGuard.Transition.PLAYER_STATE_RECOVERY
            )) {
                schedulePendingPlayerStateRecovery(playerId, request);
                return;
            }
            pendingPlayerStateRecoveries.remove(playerId, request);
            beginPlayerStateRecovery(player, current, request.completionListener());
        }, 1L);
    }

    /**
     * 保存不能になった account を使用中のオンラインプレイヤーを復旧します。
     *
     * @param accountId 復旧対象 account ID
     */
    public void recoverPlayerState(@NotNull UUID accountId) {
        recoverPlayerState(accountId, succeeded -> { });
    }

    /**
     * 保存不能になった account を使用中のオンラインプレイヤーを復旧し、結果を通知します。
     *
     * @param accountId 復旧対象 account ID
     * @param completionListener 復旧完了通知先。通知は Bukkit メインスレッドで行います
     */
    public void recoverPlayerState(
        @NotNull UUID accountId,
        @NotNull Consumer<Boolean> completionListener
    ) {
        AstPlayer astPlayer = AstPlayerCache.getAll().stream()
            .filter(candidate -> accountId.equals(candidate.getAccount().getUuid()))
            .findFirst()
            .orElse(null);
        if (astPlayer == null) {
            playerService.discardAccountStateForRecovery(accountId).whenComplete((ignored, failure) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    boolean succeeded = failure == null;
                    if (failure == null) {
                        try {
                            playerStateRecoveryAccountDiscarder.accept(accountId);
                        } catch (RuntimeException cleanupFailure) {
                            Logger.log(LogId.E_5070, cleanupFailure, accountId.toString());
                            succeeded = false;
                        }
                    }
                    playerService.finishAccountRecovery(accountId);
                    completionListener.accept(succeeded);
                })
            );
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            plugin.getServer().getScheduler().runTask(
                plugin,
                () -> recoverPlayerState(accountId, completionListener)
            );
            return;
        }
        Player player = astPlayer.getBukkit();
        recoverPlayerState(player, succeeded -> {
            if (!succeeded && player.isOnline()) {
                PlayerMessageService.getInstance().send(player, PlayerMsgId.P_7211);
            }
            completionListener.accept(succeeded);
        });
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();

        // アカウント読み込み前は MCID をゲーム内表示へ出さず、読み込み完了後にアカウント名で通知する。
        event.joinMessage(null);

        recoverLegacyLoadingAttributes(player);
        JoinAttempt attempt = startJoinLoading(player);
        enqueueJoinLoad(attempt, () -> loadUserStep(attempt, playerName));
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
        stopInitialGuideTitle(playerUuid, false);

        AstPlayer astPlayer = AstPlayerCache.get(player);
        event.quitMessage(astPlayer == null
            ? null
            : PlayerMessageService.getInstance().formatInteractiveAccountMessage(
                PlayerMsgId.P_5077,
                astPlayer
            ));
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
            boolean hasSameIpUser = playerService.consumePendingSameIpUser(attempt.playerUuid());
            if (!isJoinLoading(attempt)) {
                return;
            }
            if (user == null) {
                finishJoinLoading(attempt, false);
                return;
            }

            scheduleAsync(() -> loadAccountStep(attempt, playerName, user, hasSameIpUser), JOIN_STEP_DELAY_TICKS);
        });
    }

    private void loadAccountStep(
        JoinAttempt attempt,
        String playerName,
        UserModel user,
        boolean hasSameIpUser
    ) {
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

            scheduleAsync(
                () -> loadInventoryStep(attempt, playerName, user, account, hasSameIpUser, null),
                JOIN_STEP_DELAY_TICKS
            );
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

            loadInventoryStep(attempt, playerName, user, account, false, completionListener);
        }, completionListener);
    }

    /**
     * 保存不能から復旧する account を API から再取得して参加時データをロードします。
     *
     * @param attempt 現在のログイン試行
     * @param playerName ログ出力用プレイヤー名
     * @param expectedAccountId 旧 session が使用していた account ID
     * @param completionListener 再ロード結果の通知先
     */
    private void loadRecoveryAccountStep(
        JoinAttempt attempt,
        String playerName,
        UUID expectedAccountId,
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
            AccountModel account = user == null ? null : playerService.loadPlayerJoinAccount(user, playerName);
            if (account == null || !expectedAccountId.equals(account.getUuid())) {
                finishAccountLoad(attempt, false, completionListener);
                return;
            }
            loadInventoryStep(attempt, playerName, user, account, false, completionListener);
        }, completionListener);
    }

    private void loadInventoryStep(
        JoinAttempt attempt,
        String playerName,
        UserModel user,
        AccountModel account,
        boolean hasSameIpUser,
        @Nullable Consumer<Boolean> completionListener
    ) {
        runJoinStep(attempt, playerName, () -> {
            if (!isJoinLoading(attempt)) {
                return;
            }
            runSafely(() -> accountLoadingListener.accept(account), LogId.E_5070, playerName);
            playerService.awaitRecoveryBeforePlayerJoin(account.getUuid());
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
                        hasSameIpUser,
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
        boolean hasSameIpUser,
        @Nullable Consumer<Boolean> completionListener
    ) {
        boolean questApplied = false;
        boolean skillTreeApplied = false;
        boolean skillBindPresetsApplied = false;
        @Nullable CompletableFuture<Boolean> guideProgressLoad = null;
        PlayerService.PlayerJoinApplication playerJoinApplication = null;
        AstPlayer appliedPlayer = null;
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
                finishAccountLoad(attempt, false, completionListener);
                return;
            }
            appliedPlayer = AstPlayerCache.get(player);
            if (menuToolJoinGrantService != null && preparedMenuGrant != null) {
                if (appliedPlayer == null) {
                    throw new IllegalStateException("AstPlayer was not published after join application");
                }
                menuToolJoinGrantService.grantPreparedIfMissing(appliedPlayer, preparedMenuGrant)
                    .exceptionally(failure -> {
                        Logger.log(LogId.E_5070, failure, playerName);
                        return false;
                    });
            }
            if (completionListener == null) {
                loginBonusService.openAfterDataLoaded(player);
            }
            playerService.commitPlayerJoin(playerJoinApplication);
            if (appliedPlayer != null) {
                AstPlayer loadedPlayer = appliedPlayer;
                runSafely(() -> playerLoadedListener.accept(loadedPlayer), LogId.E_5070, playerName);
                plugin.getPlayerClassService().updatePlayerListName(appliedPlayer);
                if (completionListener == null) {
                    PlayerMessageService.getInstance().broadcastAccountMessage(PlayerMsgId.P_5076, appliedPlayer);
                }
            }
            if (guideService != null) {
                guideProgressLoad = guideService.loadProgressAsync(joinData.account().getUuid());
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
            Logger.log(LogId.E_5070, exception, playerName);
            finishAccountLoad(attempt, false, completionListener);
            return;
        }

        finishAccountLoad(attempt, true, completionListener);
        if (appliedPlayer != null && completionListener == null) {
            PlayerMessageService messageService = PlayerMessageService.getInstance();
            messageService.send(appliedPlayer, PlayerMsgId.P_5083);
            if (hasSameIpUser) {
                messageService.send(appliedPlayer, PlayerMsgId.P_5084);
            }
            if (guideProgressLoad != null) {
                scheduleInitialGuideTitleAfterProgressLoad(appliedPlayer, guideProgressLoad);
            }
        }
        notifyUnreadMailAsync(attempt, playerName, joinData.account().getUuid());
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

    /**
     * ガイド進行の読み込み完了後、現在のプレイヤーセッションへ初回ガイドtitleを開始します。
     *
     * @param astPlayer 参加処理を完了したプレイヤー
     * @param progressLoad ガイド進行の読み込みFuture
     */
    private void scheduleInitialGuideTitleAfterProgressLoad(
        @NotNull AstPlayer astPlayer,
        @NotNull CompletableFuture<Boolean> progressLoad
    ) {
        Player player = astPlayer.getBukkit();
        UUID playerUuid = player.getUniqueId();
        UUID accountId = astPlayer.getAccount().getUuid();
        progressLoad.thenAccept(loaded -> {
            if (!loaded) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Player current = plugin.getServer().getPlayer(playerUuid);
                if (current == null || current != player || !current.isOnline()) {
                    return;
                }
                AstPlayer currentAstPlayer = AstPlayerCache.get(current);
                if (currentAstPlayer == null
                    || !accountId.equals(currentAstPlayer.getAccount().getUuid())) {
                    return;
                }
                startInitialGuideTitle(currentAstPlayer);
            });
        });
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

    private void runJoinRollbackStep(String playerName, Runnable rollbackStep) {
        try {
            rollbackStep.run();
        } catch (RuntimeException rollbackFailure) {
            Logger.log(LogId.E_5070, rollbackFailure, playerName);
        }
    }

    private void notifyUnreadMailAsync(JoinAttempt attempt, String playerName, UUID accountId) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
            runSafely(() -> {
                int unreadCount = mailService.countUnread(accountId);
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

    /**
     * 読込中だけ保存されない属性 modifier で移動とジャンプを抑止します。
     *
     * @param player メインスレッド上でロードを開始する対象
     * @return この接続のロード試行
     */
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
        restoreLoadingControl(previous, true);
        BukkitTask titleTask = plugin.getServer().getScheduler().runTaskTimer(
            plugin,
            () -> showJoinLoadingTitle(attempt),
            0L,
            JOIN_LOADING_TITLE_INTERVAL_TICKS
        );
        loadingControls.put(
            playerUuid,
            new LoadingControl(
                player,
                player.getLocation().clone(),
                applyLoadingAttributeLock(player, Attribute.MOVEMENT_SPEED),
                titleTask
            )
        );
        applyLoadingAttributeLock(player, Attribute.JUMP_STRENGTH);
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
        releaseJoinLoad(attempt);
        LoadingControl loadingControl = loadingControls.remove(attempt.playerUuid());
        // 退出済みでも、この試行が変更した Player 自体から解除する。再接続先には触れない。
        restoreLoadingControl(loadingControl, !notifyComplete);

        Player player = plugin.getServer().getPlayer(attempt.playerUuid());
        if (player == attempt.player() && player.isOnline()) {
            player.clearTitle();
            if (notifyLoadCompleteMessage) {
                PlayerMessageService.getInstance().send(
                    player,
                    PlayerMsgId.P_5072,
                    elapsedMillisSince(attempt.startedAtNanos())
                );
            }
        }
    }

    /**
     * 全参加ロードを無効化し、一時属性と title task を解除します。
     * 停止時にメインスレッドから呼び出し、後続のロードを開始しません。
     */
    public void stop() {
        joinAttempts.clear();
        synchronized (joinLoadQueueLock) {
            queuedJoinLoads.clear();
            activeJoinLoads.clear();
        }
        for (LoadingControl control : loadingControls.values()) {
            restoreLoadingControl(control, true);
        }
        loadingControls.clear();
        initialGuideTitleTasks.values().forEach(BukkitTask::cancel);
        initialGuideTitleTasks.clear();
    }

    @Nullable
    private SkillTreePlayerState loadInitialSkillTreeState(
        JoinAttempt attempt,
        String playerName,
        UUID accountId,
        UUID userId
    ) {
        int maxAttempts = ConfigProperties.getInstance().getPlayerJoinSkillTreeRetryMaxAttempts();
        long delayMillis = ConfigProperties.getInstance().getPlayerJoinSkillTreeRetryInitialDelayMillis();
        long maxDelayMillis = ConfigProperties.getInstance().getPlayerJoinSkillTreeRetryMaxDelayMillis();
        for (int attemptNumber = 1; attemptNumber <= maxAttempts && isJoinLoading(attempt); attemptNumber++) {
            try {
                return skillTreeService.loadInitialPlayerState(accountId, userId);
            } catch (RuntimeException e) {
                if (e instanceof io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeCompatibilityException) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (isJoinLoading(attempt)) {
                            attempt.player().kick(PlayerMsgResource.formatComponent(PlayerMsgId.P_9050.getId()));
                            finishJoinLoading(attempt, false);
                        }
                    });
                    return null;
                }
                if (attemptNumber == 1) {
                    Logger.log(LogId.W_9002, accountId, e.getMessage());
                }
                if (attemptNumber == maxAttempts) return null;
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    Logger.error(LogId.E_5073, interrupted, playerName);
                    return null;
                }
                delayMillis = Math.min(maxDelayMillis, Math.max(delayMillis, 1L) * 2L);
            }
        }
        return null;
    }

    private void enqueueJoinLoad(@NotNull JoinAttempt attempt, @NotNull Runnable start) {
        synchronized (joinLoadQueueLock) {
            queuedJoinLoads.addLast(new QueuedJoinLoad(attempt, start));
            startQueuedJoinLoads();
        }
    }

    private void releaseJoinLoad(@NotNull JoinAttempt attempt) {
        synchronized (joinLoadQueueLock) {
            queuedJoinLoads.removeIf(queued -> queued.attempt() == attempt);
            activeJoinLoads.remove(attempt);
            startQueuedJoinLoads();
        }
    }

    private void startQueuedJoinLoads() {
        if (dispatchingJoinLoads) return;
        dispatchingJoinLoads = true;
        try {
            int maxConcurrentLoads = ConfigProperties.getInstance().getPlayerJoinMaxConcurrentLoads();
            while (activeJoinLoads.size() < maxConcurrentLoads && !queuedJoinLoads.isEmpty()) {
                QueuedJoinLoad queued = queuedJoinLoads.removeFirst();
                if (!isJoinLoading(queued.attempt())) continue;
                activeJoinLoads.add(queued.attempt());
                try {
                    scheduleAsync(queued.start(), 0L);
                } catch (RuntimeException schedulingFailure) {
                    activeJoinLoads.remove(queued.attempt());
                    Logger.log(LogId.E_5070, schedulingFailure, queued.attempt().player().getName());
                    finishJoinLoading(queued.attempt(), false);
                }
            }
        } finally {
            dispatchingJoinLoads = false;
        }
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

    /** 復旧用に保持した保存受付停止と session 遷移所有権を解放して結果を通知します。 */
    private void finishPlayerStateRecovery(
        @NotNull UUID playerId,
        @NotNull UUID accountId,
        @NotNull Consumer<Boolean> completionListener,
        boolean succeeded
    ) {
        playerService.finishAccountRecovery(accountId);
        plugin.getPlayerSessionTransitionGuard().end(
            playerId,
            PlayerSessionTransitionGuard.Transition.PLAYER_STATE_RECOVERY
        );
        completionListener.accept(succeeded);
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

    private void startInitialGuideTitle(@NotNull AstPlayer astPlayer) {
        if (guideService == null) {
            return;
        }
        Player player = astPlayer.getBukkit();
        UUID playerUuid = player.getUniqueId();
        UUID accountId = astPlayer.getAccount().getUuid();
        stopInitialGuideTitle(playerUuid, false);
        if (guideService.isInitialGuideOpened(accountId)) {
            return;
        }

        showInitialGuideTitle(player);
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(
            plugin,
            () -> {
                Player current = plugin.getServer().getPlayer(playerUuid);
                if (current == null || current != player || !current.isOnline()) {
                    stopInitialGuideTitle(playerUuid, false);
                    return;
                }
                AstPlayer currentAstPlayer = AstPlayerCache.get(current);
                if (currentAstPlayer == null
                    || !accountId.equals(currentAstPlayer.getAccount().getUuid())) {
                    stopInitialGuideTitle(playerUuid, false);
                    return;
                }
                if (guideService.isInitialGuideOpened(accountId)) {
                    stopInitialGuideTitle(playerUuid, true);
                    return;
                }
                showInitialGuideTitle(current);
            },
            0L,
            INITIAL_GUIDE_TITLE_INTERVAL_TICKS
        );
        initialGuideTitleTasks.put(playerUuid, task);
    }

    private void stopInitialGuideTitle(UUID playerUuid, boolean clearTitle) {
        BukkitTask task = initialGuideTitleTasks.remove(playerUuid);
        if (task != null) {
            task.cancel();
        }
        if (!clearTitle) {
            return;
        }
        Player player = plugin.getServer().getPlayer(playerUuid);
        if (player != null && player.isOnline()) {
            player.clearTitle();
        }
    }

    private void showInitialGuideTitle(@NotNull Player player) {
        if (!player.isOnline()) {
            return;
        }
        player.showTitle(Title.title(
            PlayerMsgResource.formatComponent(PlayerMsgId.P_5079.getId()),
            Component.empty(),
            Title.Times.times(Duration.ZERO, Duration.ofSeconds(6), Duration.ofMillis(500))
        ));
    }

    /**
     * 基礎値を保持したまま一時 modifier を付与します。メインスレッド専用です。
     *
     * @param player 読込中のプレイヤー
     * @param attribute 抑止する属性
     * @return 失敗時復元用の基礎値。属性を持たない場合は null
     */
    @Nullable
    private Double applyLoadingAttributeLock(Player player, Attribute attribute) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return null;
        }
        double previousValue = instance.getBaseValue();
        instance.removeModifier(JOIN_LOADING_MODIFIER);
        instance.addTransientModifier(new AttributeModifier(
            JOIN_LOADING_MODIFIER, -1.0D, AttributeModifier.Operation.MULTIPLY_SCALAR_1
        ));
        return previousValue;
    }

    /**
     * 旧実装の読込ロックで保存された 0 の基礎値だけを参加時に既定値へ戻します。
     *
     * @param player メインスレッド上で新規接続を開始するプレイヤー
     */
    private void recoverLegacyLoadingAttributes(Player player) {
        for (Attribute attribute : new Attribute[] {Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH}) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance != null && instance.getBaseValue() == 0.0D) {
                instance.setBaseValue(instance.getDefaultValue());
            }
        }
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

    /**
     * 対象セッションの一時 modifier を除去します。メインスレッド専用です。
     *
     * @param loadingControl 解除する制御。null の場合は何もしません
     * @param restoreMovementSpeed 失敗・停止時に元の移動速度も復元する場合は true
     */
    private void restoreLoadingControl(
        @Nullable LoadingControl loadingControl,
        boolean restoreMovementSpeed
    ) {
        if (loadingControl == null) {
            return;
        }
        if (loadingControl.titleTask() != null) {
            loadingControl.titleTask().cancel();
        }
        Player player = loadingControl.player();
        for (Attribute attribute : new Attribute[] {Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH}) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance != null) {
                instance.removeModifier(JOIN_LOADING_MODIFIER);
            }
        }
        if (restoreMovementSpeed) {
            restoreAttributeBaseValue(player, Attribute.MOVEMENT_SPEED, loadingControl.movementSpeed());
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
        Player player,
        Location lockLocation,
        @Nullable Double movementSpeed,
        @Nullable BukkitTask titleTask
    ) {
        private LoadingControl withLockLocation(Location updatedLockLocation) {
            return new LoadingControl(player, updatedLockLocation, movementSpeed, titleTask);
        }
    }

    private record JoinAttempt(UUID playerUuid, long generation, Player player, long startedAtNanos) {
    }

    private record QueuedJoinLoad(JoinAttempt attempt, Runnable start) {
    }

    private record PendingPlayerStateRecovery(
        Player player,
        UUID accountId,
        Consumer<Boolean> completionListener
    ) {
    }
}
