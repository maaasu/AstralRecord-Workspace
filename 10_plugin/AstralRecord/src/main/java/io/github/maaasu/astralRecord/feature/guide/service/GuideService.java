package io.github.maaasu.astralRecord.feature.guide.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.guide.model.GuideCondition;
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
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
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
import org.bukkit.inventory.EquipmentSlot;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class GuideService {
    private static final String PROGRESS_SECTION_NAME = "guideProgress";
    private static final Pattern REFERENCE_PATTERN = Pattern.compile("\\{([a-zA-Z_]+):([^}]+)}");

    /** 初参加者へ案内する導入ガイドの ID。 */
    public static final String INITIAL_GUIDE_ID = GuideEntry.FIRST_CHAPTER_ID;
    /** 導入ガイドを初めて開いたことを記録する step ID。 */
    public static final String INITIAL_GUIDE_OPEN_STEP_ID = "open_guide";
    private static final GuideStepKey GUIDE_OPENED_KEY = new GuideStepKey(INITIAL_GUIDE_ID, INITIAL_GUIDE_OPEN_STEP_ID);
    private static final GuideStepKey COMPLETION_NOTICE_DUE = new GuideStepKey(INITIAL_GUIDE_ID, "notice_completion_due");
    private static final String COMPLETE_NOTICE = "chapter_complete";
    private static final GuideStepKey COMPLETION_TITLE_SENT = new GuideStepKey(INITIAL_GUIDE_ID, "notice_title_" + COMPLETE_NOTICE);
    private static final GuideStepKey START_CHAT_SENT = new GuideStepKey(INITIAL_GUIDE_ID, "notice_chat_open_menu");
    private static final GuideStepKey START_TITLE_SENT = new GuideStepKey(INITIAL_GUIDE_ID, "notice_title_open_menu");
    private static final String TUTORIAL_MENU_STEP_ID = "open_menu";
    private static final String TUTORIAL_MAIL_STEP_ID = "receive_welcome_mail";
    private static final String TUTORIAL_BUNDLE_STEP_ID = "open_initial_bundle";
    private static final String TUTORIAL_WEAPON_STEP_ID = "equip_starter_weapon";
    private static final String TUTORIAL_WEAPON_ID = "20a00001";
    private static final List<TutorialRequirement> TUTORIAL_REQUIREMENTS = List.of(
        new TutorialRequirement(TUTORIAL_MENU_STEP_ID, GuideConditionType.MAIN_MENU_OPENED, null),
        new TutorialRequirement(TUTORIAL_MAIL_STEP_ID, GuideConditionType.MAIL_RECEIVED, "welcome_mail"),
        new TutorialRequirement(TUTORIAL_BUNDLE_STEP_ID, GuideConditionType.BUNDLE_OPENED, "50a00001"),
        new TutorialRequirement("buy_starter_weapon", GuideConditionType.SHOP_PURCHASED, "nox_weapon"),
        new TutorialRequirement(TUTORIAL_WEAPON_STEP_ID, GuideConditionType.ITEM_EQUIPPED, TUTORIAL_WEAPON_ID),
        new TutorialRequirement("defeat_grassboar", GuideConditionType.MOB_DEFEATED, "midgard_grassboar")
    );

    /** 旧導入ガイドの全手順。達成順に関係なく、全 ID の保存済み進行を確認します。 */
    private static final Set<String> LEGACY_INITIAL_STEP_IDS = Set.of(
        "login",
        "open_guide",
        "claim_login_bonus",
        TUTORIAL_MAIL_STEP_ID,
        TUTORIAL_BUNDLE_STEP_ID,
        "buy_starter_weapon",
        "buy_starter_armor_helmet",
        "buy_starter_armor_chest",
        "buy_starter_armor_legs",
        "buy_starter_armor_boots",
        "unlock_free_node",
        "learn_astral_edge",
        "learn_smash",
        "enhance_skill",
        "bind_skill_to_action_ring",
        "cast_astral_edge",
        "defeat_grassboar",
        "buy_hoe",
        "accept_gathering_quest",
        "report_gathering_quest",
        "freely_explore_and_change_class"
    );

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
    /** 進行キャッシュの公開・解放とロード前イベントの登録を同じロックで扱います。 */
    private final Map<UUID, List<GuideConditionEvent>> pendingConditionsByAccount = new ConcurrentHashMap<>();
    private final Set<UUID> initialGuideOpenedAccounts = ConcurrentHashMap.newKeySet();
    private volatile Consumer<AstPlayer> allGuidesCompletedListener = ignored -> { };
    private volatile Consumer<AstPlayer> tutorialCompletedListener = ignored -> { };
    private Consumer<AstPlayer> tutorialStepChangedListener = ignored -> { };
    private Predicate<AstPlayer> guideDisplayEnabled = player -> true;
    private Predicate<AstPlayer> javaGuideHud = player -> false;
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
                .filter(GuideService::isProductionTutorial)
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
        synchronized (pendingConditionsByAccount) {
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
                try {
                    Set<GuideStepKey> loaded = new LinkedHashSet<>(progressRepository.findByAccountId(accountId));
                    if (migrateLegacyTutorial(loaded)) {
                        plugin.getServer().getScheduler().runTask(plugin,
                            () -> publishLoadedProgress(accountId, generation, future, loaded, true));
                    } else {
                        publishLoadedProgress(accountId, generation, future, loaded, false);
                    }
                } catch (RuntimeException e) {
                    Logger.log(LogId.E_5182, e, "load", accountId, failureReason(e));
                    completeProgressLoad(accountId, future, false);
                }
            });
            return future;
        }
    }

    /** 旧導入ガイドの全手順を達成済みの場合に、新設された二つの手順を補います。 */
    private static boolean migrateLegacyTutorial(@NotNull Set<GuideStepKey> loaded) {
        if (!LEGACY_INITIAL_STEP_IDS.stream()
            .allMatch(stepId -> loaded.contains(new GuideStepKey(INITIAL_GUIDE_ID, stepId)))) {
            return false;
        }
        boolean menuAdded = loaded.add(new GuideStepKey(INITIAL_GUIDE_ID, TUTORIAL_MENU_STEP_ID));
        boolean weaponAdded = loaded.add(new GuideStepKey(INITIAL_GUIDE_ID, TUTORIAL_WEAPON_STEP_ID));
        return menuAdded || weaponAdded;
    }

    /** 読み込んだ進行を公開し、移行による補完はロード成功後に通常の保存へ渡します。 */
    private void publishLoadedProgress(
        @NotNull UUID accountId,
        long generation,
        @NotNull CompletableFuture<Boolean> future,
        @NotNull Set<GuideStepKey> loaded,
        boolean migrated
    ) {
        ProgressState state = new ProgressState(loaded);
        state.migrationPending = migrated;
        List<GuideConditionEvent> pending;
        boolean published;
        synchronized (pendingConditionsByAccount) {
            published = progressGenerations.getOrDefault(accountId, 0L) == generation;
            if (published) {
                progressByAccount.put(accountId, state);
                pending = pendingConditionsByAccount.remove(accountId);
            } else {
                pending = null;
            }
        }
        completeProgressLoad(accountId, future, published);
        if (!published) {
            return;
        }

        if (migrated) {
            boolean saveMigration;
            synchronized (state) {
                state.migrationPending = false;
                saveMigration = progressByAccount.get(accountId) == state;
                if (saveMigration) {
                    markProgressDirty(state);
                }
            }
            if (saveMigration) {
                inventoryService.queueLocalPlayerSave(accountId);
            }
        }

        if (pending != null && !pending.isEmpty()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (progressGenerations.getOrDefault(accountId, 0L) != generation
                    || progressByAccount.get(accountId) != state) {
                    return;
                }
                for (GuideConditionEvent event : pending) {
                    if (event.player().getBukkit().isOnline() && accountId.equals(event.player().getAccount().getUuid())
                        && AstPlayerCache.get(event.player().getBukkit()) == event.player()) {
                        recordLoadedCondition(event.player(), state, event.type(), event.targetId(),
                            event.targetLevel(), event.notifyPlayer());
                    }
                }
            });
        }
    }

    private void completeProgressLoad(
        @NotNull UUID accountId,
        @NotNull CompletableFuture<Boolean> future,
        boolean succeeded
    ) {
        progressLoadFutures.remove(accountId, future);
        future.complete(succeeded);
    }

    /** 進行のロックを保持した状態で、ローカル保存対象にします。 */
    private static void markProgressDirty(@NotNull ProgressState state) {
        state.revision = Math.addExact(state.revision, 1L);
        state.dirty = true;
    }

    /**
     * ログアウトしたアカウントの進行キャッシュを破棄します。
     *
     * @param accountId アカウント ID
     */
    public void releaseProgress(@NotNull UUID accountId) {
        synchronized (pendingConditionsByAccount) {
            progressGenerations.merge(accountId, 1L, Long::sum);
            ProgressState state = progressByAccount.get(accountId);
            if (state != null) {
                synchronized (state) {
                    state.released = true;
                    if (!state.dirty && !state.migrationPending) {
                        progressByAccount.remove(accountId, state);
                    }
                }
            }
            progressLoadFutures.remove(accountId);
            pendingConditionsByAccount.remove(accountId);
            initialGuideOpenedAccounts.remove(accountId);
        }
    }

    /**
     * 保存不能の復旧時に、指定 account の未保存ガイド進行を保存せず破棄します。
     *
     * @param accountId 破棄対象 account ID
     */
    public void discardAccountState(@NotNull UUID accountId) {
        synchronized (pendingConditionsByAccount) {
            progressGenerations.merge(accountId, 1L, Long::sum);
            progressByAccount.remove(accountId);
            progressLoadFutures.remove(accountId);
            pendingConditionsByAccount.remove(accountId);
            initialGuideOpenedAccounts.remove(accountId);
        }
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

    /** 第1章の保存済み手順数。マスターまたは進行を利用できない場合は -1 を返します。 */
    public int tutorialCompletedStepCount(@Nullable UUID accountId) {
        ProgressState state = accountId == null ? null : progressByAccount.get(accountId);
        GuideEntry chapter = getById(INITIAL_GUIDE_ID);
        if (state == null || !isProductionTutorial(chapter)) {
            return -1;
        }
        synchronized (state) {
            if (state.released) {
                return -1;
            }
            return (int) chapter.steps().stream()
                .filter(step -> state.completed.contains(new GuideStepKey(chapter.id(), step.id())))
                .count();
        }
    }

    /**
     * 導入ガイドの最初の未達成手順を返します。
     *
     * @param accountId 対象アカウント ID
     * @return 導入の未達成手順 ID。進行の未ロード・解放済み、導入ガイドなし、または導入完了の場合は null
     */
    public @Nullable String tutorialStep(@Nullable UUID accountId) {
        ProgressState state = accountId == null ? null : progressByAccount.get(accountId);
        if (state == null) {
            return null;
        }
        synchronized (state) {
            GuideEntry tutorial = getById(INITIAL_GUIDE_ID);
            if (state.released || !isProductionTutorial(tutorial)) {
                return null;
            }
            GuideStep next = firstIncompleteStep(tutorial, state.completed);
            return next == null ? null : next.id();
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
     * ログイン時に読み込んだ手持ち武器、装備、ペットから、保存前ログアウトで失われたガイドイベントを補います。
     * 呼び出し元はガイド進行とプレイヤー状態の読み込み完了後、現在のセッションを確認してください。
     * 既に達成した手順は再通知しません。
     *
     * @param player 現在ログイン中のプレイヤー
     * @param hasHatchedPet 読み込んだペットに孵化済みの個体がいる場合 true
     */
    public void reconcileLoadedAccount(@NotNull AstPlayer player, boolean hasHatchedPet) {
        UUID accountId = player.getAccount().getUuid();
        ProgressState state = progressByAccount.get(accountId);
        if (state == null || !isProgressLoaded(accountId)) {
            return;
        }
        if (hasTutorialDefinition() && AccountModeGuard.isGameplayPlayer(player)
            && !isStepCompleted(accountId, INITIAL_GUIDE_ID, TUTORIAL_WEAPON_STEP_ID)) {
            var heldItem = inventoryService.getItemReferenceInHand(player, EquipmentSlot.HAND);
            if (heldItem != null && TUTORIAL_WEAPON_ID.equals(heldItem.itemId())) {
                recordLoadedCondition(player, state, GuideConditionType.ITEM_EQUIPPED, TUTORIAL_WEAPON_ID, null, false);
            }
        }
        if (itemService.hasLoadedBrokenEquipment(accountId)
            && !isStepCompleted(accountId, REPAIR_GUIDE_ID, REPAIR_NOTICE_STEP_ID)) {
            recordLoadedCondition(player, state, GuideConditionType.EQUIPMENT_BROKEN, null, null, true);
        }
        if (hasHatchedPet) {
            recordLoadedCondition(player, state, GuideConditionType.PET_HATCHED, null, null, true);
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
        allGuidesCompletedListener = Objects.requireNonNull(listener);
    }

    /**
     * 導入ガイドの全手順を新たに達成したときのリスナーを登録します。
     *
     * @param listener 達成したプレイヤーを受け取るリスナー
     */
    public void setTutorialCompletedListener(@NotNull Consumer<AstPlayer> listener) {
        tutorialCompletedListener = Objects.requireNonNull(listener);
    }

    public void setTutorialStepChangedListener(@NotNull Consumer<AstPlayer> listener) {
        tutorialStepChangedListener = Objects.requireNonNull(listener);
    }

    /** 通知履歴はアカウントの通常保存に含め、実際の6手順とは別のIDを使います。 */
    @Nullable ChapterNotice claimChapterNotice(AstPlayer player, @Nullable String expectedStep, boolean titleAvailable) {
        UUID account = player.getAccount().getUuid();
        ProgressState state = progressByAccount.get(account);
        GuideEntry chapter = getById(INITIAL_GUIDE_ID);
        if (state == null || !isProductionTutorial(chapter) || !isGuideDisplayEnabled(player)) return null;
        ChapterNotice notice;
        synchronized (state) {
            if (state.released || state != progressByAccount.get(account)) return null;
            GuideStep next = firstIncompleteStep(chapter, state.completed);
            if (next == null ? expectedStep != null || !state.completed.contains(COMPLETION_NOTICE_DUE)
                : !next.id().equals(expectedStep)) return null;
            String noticeId = next == null ? COMPLETE_NOTICE : next.id();
            boolean startChat = next != null && !state.completed.contains(START_CHAT_SENT);
            boolean startTitle = next != null && titleAvailable && !state.completed.contains(START_TITLE_SENT);
            boolean chat = state.completed.add(new GuideStepKey(INITIAL_GUIDE_ID, "notice_chat_" + noticeId));
            boolean title = titleAvailable && state.completed.add(new GuideStepKey(INITIAL_GUIDE_ID, "notice_title_" + noticeId));
            if (startChat) state.completed.add(START_CHAT_SENT);
            if (startTitle) state.completed.add(START_TITLE_SENT);
            if (!chat && !title && !startChat && !startTitle) return null;
            markProgressDirty(state);
            int number = next == null ? 0 : chapter.steps().indexOf(next) + 1;
            notice = new ChapterNotice(number, chapter.steps().size(), chapter.title(),
                next == null ? "" : resolveText(next.text()), chat || startChat, title || startTitle,
                startChat, startTitle, !state.completed.contains(GUIDE_OPENED_KEY));
        }
        inventoryService.queueLocalPlayerSave(account);
        return notice;
    }

    boolean hasPendingChapterCompletion(UUID accountId) {
        ProgressState state = progressByAccount.get(accountId);
        if (state == null) return false;
        synchronized (state) {
            return !state.released && state.completed.contains(COMPLETION_NOTICE_DUE)
                && !state.completed.contains(COMPLETION_TITLE_SENT);
        }
    }

    /** Bukkit を起動しない単体テストで効果音だけを差し替えます。 */
    void setSoundPlayers(@NotNull Consumer<AstPlayer> step, @NotNull Consumer<AstPlayer> guide) {
        stepSoundPlayer = Objects.requireNonNull(step);
        guideSoundPlayer = Objects.requireNonNull(guide);
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
     * メインメニューを開いたことをガイドへ記録します。
     *
     * @param player メニューを開いたプレイヤー
     */
    public void recordTutorialMenuOpened(@NotNull AstPlayer player) {
        recordCondition(player, GuideConditionType.MAIN_MENU_OPENED, null);
    }

    /**
     * ゲームプレイ中に選んだホットバースロットから、所有する導入用武器を確認して記録します。
     *
     * @param player スロットを選んだプレイヤー
     * @param bukkitSlot Bukkit のホットバースロット番号
     */
    public void recordTutorialHeldWeapon(@NotNull AstPlayer player, int bukkitSlot) {
        if (bukkitSlot < 0 || bukkitSlot > 8 || !AccountModeGuard.isGameplayPlayer(player)
            || isStepCompleted(player.getAccount().getUuid(), INITIAL_GUIDE_ID, TUTORIAL_WEAPON_STEP_ID)) {
            return;
        }
        ItemModel model = inventoryService.getOwnedItemModelAtBukkitSlot(player, bukkitSlot);
        if (model != null && TUTORIAL_WEAPON_ID.equals(model.getId())) {
            recordCondition(player, GuideConditionType.ITEM_EQUIPPED, TUTORIAL_WEAPON_ID);
        }
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
        ProgressState state;
        synchronized (pendingConditionsByAccount) {
            if (eventType == GuideConditionType.GUIDE_OPENED) {
                initialGuideOpenedAccounts.add(accountId);
            }
            state = progressByAccount.get(accountId);
            if (state == null) {
                pendingConditionsByAccount.computeIfAbsent(accountId, ignored -> new CopyOnWriteArrayList<>())
                    .add(new GuideConditionEvent(player, eventType, targetId, targetLevel, notifyPlayer));
                loadProgressAsync(accountId);
                return;
            }
        }
        recordLoadedCondition(player, state, eventType, targetId, targetLevel, notifyPlayer);
    }

    private void recordLoadedCondition(
        @NotNull AstPlayer player,
        @NotNull ProgressState state,
        @NotNull GuideConditionType eventType,
        @Nullable String targetId,
        @Nullable Integer targetLevel,
        boolean notifyPlayer
    ) {
        UUID accountId = player.getAccount().getUuid();
        List<CompletedStep> newlyCompleted = new ArrayList<>();
        boolean tutorialCompleted = false;
        boolean tutorialAdvanced = false;
        boolean opened;
        synchronized (state) {
            if (state.released || progressByAccount.get(accountId) != state) {
                return;
            }
            for (GuideEntry guide : getAll()) {
                int completedBeforeGuide = newlyCompleted.size();
                GuideStep previous = isProductionTutorial(guide) ? firstIncompleteStep(guide, state.completed) : null;
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
                if (newlyCompleted.size() > completedBeforeGuide && previous != null) {
                    GuideStep next = firstIncompleteStep(guide, state.completed);
                    tutorialAdvanced = !previous.equals(next);
                    tutorialCompleted = next == null;
                    if (tutorialCompleted && notifyPlayer && isGuideDisplayEnabled(player)) {
                        state.completed.add(COMPLETION_NOTICE_DUE);
                    }
                }
            }
            opened = eventType == GuideConditionType.GUIDE_OPENED && state.completed.add(GUIDE_OPENED_KEY);
            if (opened || !newlyCompleted.isEmpty()) {
                markProgressDirty(state);
            }
        }
        if (notifyPlayer && !newlyCompleted.isEmpty()) {
            notifyCompletedSteps(player, newlyCompleted, tutorialCompleted);
        }
        if (opened || !newlyCompleted.isEmpty()) inventoryService.queueLocalPlayerSave(accountId);
        if (!newlyCompleted.isEmpty()) {
            if (areAllGuidesCompleted(accountId)) {
                allGuidesCompletedListener.accept(player);
            }
            if (notifyPlayer && tutorialCompleted) {
                tutorialCompletedListener.accept(player);
            } else if (notifyPlayer && tutorialAdvanced) {
                tutorialStepChangedListener.accept(player);
            }
        }
    }

    /** 表示設定と、ガイド用の字形を実際に適用したJava接続の判定を登録します。 */
    public void setGuideDisplayPolicy(Predicate<AstPlayer> enabled, Predicate<AstPlayer> hasHud) {
        guideDisplayEnabled = Objects.requireNonNull(enabled);
        javaGuideHud = Objects.requireNonNull(hasHud);
    }

    public boolean isGuideDisplayEnabled(@NotNull AstPlayer player) {
        return guideDisplayEnabled.test(player);
    }

    boolean usesJavaGuideHud(@NotNull AstPlayer player) {
        return javaGuideHud.test(player);
    }

    boolean shouldSendGuideReminder(@NotNull AstPlayer player) {
        return isGuideDisplayEnabled(player) && !isInitialGuideOpened(player.getAccount().getUuid());
    }

    private void notifyCompletedSteps(
        @NotNull AstPlayer player,
        @NotNull List<CompletedStep> completedSteps,
        boolean tutorialCompleted
    ) {
        if (!isGuideDisplayEnabled(player)) return;
        UUID accountId = player.getAccount().getUuid();
        Set<String> completedGuideIds = new LinkedHashSet<>();
        for (CompletedStep completed : completedSteps) {
            if (!isProductionTutorial(completed.guide())) {
                playerMessageService.send(player, PlayerMsgId.P_5180, resolveText(completed.step().text()));
            }
            stepSoundPlayer.accept(player);
            completedGuideIds.add(completed.guide().id());
        }
        for (String guideId : completedGuideIds) {
            GuideEntry guide = getById(guideId);
            if (guide != null && isGuideCompleted(accountId, guide)) {
                if (!isProductionTutorial(guide)) {
                    playerMessageService.send(player, PlayerMsgId.P_5181, resolveText(guide.title()));
                }
                guideSoundPlayer.accept(player);
            }
        }

        if (!tutorialCompleted && !isProductionTutorial(completedSteps.getLast().guide())) {
            sendNextStepHint(player, completedSteps.getLast().guide());
        }
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
        if (!isGuideDisplayEnabled(player)) return;
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
        if (nextGuide != null && nextStep != null
            && !(INITIAL_GUIDE_ID.equals(nextGuide.id()) && usesJavaGuideHud(player))) {
            playerMessageService.send(player, PlayerMsgId.P_5185, resolveText(nextGuide.title()));
            playerMessageService.send(player, PlayerMsgId.P_5186, resolveText(nextStep.text()));
        }
    }

    /** 初回案内に使用する全手順と達成条件が現行マスターに揃っている場合に true を返します。 */
    public boolean hasTutorialDefinition() {
        return isProductionTutorial(getById(INITIAL_GUIDE_ID));
    }

    /** 初回メールまたは初回支給箱の手順が補完の対象になるかを確認します。 */
    public boolean needsWelcomeTutorialReconciliation(@NotNull UUID accountId) {
        return hasTutorialDefinition() && isProgressLoaded(accountId)
            && (!isStepCompleted(accountId, INITIAL_GUIDE_ID, TUTORIAL_MAIL_STEP_ID)
                || !isStepCompleted(accountId, INITIAL_GUIDE_ID, TUTORIAL_BUNDLE_STEP_ID));
    }

    /**
     * 受取済みまたは受取対象外と確認したアカウントの導入手順を補完します。
     * 呼び出し元は主スレッドで現在のセッションとインベントリを確認してください。
     *
     * @param player 補完対象のプレイヤー
     * @param hasBundle 未開封の初回支給箱が手持ちまたは倉庫にある場合は true
     */
    public void reconcileWelcomeTutorialSteps(@NotNull AstPlayer player, boolean hasBundle) {
        UUID accountId = player.getAccount().getUuid();
        GuideStepKey mail = new GuideStepKey(INITIAL_GUIDE_ID, TUTORIAL_MAIL_STEP_ID);
        GuideStepKey bundle = new GuideStepKey(INITIAL_GUIDE_ID, TUTORIAL_BUNDLE_STEP_ID);
        synchronized (pendingConditionsByAccount) {
            ProgressState state = progressByAccount.get(accountId);
            if (state == null) {
                return;
            }
            synchronized (state) {
                if (state.released || !hasTutorialDefinition()
                    || (state.completed.contains(mail) && (hasBundle || state.completed.contains(bundle)))) {
                    return;
                }
                markProgressDirty(state);
                state.completed.add(mail);
                if (!hasBundle) {
                    state.completed.add(bundle);
                }
            }
        }

        inventoryService.queueLocalPlayerSave(accountId);
        if (areAllGuidesCompleted(accountId)) {
            allGuidesCompletedListener.accept(player);
        }
    }

    private static boolean isProductionTutorial(@Nullable GuideEntry guide) {
        if (guide == null || !INITIAL_GUIDE_ID.equals(guide.id())
            || guide.steps().size() != TUTORIAL_REQUIREMENTS.size()) {
            return false;
        }
        for (int index = 0; index < TUTORIAL_REQUIREMENTS.size(); index++) {
            TutorialRequirement requirement = TUTORIAL_REQUIREMENTS.get(index);
            GuideStep step = guide.steps().get(index);
            GuideCondition condition = step.condition();
            if (!requirement.stepId().equals(step.id()) || condition == null
                || condition.type() != requirement.type() || condition.targetLevel() != null) {
                return false;
            }
            String targetId = condition.targetId();
            if (!condition.targetIds().isEmpty()) {
                if (targetId != null || condition.targetIds().size() != 1) {
                    return false;
                }
                targetId = condition.targetIds().getFirst();
            }
            if (!Objects.equals(requirement.targetId(), targetId)) {
                return false;
            }
        }
        return true;
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
            if (capturedState.released && !capturedState.dirty && !capturedState.migrationPending) {
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

    private record TutorialRequirement(String stepId, GuideConditionType type, String targetId) {
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
        /** ロード成功のコールバック中も、移行結果を保存キューに渡すまで保持します。 */
        private boolean migrationPending;
        private boolean released;

        private ProgressState(@NotNull Set<GuideStepKey> completed) {
            this.completed.addAll(completed);
        }
    }
}
