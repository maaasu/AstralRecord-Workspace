package io.github.maaasu.astralRecord.feature.quest.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.feature.account.model.AccountExperienceResult;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryInstanceType;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateSection;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
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
    private final Map<UUID, Long> stateRevisions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pendingStateRevisions = new ConcurrentHashMap<>();
    private final Set<UUID> releaseWhenAcknowledged = ConcurrentHashMap.newKeySet();
    private final Map<RewardClaimKey, UUID> pendingRewardClaims = new ConcurrentHashMap<>();
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
        this.persistenceCoordinator = new QuestStatePersistenceCoordinator(stateRepository::load);
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
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        for (QuestPlayerState state : List.copyOf(states.values())) {
            markStateChanged(state, false);
        }
        pendingRewardClaims.clear();
        rewardProcessingTails.clear();
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
        QuestPlayerState current = states.get(accountId);
        if (current == null) {
            return;
        }
        persistenceCoordinator.recordLatest(current);
        persistenceCoordinator.markReleased(accountId);
        releaseWhenAcknowledged.add(accountId);
        if (!pendingStateRevisions.containsKey(accountId)) {
            states.remove(accountId, current);
            releaseWhenAcknowledged.remove(accountId);
            persistenceCoordinator.evictReleased(accountId);
        }
    }

    /**
     * runtime state を保持したまま、現在のクエスト状態を API/SQL へ即時保存します。
     * チャンネル移動はこの Future の正常完了を ACK として扱います。
     *
     * @param accountId 対象アカウント ID
     * @return 最新世代の保存完了 Future
     */
    public @NotNull CompletableFuture<Void> flushState(@NotNull UUID accountId) {
        return inventoryService.saveForBoundary(accountId).thenCompose(saved -> Boolean.TRUE.equals(saved)
            ? CompletableFuture.completedFuture(null)
            : CompletableFuture.failedFuture(new IllegalStateException("quest_state_not_persisted")));
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
        int maxActive = maxActiveQuests(player);
        if (state.activeQuests().size() >= maxActive) {
            send(player, PlayerMsgId.P_6601);
            return false;
        }
        UUID accountId = state.accountId();
        String acceptedNpcId = stripNullablePrefix(npcId);
        inventoryService.executeCriticalPlayerMutation(accountId, () -> {
            InventoryService.InventoryStateSnapshot inventoryBefore = inventoryService.snapshotState(accountId);
            if (inventoryBefore == null) {
                throw new QuestAcceptRejectedException(false);
            }
            QuestMutationCheckpoint questBefore;
            synchronized (this) {
                QuestPlayerState current = states.get(accountId);
                if (current != state || current.activeQuests().containsKey(quest.id())
                    || current.activeQuests().size() >= maxActive) {
                    throw new QuestAcceptRejectedException(false);
                }
                questBefore = captureQuestMutation(accountId, current);
            }
            if (!consumeRequirementsStateOnly(accountId, quest)) {
                inventoryService.restoreState(inventoryBefore);
                throw new QuestAcceptRejectedException(true);
            }
            synchronized (this) {
                state.activeQuests().put(quest.id(), QuestProgress.start(quest, acceptedNpcId));
                markStateChanged(state, false);
            }
            return new io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator.CriticalMutation<>(
                true,
                () -> {
                    inventoryService.restoreState(inventoryBefore);
                    restoreQuestMutation(accountId, state, questBefore);
                }
            );
        }).whenComplete((accepted, failure) -> mainExecutor.execute(() -> {
            if (failure != null || !Boolean.TRUE.equals(accepted)) {
                Throwable cause = unwrapFailure(failure);
                send(player, cause instanceof QuestAcceptRejectedException rejected && rejected.requirementFailure
                    ? PlayerMsgId.P_6602
                    : cause instanceof QuestAcceptRejectedException
                        ? PlayerMsgId.P_6600
                        : criticalSaveFailed(cause) ? PlayerMsgId.P_6609 : PlayerMsgId.P_6606);
                return;
            }
            questAcceptedListener.accept(player, quest.id());
            send(player, PlayerMsgId.P_6603, quest.name());
            player.getBukkit().playSound(player.getBukkit().getLocation(), Sound.UI_TOAST_IN, SoundCategory.PLAYERS, 0.7F, 1.1F);
        }));
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
            boolean progressChanged = false;
            for (QuestObjectiveDefinition objective : quest.objectives()) {
                if (objective.type() != type
                    || !objective.targetId().equalsIgnoreCase(stripPrefix(targetId))
                    || (objective.targetLevel() != null && !objective.targetLevel().equals(targetLevel))) {
                    continue;
                }
                int next = Math.min(objective.amount(), progress.progress(objective.id()) + 1);
                if (next != progress.progress(objective.id())) {
                    progress.setProgress(objective.id(), next);
                    progressChanged = true;
                }
            }
            if (isComplete(quest, progress)) {
                if (quest.isAutoReward()) {
                    // complete() は ready 状態の通常保存と、報酬を含む critical snapshot を担当する。
                    // 開始できなかった場合だけ、この呼出で進んだ objective を通常保存へ残す。
                    if (complete(player, state, quest)) {
                        progressChanged = false;
                    }
                } else {
                    progress.readyToTurnIn(true);
                    progressChanged = true;
                    notifyReady(player, quest);
                }
            }
            changed |= progressChanged;
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
                    instanceRewards.add(new PreparedInstanceReward(
                        model, InventoryInstanceType.EQUIPMENT, null));
                }
            }
            return PreparedRewards.success(stackRewards, instanceRewards);
        } catch (RuntimeException exception) {
            Logger.log(LogId.W_6601, exception, accountId, quest.id(), exception.getMessage());
            return PreparedRewards.failure(stackRewards, instanceRewards);
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
        UUID accountId = expectedState.accountId();
        inventoryService.executeCriticalPlayerMutation(accountId, () -> {
            Runnable equipmentRollback = itemService.captureEquipmentStateRollback(accountId);
            QuestMutationCheckpoint questBefore;
            synchronized (this) {
                QuestPlayerState latest = states.get(accountId);
                QuestProgress latestProgress = latest == null ? null : latest.activeQuests().get(quest.id());
                if (latest != currentState || latestProgress == null || !latestProgress.readyToTurnIn()) {
                    throw new IllegalStateException("Quest completion state changed");
                }
                questBefore = captureQuestMutation(accountId, latest);
            }
            AppliedRewards applied = null;
            try {
                applied = applyPreparedRewards(player, quest, prepared);
                if (applied == null) {
                    throw new IllegalStateException("Quest rewards could not be applied");
                }
                synchronized (this) {
                    currentState.activeQuests().remove(quest.id());
                    long now = System.currentTimeMillis();
                    currentState.completedAt().put(quest.id(), now);
                    if (quest.repeatMode() == QuestRepeatMode.COOLDOWN && quest.cooldownSeconds() > 0L) {
                        currentState.cooldownUntil().put(quest.id(), now + quest.cooldownSeconds() * 1000L);
                    }
                    markStateChanged(currentState, false);
                }
                AppliedRewards completedRewards = applied;
                return new io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator.CriticalMutation<>(
                    completedRewards,
                    () -> {
                        rollbackAppliedRewards(player, completedRewards, quest.id());
                        equipmentRollback.run();
                        restoreQuestMutation(accountId, currentState, questBefore);
                    }
                );
            } catch (RuntimeException | Error failure) {
                if (applied != null) rollbackAppliedRewards(player, applied, quest.id());
                equipmentRollback.run();
                restoreQuestMutation(accountId, currentState, questBefore);
                throw failure;
            }
        }).whenComplete((applied, failure) -> mainExecutor.execute(() -> {
            if (failure != null || applied == null) {
                cleanupPreparedInstances(prepared);
                pendingRewardClaims.remove(claimKey, claimId);
                inventoryService.refreshManagedInventoryUi(player);
                send(player, criticalSaveFailed(unwrapFailure(failure))
                    ? PlayerMsgId.P_6609
                    : PlayerMsgId.P_6606);
                completeRewardProcessing(accountId, rewardProcessing);
                return;
            }
            inventoryService.refreshManagedInventoryUi(player);
            refreshRewardDerivedState(player, applied);
            finishRewardPersistence(player, currentState, quest, claimKey, claimId,
                rewardProcessing, onCompleted);
        }));
        return true;
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

    private boolean criticalSaveFailed(@Nullable Throwable failure) {
        return failure instanceof InventorySaveCoordinator.CriticalPlayerStateSaveException
            || failure instanceof InventorySaveCoordinator.ExternalOperationPendingException;
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
            if (quest.rewards().gold() > 0L && !inventoryService.addGoldStateOnly(accountId, quest.rewards().gold())) {
                inventoryService.restoreState(inventorySnapshot);
                return null;
            }
            for (ResolvedItemReward itemReward : prepared.stackRewards()) {
                int added = inventoryService.addItemToNormalInventoryStateOnly(
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
                UUID instanceId = instanceReward.instanceId();
                if (instanceId == null) {
                    EquipmentInstance created = itemService.createLocalEquipmentInstance(instanceReward.model(), accountId);
                    if (created == null) {
                        inventoryService.restoreState(inventorySnapshot);
                        return null;
                    }
                    instanceId = UUID.fromString(created.getEquipmentInstanceId());
                }
                int added = inventoryService.addPreparedInstanceToNormalInventoryStateOnly(
                    player,
                    instanceReward.model(),
                    instanceReward.instanceType(),
                    instanceId
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
                playerClassService.grantClassExperienceStateOnly(
                    player,
                    quest.rewards().exp()
                );
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
        } catch (RuntimeException exception) {
            Logger.log(
                LogId.W_6606,
                exception,
                applied.previousAccount().getUuid(),
                questId
            );
        }
    }

    private void refreshRewardDerivedState(@NotNull AstPlayer player, @NotNull AppliedRewards applied) {
        if (!applied.progressChanged()) {
            return;
        }
        if (skillTreeService != null) {
            skillTreeService.refreshProgressDerivedState(player);
        } else {
            statusService.refreshStatus(player);
        }
        playerClassService.updatePlayerListName(player);
    }

    private void cleanupPreparedInstances(@NotNull PreparedRewards prepared) {
        List<UUID> equipmentInstanceIds = prepared.instanceRewards().stream()
            .filter(reward -> reward.instanceType() == InventoryInstanceType.EQUIPMENT)
            .map(PreparedInstanceReward::instanceId)
            .filter(java.util.Objects::nonNull)
            .toList();
        if (equipmentInstanceIds.isEmpty()) {
            return;
        }
        equipmentInstanceIds.forEach(instanceId ->
            itemService.evictEquipmentInstanceFromCache(instanceId.toString())
        );
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

    private boolean consumeRequirementsStateOnly(@NotNull UUID accountId, @NotNull QuestDefinition quest) {
        for (Map.Entry<String, Long> requirement : aggregateRequirementAmounts(quest, true).entrySet()) {
            if (!inventoryService.consumeNormalItem(accountId, requirement.getKey(), requirement.getValue())) {
                return false;
            }
        }
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
        markStateChanged(state, true);
    }

    private void markStateChanged(@NotNull QuestPlayerState state, boolean enqueueSave) {
        persistenceCoordinator.recordLatest(state);
        long revision = stateRevisions.merge(state.accountId(), 1L, Math::addExact);
        pendingStateRevisions.put(state.accountId(), revision);
        dirtyStates.add(state.accountId());
        saveDueAtMillis.put(state.accountId(), System.currentTimeMillis() + SAVE_DEBOUNCE_MILLIS);
        if (enqueueSave) {
            inventoryService.queueLocalPlayerSave(state.accountId());
        }
    }

    private @NotNull QuestMutationCheckpoint captureQuestMutation(
        @NotNull UUID accountId,
        @NotNull QuestPlayerState state
    ) {
        return new QuestMutationCheckpoint(
            state.snapshot(),
            stateRevisions.get(accountId),
            pendingStateRevisions.get(accountId),
            dirtyStates.contains(accountId),
            saveDueAtMillis.get(accountId),
            releaseWhenAcknowledged.contains(accountId)
        );
    }

    private void restoreQuestMutation(
        @NotNull UUID accountId,
        @NotNull QuestPlayerState state,
        @NotNull QuestMutationCheckpoint checkpoint
    ) {
        synchronized (this) {
            state.restore(checkpoint.state);
            states.put(accountId, state);
            restoreMapValue(stateRevisions, accountId, checkpoint.revision);
            restoreMapValue(pendingStateRevisions, accountId, checkpoint.pendingRevision);
            restoreMapValue(saveDueAtMillis, accountId, checkpoint.saveDueAtMillis);
            if (checkpoint.dirty) dirtyStates.add(accountId);
            else dirtyStates.remove(accountId);
            if (checkpoint.releaseWhenAcknowledged) releaseWhenAcknowledged.add(accountId);
            else releaseWhenAcknowledged.remove(accountId);
            persistenceCoordinator.recordLatest(state);
        }
    }

    private static <T> void restoreMapValue(
        @NotNull Map<UUID, T> values,
        @NotNull UUID accountId,
        @Nullable T value
    ) {
        if (value == null) values.remove(accountId);
        else values.put(accountId, value);
    }

    boolean hasPendingSave(@NotNull UUID accountId) {
        return pendingStateRevisions.containsKey(accountId);
    }

    /** inventory と同じ SQL transaction へ含めるクエスト完成状態を捕捉します。 */
    public @Nullable PlayerStateSection snapshotPlayerState(@NotNull UUID accountId) {
        final QuestPlayerState captured;
        final long capturedRevision;
        synchronized (this) {
            Long pendingRevision = pendingStateRevisions.get(accountId);
            QuestPlayerState current = states.get(accountId);
            if (pendingRevision == null || current == null) {
                return null;
            }
            captured = current.snapshot();
            capturedRevision = pendingRevision;
        }
        JsonObject payload = stateRepository.createSnapshotSection(captured, capturedRevision);
        return new PlayerStateSection("questState", payload,
            acknowledged -> acknowledgeSnapshot(accountId, captured, capturedRevision, acknowledged));
    }

    private void acknowledgeSnapshot(
        @NotNull UUID accountId,
        @NotNull QuestPlayerState captured,
        long capturedRevision,
        @NotNull JsonElement acknowledged
    ) {
        if (!acknowledged.isJsonObject()) {
            throw new IllegalStateException("Quest acknowledgement must be an object");
        }
        JsonObject ack = acknowledged.getAsJsonObject();
        if (!ack.has("clientRevision") || ack.get("clientRevision").getAsLong() != capturedRevision
            || !ack.has("version") || ack.get("version").getAsInt() != captured.persistedVersion() + 1) {
            throw new IllegalStateException("Quest acknowledgement version mismatch");
        }
        int persistedVersion = ack.get("version").getAsInt();
        synchronized (this) {
            QuestPlayerState current = states.get(accountId);
            if (current != null && current.persistedVersion() == captured.persistedVersion()) {
                current.acknowledgePersistedVersion(persistedVersion);
                persistenceCoordinator.recordLatest(current);
            }
            pendingStateRevisions.computeIfPresent(accountId,
                (ignored, revision) -> revision == capturedRevision ? null : revision);
            clearPersistedMarker(accountId);
            if (!pendingStateRevisions.containsKey(accountId) && releaseWhenAcknowledged.remove(accountId)) {
                states.remove(accountId);
                persistenceCoordinator.evictReleased(accountId);
            }
        }
    }

    boolean hasPendingRewardClaim(@NotNull UUID accountId, @NotNull String questId) {
        return pendingRewardClaims.containsKey(new RewardClaimKey(accountId, questId));
    }

    private void flushDueStates() {
        long now = System.currentTimeMillis();
        for (UUID accountId : List.copyOf(dirtyStates)) {
            clearPersistedMarker(accountId);
            if (!dirtyStates.contains(accountId)) {
                persistenceCoordinator.evictReleased(accountId);
                continue;
            }
            long saveDueAt = saveDueAtMillis.getOrDefault(accountId, Long.MAX_VALUE);
            if (saveDueAt > now) {
                continue;
            }
            flushStateAsync(accountId);
        }
    }

    private void flushStateAsync(@NotNull UUID accountId) {
        inventoryService.queueLocalPlayerSave(accountId);
        saveDueAtMillis.put(accountId, System.currentTimeMillis() + SAVE_DEBOUNCE_MILLIS);
    }

    private void clearPersistedMarker(@NotNull UUID accountId) {
        if (!pendingStateRevisions.containsKey(accountId)) {
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
        @Nullable UUID instanceId
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

    private record RewardClaimKey(@NotNull UUID accountId, @NotNull String questId) {
    }

    private record QuestMutationCheckpoint(
        @NotNull QuestPlayerState state,
        @Nullable Long revision,
        @Nullable Long pendingRevision,
        boolean dirty,
        @Nullable Long saveDueAtMillis,
        boolean releaseWhenAcknowledged
    ) {
    }

    private static final class QuestAcceptRejectedException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final boolean requirementFailure;

        private QuestAcceptRejectedException(boolean requirementFailure) {
            this.requirementFailure = requirementFailure;
        }
    }
}
