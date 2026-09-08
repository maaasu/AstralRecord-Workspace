package io.github.maaasu.astralRecord.feature.skill.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillSigil;
import io.github.maaasu.astralRecord.feature.skill.repository.LearnedSkillRepository;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateSection;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import io.github.maaasu.astralRecord.infrastructure.util.AsyncTaskUtil;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 習得済みスキル個体のキャッシュと player-state snapshot 更新を扱います。
 *
 * <p>同じ skillId を持つ個体を複数保持できるため、参照・バインド・強化は常に
 * learnedSkillId を正本として行います。</p>
 */
public final class LearnedSkillService {
    private static final long DEFAULT_MUTATION_TIMEOUT_MILLIS = 60_000L;

    private final Plugin plugin;
    private final LearnedSkillRepository repository;
    private final InventoryService inventoryService;
    private final long mutationTimeoutMillis;
    private final Map<UUID, List<LearnedSkillInstance>> skillsByAccount = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicBoolean> mutationLocks = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> sessionTokens = new ConcurrentHashMap<>();
    private final Map<UUID, Long> playerStateRevisions = new ConcurrentHashMap<>();
    private final Set<UUID> dirtyPlayerStates = ConcurrentHashMap.newKeySet();
    private final Set<UUID> releasedPlayerStates = ConcurrentHashMap.newKeySet();
    private final Set<UUID> retainedInitialLoads = ConcurrentHashMap.newKeySet();
    private final Map<UUID, UUID> playerStateEpochs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> acknowledgedPlayerStateRevisions = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Integer>> persistedSkillVersions = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, LocalDateTime>> persistedSkillUpdatedAts = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Integer>> pendingDeletedSkillVersions = new ConcurrentHashMap<>();

    public LearnedSkillService(
        @NotNull Plugin plugin,
        @NotNull LearnedSkillRepository repository,
        @NotNull InventoryService inventoryService
    ) {
        this(plugin, repository, inventoryService, configuredMutationTimeoutMillis());
    }

    LearnedSkillService(
        @NotNull Plugin plugin,
        @NotNull LearnedSkillRepository repository,
        @NotNull InventoryService inventoryService,
        long mutationTimeoutMillis
    ) {
        this.plugin = plugin;
        this.repository = repository;
        this.inventoryService = inventoryService;
        this.mutationTimeoutMillis = Math.max(1_000L, mutationTimeoutMillis);
    }

    private static long configuredMutationTimeoutMillis() {
        long configured = ConfigProperties.getInstance().getApiOperationTimeout();
        return configured > 0L ? configured : DEFAULT_MUTATION_TIMEOUT_MILLIS;
    }

    /**
     * 再参加では未保存の保持状態を優先して取得し、applyまでACK後の破棄を抑止します。
     * @param accountId 読込対象
     * @return 保持状態、またはAPI初期状態
     */
    public @NotNull List<LearnedSkillInstance> loadInitialSkills(@NotNull UUID accountId) {
        synchronized (this) {
            if (skillsByAccount.containsKey(accountId) && (dirtyPlayerStates.contains(accountId)
                || retainedInitialLoads.contains(accountId))) {
                retainedInitialLoads.add(accountId);
                releasedPlayerStates.remove(accountId);
                return getLearnedSkills(accountId);
            }
        }
        return normalize(repository.findByAccountId(accountId));
    }

    /**
     * 初期状態を公開します。保持中の変更は上書きせず、新しいセッションtokenだけを発行します。
     * @param accountId 対象アカウント
     * @param skills 初期ロード結果
     */
    public synchronized void applyInitialSkills(
        @NotNull UUID accountId,
        @NotNull List<LearnedSkillInstance> skills
    ) {
        releasedPlayerStates.remove(accountId);
        sessionTokens.put(accountId, UUID.randomUUID());
        if (retainedInitialLoads.remove(accountId) || dirtyPlayerStates.contains(accountId)) {
            return;
        }
        List<LearnedSkillInstance> normalized = normalize(skills);
        skillsByAccount.put(accountId, normalized);
        Map<UUID, Integer> versions = new ConcurrentHashMap<>();
        Map<UUID, LocalDateTime> updatedAts = new ConcurrentHashMap<>();
        normalized.forEach(skill -> {
            versions.put(skill.getLearnedSkillId(), skill.getVersion());
            if (skill.getUpdatedAt() != null) {
                updatedAts.put(skill.getLearnedSkillId(), skill.getUpdatedAt());
            }
        });
        persistedSkillVersions.put(accountId, versions);
        persistedSkillUpdatedAts.put(accountId, updatedAts);
        pendingDeletedSkillVersions.remove(accountId);
        playerStateRevisions.put(accountId, 0L);
        dirtyPlayerStates.remove(accountId);
        playerStateEpochs.put(accountId, UUID.randomUUID());
        acknowledgedPlayerStateRevisions.remove(accountId);
    }

    /**
     * 退出したセッションを失効させ、未保存状態は完全ACKまで保持します。
     * @param accountId 退出またはアカウント切替対象
     */
    public synchronized void invalidate(@NotNull UUID accountId) {
        sessionTokens.remove(accountId);
        retainedInitialLoads.remove(accountId);
        releasedPlayerStates.add(accountId);
        if (dirtyPlayerStates.contains(accountId)) {
            return;
        }
        evictReleasedPlayerState(accountId);
    }

    /** 保存済みかつ退出済みの状態を破棄します。呼出元は本serviceのmonitorを保持します。 */
    private void evictReleasedPlayerState(UUID accountId) {
        if (!releasedPlayerStates.remove(accountId)) return;
        skillsByAccount.remove(accountId);
        playerStateRevisions.remove(accountId);
        persistedSkillVersions.remove(accountId);
        persistedSkillUpdatedAts.remove(accountId);
        pendingDeletedSkillVersions.remove(accountId);
        dirtyPlayerStates.remove(accountId);
        playerStateEpochs.remove(accountId);
        acknowledgedPlayerStateRevisions.remove(accountId);
        mutationLocks.computeIfPresent(accountId, (ignored, lock) -> lock.get() ? lock : null);
        sessionTokens.remove(accountId);
    }

    public boolean hasLoadedSkills(@NotNull UUID accountId) {
        return skillsByAccount.containsKey(accountId);
    }

    public @NotNull List<LearnedSkillInstance> getLearnedSkills(@NotNull UUID accountId) {
        return new ArrayList<>(skillsByAccount.getOrDefault(accountId, List.of()));
    }

    public @Nullable LearnedSkillInstance findInstance(@NotNull UUID accountId, @NotNull UUID learnedSkillId) {
        return skillsByAccount.getOrDefault(accountId, List.of()).stream()
            .filter(skill -> skill.getLearnedSkillId().equals(learnedSkillId))
            .findFirst()
            .orElse(null);
    }

    public @Nullable LearnedSkillInstance findInstance(@NotNull UUID accountId, @Nullable String learnedSkillId) {
        if (learnedSkillId == null || learnedSkillId.isBlank()) return null;
        try {
            return findInstance(accountId, UUID.fromString(learnedSkillId));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public boolean ownsSkill(@NotNull UUID accountId, @NotNull String skillId) {
        return skillsByAccount.getOrDefault(accountId, List.of()).stream()
            .anyMatch(skill -> skill.getSkillId().equalsIgnoreCase(skillId));
    }

    /** @return 習得済みスキルに対する API mutation が進行中の場合は {@code true} */
    public boolean hasMutationInProgress(@NotNull UUID accountId) {
        AtomicBoolean lock = mutationLocks.get(accountId);
        return lock != null && lock.get();
    }

    /** ローカル確定と player-state 後送が利用できるレベルアップなら受付可能かを返します。 */
    public boolean canQueueLocalLevelUp(@NotNull UUID accountId) {
        return hasLoadedSkills(accountId);
    }

    /** スキルマネージャーから master 定義の素材を消費して初回習得します。 */
    public boolean learnFromManagerAsync(
        @NotNull UUID accountId,
        @NotNull String skillId,
        @NotNull UUID updatedBy,
        @NotNull List<UUID> requiredItemEntryIds,
        @NotNull Consumer<LearnedSkillInstance> onSuccess,
        @NotNull Consumer<Throwable> onFailure
    ) {
        return learnFromManagerAsync(
            accountId,
            skillId,
            updatedBy,
            requiredItemEntryIds,
            onSuccess,
            onFailure,
            () -> { }
        );
    }

    /**
     * スキル習得を受け付け、プレイヤー向け待機時間を超えた時に保留通知を呼び出します。
     *
     * @param accountId 対象アカウント ID
     * @param skillId 習得するスキル ID
     * @param updatedBy 更新者 ID
     * @param requiredItemEntryIds API が消費候補として検証する素材 entry IDs
     * @param onSuccess API 正本反映成功時の処理
     * @param onFailure API または正本同期失敗時の処理
     * @param onPending 待機時間を超え、処理中ロックを維持したままUIを閉じる処理
     * @return 処理を受け付けた場合は {@code true}
     */
    public boolean learnFromManagerAsync(
        @NotNull UUID accountId,
        @NotNull String skillId,
        @NotNull UUID updatedBy,
        @NotNull List<UUID> requiredItemEntryIds,
        @NotNull Consumer<LearnedSkillInstance> onSuccess,
        @NotNull Consumer<Throwable> onFailure,
        @NotNull Runnable onPending
    ) {
        try {
            CompletableFuture<LearnedSkillInstance> future = commitCriticalPaymentMutation(
                accountId, unitPayments(requiredItemEntryIds), () -> {
                LearnedSkillInstance created = new LearnedSkillInstance(
                    UUID.randomUUID(), accountId, skillId, 1, List.of(), 1,
                    LocalDateTime.now(), LocalDateTime.now());
                skillsByAccount.compute(accountId, (ignored, current) -> appendSkill(current, created));
                return created;
            });
            completeCriticalMutation(accountId, future, onSuccess, onFailure, onPending);
            return true;
        } catch (RuntimeException error) {
            onFailure.accept(error);
            return false;
        }
    }

    /** スキルマネージャーから master 定義の素材を消費してレベルアップします。 */
    public boolean levelUpFromManagerAsync(
        @NotNull UUID accountId,
        @NotNull UUID learnedSkillId,
        @NotNull UUID updatedBy,
        @NotNull List<UUID> requiredItemEntryIds,
        @NotNull Consumer<LearnedSkillInstance> onSuccess,
        @NotNull Consumer<Throwable> onFailure
    ) {
        return levelUpFromManagerWithPaymentsAsync(
            accountId,
            learnedSkillId,
            updatedBy,
            unitPayments(requiredItemEntryIds),
            onSuccess,
            onFailure,
            () -> { }
        );
    }

    /** 素材entryごとの数量を指定する、ローカルmutation対応のレベルアップ入口です。 */
    public boolean levelUpFromManagerWithPaymentsAsync(
        @NotNull UUID accountId,
        @NotNull UUID learnedSkillId,
        @NotNull UUID updatedBy,
        @NotNull Map<UUID, Long> requiredItemPayments,
        @NotNull Consumer<LearnedSkillInstance> onSuccess,
        @NotNull Consumer<Throwable> onFailure
    ) {
        return levelUpFromManagerWithPaymentsAsync(
            accountId,
            learnedSkillId,
            updatedBy,
            requiredItemPayments,
            onSuccess,
            onFailure,
            () -> { }
        );
    }

    /**
     * スキルレベルアップを受け付け、プレイヤー向け待機時間を超えた時に保留通知を呼び出します。
     *
     * @param accountId 対象アカウント ID
     * @param learnedSkillId 強化する習得済みスキル個体 ID
     * @param updatedBy 更新者 ID
     * @param requiredItemEntryIds API が消費候補として検証する素材 entry IDs
     * @param onSuccess API 正本反映成功時の処理
     * @param onFailure API または正本同期失敗時の処理
     * @param onPending 待機時間を超え、処理中ロックを維持したままUIを閉じる処理
     * @return 処理を受け付けた場合は {@code true}
     */
    public boolean levelUpFromManagerAsync(
        @NotNull UUID accountId,
        @NotNull UUID learnedSkillId,
        @NotNull UUID updatedBy,
        @NotNull List<UUID> requiredItemEntryIds,
        @NotNull Consumer<LearnedSkillInstance> onSuccess,
        @NotNull Consumer<Throwable> onFailure,
        @NotNull Runnable onPending
    ) {
        return levelUpFromManagerWithPaymentsAsync(
            accountId,
            learnedSkillId,
            updatedBy,
            unitPayments(requiredItemEntryIds),
            onSuccess,
            onFailure,
            onPending
        );
    }

    /** 素材数量を保持したまま SQL ACK 付きで確定するレベルアップ処理です。 */
    public boolean levelUpFromManagerWithPaymentsAsync(
        @NotNull UUID accountId,
        @NotNull UUID learnedSkillId,
        @NotNull UUID updatedBy,
        @NotNull Map<UUID, Long> requiredItemPayments,
        @NotNull Consumer<LearnedSkillInstance> onSuccess,
        @NotNull Consumer<Throwable> onFailure,
        @NotNull Runnable onPending
    ) {
        try {
            CompletableFuture<LearnedSkillInstance> future = commitCriticalPaymentMutation(
                accountId, requiredItemPayments, () -> {
                LearnedSkillInstance current = findInstance(accountId, learnedSkillId);
                if (current == null) {
                    throw new IllegalStateException("Learned skill is no longer loaded.");
                }
                LearnedSkillInstance next = withLevel(current, current.getLevel() + 1);
                replaceCached(next);
                return next;
            });
            completeCriticalMutation(accountId, future, onSuccess, onFailure, onPending);
            return true;
        } catch (RuntimeException error) {
            onFailure.accept(error);
            return false;
        }
    }

    private static @NotNull Map<UUID, Long> unitPayments(@NotNull List<UUID> entryIds) {
        Map<UUID, Long> payments = new LinkedHashMap<>();
        entryIds.forEach(entryId -> payments.merge(entryId, 1L, Math::addExact));
        return payments;
    }

    /**
     * シジルを指定した習得済みスキル個体へ装着し、装着シジルと起点オーブを正本へ同期します。
     *
     * @param accountId アカウント ID
     * @param learnedSkillId 対象の習得済みスキル個体 ID
     * @param orbInventoryEntryId 操作確定時に共通消費順で解決済みの SIGIL_ATTACH オーブ inventory entry ID
     * @param sigilId 装着するシジル item ID
     * @param sigilInventoryEntryId 操作確定時に共通消費順で解決済みのシジル inventory entry ID
     * @param updatedBy 更新者 ID
     * @param onSuccess API 更新と素材entry同期の成功時処理
     * @param onFailure API 更新または正本同期の失敗時処理
     * @return 処理を受け付けた場合は {@code true}、別の習得スキル mutation 実行中は {@code false}
     */
    public boolean attachSigilAsync(
        @NotNull UUID accountId,
        @NotNull UUID learnedSkillId,
        @NotNull UUID orbInventoryEntryId,
        @NotNull String sigilId,
        @NotNull UUID sigilInventoryEntryId,
        @NotNull UUID updatedBy,
        @NotNull Consumer<LearnedSkillInstance> onSuccess,
        @NotNull Consumer<Throwable> onFailure
    ) {
        return attachSigilAsync(
            accountId,
            learnedSkillId,
            orbInventoryEntryId,
            sigilId,
            sigilInventoryEntryId,
            updatedBy,
            onSuccess,
            onFailure,
            () -> { }
        );
    }

    /**
     * シジル装着を受け付け、プレイヤー向け待機時間を超えた時に保留通知を呼び出します。
     *
     * @param accountId 対象アカウント ID
     * @param learnedSkillId 対象の習得済みスキル個体 ID
     * @param orbInventoryEntryId 消費するシジル装着オーブ entry ID
     * @param sigilId 装着するシジル item ID
     * @param sigilInventoryEntryId 消費するシジル entry ID
     * @param updatedBy 更新者 ID
     * @param onSuccess API 正本反映成功時の処理
     * @param onFailure API または正本同期失敗時の処理
     * @param onPending 待機時間を超え、処理中ロックを維持したままUIを閉じる処理
     * @return 処理を受け付けた場合は {@code true}
     */
    public boolean attachSigilAsync(
        @NotNull UUID accountId,
        @NotNull UUID learnedSkillId,
        @NotNull UUID orbInventoryEntryId,
        @NotNull String sigilId,
        @NotNull UUID sigilInventoryEntryId,
        @NotNull UUID updatedBy,
        @NotNull Consumer<LearnedSkillInstance> onSuccess,
        @NotNull Consumer<Throwable> onFailure,
        @NotNull Runnable onPending
    ) {
        LearnedSkillInstance current = findInstance(accountId, learnedSkillId);
        if (current == null) {
            onFailure.accept(new IllegalStateException("Learned skill is no longer loaded."));
            return false;
        }
        int resolvedSlot = 0;
        while (hasSigilSlot(current, resolvedSlot)) {
            resolvedSlot++;
        }
        final int slot = resolvedSlot;
        return attachSigilLocally(
            accountId, learnedSkillId, orbInventoryEntryId,
            new LearnedSkillSigil(UUID.randomUUID(), sigilId, "", slot), sigilInventoryEntryId,
            onSuccess, onFailure
        );
    }

    /**
     * GUI が検証済みのシジル個体情報を使い、装着と二素材消費を同じ state lock で確定します。
     */
    public boolean attachSigilLocally(
        @NotNull UUID accountId,
        @NotNull UUID learnedSkillId,
        @NotNull UUID orbInventoryEntryId,
        @NotNull LearnedSkillSigil sigil,
        @NotNull UUID sigilInventoryEntryId,
        @NotNull Consumer<LearnedSkillInstance> onSuccess,
        @NotNull Consumer<Throwable> onFailure
    ) {
        try {
            CompletableFuture<LearnedSkillInstance> future = commitCriticalPaymentMutation(accountId,
                Map.of(orbInventoryEntryId, 1L, sigilInventoryEntryId, 1L), () -> {
                    LearnedSkillInstance current = findInstance(accountId, learnedSkillId);
                    if (current == null || current.getSigils().stream().anyMatch(existing ->
                        existing.getLearnedSkillSigilId().equals(sigil.getLearnedSkillSigilId())
                            || existing.getSlotIndex() == sigil.getSlotIndex()
                            || existing.getEquipGroupId().equalsIgnoreCase(sigil.getEquipGroupId()))) {
                        throw new IllegalStateException("Selected sigil can no longer be attached.");
                    }
                    List<LearnedSkillSigil> sigils = new ArrayList<>(current.getSigils());
                    sigils.add(sigil);
                    LearnedSkillInstance next = withSigils(current, sigils);
                    replaceCached(next);
                    return next;
                });
            completeCriticalMutation(accountId, future, onSuccess, onFailure, () -> { });
            return true;
        } catch (RuntimeException error) {
            onFailure.accept(error);
            return false;
        }
    }

    /**
     * 装着済みシジルを API で取り外し、返却先 entry と習得個体を正本へ同期します。
     *
     * @param accountId アカウント ID
     * @param learnedSkillId 対象の習得済みスキル個体 ID
     * @param orbInventoryEntryId 操作確定時に共通消費順で解決済みの SIGIL_DETACH オーブ inventory entry ID
     * @param learnedSkillSigilId 取り外す装着シジル行 ID
     * @param updatedBy 更新者 ID
     * @param onSuccess API 更新と返却 entry 同期の成功時処理
     * @param onFailure API 更新または正本同期の失敗時処理
     * @return 処理を受け付けた場合は {@code true}、別の習得スキル mutation 実行中は {@code false}
     */
    public boolean detachSigilAsync(
        @NotNull UUID accountId,
        @NotNull UUID learnedSkillId,
        @NotNull UUID orbInventoryEntryId,
        @NotNull UUID learnedSkillSigilId,
        @NotNull UUID updatedBy,
        @NotNull Consumer<LearnedSkillInstance> onSuccess,
        @NotNull Consumer<Throwable> onFailure
    ) {
        return detachSigilAsync(
            accountId,
            learnedSkillId,
            orbInventoryEntryId,
            learnedSkillSigilId,
            updatedBy,
            onSuccess,
            onFailure,
            () -> { }
        );
    }

    /**
     * シジル脱着を受け付け、プレイヤー向け待機時間を超えた時に保留通知を呼び出します。
     *
     * @param accountId 対象アカウント ID
     * @param learnedSkillId 対象の習得済みスキル個体 ID
     * @param orbInventoryEntryId 消費するシジル脱着オーブ entry ID
     * @param learnedSkillSigilId 取り外す装着シジル行 ID
     * @param updatedBy 更新者 ID
     * @param onSuccess API 正本反映成功時の処理
     * @param onFailure API または正本同期失敗時の処理
     * @param onPending 待機時間を超え、処理中ロックを維持したままUIを閉じる処理
     * @return 処理を受け付けた場合は {@code true}
     */
    public boolean detachSigilAsync(
        @NotNull UUID accountId,
        @NotNull UUID learnedSkillId,
        @NotNull UUID orbInventoryEntryId,
        @NotNull UUID learnedSkillSigilId,
        @NotNull UUID updatedBy,
        @NotNull Consumer<LearnedSkillInstance> onSuccess,
        @NotNull Consumer<Throwable> onFailure,
        @NotNull Runnable onPending
    ) {
        return detachSigilLocally(
            accountId, learnedSkillId, orbInventoryEntryId, learnedSkillSigilId,
            () -> { }, onSuccess, onFailure
        );
    }

    /**
     * 脱着オーブの消費、返却アイテム追加、習得個体更新を同じ account state lock で確定します。
     */
    public boolean detachSigilLocally(
        @NotNull UUID accountId,
        @NotNull UUID learnedSkillId,
        @NotNull UUID orbInventoryEntryId,
        @NotNull UUID learnedSkillSigilId,
        @NotNull Runnable returnSigilToInventory,
        @NotNull Consumer<LearnedSkillInstance> onSuccess,
        @NotNull Consumer<Throwable> onFailure
    ) {
        try {
            CompletableFuture<LearnedSkillInstance> future = commitCriticalPaymentMutation(
                accountId, Map.of(orbInventoryEntryId, 1L), () -> {
                LearnedSkillInstance current = findInstance(accountId, learnedSkillId);
                if (current == null || current.getSigils().stream().noneMatch(sigil ->
                    sigil.getLearnedSkillSigilId().equals(learnedSkillSigilId))) {
                    throw new IllegalStateException("Selected sigil is no longer attached.");
                }
                returnSigilToInventory.run();
                List<LearnedSkillSigil> sigils = current.getSigils().stream()
                    .filter(sigil -> !sigil.getLearnedSkillSigilId().equals(learnedSkillSigilId))
                    .toList();
                LearnedSkillInstance next = withSigils(current, sigils);
                replaceCached(next);
                return next;
            });
            completeCriticalMutation(accountId, future, onSuccess, onFailure, () -> { });
            return true;
        } catch (RuntimeException error) {
            onFailure.accept(error);
            return false;
        }
    }

    /**
     * 習得済みスキル個体を完成スナップショットで忘却し、ロード済みキャッシュからも除去します。
     *
     * @param accountId アカウント ID
     * @param learnedSkillId 忘却対象の個体 ID
     * @param updatedBy 更新者 ID
     * @param onSuccess SQL ACK 成功時の処理
     * @param onFailure snapshot 保存失敗時の処理
     * @return 処理を受け付けた場合は {@code true}、別の mutation 実行中なら {@code false}
     */
    public boolean forgetAsync(
        @NotNull UUID accountId,
        @NotNull UUID learnedSkillId,
        @NotNull UUID updatedBy,
        @NotNull Consumer<LearnedSkillInstance> onSuccess,
        @NotNull Consumer<Throwable> onFailure
    ) {
        return forgetAsync(accountId, learnedSkillId, updatedBy, onSuccess, onFailure, () -> { });
    }

    /**
     * 忘却を受け付け、応答が遅い場合は保存処理中であることを通知します。
     *
     * @param accountId アカウント ID
     * @param learnedSkillId 忘却対象の個体 ID
     * @param updatedBy 更新者 ID
     * @param onSuccess SQL ACK 成功時の処理
     * @param onFailure snapshot 保存失敗時の処理
     * @param onPending snapshot ACK 待機が上限を超えた時の処理
     * @return 処理を受け付けた場合は {@code true}、別の mutation 実行中なら {@code false}
     */
    public boolean forgetAsync(
        @NotNull UUID accountId,
        @NotNull UUID learnedSkillId,
        @NotNull UUID updatedBy,
        @NotNull Consumer<LearnedSkillInstance> onSuccess,
        @NotNull Consumer<Throwable> onFailure,
        @NotNull Runnable onPending
    ) {
        return forgetWithInventoryMutationAsync(
            accountId, learnedSkillId, updatedBy, () -> { }, onSuccess, onFailure, onPending);
    }

    /**
     * インベントリ内の支払い・返却とスキル忘却を同じ完成スナップショットで確定します。
     * inventoryMutation は account lane 内で実行され、保存失敗時は inventory と skill の両方を復元します。
     */
    public boolean forgetWithInventoryMutationAsync(
        @NotNull UUID accountId,
        @NotNull UUID learnedSkillId,
        @NotNull UUID updatedBy,
        @NotNull Runnable inventoryMutation,
        @NotNull Consumer<LearnedSkillInstance> onSuccess,
        @NotNull Consumer<Throwable> onFailure,
        @NotNull Runnable onPending
    ) {
        try {
            CompletableFuture<LearnedSkillInstance> future = commitCriticalInventoryMutation(
                accountId, inventoryMutation, () -> {
                    LearnedSkillInstance current = findInstance(accountId, learnedSkillId);
                    if (current == null) {
                        throw new IllegalStateException("Learned skill is no longer loaded.");
                    }
                    Integer expectedVersion = persistedSkillVersions
                        .getOrDefault(accountId, Map.of()).get(learnedSkillId);
                    if (expectedVersion != null) {
                        pendingDeletedSkillVersions.computeIfAbsent(accountId,
                            ignored -> new ConcurrentHashMap<>()).put(learnedSkillId, expectedVersion);
                    }
                    removeCached(accountId, learnedSkillId);
                    return current;
            });
            completeCriticalMutation(accountId, future, onSuccess, onFailure, onPending);
            return true;
        } catch (RuntimeException error) {
            onFailure.accept(error);
            return false;
        }
    }

    /**
     * dirty な習得済みスキル全体を player-state section として取得します。
     * 呼出元は account の inventory state lock を保持している必要があります。
     *
     * @param accountId 対象アカウント ID
     * @return dirty でない場合は {@code null}
     */
    public synchronized @Nullable PlayerStateSection snapshotPlayerState(@NotNull UUID accountId) {
        Long capturedRevision = playerStateRevisions.get(accountId);
        if (capturedRevision == null || !dirtyPlayerStates.contains(accountId)) {
            return null;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("accountId", accountId.toString());
        payload.addProperty("clientRevision", capturedRevision);
        JsonArray skills = new JsonArray();
        Set<UUID> capturedSkillIds = new LinkedHashSet<>();
        Map<UUID, Integer> capturedVersions = new LinkedHashMap<>();
        UUID capturedEpoch = playerStateEpochs.get(accountId);
        Map<UUID, Integer> baseVersions = persistedSkillVersions.getOrDefault(accountId, Map.of());
        for (LearnedSkillInstance skill : getLearnedSkills(accountId)) {
            capturedSkillIds.add(skill.getLearnedSkillId());
            capturedVersions.put(skill.getLearnedSkillId(), skill.getVersion());
            JsonObject value = new JsonObject();
            value.addProperty("learnedSkillId", skill.getLearnedSkillId().toString());
            value.addProperty("skillId", skill.getSkillId());
            value.addProperty("level", skill.getLevel());
            Integer expectedVersion = baseVersions.get(skill.getLearnedSkillId());
            value.add("expectedVersion", expectedVersion == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(expectedVersion));
            value.addProperty("targetVersion", skill.getVersion());
            JsonArray sigils = new JsonArray();
            for (LearnedSkillSigil sigil : skill.getSigils()) {
                JsonObject sigilJson = new JsonObject();
                sigilJson.addProperty("learnedSkillSigilId", sigil.getLearnedSkillSigilId().toString());
                sigilJson.addProperty("sigilId", sigil.getSigilId());
                sigilJson.addProperty("equipGroupId", sigil.getEquipGroupId());
                sigilJson.addProperty("slotIndex", sigil.getSlotIndex());
                sigils.add(sigilJson);
            }
            value.add("sigils", sigils);
            skills.add(value);
        }
        payload.add("skills", skills);
        JsonArray deletedSkills = new JsonArray();
        Set<UUID> capturedDeletedIds = new LinkedHashSet<>();
        for (Map.Entry<UUID, Integer> deleted : pendingDeletedSkillVersions
            .getOrDefault(accountId, Map.of()).entrySet()) {
            capturedDeletedIds.add(deleted.getKey());
            JsonObject value = new JsonObject();
            value.addProperty("learnedSkillId", deleted.getKey().toString());
            value.addProperty("expectedVersion", deleted.getValue());
            deletedSkills.add(value);
        }
        payload.add("deletedSkills", deletedSkills);
        return new PlayerStateSection("learnedSkills", payload,
            acknowledged -> acknowledgeSnapshot(
                accountId, capturedEpoch, capturedRevision, capturedVersions, capturedSkillIds,
                capturedDeletedIds, acknowledged));
    }

    private <T> @NotNull CompletableFuture<T> commitCriticalPaymentMutation(
        @NotNull UUID accountId,
        @NotNull Map<UUID, Long> paymentEntries,
        @NotNull java.util.function.Supplier<T> mutation
    ) {
        return withMutationLock(accountId, () ->
            commitCriticalPaymentMutationInLane(accountId, paymentEntries, mutation));
    }

    private <T> @NotNull CompletableFuture<T> commitCriticalPaymentMutationInLane(
        @NotNull UUID accountId,
        @NotNull Map<UUID, Long> paymentEntries,
        @NotNull java.util.function.Supplier<T> mutation
    ) {
        if (paymentEntries.isEmpty()) {
            return commitCriticalInventoryMutationInLane(accountId, () -> { }, mutation);
        }
        return inventoryService.executeCriticalPlayerMutation(accountId, () -> {
            InventoryService.InventoryStateSnapshot inventoryBefore = inventoryService.snapshotState(accountId);
            SkillStateCheckpoint skillsBefore = captureSkillStateCheckpoint(accountId);
            UUID operationId = UUID.randomUUID();
            boolean committed = false;
            try {
                UUID reservedOperationId = operationId;
                java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
                committed = inventoryService.executeLocalPlayerMutation(accountId, () -> {
                    if (!inventoryService.reserveLocalMutationPayment(accountId, reservedOperationId, paymentEntries)) {
                        return false;
                    }
                    return inventoryService.commitLocalOrbOperationPayment(accountId, reservedOperationId, () -> {
                        synchronized (this) {
                            result.set(mutation.get());
                            markPlayerStateDirty(accountId);
                        }
                    });
                });
                if (!committed || result.get() == null) {
                    throw new IllegalStateException("Skill mutation payment is no longer available.");
                }
                inventoryService.releaseOrbOperationPayment(accountId, reservedOperationId);
                return new InventorySaveCoordinator.CriticalMutation<>(result.get(), () -> {
                    inventoryService.restoreState(inventoryBefore);
                    restoreSkillStateCheckpoint(skillsBefore);
                });
            } catch (RuntimeException | Error failure) {
                inventoryService.restoreState(inventoryBefore);
                restoreSkillStateCheckpoint(skillsBefore);
                throw failure;
            } finally {
                if (!committed) {
                    inventoryService.releaseOrbOperationPayment(accountId, operationId);
                }
            }
        });
    }

    private <T> @NotNull CompletableFuture<T> commitCriticalInventoryMutation(
        @NotNull UUID accountId,
        @NotNull Runnable inventoryMutation,
        @NotNull java.util.function.Supplier<T> mutation
    ) {
        return withMutationLock(accountId, () ->
            commitCriticalInventoryMutationInLane(accountId, inventoryMutation, mutation));
    }

    private <T> @NotNull CompletableFuture<T> commitCriticalInventoryMutationInLane(
        @NotNull UUID accountId,
        @NotNull Runnable inventoryMutation,
        @NotNull java.util.function.Supplier<T> mutation
    ) {
        return inventoryService.executeCriticalPlayerMutation(accountId, () -> {
            InventoryService.InventoryStateSnapshot inventoryBefore = inventoryService.snapshotState(accountId);
            SkillStateCheckpoint skillsBefore = captureSkillStateCheckpoint(accountId);
            try {
                T result = inventoryService.executeLocalPlayerMutation(accountId, () -> {
                    inventoryMutation.run();
                    synchronized (this) {
                        T value = mutation.get();
                        markPlayerStateDirty(accountId);
                        return value;
                    }
                });
                return new InventorySaveCoordinator.CriticalMutation<>(result, () -> {
                    inventoryService.restoreState(inventoryBefore);
                    restoreSkillStateCheckpoint(skillsBefore);
                });
            } catch (RuntimeException | Error failure) {
                inventoryService.restoreState(inventoryBefore);
                restoreSkillStateCheckpoint(skillsBefore);
                throw failure;
            }
        });
    }

    private <T> @NotNull CompletableFuture<T> withMutationLock(
        @NotNull UUID accountId,
        @NotNull java.util.function.Supplier<CompletableFuture<T>> operation
    ) {
        AtomicBoolean lock = mutationLocks.computeIfAbsent(accountId, ignored -> new AtomicBoolean());
        if (!lock.compareAndSet(false, true)) {
            throw new IllegalStateException("A learned-skill mutation is already in progress.");
        }
        if (!sessionTokens.containsKey(accountId)) {
            releaseMutationLock(accountId, lock);
            throw new IllegalStateException("Learned-skill session is no longer active.");
        }
        try {
            CompletableFuture<T> future = operation.get();
            future.whenComplete((ignored, failure) -> releaseMutationLock(accountId, lock));
            return future;
        } catch (RuntimeException | Error failure) {
            releaseMutationLock(accountId, lock);
            throw failure;
        }
    }

    private synchronized @NotNull SkillStateCheckpoint captureSkillStateCheckpoint(@NotNull UUID accountId) {
        return new SkillStateCheckpoint(
            accountId,
            List.copyOf(skillsByAccount.getOrDefault(accountId, List.of())),
            dirtyPlayerStates.contains(accountId),
            playerStateEpochs.get(accountId),
            playerStateRevisions.get(accountId),
            acknowledgedPlayerStateRevisions.get(accountId),
            Map.copyOf(persistedSkillVersions.getOrDefault(accountId, Map.of())),
            Map.copyOf(persistedSkillUpdatedAts.getOrDefault(accountId, Map.of())),
            Map.copyOf(pendingDeletedSkillVersions.getOrDefault(accountId, Map.of()))
        );
    }

    private synchronized void restoreSkillStateCheckpoint(@NotNull SkillStateCheckpoint checkpoint) {
        UUID accountId = checkpoint.accountId();
        skillsByAccount.put(accountId, checkpoint.skills());
        restoreMapValue(playerStateEpochs, accountId, checkpoint.epoch());
        restoreMapValue(playerStateRevisions, accountId, checkpoint.revision());
        restoreMapValue(acknowledgedPlayerStateRevisions, accountId, checkpoint.acknowledgedRevision());
        persistedSkillVersions.put(accountId, new ConcurrentHashMap<>(checkpoint.persistedVersions()));
        persistedSkillUpdatedAts.put(accountId, new ConcurrentHashMap<>(checkpoint.persistedUpdatedAts()));
        if (checkpoint.pendingDeletedVersions().isEmpty()) pendingDeletedSkillVersions.remove(accountId);
        else pendingDeletedSkillVersions.put(accountId, new ConcurrentHashMap<>(checkpoint.pendingDeletedVersions()));
        if (checkpoint.dirty()) dirtyPlayerStates.add(accountId);
        else dirtyPlayerStates.remove(accountId);
    }

    private static <K, V> void restoreMapValue(Map<K, V> map, K key, @Nullable V value) {
        if (value == null) map.remove(key);
        else map.put(key, value);
    }

    private <T> void completeCriticalMutation(
        @NotNull UUID accountId,
        @NotNull CompletableFuture<T> future,
        @NotNull Consumer<T> onSuccess,
        @NotNull Consumer<Throwable> onFailure,
        @NotNull Runnable onPending
    ) {
        UUID sessionToken = sessionTokens.get(accountId);
        CompletableFuture.delayedExecutor(mutationTimeoutMillis, TimeUnit.MILLISECONDS).execute(() -> {
            if (!future.isDone() && sessionToken != null && isCurrentSession(accountId, sessionToken)) {
                AsyncTaskUtil.runSyncEventually(plugin, () -> {
                    if (isCurrentSession(accountId, sessionToken)) onPending.run();
                });
            }
        });
        future.whenComplete((result, failure) -> AsyncTaskUtil.runSyncEventually(plugin, () -> {
            if (sessionToken == null || !isCurrentSession(accountId, sessionToken)) return;
            if (failure == null) onSuccess.accept(result);
            else onFailure.accept(unwrapCompletionFailure(failure));
        }));
    }

    private static @NotNull Throwable unwrapCompletionFailure(@NotNull Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
            || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private void markPlayerStateDirty(@NotNull UUID accountId) {
        dirtyPlayerStates.add(accountId);
        playerStateEpochs.computeIfAbsent(accountId, ignored -> UUID.randomUUID());
        playerStateRevisions.merge(accountId, 1L, Long::sum);
    }

    private synchronized void acknowledgeSnapshot(
        @NotNull UUID accountId,
        UUID capturedEpoch,
        long capturedRevision,
        Map<UUID, Integer> capturedVersions,
        @NotNull Set<UUID> capturedSkillIds,
        @NotNull Set<UUID> capturedDeletedIds,
        @NotNull JsonElement acknowledged
    ) {
        if (!java.util.Objects.equals(capturedEpoch, playerStateEpochs.get(accountId))
            || capturedRevision <= acknowledgedPlayerStateRevisions.getOrDefault(accountId, -1L)
            || !dirtyPlayerStates.contains(accountId) || !acknowledged.isJsonObject()) return;
        JsonObject metadata = acknowledged.getAsJsonObject();
        try {
            if (!metadata.has("clientRevision")
                || metadata.get("clientRevision").getAsLong() != capturedRevision
                || !metadata.has("entries") || !metadata.get("entries").isJsonArray()
                || !metadata.has("deletedIds") || !metadata.get("deletedIds").isJsonArray()) return;
            Map<UUID, JsonObject> entries = new LinkedHashMap<>();
            for (JsonElement entry : metadata.getAsJsonArray("entries")) {
                JsonObject value = entry.getAsJsonObject();
                UUID id = UUID.fromString(value.get("learnedSkillId").getAsString());
                if (!value.has("version") || value.get("version").isJsonNull() || entries.put(id, value) != null) return;
            }
            Set<UUID> deletedIds = new LinkedHashSet<>();
            for (JsonElement deleted : metadata.getAsJsonArray("deletedIds")) {
                if (!deletedIds.add(UUID.fromString(deleted.getAsString()))) return;
            }
            if (!entries.keySet().equals(capturedSkillIds) || !deletedIds.equals(capturedDeletedIds)) return;
            Map<UUID, Integer> receivedVersions = new LinkedHashMap<>();
            Map<UUID, LocalDateTime> receivedUpdatedAts = new LinkedHashMap<>();
            for (Map.Entry<UUID, JsonObject> entry : entries.entrySet()) {
                receivedVersions.put(entry.getKey(), entry.getValue().get("version").getAsInt());
                if (entry.getValue().has("updatedAt") && !entry.getValue().get("updatedAt").isJsonNull()) {
                    receivedUpdatedAts.put(entry.getKey(), LocalDateTime.parse(entry.getValue().get("updatedAt").getAsString()));
                }
            }
            Map<UUID, Integer> versions = persistedSkillVersions.computeIfAbsent(accountId, ignored -> new ConcurrentHashMap<>());
            Map<UUID, LocalDateTime> updatedAts = persistedSkillUpdatedAts.computeIfAbsent(accountId, ignored -> new ConcurrentHashMap<>());
            Map<UUID, Integer> pending = pendingDeletedSkillVersions.get(accountId);
            for (Map.Entry<UUID, JsonObject> entry : entries.entrySet()) {
                int version = receivedVersions.get(entry.getKey());
                versions.put(entry.getKey(), version);
                LearnedSkillInstance current = findInstance(accountId, entry.getKey());
                if (current != null) {
                    int nextVersion = version + current.getVersion() - capturedVersions.get(entry.getKey());
                    replaceCached(new LearnedSkillInstance(current.getLearnedSkillId(), accountId,
                        current.getSkillId(), current.getLevel(), current.getSigils(), nextVersion,
                        current.getCreatedAt(), current.getUpdatedAt()));
                } else {
                    // 捕捉だけでは送信済みと見なさない。新規learnの実ACKで初めて削除期待版が確定する。
                    pending = pendingDeletedSkillVersions.computeIfAbsent(accountId, ignored -> new ConcurrentHashMap<>());
                    pending.put(entry.getKey(), version);
                }
                if (pending != null && pending.containsKey(entry.getKey())) pending.put(entry.getKey(), version);
                if (receivedUpdatedAts.containsKey(entry.getKey())) {
                    updatedAts.put(entry.getKey(), receivedUpdatedAts.get(entry.getKey()));
                }
            }
            for (UUID id : deletedIds) {
                versions.remove(id); updatedAts.remove(id);
                if (pending != null) pending.remove(id);
            }
        } catch (RuntimeException malformedAck) { return; }
        acknowledgedPlayerStateRevisions.put(accountId, capturedRevision);
        if (playerStateRevisions.getOrDefault(accountId, 0L) == capturedRevision) {
            dirtyPlayerStates.remove(accountId);
            evictReleasedPlayerState(accountId);
        }
    }

    private static @NotNull List<LearnedSkillInstance> appendSkill(
        @Nullable List<LearnedSkillInstance> current,
        @NotNull LearnedSkillInstance appended
    ) {
        List<LearnedSkillInstance> values = new ArrayList<>(current == null ? List.of() : current);
        values.add(appended);
        return List.copyOf(values);
    }

    private static @NotNull LearnedSkillInstance withLevel(
        @NotNull LearnedSkillInstance current,
        int level
    ) {
        return new LearnedSkillInstance(current.getLearnedSkillId(), current.getAccountId(), current.getSkillId(),
            level, current.getSigils(), current.getVersion() + 1, current.getCreatedAt(), LocalDateTime.now());
    }

    private static @NotNull LearnedSkillInstance withSigils(
        @NotNull LearnedSkillInstance current,
        @NotNull List<LearnedSkillSigil> sigils
    ) {
        return new LearnedSkillInstance(current.getLearnedSkillId(), current.getAccountId(), current.getSkillId(),
            current.getLevel(), List.copyOf(sigils), current.getVersion() + 1,
            current.getCreatedAt(), LocalDateTime.now());
    }

    private static boolean hasSigilSlot(@NotNull LearnedSkillInstance skill, int slot) {
        return skill.getSigils().stream().anyMatch(existing -> existing.getSlotIndex() == slot);
    }

    private void releaseMutationLock(UUID accountId, AtomicBoolean lock) {
        mutationLocks.remove(accountId, lock);
        lock.set(false);
    }

    private boolean isCurrentSession(UUID accountId, UUID sessionToken) {
        return sessionToken.equals(sessionTokens.get(accountId));
    }

    private void replaceCached(LearnedSkillInstance updated) {
        skillsByAccount.compute(updated.getAccountId(), (ignored, current) -> {
            List<LearnedSkillInstance> next = new ArrayList<>(current == null ? List.of() : current);
            next.removeIf(skill -> skill.getLearnedSkillId().equals(updated.getLearnedSkillId()));
            next.add(updated);
            return normalize(next);
        });
    }

    private void removeCached(UUID accountId, UUID learnedSkillId) {
        skillsByAccount.computeIfPresent(accountId, (ignored, current) ->
            current.stream().filter(skill -> !skill.getLearnedSkillId().equals(learnedSkillId)).toList()
        );
    }

    private static List<LearnedSkillInstance> normalize(List<LearnedSkillInstance> skills) {
        return skills.stream()
            .sorted(Comparator.comparing(LearnedSkillInstance::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(LearnedSkillInstance::getLearnedSkillId))
            .toList();
    }

    private record SkillStateCheckpoint(
        @NotNull UUID accountId,
        @NotNull List<LearnedSkillInstance> skills,
        boolean dirty,
        @Nullable UUID epoch,
        @Nullable Long revision,
        @Nullable Long acknowledgedRevision,
        @NotNull Map<UUID, Integer> persistedVersions,
        @NotNull Map<UUID, LocalDateTime> persistedUpdatedAts,
        @NotNull Map<UUID, Integer> pendingDeletedVersions
    ) {
    }


}
