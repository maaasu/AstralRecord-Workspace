package io.github.maaasu.astralRecord.feature.guide.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.guide.model.GuideConditionType;
import io.github.maaasu.astralRecord.feature.guide.model.GuideEntry;
import io.github.maaasu.astralRecord.feature.guide.model.GuideStep;
import io.github.maaasu.astralRecord.feature.guide.model.GuideStepKey;
import io.github.maaasu.astralRecord.feature.guide.repository.GuideProgressRepository;
import io.github.maaasu.astralRecord.feature.guide.repository.GuideRepository;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateSection;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;

public class GuideService {
    private static final String PROGRESS_SECTION_NAME = "guideProgress";
    private static final Pattern REFERENCE_PATTERN = Pattern.compile("\\{([a-zA-Z_]+):([^}]+)}");

    /** 初参加者へ案内する導入ガイドの ID。 */
    public static final String INITIAL_GUIDE_ID = "beginner_onboarding";
    /** 導入ガイドを初めて開いたことを記録する step ID。 */
    public static final String INITIAL_GUIDE_OPEN_STEP_ID = "open_guide";
    private static final String REPAIR_GUIDE_ID = "first_equipment_repair";
    private static final String REPAIR_NOTICE_STEP_ID = "notice_broken_equipment";
    private static final String REPAIR_USE_STEP_ID = "repair_with_sindri";

    private final AstralRecord plugin;
    private final GuideRepository repository;
    private final GuideProgressRepository progressRepository;
    private final PlayerMessageService playerMessageService;
    private final ItemService itemService;
    private final PlayerClassService playerClassService;
    private final WorldService worldService;
    private final InventoryService inventoryService;
    private final Map<String, GuideEntry> loadedGuides = new LinkedHashMap<>();
    private final Map<UUID, ProgressState> progressByAccount = new ConcurrentHashMap<>();
    private final Map<UUID, Long> progressGenerations = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Boolean>> progressLoadFutures = new ConcurrentHashMap<>();
    private final Map<UUID, List<GuideConditionEvent>> pendingConditionsByAccount = new ConcurrentHashMap<>();
    private final Set<UUID> initialGuideOpenedAccounts = ConcurrentHashMap.newKeySet();
    private volatile Consumer<AstPlayer> allGuidesCompletedListener = ignored -> { };
    private Consumer<AstPlayer> stepSoundPlayer = player -> GuiSound.GUIDE_STEP.play(player.getBukkit());
    private Consumer<AstPlayer> guideSoundPlayer = player -> GuiSound.GUIDE_COMPLETE.play(player.getBukkit());

    /**
     * ガイドマスターとアカウント進行を扱うサービスを生成します。
     *
     * @param plugin 非同期API通信のschedulerに使用するPlugin
     * @param repository ガイドマスターrepository
     * @param progressRepository ガイド進行repository
     * @param itemService item参照解決サービス
     * @param playerClassService class参照解決サービス
     * @param worldService world参照解決サービス
     * @param playerMessageService 達成通知サービス
     * @param inventoryService プレイヤー完成状態の集約保存サービス
     */
    public GuideService(
        @NotNull AstralRecord plugin,
        @NotNull GuideRepository repository,
        @NotNull GuideProgressRepository progressRepository,
        @NotNull ItemService itemService,
        @NotNull PlayerClassService playerClassService,
        @NotNull WorldService worldService,
        @NotNull PlayerMessageService playerMessageService,
        @NotNull InventoryService inventoryService
    ) {
        this.plugin = plugin;
        this.repository = repository;
        this.progressRepository = progressRepository;
        this.itemService = itemService;
        this.playerClassService = playerClassService;
        this.worldService = worldService;
        this.playerMessageService = playerMessageService;
        this.inventoryService = inventoryService;
    }

    public synchronized int loadAll() {
        List<GuideEntry> snapshot = loadEntrySnapshot();
        replaceEntrySnapshot(snapshot);
        return loadedGuides.size();
    }

    /**
     * ガイド定義を読み込み、公開前のスナップショットを作成します。
     *
     * @return ガイド定義スナップショット
     */
    public @NotNull List<GuideEntry> loadEntrySnapshot() {
        try {
            return repository.findAll().stream()
                .sorted(Comparator
                    .comparingInt((GuideEntry guide) -> categoryOrder(guide.category()))
                    .thenComparingInt(GuideEntry::displayOrder)
                    .thenComparing(GuideEntry::id))
                .toList();
        } catch (RuntimeException e) {
            Logger.log(LogId.E_5181, e, failureReason(e));
            synchronized (this) {
                return List.copyOf(loadedGuides.values());
            }
        }
    }

    /**
     * 準備済みガイド定義を実行時キャッシュへ一括反映します。
     *
     * @param snapshot ガイド定義スナップショット
     */
    public synchronized void replaceEntrySnapshot(@NotNull List<GuideEntry> snapshot) {
        loadedGuides.clear();
        for (GuideEntry guide : snapshot) {
            loadedGuides.put(guide.id(), guide);
        }
    }

    public synchronized @NotNull List<GuideEntry> getAll() {
        return loadedGuides.values().stream()
            .sorted(Comparator
                .comparingInt((GuideEntry guide) -> categoryOrder(guide.category()))
                .thenComparingInt(GuideEntry::displayOrder)
                .thenComparing(GuideEntry::id))
            .toList();
    }

    public synchronized @Nullable GuideEntry getById(@NotNull String guideId) {
        return loadedGuides.get(guideId);
    }

    /**
     * アカウントのガイド進行を非同期で読み込みます。読み込み済みの場合は即時完了したFutureを返します。
     *
     * @param accountId アカウント ID
     * @return 進行を正常に取得して現世代へ反映できた場合にtrueで完了するFuture。
     *         読み込み失敗または古い世代の結果になった場合はfalse
     */
    public @NotNull CompletableFuture<Boolean> loadProgressAsync(@NotNull UUID accountId) {
        ProgressState cached = progressByAccount.get(accountId);
        if (cached != null) {
            synchronized (cached) {
                cached.released = false;
            }
            return CompletableFuture.completedFuture(true);
        }
        CompletableFuture<Boolean> existing = progressLoadFutures.get(accountId);
        if (existing != null) {
            return existing;
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        existing = progressLoadFutures.putIfAbsent(accountId, future);
        if (existing != null) {
            return existing;
        }
        long generation = progressGenerations.getOrDefault(accountId, 0L);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean loadedSuccessfully = false;
            try {
                Set<GuideStepKey> loaded = ConcurrentHashMap.newKeySet();
                loaded.addAll(progressRepository.findByAccountId(accountId));
                if (progressGenerations.getOrDefault(accountId, 0L) == generation) {
                    ProgressState state = new ProgressState(loaded);
                    progressByAccount.put(accountId, state);
                    List<GuideConditionEvent> pending = pendingConditionsByAccount.remove(accountId);
                    if (pending != null && !pending.isEmpty()) {
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (progressGenerations.getOrDefault(accountId, 0L) != generation
                                || progressByAccount.get(accountId) != state) {
                                return;
                            }
                            for (GuideConditionEvent event : pending) {
                                if (event.player().getBukkit().isOnline()
                                    && AstPlayerCache.get(event.player().getBukkit()) == event.player()) {
                                    recordCondition(event.player(), event.type(), event.targetId(),
                                        event.targetLevel(), event.notifyPlayer());
                                }
                            }
                        });
                    }
                    loadedSuccessfully = true;
                }
            } catch (RuntimeException e) {
                Logger.log(LogId.E_5182, e, "load", accountId, failureReason(e));
            } finally {
                progressLoadFutures.remove(accountId, future);
                future.complete(loadedSuccessfully);
            }
        });
        return future;
    }

    /**
     * ログアウトしたアカウントの進行キャッシュを破棄します。
     *
     * @param accountId アカウント ID
     */
    public void releaseProgress(@NotNull UUID accountId) {
        progressGenerations.merge(accountId, 1L, Long::sum);
        ProgressState state = progressByAccount.get(accountId);
        if (state != null) {
            synchronized (state) {
                state.released = true;
                if (!state.dirty) {
                    progressByAccount.remove(accountId, state);
                }
            }
        }
        progressLoadFutures.remove(accountId);
        pendingConditionsByAccount.remove(accountId);
        initialGuideOpenedAccounts.remove(accountId);
    }

    /**
     * 保存不能の復旧時に、指定 account の未保存ガイド進行を保存せず破棄します。
     *
     * @param accountId 破棄対象 account ID
     */
    public void discardAccountState(@NotNull UUID accountId) {
        progressGenerations.merge(accountId, 1L, Long::sum);
        progressByAccount.remove(accountId);
        progressLoadFutures.remove(accountId);
        pendingConditionsByAccount.remove(accountId);
        initialGuideOpenedAccounts.remove(accountId);
    }

    /**
     * ガイドが達成済みか判定します。
     *
     * @param accountId アカウント ID。未ロード時は null 可
     * @param guide 対象ガイド
     * @return 全手順が達成済みの場合は true
     */
    public boolean isGuideCompleted(@Nullable UUID accountId, @NotNull GuideEntry guide) {
        return !guide.steps().isEmpty()
            && guide.steps().stream().allMatch(step -> isStepCompleted(accountId, guide.id(), step.id()));
    }

    /**
     * 進行が読み込まれ、未完了のガイドが残っている場合だけ true を返します。
     * 後から追加されたガイドも現在のマスターに照らして判定します。
     * @param accountId 対象アカウント ID
     * @return 読み込み済みで未完了のガイドがあれば true
     */
    public boolean hasIncompleteGuide(@NotNull UUID accountId) {
        ProgressState state = progressByAccount.get(accountId);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            if (state.released) {
                return false;
            }
            return getAll().stream().anyMatch(guide -> firstIncompleteStep(guide, state.completed) != null);
        }
    }

    /**
     * ガイド進行が現在のセッションに読み込まれているか確認します。
     *
     * @param accountId 対象アカウント ID
     * @return 読み込み済みで、解放されていなければ true
     */
    public boolean isProgressLoaded(@NotNull UUID accountId) {
        ProgressState state = progressByAccount.get(accountId);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            return !state.released;
        }
    }

    /**
     * ログイン時に読み込んだ装備とペットから、保存前ログアウトで失われたガイドイベントを補います。
     * 呼び出し元はガイド進行とプレイヤー状態の読み込み完了後、現在のセッションを確認してください。
     * 既に達成した手順は再通知しません。
     *
     * @param player 現在ログイン中のプレイヤー
     * @param hasHatchedPet 読み込んだペットに孵化済みの個体がいる場合 true
     */
    public void reconcileLoadedAccount(@NotNull AstPlayer player, boolean hasHatchedPet) {
        UUID accountId = player.getAccount().getUuid();
        if (!isProgressLoaded(accountId)) {
            return;
        }
        if (itemService.hasLoadedBrokenEquipment(accountId)
            && !isStepCompleted(accountId, REPAIR_GUIDE_ID, REPAIR_NOTICE_STEP_ID)) {
            recordCondition(player, GuideConditionType.EQUIPMENT_BROKEN, null);
        }
        if (hasHatchedPet) {
            recordCondition(player, GuideConditionType.PET_HATCHED, null);
        }
    }

    /**
     * 現在のガイドマスターの全手順が完了したか確認します。
     *
     * @param accountId 対象アカウント ID
     * @return 進行読み込み済み、ガイドが存在し全手順完了なら true
     */
    public boolean areAllGuidesCompleted(@NotNull UUID accountId) {
        ProgressState state = progressByAccount.get(accountId);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            List<GuideEntry> guides = getAll();
            return !state.released && !guides.isEmpty()
                && guides.stream().allMatch(guide -> !guide.steps().isEmpty()
                    && firstIncompleteStep(guide, state.completed) == null);
        }
    }

    /**
     * 全ガイド完了後の通知処理を登録します。手順の新規達成時に呼びます。
     *
     * @param listener 完了したプレイヤーを受け取るリスナー
     */
    public void setAllGuidesCompletedListener(@NotNull Consumer<AstPlayer> listener) {
        allGuidesCompletedListener = java.util.Objects.requireNonNull(listener);
    }

    /** Bukkit を起動しない単体テストで効果音だけを差し替えます。 */
    void setSoundPlayers(@NotNull Consumer<AstPlayer> step, @NotNull Consumer<AstPlayer> guide) {
        stepSoundPlayer = java.util.Objects.requireNonNull(step);
        guideSoundPlayer = java.util.Objects.requireNonNull(guide);
    }

    /**
     * ガイド手順が達成済みか判定します。
     *
     * @param accountId アカウント ID。未ロード時は null 可
     * @param guideId ガイド ID
     * @param stepId 手順 ID
     * @return 達成済みの場合は true
     */
    public boolean isStepCompleted(@Nullable UUID accountId, @NotNull String guideId, @NotNull String stepId) {
        ProgressState state = accountId == null ? null : progressByAccount.get(accountId);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            return state.completed.contains(new GuideStepKey(guideId, stepId));
        }
    }

    /**
     * 導入ガイドを開いたことが確認済みか判定します。
     * <p>
     * 進行ロード中に開いた場合は pending event も確認するため、ロード完了前に title が再表示されません。
     *
     * @param accountId アカウント ID
     * @return 導入ガイド開封済みなら true
     */
    public boolean isInitialGuideOpened(@Nullable UUID accountId) {
        if (accountId == null) {
            return false;
        }
        if (initialGuideOpenedAccounts.contains(accountId)) {
            return true;
        }
        if (isStepCompleted(accountId, INITIAL_GUIDE_ID, INITIAL_GUIDE_OPEN_STEP_ID)) {
            return true;
        }
        List<GuideConditionEvent> pending = pendingConditionsByAccount.get(accountId);
        return pending != null && pending.stream()
            .anyMatch(event -> event.type() == GuideConditionType.GUIDE_OPENED);
    }

    /**
     * ゲーム内イベントをガイド条件として評価し、条件に一致する未達成手順を更新します。
     *
     * @param player イベントを実行したプレイヤー
     * @param eventType イベント種別
     * @param targetId 対象 ID。対象を持たないイベントでは null
     */
    public void recordCondition(
        @NotNull AstPlayer player,
        @NotNull GuideConditionType eventType,
        @Nullable String targetId
    ) {
        recordCondition(player, eventType, targetId, null);
    }

    /** Mob などレベルを持つイベントをガイド条件として評価します。 */
    public void recordCondition(
        @NotNull AstPlayer player,
        @NotNull GuideConditionType eventType,
        @Nullable String targetId,
        @Nullable Integer targetLevel
    ) {
        recordCondition(player, eventType, targetId, targetLevel, true);
    }

    /** 現在のプレイヤーセッションの状態変更を、効果音・チャット通知なしでガイドへ記録します。 */
    public void recordConditionSilently(
        @NotNull AstPlayer player,
        @NotNull GuideConditionType eventType,
        @Nullable String targetId
    ) {
        recordCondition(player, eventType, targetId, null, false);
    }

    private void recordCondition(
        @NotNull AstPlayer player,
        @NotNull GuideConditionType eventType,
        @Nullable String targetId,
        @Nullable Integer targetLevel,
        boolean notifyPlayer
    ) {
        UUID accountId = player.getAccount().getUuid();
        if (eventType == GuideConditionType.GUIDE_OPENED) {
            initialGuideOpenedAccounts.add(accountId);
        }
        ProgressState state = progressByAccount.get(accountId);
        if (state == null) {
            pendingConditionsByAccount.computeIfAbsent(accountId, ignored -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(new GuideConditionEvent(player, eventType, targetId, targetLevel, notifyPlayer));
            loadProgressAsync(accountId);
            return;
        }

        List<CompletedStep> newlyCompleted = new ArrayList<>();
        synchronized (state) {
            for (GuideEntry guide : getAll()) {
                for (GuideStep step : GuideProgressEvaluator.evaluate(
                    guide, state.completed, eventType, targetId, targetLevel
                )) {
                    // 修理ガイドは破損を見てからのシンドリ使用だけを手順として認める。
                    if (REPAIR_GUIDE_ID.equals(guide.id()) && REPAIR_USE_STEP_ID.equals(step.id())
                        && !state.completed.contains(new GuideStepKey(REPAIR_GUIDE_ID, REPAIR_NOTICE_STEP_ID))) {
                        continue;
                    }
                    GuideStepKey key = new GuideStepKey(guide.id(), step.id());
                    if (state.completed.add(key)) {
                        newlyCompleted.add(new CompletedStep(guide, step));
                    }
                }
            }
            if (!newlyCompleted.isEmpty()) {
                state.revision = Math.addExact(state.revision, 1L);
                state.dirty = true;
            }
        }
        if (notifyPlayer && !newlyCompleted.isEmpty()) {
            notifyCompletedSteps(player, newlyCompleted);
        }
        if (!newlyCompleted.isEmpty()) {
            inventoryService.queueLocalPlayerSave(accountId);
            if (areAllGuidesCompleted(accountId)) {
                allGuidesCompletedListener.accept(player);
            }
        }
    }

    private void notifyCompletedSteps(@NotNull AstPlayer player, @NotNull List<CompletedStep> completedSteps) {
        UUID accountId = player.getAccount().getUuid();
        Set<String> completedGuideIds = new LinkedHashSet<>();
        for (CompletedStep completed : completedSteps) {
            playerMessageService.send(player, PlayerMsgId.P_5180, resolveText(completed.step().text()));
            stepSoundPlayer.accept(player);
            completedGuideIds.add(completed.guide().id());
        }
        for (String guideId : completedGuideIds) {
            GuideEntry guide = getById(guideId);
            if (guide != null && isGuideCompleted(accountId, guide)) {
                playerMessageService.send(player, PlayerMsgId.P_5181, resolveText(guide.title()));
                guideSoundPlayer.accept(player);
            }
        }

        sendNextStepHint(player, completedSteps.getLast().guide());
    }

    /**
     * 表示順で最初の案内可能な未完了ガイドと、その未完了手順を1件通知します。
     * 進行未読込・解放済み、または候補がない場合は通知しません。主スレッドから呼び出してください。
     *
     * @param player 案内を受け取るオンラインプレイヤー
     */
    public void sendNextStepHint(@NotNull AstPlayer player) {
        sendNextStepHint(player, null);
    }

    /**
     * 達成直後は同じガイドの残りを優先し、なければ表示順で次の手順を通知します。
     *
     * @param player 案内を受け取るプレイヤー
     * @param preferredGuide 優先するガイド。定期案内ではnull
     */
    private void sendNextStepHint(@NotNull AstPlayer player, @Nullable GuideEntry preferredGuide) {
        UUID accountId = player.getAccount().getUuid();
        ProgressState state = progressByAccount.get(accountId);
        if (state == null) {
            return;
        }
        Set<GuideStepKey> completed;
        synchronized (state) {
            if (state.released) {
                return;
            }
            completed = Set.copyOf(state.completed);
        }
        GuideStep nextStep = preferredGuide == null ? null : firstIncompleteStep(preferredGuide, completed);
        GuideEntry nextGuide = nextStep == null ? null : preferredGuide;
        if (nextStep == null) {
            for (GuideEntry guide : getAll()) {
                if (REPAIR_GUIDE_ID.equals(guide.id())
                    && !completed.contains(new GuideStepKey(REPAIR_GUIDE_ID, REPAIR_NOTICE_STEP_ID))) {
                    continue;
                }
                nextStep = firstIncompleteStep(guide, completed);
                if (nextStep != null) {
                    nextGuide = guide;
                    break;
                }
            }
        }
        if (nextGuide != null && nextStep != null) {
            playerMessageService.send(player, PlayerMsgId.P_5185, resolveText(nextGuide.title()));
            playerMessageService.send(player, PlayerMsgId.P_5186, resolveText(nextStep.text()));
        }
    }

    private static @Nullable GuideStep firstIncompleteStep(
        @NotNull GuideEntry guide,
        @NotNull Set<GuideStepKey> completed
    ) {
        return guide.steps().stream()
            .filter(step -> !completed.contains(new GuideStepKey(guide.id(), step.id())))
            .findFirst().orElse(null);
    }

    /** inventory と同じ SQL transaction へ含めるガイド完成状態を捕捉します。 */
    public @Nullable PlayerStateSection snapshotPlayerState(@NotNull UUID accountId) {
        ProgressState state = progressByAccount.get(accountId);
        if (state == null) {
            return null;
        }

        long capturedRevision;
        Set<GuideStepKey> capturedSteps;
        synchronized (state) {
            if (!state.dirty) {
                return null;
            }
            capturedRevision = state.revision;
            capturedSteps = Set.copyOf(state.completed);
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("accountId", accountId.toString());
        payload.addProperty("clientRevision", capturedRevision);
        payload.addProperty("isFullSnapshot", true);
        JsonArray completedSteps = new JsonArray();
        capturedSteps.stream()
            .sorted(Comparator.comparing(GuideStepKey::guideId).thenComparing(GuideStepKey::stepId))
            .forEach(key -> {
                JsonObject row = new JsonObject();
                row.addProperty("guideId", key.guideId());
                row.addProperty("stepId", key.stepId());
                completedSteps.add(row);
            });
        payload.add("completedStepKeys", completedSteps);
        return new PlayerStateSection(PROGRESS_SECTION_NAME, payload,
            acknowledgement -> acknowledgeProgress(accountId, state, capturedRevision, capturedSteps, acknowledgement));
    }

    private void acknowledgeProgress(
        @NotNull UUID accountId,
        @NotNull ProgressState capturedState,
        long capturedRevision,
        @NotNull Set<GuideStepKey> capturedSteps,
        @NotNull JsonElement acknowledgement
    ) {
        if (!acknowledgement.isJsonObject()) {
            throw new IllegalStateException("Guide progress acknowledgement must be an object");
        }
        JsonObject ack = acknowledgement.getAsJsonObject();
        if (!ack.has("clientRevision") || ack.get("clientRevision").getAsLong() != capturedRevision
            || !ack.has("completedStepKeys") || !ack.get("completedStepKeys").isJsonArray()
            || !parseAcknowledgedSteps(ack.getAsJsonArray("completedStepKeys")).equals(capturedSteps)) {
            throw new IllegalStateException("Guide progress acknowledgement mismatch");
        }

        synchronized (capturedState) {
            if (progressByAccount.get(accountId) != capturedState
                || capturedRevision <= capturedState.acknowledgedRevision) {
                return;
            }
            capturedState.acknowledgedRevision = capturedRevision;
            if (capturedState.revision == capturedRevision) {
                capturedState.dirty = false;
            }
            if (capturedState.released && !capturedState.dirty) {
                progressByAccount.remove(accountId, capturedState);
            }
        }
    }

    private @NotNull Set<GuideStepKey> parseAcknowledgedSteps(@NotNull JsonArray rows) {
        Set<GuideStepKey> result = new HashSet<>();
        for (JsonElement element : rows) {
            if (!element.isJsonObject()) {
                throw new IllegalStateException("Guide progress acknowledgement entry must be an object");
            }
            JsonObject row = element.getAsJsonObject();
            if (!row.has("guideId") || !row.has("stepId")
                || !result.add(new GuideStepKey(row.get("guideId").getAsString(), row.get("stepId").getAsString()))) {
                throw new IllegalStateException("Guide progress acknowledgement contains an invalid or duplicate entry");
            }
        }
        return result;
    }

    public @NotNull String resolveText(@NotNull String text) {
        Matcher matcher = REFERENCE_PATTERN.matcher(text);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String replacement = resolveReference(matcher.group(1), matcher.group(2));
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private @NotNull String resolveReference(@NotNull String type, @NotNull String id) {
        String normalizedType = type.trim().toLowerCase(Locale.ROOT);
        String normalizedId = id.trim();
        return switch (normalizedType) {
            case "item" -> resolveItem(normalizedId);
            case "class" -> resolveClass(normalizedId);
            case "world" -> resolveWorld(normalizedId);
            case "menu" -> menuName(normalizedId);
            default -> "&f未登録の情報";
        };
    }

    private @NotNull String resolveItem(@NotNull String itemId) {
        ItemModel item = itemService.findLoadedById(itemId);
        return item == null || item.getName() == null || item.getName().isBlank() ? "&f未登録のアイテム" : item.getName();
    }

    private @NotNull String resolveClass(@NotNull String classId) {
        if (playerClassService.getLoadedClass(classId) == null) {
            return "&f未登録のクラス";
        }
        return playerClassService.getDisplayName(classId);
    }

    private @NotNull String resolveWorld(@NotNull String worldId) {
        WorldMasterData world = worldService.getById(worldId);
        return world == null || world.displayName() == null || world.displayName().isBlank()
            ? "&f未登録のワールド"
            : world.displayName();
    }

    private @NotNull String menuName(@NotNull String menuId) {
        return switch (menuId.trim().toLowerCase(Locale.ROOT)) {
            case "equipment" -> "&6装備";
            case "skill_bind" -> "&bスキルマネージャー";
            case "status" -> "&eステータス";
            case "guide" -> "&dガイド";
            case "mail" -> "&dメール";
            default -> "&f未登録のメニュー";
        };
    }

    private static int categoryOrder(@NotNull String category) {
        return switch (category.trim().toLowerCase(Locale.ROOT)) {
            case "beginner" -> 10;
            case "equipment" -> 20;
            case "skill" -> 30;
            case "world" -> 40;
            default -> 100;
        };
    }

    private static @NotNull String failureReason(@NotNull Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private record GuideConditionEvent(
        @NotNull AstPlayer player,
        @NotNull GuideConditionType type,
        @Nullable String targetId,
        @Nullable Integer targetLevel,
        boolean notifyPlayer
    ) {
    }

    private record CompletedStep(@NotNull GuideEntry guide, @NotNull GuideStep step) {
    }

    private static final class ProgressState {
        private final Set<GuideStepKey> completed = new LinkedHashSet<>();
        private long revision;
        private long acknowledgedRevision = -1L;
        private boolean dirty;
        private boolean released;

        private ProgressState(@NotNull Set<GuideStepKey> completed) {
            this.completed.addAll(completed);
        }
    }
}
