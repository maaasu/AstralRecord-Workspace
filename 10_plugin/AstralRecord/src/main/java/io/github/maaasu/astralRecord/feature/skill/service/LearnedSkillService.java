package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillMaterialMutationResult;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillSigilDetachResult;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillMutationException;
import io.github.maaasu.astralRecord.feature.skill.repository.LearnedSkillRepository;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.AsyncTaskUtil;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 習得済みスキル個体のキャッシュと API 更新を扱います。
 *
 * <p>同じ skillId を持つ個体を複数保持できるため、参照・バインド・強化は常に
 * learnedSkillId を正本として行います。</p>
 */
public final class LearnedSkillService {
    private static final long DEFAULT_MUTATION_TIMEOUT_MILLIS = 60_000L;
    private static final long MUTATION_RETRY_INITIAL_MILLIS = 250L;
    private static final long MUTATION_RETRY_MAX_MILLIS = 5_000L;
    private static final int MUTATION_RETRY_MAX_ATTEMPTS = 20;

    private final Plugin plugin;
    private final LearnedSkillRepository repository;
    private final InventoryService inventoryService;
    private final long mutationTimeoutMillis;
    private final Map<UUID, List<LearnedSkillInstance>> skillsByAccount = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicBoolean> mutationLocks = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> sessionTokens = new ConcurrentHashMap<>();

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

    public @NotNull List<LearnedSkillInstance> loadInitialSkills(@NotNull UUID accountId) {
        return normalize(repository.findByAccountId(accountId));
    }

    public void applyInitialSkills(
        @NotNull UUID accountId,
        @NotNull List<LearnedSkillInstance> skills
    ) {
        skillsByAccount.put(accountId, normalize(skills));
        sessionTokens.put(accountId, UUID.randomUUID());
    }

    public void invalidate(@NotNull UUID accountId) {
        skillsByAccount.remove(accountId);
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
        UUID operationId = UUID.randomUUID();
        return mutateAsync(
            accountId,
            requiredItemEntryIds,
            () -> managerMutationOutcome(repository.learn(accountId, skillId, updatedBy, operationId)),
            onSuccess,
            onFailure,
            onPending
        );
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
        return levelUpFromManagerAsync(
            accountId,
            learnedSkillId,
            updatedBy,
            requiredItemEntryIds,
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
        UUID operationId = UUID.randomUUID();
        return mutateAsync(
            accountId,
            requiredItemEntryIds,
            () -> managerMutationOutcome(repository.levelUp(accountId, learnedSkillId, updatedBy, operationId)),
            onSuccess,
            onFailure,
            onPending
        );
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
        UUID operationId = UUID.randomUUID();
        return mutateAsync(
            accountId,
            List.of(orbInventoryEntryId, sigilInventoryEntryId),
            () -> oneEachMutationOutcome(
                repository.attachSigil(
                    accountId,
                    learnedSkillId,
                    orbInventoryEntryId,
                    sigilId,
                    sigilInventoryEntryId,
                    updatedBy,
                    operationId
                ),
                List.of(orbInventoryEntryId, sigilInventoryEntryId)
            ),
            onSuccess,
            onFailure,
            onPending
        );
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
        UUID operationId = UUID.randomUUID();
        return mutateAsync(
            accountId,
            List.of(orbInventoryEntryId),
            () -> {
                LearnedSkillSigilDetachResult result = repository.detachSigil(
                    accountId,
                    learnedSkillId,
                    orbInventoryEntryId,
                    learnedSkillSigilId,
                    updatedBy,
                    operationId
                );
                return new MutationOutcome(
                    result.getSkill(),
                    Map.of(orbInventoryEntryId, 1L),
                    List.of(result.getReturnedInventoryEntryId()),
                    false
                );
            },
            onSuccess,
            onFailure,
            onPending
        );
    }

    /**
     * 習得済みスキル個体を API から忘却し、ロード済みキャッシュからも除去します。
     *
     * @param accountId アカウント ID
     * @param learnedSkillId 忘却対象の個体 ID
     * @param updatedBy 更新者 ID
     * @param onSuccess API 更新成功時の処理
     * @param onFailure API 更新失敗時の処理
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
     * 忘却を受け付け、応答が遅い場合は保留通知を行いながら同じ operationId で再試行します。
     * 忘却はインベントリ素材を伴わないため、素材保存の事前待機は行いません。
     *
     * @param accountId アカウント ID
     * @param learnedSkillId 忘却対象の個体 ID
     * @param updatedBy 更新者 ID
     * @param onSuccess API 更新成功時の処理
     * @param onFailure API 更新失敗時の処理
     * @param onPending API mutation 開始後に応答待ちが上限を超えた時の処理
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
        UUID operationId = UUID.randomUUID();
        return mutateAsync(
            accountId,
            List.of(),
            () -> new MutationOutcome(
                repository.forget(accountId, learnedSkillId, updatedBy, operationId),
                Map.of(),
                List.of(),
                true
            ),
            onSuccess,
            onFailure,
            onPending,
            false
        );
    }

    private boolean mutateAsync(
        UUID accountId,
        UUID materialInventoryEntryId,
        Mutation mutation,
        Consumer<LearnedSkillInstance> onSuccess,
        Consumer<Throwable> onFailure
    ) {
        return mutateAsync(
            accountId,
            List.of(materialInventoryEntryId),
            mutation,
            onSuccess,
            onFailure,
            () -> { }
        );
    }

    private boolean mutateAsync(
        UUID accountId,
        List<UUID> materialInventoryEntryIds,
        Mutation mutation,
        Consumer<LearnedSkillInstance> onSuccess,
        Consumer<Throwable> onFailure,
        Runnable onPending
    ) {
        return mutateAsync(
            accountId,
            materialInventoryEntryIds,
            mutation,
            onSuccess,
            onFailure,
            onPending,
            true
        );
    }

    private boolean mutateAsync(
        UUID accountId,
        List<UUID> materialInventoryEntryIds,
        Mutation mutation,
        Consumer<LearnedSkillInstance> onSuccess,
        Consumer<Throwable> onFailure,
        Runnable onPending,
        boolean saveBeforeMutation
    ) {
        AtomicBoolean lock = mutationLocks.computeIfAbsent(accountId, ignored -> new AtomicBoolean());
        if (!lock.compareAndSet(false, true)) return false;
        UUID sessionToken = sessionTokens.get(accountId);
        if (sessionToken == null) {
            releaseMutationLock(accountId, lock);
            return false;
        }

        MutationWatchdog watchdog;
        try {
            watchdog = scheduleMutationWatchdog(
                accountId,
                sessionToken,
                lock,
                onPending,
                error -> notifyFailureOnCurrentSession(accountId, sessionToken, onFailure, error)
            );
        } catch (Throwable schedulingFailure) {
            releaseMutationLock(accountId, lock);
            Logger.log(
                LogId.W_5252,
                "skill_mutation_watchdog_schedule",
                schedulingFailure.getMessage()
            );
            return false;
        }
        MutationRetryState retryState = new MutationRetryState();
        if (!saveBeforeMutation) {
            scheduleMutationAttempt(
                accountId,
                sessionToken,
                materialInventoryEntryIds,
                mutation,
                onSuccess,
                onFailure,
                lock,
                watchdog,
                retryState,
                0L
            );
            return true;
        }
        final CompletableFuture<Boolean> saveFuture;
        try {
            saveFuture = inventoryService.saveNow(accountId);
        } catch (Throwable saveFailure) {
            completeFailure(accountId, sessionToken, lock, watchdog, onFailure, saveFailure);
            return true;
        }
        saveFuture.whenComplete((saved, saveError) -> {
            if (watchdog.isSettled()) {
                return;
            }
            if (!isCurrentSession(accountId, sessionToken)) {
                watchdog.complete();
                releaseMutationLock(accountId, lock);
                return;
            }
            // 直前の保存キューが失敗しても、素材消費 API は inventoryEntryId を正本で検証する。
            // ここで利用者操作を中断すると、無関係な stale entry の同期失敗だけで次のスキル習得・
            // 合成を永続的に拒否してしまう。保存完了（成功／失敗）後に API mutation を直列実行し、
            // 成否どちらでも素材 entry を API 正本へ再同期する。
            scheduleMutationAttempt(
                accountId,
                sessionToken,
                materialInventoryEntryIds,
                mutation,
                onSuccess,
                onFailure,
                lock,
                watchdog,
                retryState,
                0L
            );
        });
        return true;
    }

    private void scheduleMutationAttempt(
        UUID accountId,
        UUID sessionToken,
        List<UUID> materialInventoryEntryIds,
        Mutation mutation,
        Consumer<LearnedSkillInstance> onSuccess,
        Consumer<Throwable> onFailure,
        AtomicBoolean lock,
        MutationWatchdog watchdog,
        MutationRetryState retryState,
        long delayMillis
    ) {
        Runnable attempt = () -> runMutationAttempt(
            accountId,
            sessionToken,
            materialInventoryEntryIds,
            mutation,
            onSuccess,
            onFailure,
            lock,
            watchdog,
            retryState
        );
        try {
            if (delayMillis <= 0L) {
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, attempt);
            } else {
                plugin.getServer().getScheduler().runTaskLaterAsynchronously(
                    plugin,
                    attempt,
                    Math.max(1L, (delayMillis + 49L) / 50L)
                );
            }
        } catch (Throwable schedulingFailure) {
            Logger.log(
                LogId.W_5252,
                "skill_mutation_async_schedule",
                schedulingFailure.getMessage()
            );
            try {
                CompletableFuture.delayedExecutor(
                    Math.max(MUTATION_RETRY_INITIAL_MILLIS, delayMillis),
                    TimeUnit.MILLISECONDS
                ).execute(attempt);
            } catch (Throwable fallbackFailure) {
                completeFailure(
                    accountId,
                    sessionToken,
                    lock,
                    watchdog,
                    onFailure,
                    fallbackFailure
                );
            }
        }
    }

    private void runMutationAttempt(
        UUID accountId,
        UUID sessionToken,
        List<UUID> materialInventoryEntryIds,
        Mutation mutation,
        Consumer<LearnedSkillInstance> onSuccess,
        Consumer<Throwable> onFailure,
        AtomicBoolean lock,
        MutationWatchdog watchdog,
        MutationRetryState retryState
    ) {
        if (watchdog.isSettled()) return;
        if (!isCurrentSession(accountId, sessionToken)) {
            watchdog.complete();
            releaseMutationLock(accountId, lock);
            return;
        }
        if (!watchdog.beginMutation()) return;
        retryState.attempts++;
        try {
            MutationOutcome outcome = mutation.execute();
            LearnedSkillInstance result = outcome.skill();
            if (!isCurrentSession(accountId, sessionToken)) {
                watchdog.complete();
                releaseMutationLock(accountId, lock);
                return;
            }
            LinkedHashSet<UUID> reconciliationIds = new LinkedHashSet<>(materialInventoryEntryIds);
            reconciliationIds.addAll(outcome.consumedAmounts().keySet());
            reconciliationIds.addAll(outcome.additionalReconciliationEntryIds());
            for (UUID materialInventoryEntryId : reconciliationIds) {
                try {
                    inventoryService.reconcileAuthoritativeEntry(accountId, materialInventoryEntryId);
                } catch (Throwable reconciliationError) {
                    Long consumedAmount = outcome.consumedAmounts().get(materialInventoryEntryId);
                    if (consumedAmount != null && consumedAmount > 0L) {
                        inventoryService.consumeOwnedEntryAfterAuthoritativeMutation(
                            accountId,
                            materialInventoryEntryId,
                            consumedAmount
                        );
                    }
                    Logger.log(LogId.W_5252, "skill_mutation_reconcile", reconciliationError.getMessage());
                }
            }
            // cache更新は Bukkit API を含まないため先に確定し、成功通知と GUI 操作だけを
            // main task として再受付する。受付拒否時に onSuccess を捨てると呼出元の GUI
            // session lock が残るため、AsyncTaskUtil 側で一時拒否を再試行する。
            if (outcome.removeFromCache()) {
                removeCached(accountId, result.getLearnedSkillId());
            } else {
                replaceCached(result);
            }
            AsyncTaskUtil.runSyncEventually(plugin, () -> {
                try {
                    if (isCurrentSession(accountId, sessionToken)) {
                        onSuccess.accept(result);
                    }
                } finally {
                    watchdog.complete();
                    releaseMutationLock(accountId, lock);
                }
            });
        } catch (Throwable error) {
            if (isRetryableMutationTransport(error)
                && retryState.attempts < MUTATION_RETRY_MAX_ATTEMPTS) {
                scheduleMutationRetry(
                    accountId,
                    sessionToken,
                    materialInventoryEntryIds,
                    mutation,
                    onSuccess,
                    onFailure,
                    lock,
                    watchdog,
                    retryState
                );
                return;
            }
            // API応答不明の再送にも上限を設け、恒常障害時の負荷とaccount lockの滞留を防ぐ。
            // 上限到達時も同じ operationId の結果を正本から再確認してから失敗完了する。
            reconcileAfterFailure(accountId, sessionToken, materialInventoryEntryIds);
            completeFailure(accountId, sessionToken, lock, watchdog, onFailure, error);
        }
    }

    private void scheduleMutationRetry(
        UUID accountId,
        UUID sessionToken,
        List<UUID> materialInventoryEntryIds,
        Mutation mutation,
        Consumer<LearnedSkillInstance> onSuccess,
        Consumer<Throwable> onFailure,
        AtomicBoolean lock,
        MutationWatchdog watchdog,
        MutationRetryState retryState
    ) {
        if (watchdog.isSettled() || !isCurrentSession(accountId, sessionToken)) return;
        long delayMillis = retryState.nextDelayMillis;
        retryState.nextDelayMillis = Math.min(MUTATION_RETRY_MAX_MILLIS, delayMillis * 2L);
        scheduleMutationAttempt(
            accountId,
            sessionToken,
            materialInventoryEntryIds,
            mutation,
            onSuccess,
            onFailure,
            lock,
            watchdog,
            retryState,
            delayMillis
        );
    }

    private static boolean isRetryableMutationTransport(@NotNull Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof LearnedSkillMutationException mutationException) {
                return isRetryableHttpStatus(mutationException.getStatusCode());
            }
            if (current instanceof java.io.IOException) return true;
            if (current instanceof InterruptedException) return false;
            current = current.getCause();
        }
        return false;
    }

    /**
     * API が業務結果を返せず、同じ operationId で再送して結果を確定すべき HTTP status です。
     * 4xx の業務エラー（素材不足、権限不成立、冪等キー衝突など）と、アプリ設定・スキーマ不備を
     * 示す 500 は再送しません。
     */
    private static boolean isRetryableHttpStatus(@Nullable Integer statusCode) {
        if (statusCode == null) return false;
        return statusCode == 408
            || statusCode == 425
            || statusCode == 429
            || statusCode == 502
            || statusCode == 503
            || statusCode == 504;
    }

    private void reconcileAfterFailure(UUID accountId, UUID sessionToken, List<UUID> materialInventoryEntryIds) {
        if (!isCurrentSession(accountId, sessionToken)) return;
        try {
            List<LearnedSkillInstance> refreshed = normalize(repository.findByAccountId(accountId));
            for (UUID materialInventoryEntryId : materialInventoryEntryIds) {
                inventoryService.reconcileAuthoritativeEntry(accountId, materialInventoryEntryId);
            }
            if (isCurrentSession(accountId, sessionToken)) {
                skillsByAccount.put(accountId, refreshed);
            }
        } catch (Throwable ignored) {
            // 元の mutation 例外を通知する。再同期は次回ロードでも再試行される。
        }
    }

    private void reconcileSkillsAfterFailure(UUID accountId, UUID sessionToken) {
        if (!isCurrentSession(accountId, sessionToken)) return;
        try {
            List<LearnedSkillInstance> refreshed = normalize(repository.findByAccountId(accountId));
            if (isCurrentSession(accountId, sessionToken)) {
                skillsByAccount.put(accountId, refreshed);
            }
        } catch (Throwable ignored) {
            // 元の mutation 例外を通知し、次回ロードで再同期する。
        }
    }

    private void completeFailure(
        UUID accountId,
        UUID sessionToken,
        AtomicBoolean lock,
        MutationWatchdog watchdog,
        Consumer<Throwable> onFailure,
        Throwable error
    ) {
        Runnable finish = () -> {
            watchdog.complete();
            releaseMutationLock(accountId, lock);
            notifyFailureOnCurrentSession(accountId, sessionToken, onFailure, error);
        };
        // onFailure は呼出元で GUI / Bukkit API を扱うため、main task の受付拒否時に
        // 非同期 thread から直接呼び出さず、受付成功まで再試行する。
        AsyncTaskUtil.runSyncEventually(plugin, finish);
    }

    private MutationWatchdog scheduleMutationWatchdog(
        UUID accountId,
        UUID sessionToken,
        AtomicBoolean lock,
        Runnable onPending,
        Consumer<Throwable> onPreflightFailure
    ) {
        MutationWatchdog watchdog = new MutationWatchdog();
        long delayTicks = Math.max(1L, (mutationTimeoutMillis + 49L) / 50L);
        watchdog.timeoutTask = plugin.getServer().getScheduler().runTaskLaterAsynchronously(
            plugin,
            () -> {
                if (!watchdog.isActive()) {
                    return;
                }
                if (!isCurrentSession(accountId, sessionToken)) {
                    if (watchdog.settleBeforeMutation()) {
                        releaseMutationLock(accountId, lock);
                    }
                    return;
                }
                if (!watchdog.pendingNotified.compareAndSet(false, true)) {
                    return;
                }
                AsyncTaskUtil.runSyncEventually(plugin, () -> {
                    try {
                        if (!watchdog.isActive()) return;
                        if (watchdog.hasMutationStarted()) {
                            if (isCurrentSession(accountId, sessionToken)) onPending.run();
                        } else if (watchdog.settleBeforeMutation()) {
                            releaseMutationLock(accountId, lock);
                            onPreflightFailure.accept(new MutationPreflightTimeoutException(accountId));
                        } else if (watchdog.hasMutationStarted()
                            && watchdog.isActive()
                            && isCurrentSession(accountId, sessionToken)) {
                            onPending.run();
                        }
                    } finally {
                        if (watchdog.settleBeforeMutation()) {
                            releaseMutationLock(accountId, lock);
                        }
                    }
                });
            },
            delayTicks
        );
        return watchdog;
    }

    private void releaseMutationLock(UUID accountId, AtomicBoolean lock) {
        mutationLocks.remove(accountId, lock);
        lock.set(false);
    }

    private boolean isCurrentSession(UUID accountId, UUID sessionToken) {
        return sessionToken.equals(sessionTokens.get(accountId));
    }

    private void notifyFailureOnCurrentSession(
        UUID accountId,
        UUID sessionToken,
        Consumer<Throwable> onFailure,
        Throwable error
    ) {
        if (!isCurrentSession(accountId, sessionToken)) return;
        try {
            onFailure.accept(error);
        } catch (Throwable callbackFailure) {
            Logger.log(LogId.W_5252, "skill_mutation_failure_callback", callbackFailure.getMessage());
        }
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

    private static MutationOutcome managerMutationOutcome(LearnedSkillMaterialMutationResult result) {
        Map<UUID, Long> consumedAmounts = new LinkedHashMap<>();
        result.getConsumedMaterials().forEach(material -> consumedAmounts.merge(
            material.getInventoryEntryId(),
            material.getConsumedAmount(),
            Long::sum
        ));
        return new MutationOutcome(result.getSkill(), Map.copyOf(consumedAmounts), List.of(), false);
    }

    private static MutationOutcome oneEachMutationOutcome(
        LearnedSkillInstance skill,
        List<UUID> consumedEntryIds
    ) {
        Map<UUID, Long> consumedAmounts = new LinkedHashMap<>();
        consumedEntryIds.forEach(entryId -> consumedAmounts.merge(entryId, 1L, Long::sum));
        return new MutationOutcome(skill, Map.copyOf(consumedAmounts), List.of(), false);
    }

    private record MutationOutcome(
        LearnedSkillInstance skill,
        Map<UUID, Long> consumedAmounts,
        List<UUID> additionalReconciliationEntryIds,
        boolean removeFromCache
    ) {
    }

    private static final class MutationRetryState {
        private long nextDelayMillis = MUTATION_RETRY_INITIAL_MILLIS;
        private int attempts;
    }

    private static final class MutationWatchdog {
        private final Object stateLock = new Object();
        private final AtomicBoolean settled = new AtomicBoolean();
        private final AtomicBoolean pendingNotified = new AtomicBoolean();
        private boolean mutationStarted;
        private @Nullable BukkitTask timeoutTask;

        private boolean isActive() {
            return !settled.get();
        }

        private boolean beginMutation() {
            synchronized (stateLock) {
                if (settled.get()) return false;
                mutationStarted = true;
                return true;
            }
        }

        private boolean hasMutationStarted() {
            synchronized (stateLock) {
                return mutationStarted;
            }
        }

        private boolean settleBeforeMutation() {
            synchronized (stateLock) {
                if (settled.get() || mutationStarted) return false;
                settled.set(true);
            }
            cancelTimeoutTask();
            return true;
        }

        private void complete() {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            cancelTimeoutTask();
        }

        private void cancelTimeoutTask() {
            if (timeoutTask == null) return;
            try {
                timeoutTask.cancel();
            } catch (Throwable ignored) {
                // task取消失敗でもsettled状態とmutation lockの解放を優先する。
            }
        }

        private boolean isSettled() {
            return settled.get();
        }
    }

    @FunctionalInterface
    private interface Mutation {
        MutationOutcome execute();
    }

    /** API mutation開始前の保存待機が上限を超え、外部副作用なしに中断したことを表します。 */
    public static final class MutationPreflightTimeoutException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public MutationPreflightTimeoutException(@NotNull UUID accountId) {
            super("Skill mutation preflight timed out for account " + accountId);
        }
    }

}
