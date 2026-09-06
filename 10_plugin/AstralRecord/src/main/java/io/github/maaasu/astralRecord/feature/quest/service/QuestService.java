package io.github.maaasu.astralRecord.feature.quest.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountExperienceResult;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryInstanceType;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.playerclass.model.ClassExperienceResult;
import io.github.maaasu.astralRecord.feature.quest.model.QuestBoardDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestCompletionMode;
import io.github.maaasu.astralRecord.feature.quest.model.QuestDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestDisplayState;
import io.github.maaasu.astralRecord.feature.quest.model.QuestItemStackDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestObjectiveDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestObjectiveType;
import io.github.maaasu.astralRecord.feature.quest.model.QuestPlayerState;
import io.github.maaasu.astralRecord.feature.quest.model.QuestProgress;
import io.github.maaasu.astralRecord.feature.quest.model.QuestRepeatMode;
import io.github.maaasu.astralRecord.feature.quest.model.QuestRequirementDefinition;
import io.github.maaasu.astralRecord.feature.quest.repository.QuestBoardRepository;
import io.github.maaasu.astralRecord.feature.quest.repository.QuestDefinitionRepository;
import io.github.maaasu.astralRecord.feature.quest.repository.QuestPlayerStateRepository;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

public final class QuestService {
    private static final int DEFAULT_MAX_ACTIVE_QUESTS = 3;
    private static final String UNREGISTERED_ITEM_DISPLAY_NAME = "未登録のアイテム";
    private static final String REWARD_SOURCE = "quest_reward";
    private static final long SAVE_INTERVAL_TICKS = 20L;
    private static final long SAVE_DEBOUNCE_MILLIS = 1_000L;

    private final Plugin plugin;
    private final QuestDefinitionRepository questRepository;
    private final QuestBoardRepository boardRepository;
    private final QuestPlayerStateRepository stateRepository;
    private final ItemService itemService;
    private final InventoryService inventoryService;
    private final AccountService accountService;
    private final PlayerClassService playerClassService;
    private final StatusService statusService;
    private SkillTreeService skillTreeService;
    private final ParticleDisplayService particleDisplayService;
    private final Executor asyncExecutor;
    private final Executor mainExecutor;
    private final QuestStatePersistenceCoordinator persistenceCoordinator;
    private final Map<String, QuestDefinition> quests = new LinkedHashMap<>();
    private final Map<String, QuestBoardDefinition> boards = new LinkedHashMap<>();
    private final Map<UUID, QuestPlayerState> states = new LinkedHashMap<>();
    private final Set<UUID> dirtyStates = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> saveDueAtMillis = new ConcurrentHashMap<>();
    private final Map<RewardClaimKey, UUID> pendingRewardClaims = new ConcurrentHashMap<>();
    private final Map<RewardClaimKey, RewardPersistenceRetry> rewardPersistenceRetries = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> rewardProcessingTails = new ConcurrentHashMap<>();
    private BiConsumer<AstPlayer, String> questAcceptedListener = (player, questId) -> { };
    private BiConsumer<AstPlayer, String> questCompletedListener = (player, questId) -> { };
    private BukkitTask saveTask;
    private volatile boolean stopping;

    public QuestService(
        @NotNull Plugin plugin,
        @NotNull QuestDefinitionRepository questRepository,
        @NotNull QuestBoardRepository boardRepository,
        @NotNull QuestPlayerStateRepository stateRepository,
        @NotNull ItemService itemService,
        @NotNull InventoryService inventoryService,
        @NotNull AccountService accountService,
        @NotNull PlayerClassService playerClassService,
        @NotNull StatusService statusService,
        @NotNull ParticleDisplayService particleDisplayService
    ) {
        this(
            plugin,
            questRepository,
            boardRepository,
            stateRepository,
            itemService,
            inventoryService,
            accountService,
            playerClassService,
            statusService,
            particleDisplayService,
            command -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, command),
            command -> plugin.getServer().getScheduler().runTask(plugin, command)
        );
    }

    /** クエスト報酬によるレベル変化をスキルツリーへ反映するサービスを設定します。 */
    public void setSkillTreeService(@NotNull SkillTreeService skillTreeService) {
        this.skillTreeService = skillTreeService;
    }

    /**
     * クエスト受領成功時の通知先を設定します。
     *
     * @param questAcceptedListener 受領したプレイヤーとクエスト ID を受け取る通知先
     */
    public void setQuestAcceptedListener(@NotNull BiConsumer<AstPlayer, String> questAcceptedListener) {
        this.questAcceptedListener = questAcceptedListener;
    }

    /**
     * クエスト完了成功時の通知先を設定します。
     * 報酬と関連状態の保存が成功した後、プレイヤーとクエスト IDを通知します。
     *
     * @param questCompletedListener 完了したプレイヤーとクエスト ID を受け取る通知先
     */
    public void setQuestCompletedListener(@NotNull BiConsumer<AstPlayer, String> questCompletedListener) {
        this.questCompletedListener = questCompletedListener;
    }

    QuestService(
        @NotNull QuestDefinitionRepository questRepository,
        @NotNull QuestBoardRepository boardRepository,
        @NotNull QuestPlayerStateRepository stateRepository,
        @NotNull ItemService itemService,
        @NotNull InventoryService inventoryService,
        @NotNull AccountService accountService,
        @NotNull PlayerClassService playerClassService,
        @NotNull StatusService statusService,
        @NotNull ParticleDisplayService particleDisplayService
    ) {
        this(
            null,
            questRepository,
            boardRepository,
            stateRepository,
            itemService,
            inventoryService,
            accountService,
            playerClassService,
            statusService,
            particleDisplayService,
            Runnable::run,
            Runnable::run
        );
    }

    QuestService(
        @Nullable Plugin plugin,
        @NotNull QuestDefinitionRepository questRepository,
        @NotNull QuestBoardRepository boardRepository,
        @NotNull QuestPlayerStateRepository stateRepository,
        @NotNull ItemService itemService,
        @NotNull InventoryService inventoryService,
        @NotNull AccountService accountService,
        @NotNull PlayerClassService playerClassService,
        @NotNull StatusService statusService,
        @NotNull ParticleDisplayService particleDisplayService,
        @NotNull Executor asyncExecutor,
        @NotNull Executor mainExecutor
    ) {
        this.plugin = plugin;
        this.questRepository = questRepository;
        this.boardRepository = boardRepository;
        this.stateRepository = stateRepository;
        this.itemService = itemService;
        this.inventoryService = inventoryService;
        this.accountService = accountService;
        this.playerClassService = playerClassService;
        this.statusService = statusService;
        this.particleDisplayService = particleDisplayService;
        this.asyncExecutor = asyncExecutor;
        this.mainExecutor = mainExecutor;
        this.persistenceCoordinator = new QuestStatePersistenceCoordinator(
            new QuestStatePersistenceCoordinator.StateStorage() {
                @Override
                public @NotNull QuestPlayerState load(@NotNull UUID accountId) {
                    return stateRepository.load(accountId);
                }

                @Override
                public void save(@NotNull QuestPlayerState state) {
                    stateRepository.save(state);
                }
            },
            asyncExecutor
        );
    }

    /** クエスト状態の定期保存タスクを開始します。 */
    public void start() {
        if (plugin == null || saveTask != null) {
            return;
        }
        stopping = false;
        saveTask = plugin.getServer().getScheduler().runTaskTimer(
            plugin,
            this::flushDueStates,
            SAVE_INTERVAL_TICKS,
            SAVE_INTERVAL_TICKS
        );
    }

    /**
     * 定期保存を停止し、進行中の保存に最新世代を連結して完了を待ちます。
     */
    public void stop() {
        stopping = true;
        persistenceCoordinator.beginShutdown();
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        for (QuestPlayerState state : List.copyOf(states.values())) {
            persistenceCoordinator.recordLatest(state);
            dirtyStates.add(state.accountId());
            saveDueAtMillis.put(state.accountId(), 0L);
        }
        for (UUID accountId : persistenceCoordinator.accountIds()) {
            persistenceCoordinator.flushLatest(accountId);
        }
        persistenceCoordinator.awaitAll();
        persistenceCoordinator.retryOutstandingSynchronously();
        states.clear();
        dirtyStates.clear();
        saveDueAtMillis.clear();
        pendingRewardClaims.clear();
        rewardPersistenceRetries.clear();
        rewardProcessingTails.clear();
        persistenceCoordinator.clear();
    }

    /**
     * ログイン用クエスト状態を読み込みます。未完了の保存がある場合は保持中の最新世代を返します。
     *
     * @param accountId 対象アカウント ID
     * @return 適用時検証トークンを含む初期状態
     */
    public @NotNull InitialState loadInitialState(@NotNull UUID accountId) {
        QuestStatePersistenceCoordinator.LoadedState loaded = persistenceCoordinator.load(accountId);
        return new InitialState(loaded.accountId(), loaded.loadToken(), loaded.generation(), loaded.state());
    }

    /**
     * 初期状態を現在の保存世代と照合し、最新ロード要求の場合だけセッションへ適用します。
     *
     * @param initialState {@link #loadInitialState(UUID)} の戻り値
     * @return 適用できた場合は {@code true}
     */
    public boolean applyInitialState(@NotNull InitialState initialState) {
        QuestPlayerState state = persistenceCoordinator.apply(initialState.coordinatorState());
        if (state == null) {
            return false;
        }
        states.put(state.accountId(), state);
        clearPersistedMarker(state.accountId());
        return true;
    }

    void applyInitialState(@NotNull QuestPlayerState state) {
        persistenceCoordinator.activate(state.accountId());
        states.put(state.accountId(), state);
        clearPersistedMarker(state.accountId());
    }

    /**
     * ログイン中断時に未適用の初期状態トークンを破棄します。
     *
     * @param initialState 破棄する初期状態
     */
    public void discardInitialState(@NotNull InitialState initialState) {
        persistenceCoordinator.discard(initialState.coordinatorState());
    }

    /**
     * ログアウト時の最新状態を保持して保存し、即時再ログインから参照可能にします。
     *
     * @param accountId 対象アカウント ID
     */
    public void releaseState(@NotNull UUID accountId) {
        QuestPlayerState current = states.remove(accountId);
        if (current == null) {
            return;
        }
        persistenceCoordinator.recordLatest(current);
        persistenceCoordinator.markReleased(accountId);
        dirtyStates.add(accountId);
        saveDueAtMillis.put(accountId, 0L);
        flushStateAsync(accountId);
    }

    public int loadAll() {
        MasterDataSnapshot snapshot = loadMasterDataSnapshot();
        replaceMasterDataSnapshot(snapshot);
        return quests.size();
    }

    /**
     * クエストと掲示板定義を読み込み、公開前のスナップショットを作成します。
     *
     * @return クエストマスタスナップショット
     */
    public @NotNull MasterDataSnapshot loadMasterDataSnapshot() {
        return new MasterDataSnapshot(
            List.copyOf(questRepository.findAll()),
            List.copyOf(boardRepository.findAll())
        );
    }

    /**
     * 準備済みクエストマスタを実行時キャッシュへ一括反映します。
     *
     * @param snapshot クエストマスタスナップショット
     */
    public void replaceMasterDataSnapshot(@NotNull MasterDataSnapshot snapshot) {
        quests.clear();
        boards.clear();
        for (QuestDefinition quest : snapshot.quests()) {
            quests.put(quest.id(), quest);
        }
        for (QuestBoardDefinition board : snapshot.boards()) {
            boards.put(board.id(), board);
        }
    }

    /** 公開前に準備したクエスト定義と掲示板定義の immutable スナップショットです。 */
    public record MasterDataSnapshot(
        @NotNull List<QuestDefinition> quests,
        @NotNull List<QuestBoardDefinition> boards
    ) {
    }

    public @Nullable QuestDefinition findQuest(@NotNull String questId) {
        return quests.get(stripPrefix(questId));
    }

    public @Nullable QuestBoardDefinition findBoard(@NotNull String boardId) {
        return boards.get(stripPrefix(boardId));
    }

    public @NotNull List<QuestDefinition> activeQuests(@NotNull AstPlayer player) {
        QuestPlayerState state = state(player);
        return state.activeQuests().keySet().stream()
            .map(quests::get)
            .filter(quest -> quest != null)
            .toList();
    }

    public @Nullable QuestProgress progress(@NotNull AstPlayer player, @NotNull String questId) {
        return state(player).activeQuests().get(stripPrefix(questId));
    }

    public @NotNull QuestDisplayState displayState(@NotNull AstPlayer player, @NotNull QuestDefinition quest) {
        QuestPlayerState state = state(player);
        QuestProgress progress = state.activeQuests().get(quest.id());
        if (progress != null) {
            return progress.readyToTurnIn() ? QuestDisplayState.READY_TO_TURN_IN : QuestDisplayState.IN_PROGRESS;
        }
        long now = System.currentTimeMillis();
        long cooldownUntil = state.cooldownUntil().getOrDefault(quest.id(), 0L);
        if (cooldownUntil > now) {
            return QuestDisplayState.COOLDOWN;
        }
        if (quest.repeatMode() == QuestRepeatMode.ONCE && state.completedAt().containsKey(quest.id())) {
            return QuestDisplayState.COMPLETED;
        }
        return canMeetRequirements(player, quest) ? QuestDisplayState.AVAILABLE : QuestDisplayState.LOCKED;
    }

    public long cooldownRemainingSeconds(@NotNull AstPlayer player, @NotNull QuestDefinition quest) {
        long cooldownUntil = state(player).cooldownUntil().getOrDefault(quest.id(), 0L);
        return Math.max(0L, (cooldownUntil - System.currentTimeMillis() + 999L) / 1000L);
    }

    public int maxActiveQuests(@NotNull AstPlayer player) {
        double statusValue = statusService.getStatus(player).getMaxValue(StatusType.QUEST_LIMIT);
        return Math.max(DEFAULT_MAX_ACTIVE_QUESTS, (int) Math.floor(statusValue));
    }

    /**
     * クエストを受領し、必要な条件itemを消費して進行状態を保存予約します。
     *
     * @param player 受領するgameplay accountのプレイヤー
     * @param quest 受領するクエスト定義
     * @param npcId 受領元NPC ID。未指定の場合はNPC指定なしとして扱う
     * @return 受領して保存予約まで開始できた場合は{@code true}、受領条件または報酬保存中のclaimにより拒否した場合は{@code false}
     */
    public boolean accept(@NotNull AstPlayer player, @NotNull QuestDefinition quest, @Nullable String npcId) {
        if (!AccountModeGuard.isGameplayPlayer(player)) {
            return false;
        }
        QuestPlayerState state = state(player);
        if (pendingRewardClaims.containsKey(new RewardClaimKey(state.accountId(), quest.id()))) {
            send(player, PlayerMsgId.P_6600);
            return false;
        }
        QuestDisplayState displayState = displayState(player, quest);
        if (displayState != QuestDisplayState.AVAILABLE) {
            send(player, PlayerMsgId.P_6600);
            return false;
        }
        if (state.activeQuests().size() >= maxActiveQuests(player)) {
            send(player, PlayerMsgId.P_6601);
            return false;
        }
        if (!consumeRequirements(player, quest)) {
            send(player, PlayerMsgId.P_6602);
            return false;
        }
        state.activeQuests().put(quest.id(), QuestProgress.start(quest, stripNullablePrefix(npcId)));
        save(state);
        questAcceptedListener.accept(player, quest.id());
        send(player, PlayerMsgId.P_6603, quest.name());
        player.getBukkit().playSound(player.getBukkit().getLocation(), Sound.UI_TOAST_IN, SoundCategory.PLAYERS, 0.7F, 1.1F);
        return true;
    }

    public boolean abandon(@NotNull AstPlayer player, @NotNull String questId) {
        QuestPlayerState state = state(player);
        QuestProgress removed = state.activeQuests().remove(stripPrefix(questId));
        if (removed == null) {
            return false;
        }
        save(state);
        QuestDefinition quest = quests.get(removed.questId());
        send(player, PlayerMsgId.P_6604, quest == null ? removed.questId() : quest.name());
        player.getBukkit().playSound(player.getBukkit().getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.PLAYERS, 0.55F, 0.75F);
        return true;
    }

    /**
     * クエストを報告し、報酬処理を開始します。
     *
     * @param player 報告する gameplay account のプレイヤー
     * @param quest 報告するクエスト定義
     * @param npcId 報告元NPC ID。NPC完了以外では未指定でもよい
     * @return 報告条件を満たして報酬処理を開始できた場合は{@code true}
     */
    public boolean turnIn(@NotNull AstPlayer player, @NotNull QuestDefinition quest, @Nullable String npcId) {
        return turnIn(player, quest, npcId, () -> {
        });
    }

    /**
     * クエストを報告し、報酬とクエスト状態の永続化成功後に完了通知を呼び出します。
     *
     * @param player 報告する gameplay account のプレイヤー
     * @param quest 報告するクエスト定義
     * @param npcId 報告元NPC ID。NPC完了以外では未指定でもよい
     * @param onCompleted 報酬処理と関連する永続化が成功した後にメインスレッドで呼び出す処理
     * @return 報告条件を満たして報酬処理を開始できた場合は{@code true}
     */
    public boolean turnIn(
        @NotNull AstPlayer player,
        @NotNull QuestDefinition quest,
        @Nullable String npcId,
        @NotNull Runnable onCompleted
    ) {
        QuestPlayerState state = state(player);
        QuestProgress progress = state.activeQuests().get(quest.id());
        if (progress == null || !progress.readyToTurnIn()) {
            return false;
        }
        String requiredNpc = quest.turnInNpcId() == null ? progress.acceptedNpcId() : quest.turnInNpcId();
        if (quest.completionMode() == QuestCompletionMode.NPC
            && requiredNpc != null
            && (npcId == null || !requiredNpc.equalsIgnoreCase(stripPrefix(npcId)))) {
            send(player, PlayerMsgId.P_6605);
            return false;
        }
        return complete(player, state, quest, onCompleted);
    }

    public void recordMobKill(@NotNull AstPlayer player, @NotNull String mobId) {
        recordMobKill(player, mobId, null);
    }

    /** 指定された Mob レベルに一致するクエスト進行を記録します。 */
    public void recordMobKill(
        @NotNull AstPlayer player,
        @NotNull String mobId,
        @Nullable Integer mobLevel
    ) {
        recordObjective(player, QuestObjectiveType.KILL_MOB, mobId, mobLevel);
    }

    public void recordGathering(@NotNull AstPlayer player, @NotNull String gatheringId) {
        recordObjective(player, QuestObjectiveType.GATHERING, gatheringId, null);
    }

    private void recordObjective(
        @NotNull AstPlayer player,
        @NotNull QuestObjectiveType type,
        @NotNull String targetId,
        @Nullable Integer targetLevel
    ) {
        QuestPlayerState state = state(player);
        boolean changed = false;
        for (QuestProgress progress : new ArrayList<>(state.activeQuests().values())) {
            QuestDefinition quest = quests.get(progress.questId());
            if (quest == null || progress.readyToTurnIn()) {
                continue;
            }
            for (QuestObjectiveDefinition objective : quest.objectives()) {
                if (objective.type() != type
                    || !objective.targetId().equalsIgnoreCase(stripPrefix(targetId))
                    || (objective.targetLevel() != null && !objective.targetLevel().equals(targetLevel))) {
                    continue;
                }
                int next = Math.min(objective.amount(), progress.progress(objective.id()) + 1);
                if (next != progress.progress(objective.id())) {
                    progress.setProgress(objective.id(), next);
                    changed = true;
                }
            }
            if (isComplete(quest, progress)) {
                if (quest.isAutoReward()) {
                    complete(player, state, quest);
                } else {
                    progress.readyToTurnIn(true);
                    changed = true;
                    notifyReady(player, quest);
                }
            }
        }
        if (changed) {
            save(state);
        }
    }

    private boolean isComplete(@NotNull QuestDefinition quest, @NotNull QuestProgress progress) {
        for (QuestObjectiveDefinition objective : quest.objectives()) {
            if (progress.progress(objective.id()) < objective.amount()) {
                return false;
            }
        }
        return true;
    }

    private boolean complete(@NotNull AstPlayer player, @NotNull QuestPlayerState state, @NotNull QuestDefinition quest) {
        return complete(player, state, quest, () -> {
        });
    }

    private boolean complete(
        @NotNull AstPlayer player,
        @NotNull QuestPlayerState state,
        @NotNull QuestDefinition quest,
        @NotNull Runnable onCompleted
    ) {
        if (stopping) {
            return false;
        }
        QuestProgress progress = state.activeQuests().get(quest.id());
        if (progress == null) {
            return false;
        }
        RewardClaimKey claimKey = new RewardClaimKey(state.accountId(), quest.id());
        UUID claimId = UUID.randomUUID();
        if (pendingRewardClaims.putIfAbsent(claimKey, claimId) != null) {
            return false;
        }
        if (!progress.readyToTurnIn()) {
            progress.readyToTurnIn(true);
            save(state);
        }

        AtomicReference<Boolean> immediateResult = new AtomicReference<>();
        CompletableFuture<PreparedRewards> preparation;
        try {
            preparation = CompletableFuture.supplyAsync(
                () -> prepareRewards(state.accountId(), quest),
                asyncExecutor
            );
        } catch (RuntimeException exception) {
            pendingRewardClaims.remove(claimKey, claimId);
            Logger.log(LogId.W_6601, exception, state.accountId(), quest.id(), exception.getMessage());
            send(player, PlayerMsgId.P_6606);
            return false;
        }
        preparation.whenComplete((prepared, failure) -> {
            PreparedRewards result = prepared;
            if (failure != null) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                Logger.log(LogId.W_6601, cause, state.accountId(), quest.id(), cause.getMessage());
                result = PreparedRewards.failure(List.of(), List.of());
            }
            PreparedRewards completedPreparation = result;
            try {
                enqueuePreparedRewards(
                    player,
                    state,
                    quest,
                    claimKey,
                    claimId,
                    completedPreparation,
                    immediateResult,
                    onCompleted
                );
            } catch (RuntimeException exception) {
                pendingRewardClaims.remove(claimKey, claimId);
                cleanupPreparedInstances(completedPreparation);
                Logger.log(LogId.W_6601, exception, state.accountId(), quest.id(), exception.getMessage());
            }
        });
        return immediateResult.get() == null || immediateResult.get();
    }

    /**
     * 同一accountの報酬反映と補償を直列化し、あるclaimの全量補償が後続claimを巻き戻さないようにします。
     *
     * @param player 報酬を受け取るプレイヤー
     * @param expectedState 報酬準備開始時のクエスト状態
     * @param quest 報酬対象クエスト
     * @param claimKey 多重受取防止キー
     * @param claimId 今回の受取要求 ID
     * @param prepared 準備済み報酬
     * @param immediateResult 同期executorでの即時結果格納先
     * @param onCompleted 報酬処理と関連する永続化が成功した後に呼び出す処理
     */
    private void enqueuePreparedRewards(
        @NotNull AstPlayer player,
        @NotNull QuestPlayerState expectedState,
        @NotNull QuestDefinition quest,
        @NotNull RewardClaimKey claimKey,
        @NotNull UUID claimId,
        @NotNull PreparedRewards prepared,
        @NotNull AtomicReference<Boolean> immediateResult,
        @NotNull Runnable onCompleted
    ) {
        UUID accountId = expectedState.accountId();
        CompletableFuture<Void> rewardProcessing = new CompletableFuture<>();
        AtomicReference<CompletableFuture<Void>> previousReference = new AtomicReference<>();
        rewardProcessingTails.compute(accountId, (ignored, previous) -> {
            previousReference.set(previous == null ? CompletableFuture.completedFuture(null) : previous);
            return rewardProcessing;
        });
        previousReference.get().handle((ignored, failure) -> null).thenRun(() -> {
            try {
                mainExecutor.execute(() -> immediateResult.set(finishPreparedRewards(
                    player,
                    expectedState,
                    quest,
                    claimKey,
                    claimId,
                    prepared,
                    rewardProcessing,
                    onCompleted
                )));
            } catch (RuntimeException exception) {
                pendingRewardClaims.remove(claimKey, claimId);
                cleanupPreparedInstances(prepared);
                Logger.log(LogId.W_6601, exception, expectedState.accountId(), quest.id(), exception.getMessage());
                immediateResult.set(false);
                completeRewardProcessing(accountId, rewardProcessing);
            }
        });
    }

    private @NotNull PreparedRewards prepareRewards(
        @NotNull UUID accountId,
        @NotNull QuestDefinition quest
    ) {
        List<ResolvedItemReward> stackRewards = new ArrayList<>();
        List<PreparedInstanceReward> instanceRewards = new ArrayList<>();
        try {
            for (QuestItemStackDefinition item : quest.rewards().items()) {
                ItemModel model = resolveItem(item);
                if (model == null) {
                    return PreparedRewards.failure(stackRewards, instanceRewards);
                }
                ItemCategory category = ItemCategory.fromApiValue(model.getCategory());
                if (category != ItemCategory.EQUIPMENT) {
                    stackRewards.add(new ResolvedItemReward(model, item.amount()));
                    continue;
                }
                for (int index = 0; index < item.amount(); index++) {
                    UUID instanceId = prepareEquipmentInstance(model, accountId);
                    if (instanceId == null) {
                        return PreparedRewards.failure(stackRewards, instanceRewards);
                    }
                    instanceRewards.add(new PreparedInstanceReward(
                        model, InventoryInstanceType.EQUIPMENT, instanceId));
                }
            }
            return PreparedRewards.success(stackRewards, instanceRewards);
        } catch (RuntimeException exception) {
            Logger.log(LogId.W_6601, exception, accountId, quest.id(), exception.getMessage());
            return PreparedRewards.failure(stackRewards, instanceRewards);
        }
    }

    private @Nullable UUID prepareEquipmentInstance(
        @NotNull ItemModel model,
        @NotNull UUID accountId
    ) {
        EquipmentInstance instance = itemService.createEquipmentInstance(
            model.getId(), accountId.toString(), REWARD_SOURCE, accountId.toString());
        String instanceId = instance == null ? null : instance.getEquipmentInstanceId();
        if (instanceId == null) {
            return null;
        }
        try {
            return UUID.fromString(instanceId);
        } catch (IllegalArgumentException exception) {
            itemService.deleteEquipmentInstance(instanceId);
            return null;
        }
    }

    /**
     * 準備済み報酬を反映し、account単位の保存・補償処理を開始します。
     *
     * @param player 報酬を受け取るプレイヤー
     * @param expectedState 報酬準備開始時のクエスト状態
     * @param quest 報酬対象クエスト
     * @param claimKey 多重受取防止キー
     * @param claimId 今回の受取要求 ID
     * @param prepared 準備済み報酬
     * @param rewardProcessing account単位報酬処理の完了 Future
     * @param onCompleted 報酬処理と関連する永続化が成功した後に呼び出す処理
     * @return 報酬反映と保存処理を開始できた場合は {@code true}
     */
    private boolean finishPreparedRewards(
        @NotNull AstPlayer player,
        @NotNull QuestPlayerState expectedState,
        @NotNull QuestDefinition quest,
        @NotNull RewardClaimKey claimKey,
        @NotNull UUID claimId,
        @NotNull PreparedRewards prepared,
        @NotNull CompletableFuture<Void> rewardProcessing,
        @NotNull Runnable onCompleted
    ) {
        if (!claimId.equals(pendingRewardClaims.get(claimKey))) {
            cleanupPreparedInstances(prepared);
            completeRewardProcessing(expectedState.accountId(), rewardProcessing);
            return false;
        }
        QuestPlayerState currentState = states.get(expectedState.accountId());
        if (stopping || currentState != expectedState || !prepared.success()) {
            cleanupPreparedInstances(prepared);
            pendingRewardClaims.remove(claimKey, claimId);
            if (!stopping && currentState == expectedState) {
                send(player, PlayerMsgId.P_6606);
            }
            completeRewardProcessing(expectedState.accountId(), rewardProcessing);
            return false;
        }
        QuestProgress currentProgress = currentState.activeQuests().get(quest.id());
        if (currentProgress == null || !currentProgress.readyToTurnIn()) {
            cleanupPreparedInstances(prepared);
            pendingRewardClaims.remove(claimKey, claimId);
            completeRewardProcessing(expectedState.accountId(), rewardProcessing);
            return false;
        }
        AppliedRewards applied = inventoryService.executeLocalPlayerMutation(
            expectedState.accountId(),
            () -> applyPreparedRewards(player, quest, prepared)
        );
        if (applied == null) {
            cleanupPreparedInstances(prepared);
            pendingRewardClaims.remove(claimKey, claimId);
            send(player, PlayerMsgId.P_6606);
            completeRewardProcessing(expectedState.accountId(), rewardProcessing);
            return false;
        }

        currentState.activeQuests().remove(quest.id());
        long now = System.currentTimeMillis();
        currentState.completedAt().put(quest.id(), now);
        if (quest.repeatMode() == QuestRepeatMode.COOLDOWN && quest.cooldownSeconds() > 0L) {
            currentState.cooldownUntil().put(quest.id(), now + quest.cooldownSeconds() * 1000L);
        }
        // 保存開始後の失敗は応答不明も含むため、報酬や後続操作を補償で巻き戻さない。
        save(currentState);
        continueRewardPersistence(player, currentState, quest, claimKey, claimId,
            applied, rewardProcessing, onCompleted, 0);
        return true;
    }

    /**
     * 最新quest保存とinventory保存を順に試み、失敗時は完了状態とclaimを保持して再送します。
     * 再送では報酬を再付与せず、coordinatorにある最新世代を使い、後続quest進行も保持します。
     *
     * @param player 対象プレイヤー
     * @param currentState 完了をローカル確定した状態
     * @param quest 対象クエスト
     * @param claimKey 多重受取防止キー
     * @param claimId 今回の受取要求ID
     * @param applied 反映済み報酬
     * @param rewardProcessing account単位の待機Future
     * @param onCompleted 保存成功後のcallback
     * @param attempt 再試行回数
     */
    private void continueRewardPersistence(
        @NotNull AstPlayer player,
        @NotNull QuestPlayerState currentState,
        @NotNull QuestDefinition quest,
        @NotNull RewardClaimKey claimKey,
        @NotNull UUID claimId,
        @NotNull AppliedRewards applied,
        @NotNull CompletableFuture<Void> rewardProcessing,
        @NotNull Runnable onCompleted,
        int attempt
    ) {
        if (!claimId.equals(pendingRewardClaims.get(claimKey))) {
            return;
        }
        CompletableFuture<Boolean> persistence;
        try {
            persistence = persistenceCoordinator.flushLatest(currentState.accountId()).thenCompose(ignored -> {
                // 通常flush/logoutと同じtailへ合流した場合、tail自体は失敗を吸収する。
                // 完了だけを成功とせず、最新世代の保存済み状態も確認する。
                if (!persistenceCoordinator.isLatestPersisted(currentState.accountId())) {
                    return CompletableFuture.failedFuture(new IllegalStateException("quest_state_not_persisted"));
                }
                return applied.inventorySnapshot() == null && !applied.progressChanged()
                    ? CompletableFuture.completedFuture(true)
                    : inventoryService.saveNow(currentState.accountId());
            });
        } catch (RuntimeException exception) {
            persistence = CompletableFuture.failedFuture(exception);
        }
        persistence.whenComplete((saved, failure) -> {
            Runnable completion = () -> {
                if (!claimId.equals(pendingRewardClaims.get(claimKey))) {
                    return;
                }
                if (failure == null && Boolean.TRUE.equals(saved)) {
                    rewardPersistenceRetries.remove(claimKey);
                    clearPersistedMarker(currentState.accountId());
                    finishRewardPersistence(player, currentState, quest, claimKey, claimId,
                        rewardProcessing, onCompleted);
                } else {
                    Throwable cause = unwrapFailure(failure);
                    if (cause == null) {
                        Logger.warn(LogId.W_5071, "quest_reward", quest.id(), currentState.accountId(), "save_returned_false");
                    } else {
                        Logger.error(LogId.W_5071, cause, "quest_reward", quest.id(), currentState.accountId(), cause.getClass().getSimpleName());
                    }
                    scheduleRewardPersistenceRetry(claimKey, attempt, () ->
                        continueRewardPersistence(player, currentState, quest, claimKey, claimId,
                            applied, rewardProcessing, onCompleted, attempt + 1));
                }
            };
            try {
                mainExecutor.execute(completion);
            } catch (RuntimeException exception) {
                // 完了処理の受付失敗でもclaimを保持する。成功済み保存を報酬取消へ変換しない。
                Logger.error(LogId.W_5071, exception, "quest_reward_completion", quest.id(), currentState.accountId(), exception.getClass().getSimpleName());
                scheduleRewardPersistenceRetry(claimKey, attempt, completion);
            }
        });
    }

    /**
     * 次の定期tickへ再送を予約します。同期executorでも再帰せず、1秒から最大60秒へbackoffします。
     */
    private void scheduleRewardPersistenceRetry(
        @NotNull RewardClaimKey key, int attempt, @NotNull Runnable action
    ) {
        long delay = Math.min(60_000L, 1_000L << Math.min(6, Math.max(0, attempt)));
        long dueAt = Math.max(System.currentTimeMillis() + delay,
            persistenceCoordinator.retryNotBeforeMillis(key.accountId()));
        rewardPersistenceRetries.put(key, new RewardPersistenceRetry(dueAt, action));
    }

    /** 定期tickで期限到来した報酬保存だけを一度取り出して再試行します。 */
    void retryRewardPersistence(long now) {
        for (var entry : List.copyOf(rewardPersistenceRetries.entrySet())) {
            RewardPersistenceRetry retry = entry.getValue();
            if (retry.dueAtMillis() <= now && rewardPersistenceRetries.remove(entry.getKey(), retry)) {
                retry.action().run();
            }
        }
    }

    /**
     * 全永続化の成功後にだけクエスト完了演出を実行し、受取中状態とaccount単位の待機を解除します。
     *
     * @param player 対象プレイヤー
     * @param expectedState 保存したクエスト状態
     * @param quest 完了したクエスト
     * @param claimKey 多重受取防止キー
     * @param claimId 今回の受取要求 ID
     * @param rewardProcessing account単位報酬処理の完了 Future
     * @param onCompleted 報酬処理と関連する永続化が成功した後に呼び出す処理
     */
    private void finishRewardPersistence(
        @NotNull AstPlayer player,
        @NotNull QuestPlayerState expectedState,
        @NotNull QuestDefinition quest,
        @NotNull RewardClaimKey claimKey,
        @NotNull UUID claimId,
        @NotNull CompletableFuture<Void> rewardProcessing,
        @NotNull Runnable onCompleted
    ) {
        if (!claimId.equals(pendingRewardClaims.get(claimKey))) {
            completeRewardProcessing(expectedState.accountId(), rewardProcessing);
            return;
        }
        boolean completed = !stopping && states.get(expectedState.accountId()) == expectedState;
        try {
            if (completed) {
                notifyComplete(player, quest);
            }
        } catch (RuntimeException exception) {
            Logger.log(LogId.W_6605, exception, expectedState.accountId(), quest.id());
        } finally {
            pendingRewardClaims.remove(claimKey, claimId);
            completeRewardProcessing(expectedState.accountId(), rewardProcessing);
        }
        if (completed) {
            try {
                onCompleted.run();
            } catch (RuntimeException exception) {
                Logger.log(LogId.W_6605, exception, expectedState.accountId(), quest.id());
            }
        }
    }

    /**
     * account単位の報酬反映・保存再試行の待機列を進めます。
     *
     * @param accountId 対象account ID
     * @param rewardProcessing 今回の報酬処理の完了 Future
     */
    private void completeRewardProcessing(
        @NotNull UUID accountId,
        @NotNull CompletableFuture<Void> rewardProcessing
    ) {
        rewardProcessing.complete(null);
        rewardProcessingTails.remove(accountId, rewardProcessing);
    }

    private @Nullable Throwable unwrapFailure(@Nullable Throwable failure) {
        if (failure == null) {
            return null;
        }
        return failure.getCause() == null ? failure : failure.getCause();
    }

    private @Nullable AppliedRewards applyPreparedRewards(
        @NotNull AstPlayer player,
        @NotNull QuestDefinition quest,
        @NotNull PreparedRewards prepared
    ) {
        UUID accountId = player.getAccount().getUuid();
        AccountModel previousAccount = player.getAccount();
        String previousClassId = player.getClassId();
        int previousClassLevel = player.getClassLevel();
        long previousClassExperience = player.getClassExperience();
        boolean hasInventoryRewards = quest.rewards().gold() > 0L
            || !prepared.stackRewards().isEmpty()
            || !prepared.instanceRewards().isEmpty();
        InventoryService.InventoryStateSnapshot inventorySnapshot = hasInventoryRewards
            ? inventoryService.snapshotState(accountId)
            : null;
        if (hasInventoryRewards && inventorySnapshot == null) {
            return null;
        }
        boolean progressChanged = false;
        try {
            if (quest.rewards().gold() > 0L && !inventoryService.addGold(player, quest.rewards().gold())) {
                inventoryService.restoreState(inventorySnapshot);
                return null;
            }
            for (ResolvedItemReward itemReward : prepared.stackRewards()) {
                int added = inventoryService.addItemToNormalInventory(
                    player,
                    itemReward.model(),
                    itemReward.amount(),
                    REWARD_SOURCE
                );
                if (added != itemReward.amount()) {
                    inventoryService.restoreState(inventorySnapshot);
                    return null;
                }
            }
            for (PreparedInstanceReward instanceReward : prepared.instanceRewards()) {
                int added = inventoryService.addPreparedInstanceToNormalInventory(
                    player,
                    instanceReward.model(),
                    instanceReward.instanceType(),
                    instanceReward.instanceId()
                );
                if (added != 1) {
                    inventoryService.restoreState(inventorySnapshot);
                    return null;
                }
            }

            if (quest.rewards().exp() > 0) {
                AccountExperienceResult result = accountService.grantExperienceCached(
                    player.getAccount(),
                    quest.rewards().exp(),
                    player.getUser().getUuid()
                );
                progressChanged = true;
                player.setAccount(result.updatedAccount());
                ClassExperienceResult classProgress = playerClassService.grantClassExperience(
                    player,
                    quest.rewards().exp()
                );
                if (result.leveledUp() || classProgress.getLeveledUp()) {
                    if (skillTreeService != null) {
                        skillTreeService.refreshProgressDerivedState(player);
                    } else {
                        statusService.refreshStatus(player);
                    }
                }
            }
            return new AppliedRewards(
                inventorySnapshot,
                previousAccount,
                previousClassId,
                previousClassLevel,
                previousClassExperience,
                progressChanged
            );
        } catch (RuntimeException exception) {
            AppliedRewards partial = new AppliedRewards(
                inventorySnapshot,
                previousAccount,
                previousClassId,
                previousClassLevel,
                previousClassExperience,
                progressChanged
            );
            rollbackAppliedRewards(player, partial, quest.id());
            Logger.log(LogId.W_6604, exception, accountId, quest.id());
            return null;
        }
    }

    /**
     * 同期報酬反映中の失敗だけを、同じinventory mutation lock内で補償します。
     * API保存開始後や非同期callbackから呼び出してはなりません。
     */
    private void rollbackAppliedRewards(
        @NotNull AstPlayer player,
        @NotNull AppliedRewards applied,
        @NotNull String questId
    ) {
        if (applied.inventorySnapshot() != null) {
            try {
                if (!inventoryService.restoreState(applied.inventorySnapshot())) {
                    Logger.warn(
                        LogId.W_6606,
                        applied.previousAccount().getUuid(),
                        questId
                    );
                }
            } catch (RuntimeException exception) {
                Logger.log(
                    LogId.W_6606,
                    exception,
                    applied.previousAccount().getUuid(),
                    questId
                );
            }
        }
        if (!applied.progressChanged()) {
            return;
        }
        try {
            player.setAccount(applied.previousAccount());
            player.selectClass(applied.previousClassId());
            player.setClassLevel(applied.previousClassLevel());
            player.setClassExperience(applied.previousClassExperience());
            accountService.restoreCachedProgress(
                applied.previousAccount(),
                player.getUser().getUuid()
            );
            if (skillTreeService != null) {
                skillTreeService.refreshProgressDerivedState(player);
            }
            statusService.refreshStatus(player);
            playerClassService.updatePlayerListName(player);
        } catch (RuntimeException exception) {
            Logger.log(
                LogId.W_6606,
                exception,
                applied.previousAccount().getUuid(),
                questId
            );
        }
    }

    private void cleanupPreparedInstances(@NotNull PreparedRewards prepared) {
        List<UUID> equipmentInstanceIds = prepared.instanceRewards().stream()
            .filter(reward -> reward.instanceType() == InventoryInstanceType.EQUIPMENT)
            .map(PreparedInstanceReward::instanceId)
            .toList();
        if (equipmentInstanceIds.isEmpty()) {
            return;
        }
        asyncExecutor.execute(() -> equipmentInstanceIds.forEach(instanceId ->
            itemService.deleteEquipmentInstance(instanceId.toString())
        ));
    }

    private boolean canMeetRequirements(@NotNull AstPlayer player, @NotNull QuestDefinition quest) {
        UUID accountId = player.getAccount().getUuid();
        for (Map.Entry<String, Long> requirement : aggregateRequirementAmounts(quest, false).entrySet()) {
            if (inventoryService.getNormalItemAmount(accountId, requirement.getKey()) < requirement.getValue()) {
                return false;
            }
        }
        return true;
    }

    private boolean consumeRequirements(@NotNull AstPlayer player, @NotNull QuestDefinition quest) {
        if (!canMeetRequirements(player, quest)) {
            return false;
        }
        UUID accountId = player.getAccount().getUuid();
        for (Map.Entry<String, Long> requirement : aggregateRequirementAmounts(quest, true).entrySet()) {
            if (!inventoryService.consumeNormalItem(accountId, requirement.getKey(), requirement.getValue())) {
                return false;
            }
        }
        saveInventory(accountId);
        return true;
    }

    private @NotNull Map<String, Long> aggregateRequirementAmounts(
        @NotNull QuestDefinition quest,
        boolean consumedOnly
    ) {
        Map<String, Long> amounts = new LinkedHashMap<>();
        for (QuestRequirementDefinition requirement : quest.requirements()) {
            if (consumedOnly && !requirement.consume()) {
                continue;
            }
            amounts.merge(
                requirement.item().itemId(),
                (long) requirement.item().amount(),
                (current, added) -> {
                    try {
                        return Math.addExact(current, added);
                    } catch (ArithmeticException ignored) {
                        return Long.MAX_VALUE;
                    }
                }
            );
        }
        return amounts;
    }

    private void saveInventory(@NotNull UUID accountId) {
        asyncExecutor.execute(() -> inventoryService.saveNow(accountId));
    }

    /**
     * クエストGUI向けにitemの表示名を解決します。
     * 未ロードのitemはitemServiceのロード経路から取得し、解決できない場合は内部IDを表示しません。
     *
     * @param item 表示するitem定義。item IDとカテゴリを持つ必要があり、{@code null}は不可
     * @return 色コードを除去したitem表示名。解決できない場合は汎用の未登録表示
     */
    public @NotNull String resolveItemDisplayName(@NotNull QuestItemStackDefinition item) {
        ItemModel model = resolveItem(item);
        if (model == null || model.getName() == null || model.getName().isBlank()) {
            return UNREGISTERED_ITEM_DISPLAY_NAME;
        }
        String displayName = ColorCodeUtil.stripColor(ColorCodeUtil.translateAlternateColorCodes(model.getName()));
        return displayName == null || displayName.isBlank() ? UNREGISTERED_ITEM_DISPLAY_NAME : displayName;
    }

    /**
     * クエスト報酬GUI向けにitemの表示名をカラーコード対応Componentへ変換します。
     * 未ロードのitemはitemServiceのロード経路から取得し、解決できない場合は汎用の未登録表示を返します。
     *
     * @param item 表示するitem定義。item IDとカテゴリを持つ必要があり、{@code null}は不可
     * @return item名のカラーコードを反映したComponent
     */
    public @NotNull Component resolveItemDisplayComponent(@NotNull QuestItemStackDefinition item) {
        ItemModel model = resolveItem(item);
        if (model == null || model.getName() == null || model.getName().isBlank()) {
            return Component.text(UNREGISTERED_ITEM_DISPLAY_NAME);
        }
        return ColorCodeUtil.toComponent(model.getName(), UNREGISTERED_ITEM_DISPLAY_NAME);
    }

    private @Nullable ItemModel resolveItem(@NotNull QuestItemStackDefinition item) {
        ItemModel model = itemService.findLoadedById(item.itemId());
        return model != null ? model : itemService.loadItem(item.itemId(), item.category());
    }

    private @NotNull QuestPlayerState state(@NotNull AstPlayer player) {
        UUID accountId = player.getAccount().getUuid();
        QuestPlayerState state = states.computeIfAbsent(
            accountId,
            id -> new QuestPlayerState(id, Map.of(), Map.of(), Map.of())
        );
        persistenceCoordinator.activate(accountId);
        return state;
    }

    private void save(@NotNull QuestPlayerState state) {
        persistenceCoordinator.recordLatest(state);
        dirtyStates.add(state.accountId());
        saveDueAtMillis.put(state.accountId(), System.currentTimeMillis() + SAVE_DEBOUNCE_MILLIS);
    }

    boolean hasPendingSave(@NotNull UUID accountId) {
        return persistenceCoordinator.hasPendingSave(accountId);
    }

    boolean hasPendingRewardClaim(@NotNull UUID accountId, @NotNull String questId) {
        return pendingRewardClaims.containsKey(new RewardClaimKey(accountId, questId));
    }

    private void flushDueStates() {
        long now = System.currentTimeMillis();
        retryRewardPersistence(now);
        for (UUID accountId : List.copyOf(dirtyStates)) {
            clearPersistedMarker(accountId);
            if (!dirtyStates.contains(accountId)) {
                persistenceCoordinator.evictReleasedPersisted(accountId);
                continue;
            }
            long saveDueAt = saveDueAtMillis.getOrDefault(accountId, Long.MAX_VALUE);
            long retryNotBefore = persistenceCoordinator.retryNotBeforeMillis(accountId);
            if (Math.max(saveDueAt, retryNotBefore) > now) {
                continue;
            }
            flushStateAsync(accountId);
        }
    }

    private void flushStateAsync(@NotNull UUID accountId) {
        CompletableFuture<Void> future = persistenceCoordinator.flushLatest(accountId);
        future.thenRun(() -> {
            clearPersistedMarker(accountId);
            persistenceCoordinator.evictReleasedPersisted(accountId);
        });
    }

    private void clearPersistedMarker(@NotNull UUID accountId) {
        if (persistenceCoordinator.isLatestPersisted(accountId)) {
            dirtyStates.remove(accountId);
            saveDueAtMillis.remove(accountId);
        }
    }

    private void notifyReady(@NotNull AstPlayer player, @NotNull QuestDefinition quest) {
        send(player, PlayerMsgId.P_6607, quest.name());
        playQuestEffect(player.getBukkit());
    }

    private void notifyComplete(@NotNull AstPlayer player, @NotNull QuestDefinition quest) {
        send(player, PlayerMsgId.P_6608, quest.name());
        playQuestEffect(player.getBukkit());
        questCompletedListener.accept(player, quest.id());
    }

    private void playQuestEffect(@NotNull Player player) {
        Location location = player.getLocation().add(0.0D, 1.0D, 0.0D);
        GuiSound.CONFIRM.play(player);
        particleDisplayService.spawnForNearbyViewers(location, SharedParticleDefinitions.PLAYER_LEVEL_UP_TOTEM);
        particleDisplayService.spawnForNearbyViewers(location, SharedParticleDefinitions.PLAYER_LEVEL_UP_END_ROD);
    }

    private void send(@NotNull AstPlayer player, @NotNull PlayerMsgId msgId, Object... args) {
        PlayerMessageService.getInstance().send(player, msgId, args);
    }

    private @NotNull String stripPrefix(@NotNull String raw) {
        String trimmed = raw.trim();
        int index = trimmed.indexOf(':');
        return (index < 0 ? trimmed : trimmed.substring(index + 1)).trim();
    }

    private @Nullable String stripNullablePrefix(@Nullable String raw) {
        return raw == null || raw.isBlank() ? null : stripPrefix(raw);
    }

    /**
     * 非同期ロード結果と、適用時の世代検証に使用するトークンです。
     *
     * @param accountId 対象アカウント ID
     * @param loadToken ロード要求トークン
     * @param generation ロード時に確認した保存世代
     * @param state ロード済み状態
     */
    public record InitialState(
        @NotNull UUID accountId,
        long loadToken,
        long generation,
        @NotNull QuestPlayerState state
    ) {
        private @NotNull QuestStatePersistenceCoordinator.LoadedState coordinatorState() {
            return new QuestStatePersistenceCoordinator.LoadedState(accountId, loadToken, generation, state);
        }
    }

    private record ResolvedItemReward(@NotNull ItemModel model, int amount) {
    }

    private record PreparedInstanceReward(
        @NotNull ItemModel model,
        @NotNull InventoryInstanceType instanceType,
        @NotNull UUID instanceId
    ) {
    }

    private record PreparedRewards(
        boolean success,
        @NotNull List<ResolvedItemReward> stackRewards,
        @NotNull List<PreparedInstanceReward> instanceRewards
    ) {
        private PreparedRewards {
            stackRewards = List.copyOf(stackRewards);
            instanceRewards = List.copyOf(instanceRewards);
        }

        private static @NotNull PreparedRewards success(
            @NotNull List<ResolvedItemReward> stackRewards,
            @NotNull List<PreparedInstanceReward> instanceRewards
        ) {
            return new PreparedRewards(true, stackRewards, instanceRewards);
        }

        private static @NotNull PreparedRewards failure(
            @NotNull List<ResolvedItemReward> stackRewards,
            @NotNull List<PreparedInstanceReward> instanceRewards
        ) {
            return new PreparedRewards(false, stackRewards, instanceRewards);
        }
    }

    private record AppliedRewards(
        @Nullable InventoryService.InventoryStateSnapshot inventorySnapshot,
        @NotNull AccountModel previousAccount,
        @NotNull String previousClassId,
        int previousClassLevel,
        long previousClassExperience,
        boolean progressChanged
    ) {
    }

    private record RewardPersistenceRetry(long dueAtMillis, @NotNull Runnable action) {
    }

    private record RewardClaimKey(@NotNull UUID accountId, @NotNull String questId) {
    }
}
