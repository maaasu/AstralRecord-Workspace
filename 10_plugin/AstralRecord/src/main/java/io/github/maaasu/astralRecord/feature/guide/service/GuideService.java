package io.github.maaasu.astralRecord.feature.guide.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.guide.model.GuideConditionType;
import io.github.maaasu.astralRecord.feature.guide.model.GuideEntry;
import io.github.maaasu.astralRecord.feature.guide.model.GuideStep;
import io.github.maaasu.astralRecord.feature.guide.model.GuideStepKey;
import io.github.maaasu.astralRecord.feature.guide.repository.GuideProgressRepository;
import io.github.maaasu.astralRecord.feature.guide.repository.GuideRepository;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
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

import java.util.Comparator;
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

public class GuideService {
    private static final long PROGRESS_RETRY_DELAY_TICKS = 100L;
    private static final Pattern REFERENCE_PATTERN = Pattern.compile("\\{([a-zA-Z_]+):([^}]+)}");

    /** 初参加者へ案内する導入ガイドの ID。 */
    public static final String INITIAL_GUIDE_ID = "beginner_onboarding";
    /** 導入ガイドを初めて開いたことを記録する step ID。 */
    public static final String INITIAL_GUIDE_OPEN_STEP_ID = "open_guide";

    private final AstralRecord plugin;
    private final GuideRepository repository;
    private final GuideProgressRepository progressRepository;
    private final PlayerMessageService playerMessageService;
    private final ItemService itemService;
    private final PlayerClassService playerClassService;
    private final WorldService worldService;
    private final Map<String, GuideEntry> loadedGuides = new LinkedHashMap<>();
    private final Map<UUID, Set<GuideStepKey>> completedStepsByAccount = new ConcurrentHashMap<>();
    private final Map<UUID, Long> progressGenerations = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Boolean>> progressLoadFutures = new ConcurrentHashMap<>();
    private final Map<UUID, List<GuideConditionEvent>> pendingConditionsByAccount = new ConcurrentHashMap<>();
    private final Set<UUID> initialGuideOpenedAccounts = ConcurrentHashMap.newKeySet();

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
     */
    public GuideService(
        @NotNull AstralRecord plugin,
        @NotNull GuideRepository repository,
        @NotNull GuideProgressRepository progressRepository,
        @NotNull ItemService itemService,
        @NotNull PlayerClassService playerClassService,
        @NotNull WorldService worldService,
        @NotNull PlayerMessageService playerMessageService
    ) {
        this.plugin = plugin;
        this.repository = repository;
        this.progressRepository = progressRepository;
        this.itemService = itemService;
        this.playerClassService = playerClassService;
        this.worldService = worldService;
        this.playerMessageService = playerMessageService;
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
        if (completedStepsByAccount.containsKey(accountId)) {
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
                    completedStepsByAccount.put(accountId, loaded);
                    List<GuideConditionEvent> pending = pendingConditionsByAccount.remove(accountId);
                    if (pending != null && !pending.isEmpty()) {
                        plugin.getServer().getScheduler().runTask(plugin, () -> pending.forEach(event ->
                            recordCondition(
                                event.player(),
                                event.type(),
                                event.targetId(),
                                event.targetLevel(),
                                event.notifyPlayer()
                            )
                        ));
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
        completedStepsByAccount.remove(accountId);
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
     * ガイド手順が達成済みか判定します。
     *
     * @param accountId アカウント ID。未ロード時は null 可
     * @param guideId ガイド ID
     * @param stepId 手順 ID
     * @return 達成済みの場合は true
     */
    public boolean isStepCompleted(@Nullable UUID accountId, @NotNull String guideId, @NotNull String stepId) {
        Set<GuideStepKey> completed = accountId == null ? null : completedStepsByAccount.get(accountId);
        return completed != null && completed.contains(new GuideStepKey(guideId, stepId));
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
        Set<GuideStepKey> completed = completedStepsByAccount.get(accountId);
        if (completed == null) {
            pendingConditionsByAccount.computeIfAbsent(accountId, ignored -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(new GuideConditionEvent(player, eventType, targetId, targetLevel, notifyPlayer));
            loadProgressAsync(accountId);
            return;
        }

        for (GuideEntry guide : getAll()) {
            for (GuideStep step : GuideProgressEvaluator.evaluate(
                guide, completed, eventType, targetId, targetLevel
            )) {
                GuideStepKey key = new GuideStepKey(guide.id(), step.id());
                if (!completed.add(key)) {
                    continue;
                }

                if (notifyPlayer) {
                    notifyStepCompleted(player, guide, step);
                }
                persistStepAsync(player, guide, key);
            }
        }
    }

    private void notifyStepCompleted(
        @NotNull AstPlayer player,
        @NotNull GuideEntry guide,
        @NotNull GuideStep step
    ) {
        playerMessageService.send(player, PlayerMsgId.P_5180, resolveText(step.text()));
        GuiSound.GUIDE_STEP.play(player.getBukkit());
        if (isGuideCompleted(player.getAccount().getUuid(), guide)) {
            playerMessageService.send(player, PlayerMsgId.P_5181, resolveText(guide.title()));
            GuiSound.GUIDE_COMPLETE.play(player.getBukkit());
        }
    }

    private void persistStepAsync(
        @NotNull AstPlayer player,
        @NotNull GuideEntry guide,
        @NotNull GuideStepKey key
    ) {
        UUID accountId = player.getAccount().getUuid();
        UUID updatedBy = player.getUser().getUuid();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                progressRepository.completeStep(accountId, key, updatedBy);
            } catch (RuntimeException e) {
                Set<GuideStepKey> completed = completedStepsByAccount.get(accountId);
                if (completed != null && completed.contains(key)) {
                    plugin.getServer().getScheduler().runTaskLaterAsynchronously(
                        plugin,
                        () -> persistStepAsync(player, guide, key),
                        PROGRESS_RETRY_DELAY_TICKS
                    );
                }
                Logger.log(LogId.E_5182, e, "complete", guide.id() + ":" + key.stepId(), failureReason(e));
            }
        });
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
}
