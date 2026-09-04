package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 同一アカウントの即時インベントリ保存を直列化するコーディネーターです。
 * <p>
 * ストレージ GUI のクローズとログアウトが同時に発生しても、ログアウト保存は先行する
 * クローズ保存の完了後に実行されます。連続するクローズ保存は一件にまとめ、API 呼び出しを
 * Bukkit メインスレッドへ持ち込まないよう、指定された非同期 executor 上で処理します。
 */
public final class InventorySaveCoordinator {

    private static final long EXTERNAL_SAVE_RETRY_INITIAL_MILLIS = 25L;
    private static final long EXTERNAL_SAVE_RETRY_MAX_MILLIS = 1_000L;
    private static final long DEFAULT_EXTERNAL_OPERATION_TIMEOUT_MILLIS = 60_000L;

    private final InventoryPersistence persistence;
    private final PlayerInventoryStateRegistry stateRegistry;
    private final Executor asyncExecutor;
    private final long externalOperationTimeoutMillis;
    private final Object laneLock = new Object();
    private final Map<UUID, SaveLane> lanes = new HashMap<>();
    private final Object retainedStateLock = new Object();
    private final Object unresolvedBoundaryLock = new Object();
    private final Map<UUID, RetainedStateSlot> retainedStates = new HashMap<>();
    /** accountごとに外部操作の所有者を保持し、古いcleanupが新しい操作を解除しないようにします。 */
    private final Map<UUID, UUID> unresolvedExternalOperations = new ConcurrentHashMap<>();
    private boolean closing;

    /**
     * 保存コーディネーターを構築します。
     *
     * @param persistence インベントリ永続化
     * @param stateRegistry プレイヤー状態レジストリ
     * @param asyncExecutor API I/O を実行する非同期 executor
     */
    public InventorySaveCoordinator(
        @NotNull InventoryPersistence persistence,
        @NotNull PlayerInventoryStateRegistry stateRegistry,
        @NotNull Executor asyncExecutor
    ) {
        this(
            persistence,
            stateRegistry,
            asyncExecutor,
            configuredExternalOperationTimeoutMillis()
        );
    }

    /**
     * 保存コーディネーターを構築します。
     *
     * @param persistence インベントリ永続化
     * @param stateRegistry プレイヤー状態レジストリ
     * @param asyncExecutor API I/O を実行する非同期 executor
     * @param externalOperationTimeoutMillis 外部操作前後の保存再試行を許可する上限時間
     */
    public InventorySaveCoordinator(
        @NotNull InventoryPersistence persistence,
        @NotNull PlayerInventoryStateRegistry stateRegistry,
        @NotNull Executor asyncExecutor,
        long externalOperationTimeoutMillis
    ) {
        this.persistence = persistence;
        this.stateRegistry = stateRegistry;
        this.asyncExecutor = asyncExecutor;
        this.externalOperationTimeoutMillis = Math.max(1_000L, externalOperationTimeoutMillis);
    }

    private static long configuredExternalOperationTimeoutMillis() {
        long configured = ConfigProperties.getInstance().getApiOperationTimeout();
        return configured > 0L ? configured : DEFAULT_EXTERNAL_OPERATION_TIMEOUT_MILLIS;
    }

    /**
     * 現在登録されている state を即時保存します。同時期の要求は一件にまとめられます。
     *
     * @param accountId 対象アカウント ID
     * @return 保存に成功した場合 {@code true} となる future
     */
    public @NotNull CompletableFuture<Boolean> saveNow(@NotNull UUID accountId) {
        PlayerInventoryState state = stateRegistry.get(accountId);
        if (state == null) {
            Logger.warn(LogId.W_5254, accountId);
            return CompletableFuture.completedFuture(false);
        }
        state.markDirty();
        return enqueue(accountId, true, () -> {
            if (stateRegistry.get(accountId) != state) {
                return true;
            }
            if (unresolvedExternalOperations.containsKey(accountId)) {
                state.restoreDirty();
                return false;
            }
            boolean succeeded = persistence.saveNow(state);
            if (!succeeded) {
                Logger.warn(LogId.W_5255, accountId);
            }
            return succeeded;
        });
    }

    /**
     * オートセーブを同一アカウントの即時保存・ログアウト保存と同じキューへ登録します。
     * <p>
     * キュー待機中に対象 state が旧セッションのものになった場合は保存を省略します。
     * 同時期の即時保存とは一件にまとめられ、即時保存が立てた dirty も同じ処理で保存されます。
     *
     * @param state オートセーブ対象として取得済みの state
     * @return 未保存変更を残さず処理できた場合 {@code true} となる future
     */
    public @NotNull CompletableFuture<Boolean> saveAuto(@NotNull PlayerInventoryState state) {
        UUID accountId = state.getAccountId();
        return enqueue(accountId, true, () -> {
            if (stateRegistry.get(accountId) != state) {
                return true;
            }
            if (unresolvedExternalOperations.containsKey(accountId)) {
                state.restoreDirty();
                return false;
            }
            persistence.save(state, InventoryPersistence.SaveTrigger.AUTO);
            return !persistence.hasPendingChanges(state);
        });
    }

    /**
     * 現在の inventory state を保存した直後に、同じアカウント lane 内で外部の原子操作を実行します。
     * <p>
     * この job は coalesce されません。操作の supplier が戻るまで後続の auto-save / logout-save は
     * 開始されないため、API が直接更新した inventory entry を supplier 内で正本照合してから lane を
     * 解放できます。支払いのローカル先行消費は行わず、API transaction を唯一の消費正本にします。
     *
     * @param accountId 対象アカウント ID
     * @param operation 事前保存後に実行する原子操作と正本照合
     * @param <T> 操作結果型
     * @return 操作結果を返す future。事前保存失敗時または lane 実行失敗時は例外完了する
     */
    public <T> @NotNull CompletableFuture<T> executeExclusiveAfterSave(
        @NotNull UUID accountId,
        @NotNull Supplier<T> operation
    ) {
        UUID boundaryToken = claimExternalBoundary(accountId, null, false);
        if (boundaryToken == null) {
            return rejectedExternalOperation(accountId);
        }
        PlayerInventoryState expectedState = stateRegistry.get(accountId);
        CompletableFuture<T> operationResult = new CompletableFuture<>();
        if (expectedState == null) {
            releaseExternalBoundary(accountId, boundaryToken);
            operationResult.completeExceptionally(
                new IllegalStateException("Inventory state is not loaded for account " + accountId)
            );
            return operationResult;
        }

        expectedState.markDirty();
        // lane待機中もaccepted済み外部操作として扱い、shutdown/quitの先行snapshot保存を防ぐ。
        boolean boundaryAdded = true;
        AtomicBoolean operationStarted = new AtomicBoolean();
        AtomicReference<T> completedResult = new AtomicReference<>();
        CompletableFuture<Boolean> laneResult = enqueue(accountId, false, () -> {
            if (stateRegistry.get(accountId) != expectedState) {
                releaseExternalBoundary(accountId, boundaryToken);
                throw new IllegalStateException("Inventory state generation changed for account " + accountId);
            }
            if (!persistence.saveNow(expectedState)) {
                releaseExternalBoundary(accountId, boundaryToken);
                Logger.warn(LogId.W_5255, accountId);
                throw new IllegalStateException("Failed to persist inventory before external operation");
            }

            operationStarted.set(true);
            T result = operation.get();
            completedResult.set(result);
            releaseExternalBoundary(accountId, boundaryToken);
            return true;
        });
        laneResult.whenComplete((succeeded, throwable) -> {
            if (!operationStarted.get()
                && (throwable != null || !Boolean.TRUE.equals(succeeded))) {
                releaseExternalBoundary(accountId, boundaryToken);
            }
            if (throwable != null) {
                operationResult.completeExceptionally(throwable);
            } else if (!Boolean.TRUE.equals(succeeded)) {
                operationResult.completeExceptionally(new IllegalStateException(
                    "Inventory save coordinator rejected external operation for account " + accountId
                ));
            } else {
                operationResult.complete(completedResult.get());
            }
        });
        return operationResult;
    }

    /**
     * 保存済み entry baseline を受け取る外部原子操作を、同一 account lane 内で実行します。
     * <p>
     * pre-save は保存中のローカル変更が無くなるまで繰り返し、最後に API が返した persisted rows を
     * operation へ渡します。operation はその baseline・現在のローカル state・API 正本を三者マージし、
     * 一度だけ結果を返してください。その後も dirty が安定して消えるまで保存を繰り返し、state monitor
     * 内で未解決境界を解除します。これにより API 待機中の報酬・消費を失わず、logout / shutdown save を
     * マージ済み状態の永続化より先へ進めません。
     *
     * @param accountId 対象アカウント ID
     * @param operation 保存済み baseline を受け取る原子操作と三者マージ
     * @param <T> 操作結果型
     * @return 操作結果を返す future
     */
    public <T> @NotNull CompletableFuture<T> executeExclusiveAfterSave(
        @NotNull UUID accountId,
        @NotNull Function<InventoryPersistence.PersistedInventoryBaseline, T> operation
    ) {
        return executeExclusiveAfterSaveInternal(accountId, null, operation, false);
    }

    /** 指定した外部 operation ID を境界所有者として保存 lane 内の原子操作を開始します。 */
    public <T> @NotNull CompletableFuture<T> executeExclusiveAfterSave(
        @NotNull UUID accountId,
        @NotNull UUID operationId,
        @NotNull Function<InventoryPersistence.PersistedInventoryBaseline, T> operation
    ) {
        return executeExclusiveAfterSaveInternal(accountId, operationId, operation, false);
    }

    /**
     * 既に未解決境界を保持している外部操作の recovery を、同じ account lane で実行します。
     * <p>通常の新規操作からは使用せず、同一 operation ID の正本照会だけに使用してください。</p>
     *
     * @param accountId 対象アカウント ID
     * @param operation 保存済み baseline を受け取る recovery 操作
     * @param <T> 操作結果型
     * @return recovery 結果を返す future
     */
    public <T> @NotNull CompletableFuture<T> executeExclusiveAfterSaveRecovery(
        @NotNull UUID accountId,
        @NotNull Function<InventoryPersistence.PersistedInventoryBaseline, T> operation
    ) {
        return executeExclusiveAfterSaveInternal(accountId, null, operation, true);
    }

    /** 同一 operation ID の保留操作を、所有者を変えずに recovery します。 */
    public <T> @NotNull CompletableFuture<T> executeExclusiveAfterSaveRecovery(
        @NotNull UUID accountId,
        @NotNull UUID operationId,
        @NotNull Function<InventoryPersistence.PersistedInventoryBaseline, T> operation
    ) {
        return executeExclusiveAfterSaveInternal(accountId, operationId, operation, true);
    }

    private <T> @NotNull CompletableFuture<T> executeExclusiveAfterSaveInternal(
        @NotNull UUID accountId,
        @Nullable UUID operationId,
        @NotNull Function<InventoryPersistence.PersistedInventoryBaseline, T> operation,
        boolean allowExistingBoundary
    ) {
        UUID boundaryToken = claimExternalBoundary(accountId, operationId, allowExistingBoundary);
        if (boundaryToken == null) {
            return rejectedExternalOperation(accountId);
        }
        PlayerInventoryState expectedState = stateRegistry.get(accountId);
        CompletableFuture<T> operationResult = new CompletableFuture<>();
        if (expectedState == null) {
            if (!allowExistingBoundary) {
                releaseExternalBoundary(accountId, boundaryToken);
            }
            operationResult.completeExceptionally(
                new IllegalStateException("Inventory state is not loaded for account " + accountId)
            );
            return operationResult;
        }

        expectedState.markDirty();
        boolean releaseBoundaryOnPreSaveFailure = !allowExistingBoundary;
        AtomicBoolean operationStarted = new AtomicBoolean();
        AtomicReference<T> completedResult = new AtomicReference<>();
        CompletableFuture<Boolean> laneResult = enqueue(accountId, false, () -> {
            if (stateRegistry.get(accountId) != expectedState) {
                if (releaseBoundaryOnPreSaveFailure) {
                    releaseExternalBoundary(accountId, boundaryToken);
                }
                throw new IllegalStateException("Inventory state generation changed for account " + accountId);
            }

            InventoryPersistence.PersistedInventoryBaseline baseline;
            try {
                baseline = awaitStablePreSave(accountId, expectedState);
            } catch (RuntimeException | Error preSaveFailure) {
                // operation.apply has not run, so no external transaction can be in doubt.
                if (releaseBoundaryOnPreSaveFailure) {
                    releaseExternalBoundary(accountId, boundaryToken);
                }
                throw preSaveFailure;
            }
            operationStarted.set(true);
            T result = operation.apply(baseline);
            completedResult.set(result);
            persistMergedStateUntilStable(accountId, expectedState, boundaryToken);
            return true;
        });
        laneResult.whenComplete((succeeded, throwable) -> {
            if (releaseBoundaryOnPreSaveFailure && !operationStarted.get()
                && (throwable != null || !Boolean.TRUE.equals(succeeded))) {
                releaseExternalBoundary(accountId, boundaryToken);
            }
            if (throwable != null) {
                operationResult.completeExceptionally(throwable);
            } else if (!Boolean.TRUE.equals(succeeded)) {
                operationResult.completeExceptionally(new IllegalStateException(
                    "Inventory save coordinator rejected external operation for account " + accountId
                ));
            } else {
                operationResult.complete(completedResult.get());
            }
        });
        return operationResult;
    }

    /**
     * 外部原子操作の開始前に、同一 account の保存済み baseline だけを取得します。
     * <p>
     * 成功後も未解決境界は維持するため、呼び出し側は API 応答待機中に autosave や logout save が
     * 操作前 state を保存しないよう保護されます。取得した handle は
     * {@link #completePreparedExternalOperation(PreparedExternalOperation, Function)} で成功完了するか、
     * API が未確定のまま失敗した場合に {@link #abandonPreparedExternalOperation(PreparedExternalOperation)}
     * で明示的に解除してください。
     *
     * @param accountId 対象アカウント ID
     * @return 保存済み baseline と対象 state を保持する handle の future
     * @throws IllegalStateException 対象 state が未ロード、または pre-save を完了できない場合
     */
    public @NotNull CompletableFuture<PreparedExternalOperation> prepareExternalOperationAfterSave(
        @NotNull UUID accountId
    ) {
        UUID boundaryToken = claimExternalBoundary(accountId, null, false);
        if (boundaryToken == null) {
            CompletableFuture<PreparedExternalOperation> rejected = new CompletableFuture<>();
            rejected.completeExceptionally(new ExternalOperationPendingException(accountId));
            return rejected;
        }
        PlayerInventoryState expectedState = stateRegistry.get(accountId);
        CompletableFuture<PreparedExternalOperation> preparedResult = new CompletableFuture<>();
        if (expectedState == null) {
            releaseExternalBoundary(accountId, boundaryToken);
            preparedResult.completeExceptionally(
                new IllegalStateException("Inventory state is not loaded for account " + accountId)
            );
            return preparedResult;
        }

        expectedState.markDirty();
        boolean boundaryAdded = true;
        AtomicReference<InventoryPersistence.PersistedInventoryBaseline> baselineReference = new AtomicReference<>();
        CompletableFuture<Boolean> laneResult = enqueue(accountId, false, () -> {
            if (stateRegistry.get(accountId) != expectedState) {
                if (boundaryAdded) {
                    releaseExternalBoundary(accountId, boundaryToken);
                }
                throw new IllegalStateException("Inventory state generation changed for account " + accountId);
            }
            try {
                baselineReference.set(awaitStablePreSave(accountId, expectedState));
                return true;
            } catch (RuntimeException | Error preSaveFailure) {
                if (boundaryAdded) {
                    releaseExternalBoundary(accountId, boundaryToken);
                }
                throw preSaveFailure;
            }
        });
        laneResult.whenComplete((succeeded, throwable) -> {
            if (throwable != null) {
                if (boundaryAdded) {
                    releaseExternalBoundary(accountId, boundaryToken);
                }
                preparedResult.completeExceptionally(throwable);
            } else if (!Boolean.TRUE.equals(succeeded) || baselineReference.get() == null) {
                if (boundaryAdded) {
                    releaseExternalBoundary(accountId, boundaryToken);
                }
                preparedResult.completeExceptionally(new IllegalStateException(
                    "Inventory save coordinator rejected external operation for account " + accountId
                ));
            } else {
                preparedResult.complete(new PreparedExternalOperation(
                    accountId,
                    expectedState,
                    baselineReference.get(),
                    boundaryToken
                ));
            }
        });
        return preparedResult;
    }

    /**
     * 事前保存済み handle の同一 account lane で API 正本照合と再保存を完了します。
     * <p>
     * operation が例外になった場合は、API transaction が確定済みの可能性を保護するため未解決境界を
     * 解除しません。同じ handle と operation ID の API replay で再試行し、再同期と保存が成功した時だけ
     * 境界を解除します。
     *
     * @param prepared {@link #prepareExternalOperationAfterSave(UUID)} が返した handle
     * @param operation API 正本照合を行う処理
     * @param <T> 処理結果型
     * @return 再同期・保存を完了した結果 future
     * @throws IllegalStateException handle が無効、または対象 state 世代が変化した場合
     */
    public <T> @NotNull CompletableFuture<T> completePreparedExternalOperation(
        @NotNull PreparedExternalOperation prepared,
        @NotNull Function<InventoryPersistence.PersistedInventoryBaseline, T> operation
    ) {
        UUID accountId = prepared.accountId();
        CompletableFuture<T> operationResult = new CompletableFuture<>();
        if (!ownsExternalBoundary(accountId, prepared.boundaryToken())) {
            operationResult.completeExceptionally(new IllegalStateException(
                "External operation is no longer unresolved for account " + accountId
            ));
            return operationResult;
        }
        AtomicReference<T> completedResult = new AtomicReference<>();
        CompletableFuture<Boolean> laneResult = enqueue(accountId, false, () -> {
            if (stateRegistry.get(accountId) != prepared.state()) {
                throw new IllegalStateException("Inventory state generation changed for account " + accountId);
            }
            T result = operation.apply(prepared.baseline());
            completedResult.set(result);
            persistMergedStateUntilStable(accountId, prepared.state(), prepared.boundaryToken());
            return true;
        });
        laneResult.whenComplete((succeeded, throwable) -> {
            if (throwable != null) {
                operationResult.completeExceptionally(throwable);
            } else if (!Boolean.TRUE.equals(succeeded)) {
                operationResult.completeExceptionally(new IllegalStateException(
                    "Inventory save coordinator rejected external operation for account " + accountId
                ));
            } else {
                operationResult.complete(completedResult.get());
            }
        });
        return operationResult;
    }

    /**
     * API transaction が未確定のまま失敗した事前保存 handle を破棄します。
     *
     * @param prepared 破棄する handle
     */
    public void abandonPreparedExternalOperation(@NotNull PreparedExternalOperation prepared) {
        if (stateRegistry.get(prepared.accountId()) == prepared.state()) {
            releaseExternalBoundary(prepared.accountId(), prepared.boundaryToken());
        }
    }

    private @NotNull InventoryPersistence.PersistedInventoryBaseline awaitStablePreSave(
        @NotNull UUID accountId,
        @NotNull PlayerInventoryState expectedState
    ) {
        long deadlineNanos = externalOperationDeadline();
        long retryDelayMillis = EXTERNAL_SAVE_RETRY_INITIAL_MILLIS;
        while (true) {
            if (stateRegistry.get(accountId) != expectedState) {
                throw new IllegalStateException("Inventory state generation changed for account " + accountId);
            }
            InventoryPersistence.PersistedInventoryBaseline baseline =
                persistence.saveNowWithBaseline(expectedState);
            if (baseline != null && baseline.accountId().equals(accountId)) {
                return baseline;
            }
            ensureExternalOperationWithinDeadline(accountId, deadlineNanos, "before external operation");
            Logger.warn(LogId.W_5255, accountId);
            waitForExternalSaveRetry(accountId, retryDelayMillis, deadlineNanos);
            retryDelayMillis = Math.min(EXTERNAL_SAVE_RETRY_MAX_MILLIS, retryDelayMillis * 2L);
        }
    }

    private void persistMergedStateUntilStable(
        @NotNull UUID accountId,
        @NotNull PlayerInventoryState expectedState,
        @NotNull UUID boundaryToken
    ) {
        long deadlineNanos = externalOperationDeadline();
        long retryDelayMillis = EXTERNAL_SAVE_RETRY_INITIAL_MILLIS;
        while (true) {
            if (stateRegistry.get(accountId) != expectedState) {
                throw new IllegalStateException(
                    "Inventory state generation changed after external operation for account " + accountId
                );
            }
            boolean saved = persistence.saveNow(expectedState);
            synchronized (expectedState) {
                if (stateRegistry.get(accountId) != expectedState) {
                    throw new IllegalStateException(
                        "Inventory state generation changed after external operation for account " + accountId
                    );
                }
                if (saved && !persistence.hasPendingChanges(expectedState)) {
                    releaseExternalBoundary(accountId, boundaryToken);
                    return;
                }
            }
            ensureExternalOperationWithinDeadline(accountId, deadlineNanos, "after external operation");
            Logger.warn(LogId.W_5255, accountId);
            waitForExternalSaveRetry(accountId, retryDelayMillis, deadlineNanos);
            retryDelayMillis = Math.min(EXTERNAL_SAVE_RETRY_MAX_MILLIS, retryDelayMillis * 2L);
        }
    }

    private long externalOperationDeadline() {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(externalOperationTimeoutMillis);
    }

    private static void ensureExternalOperationWithinDeadline(
        @NotNull UUID accountId,
        long deadlineNanos,
        @NotNull String phase
    ) {
        if (System.nanoTime() >= deadlineNanos) {
            throw new ExternalOperationTimeoutException(accountId, phase);
        }
    }

    private static void waitForExternalSaveRetry(
        @NotNull UUID accountId,
        long delayMillis,
        long deadlineNanos
    ) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            throw new ExternalOperationTimeoutException(accountId, "external save retry");
        }
        long remainingMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
        try {
            TimeUnit.MILLISECONDS.sleep(Math.min(Math.max(1L, delayMillis), remainingMillis));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Inventory reconciliation save interrupted for account " + accountId,
                interrupted
            );
        }
    }

    /** 外部操作の正本境界を保ったまま、保存再試行を打ち切る例外です。 */
    public static final class ExternalOperationTimeoutException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public ExternalOperationTimeoutException(@NotNull UUID accountId, @NotNull String phase) {
            super("External operation save timed out for account " + accountId + " (" + phase + ")");
        }
    }

    /**
     * ログアウト保存より前に完了させるセッション終了時の状態補償を、同一アカウントの保存キューへ登録します。
     * <p>
     * 呼び出し側は {@link #saveOnLogout(UUID, PlayerInventoryState, Runnable)} より先に登録してください。
     * 補償処理は非同期 executor 上で一件ずつ実行され、{@code false} の返却や例外で失敗しても
     * 後続のログアウト保存は継続します。例外は返却 future へ伝播します。
     *
     * @param accountId 対象アカウント ID
     * @param reconciliation セッション中の進行処理を確定または補償する処理
     * @return 補償処理の結果を返す future
     */
    public @NotNull CompletableFuture<Boolean> enqueueLogoutReconciliation(
        @NotNull UUID accountId,
        @NotNull Supplier<Boolean> reconciliation
    ) {
        return enqueue(accountId, false, reconciliation);
    }

    /**
     * ログアウト保存を、同一アカウントの先行保存完了後に実行します。
     * <p>
     * 保存成功時だけ state と永続化補助キャッシュを解放します。失敗時は dirty を戻して
     * state をレジストリへ残すため、後続のオートセーブで再試行できます。
     *
     * @param accountId 対象アカウント ID
     * @param expectedState ログアウトしたセッションの state。未ロード時は {@code null}
     * @param logoutSave API 永続化を行うログアウト保存処理
     * @return インベントリ変更を残さず保存できた場合 {@code true} となる future
     */
    public @NotNull CompletableFuture<Boolean> saveOnLogout(
        @NotNull UUID accountId,
        @Nullable PlayerInventoryState expectedState,
        @NotNull Runnable logoutSave
    ) {
        return saveOnLogoutWithResult(accountId, expectedState, () -> {
            logoutSave.run();
            return true;
        });
    }

    /**
     * 結果を返すログアウト保存を、同一アカウントの先行保存完了後に実行します。
     * <p>
     * インベントリ以外の保存結果も含めて成功判定を行うため、アカウント切替など、旧セッションの
     * 全保存成功を要求する処理から使用します。
     *
     * @param accountId 対象アカウント ID
     * @param expectedState ログアウトしたセッションの state。未ロード時は {@code null}
     * @param logoutSave API 永続化を行い、成功した場合 {@code true} を返すログアウト保存処理
     * @return すべての保存が成功した場合 {@code true} となる future
     */
    public @NotNull CompletableFuture<Boolean> saveOnLogoutWithResult(
        @NotNull UUID accountId,
        @Nullable PlayerInventoryState expectedState,
        @NotNull Supplier<Boolean> logoutSave
    ) {
        CompletableFuture<Boolean> future = enqueue(accountId, false, () -> {
            if (unresolvedExternalOperations.containsKey(accountId)) {
                if (expectedState != null) {
                    expectedState.restoreDirty();
                }
                return false;
            }
            boolean logoutSucceeded = Boolean.TRUE.equals(logoutSave.get());
            boolean inventorySucceeded = expectedState == null || !persistence.hasPendingChanges(expectedState);
            boolean succeeded = logoutSucceeded && inventorySucceeded;
            if (!succeeded) {
                if (expectedState != null) {
                    expectedState.restoreDirty();
                }
                Logger.warn(LogId.W_5256, accountId);
            }
            return succeeded;
        });
        future.whenComplete((succeeded, throwable) -> {
            if (throwable != null) {
                if (expectedState != null) {
                    expectedState.restoreDirty();
                    retainAfterFailure(accountId, expectedState);
                }
                return;
            }
            if (expectedState == null) {
                return;
            }
            if (!Boolean.TRUE.equals(succeeded)) {
                retainAfterFailure(accountId, expectedState);
                return;
            }
            releasePersistedState(accountId, expectedState);
        });
        return future;
    }

    /**
     * ログアウト保存失敗後の state がオートセーブで正常化した場合に遅延解放します。
     *
     * @param state オートセーブを終えた state
     */
    public void cleanupAfterRetry(@NotNull PlayerInventoryState state) {
        UUID accountId = state.getAccountId();
        if (unresolvedExternalOperations.containsKey(accountId) || persistence.hasPendingChanges(state)) {
            return;
        }
        boolean removed;
        synchronized (retainedStateLock) {
            RetainedStateSlot slot = retainedStates.get(accountId);
            if (slot == null || slot.state != state || slot.claimed) {
                return;
            }
            retainedStates.remove(accountId);
            removed = stateRegistry.remove(accountId, state);
        }
        if (removed) {
            persistence.clearAccount(accountId);
        }
    }

    /**
     * 呼び出し時点までに同一アカウントへ登録された保存の後ろへバリアを追加します。
     * 再ログイン時は、この future の完了後に永続化済み state を読み込んでください。
     *
     * @param accountId 対象アカウント ID
     * @return 先行保存がすべて完了したときに完了する future
     */
    public @NotNull CompletableFuture<Void> awaitQueuedSaves(@NotNull UUID accountId) {
        CompletableFuture<Boolean> barrier = new CompletableFuture<>();
        synchronized (laneLock) {
            SaveLane lane = lanes.get(accountId);
            if (lane == null) {
                return CompletableFuture.completedFuture(null);
            }
            lane.jobs.addLast(new SaveJob(false, () -> true, barrier));
        }
        return barrier.thenApply(ignored -> null);
    }

    /**
     * 呼び出し時点までに同一アカウントへ登録された保存の後ろへ、失敗を伝播するバリアを追加します。
     * <p>
     * 通常の再ログインで使用する {@link #awaitQueuedSaves(UUID)} は、既存仕様どおり先行保存の
     * {@code false} を呼び出し側へ伝播しません。一方、アカウント切替では旧セッションを破棄するため、
     * 先行保存の {@code false} または例外を検知して切替を中止する必要があります。
     *
     * @param accountId 対象アカウント ID
     * @return 先行保存がすべて成功したときに完了し、失敗時は例外完了する future
     */
    public @NotNull CompletableFuture<Void> awaitQueuedSavesOrThrow(@NotNull UUID accountId) {
        CompletableFuture<Boolean> barrier = new CompletableFuture<>();
        List<CompletableFuture<Boolean>> priorResults;
        boolean startDrain = false;
        SaveLane lane;
        synchronized (laneLock) {
            lane = lanes.get(accountId);
            if (lane == null) {
                return CompletableFuture.completedFuture(null);
            }

            priorResults = new ArrayList<>();
            if (lane.inFlight != null) {
                priorResults.addAll(lane.inFlight.results);
            }
            for (SaveJob job : lane.jobs) {
                priorResults.addAll(job.results);
            }
            lane.jobs.addLast(new SaveJob(false, () -> {
                for (CompletableFuture<Boolean> priorResult : priorResults) {
                    if (!Boolean.TRUE.equals(priorResult.join())) {
                        throw new IllegalStateException(
                            "A prior account save failed for account " + accountId
                        );
                    }
                }
                return true;
            }, barrier));
            if (!lane.running) {
                lane.running = true;
                startDrain = true;
            }
        }
        if (startDrain) {
            SaveLane scheduledLane = lane;
            try {
                asyncExecutor.execute(() -> drain(accountId, scheduledLane));
            } catch (Throwable throwable) {
                failLane(accountId, scheduledLane, throwable);
            }
        }
        return barrier.thenApply(ignored -> null);
    }

    /**
     * API側の原子操作結果または正本照合が未確定で、ローカルstateを保存してはならないか返します。
     *
     * @param accountId 対象アカウントID
     * @return 未確定操作を保持している場合 {@code true}
     */
    public boolean hasUnresolvedExternalOperation(@NotNull UUID accountId) {
        return unresolvedExternalOperations.containsKey(accountId);
    }

    private @Nullable UUID claimExternalBoundary(
        @NotNull UUID accountId,
        @Nullable UUID requestedToken,
        boolean allowExistingBoundary
    ) {
        synchronized (unresolvedBoundaryLock) {
            UUID currentToken = unresolvedExternalOperations.get(accountId);
            if (allowExistingBoundary) {
                // recovery は、先行 operation の pending 境界を再確認したうえで同じ境界を
                // 取り戻す。通常は既に存在するが、先行 lane の失敗処理と recovery の受付が
                // 競合して一瞬解除された場合も、同じ operation ID の回復を永久に拒否しない。
                if (currentToken != null
                    && (requestedToken == null || requestedToken.equals(currentToken))) {
                    return currentToken;
                }
                if (currentToken != null) return null;
                UUID recoveryToken = requestedToken == null ? UUID.randomUUID() : requestedToken;
                unresolvedExternalOperations.put(accountId, recoveryToken);
                return recoveryToken;
            }
            if (currentToken != null) {
                return null;
            }
            UUID boundaryToken = requestedToken == null ? UUID.randomUUID() : requestedToken;
            unresolvedExternalOperations.put(accountId, boundaryToken);
            return boundaryToken;
        }
    }

    private void releaseExternalBoundary(@NotNull UUID accountId, @NotNull UUID boundaryToken) {
        unresolvedExternalOperations.remove(accountId, boundaryToken);
    }

    private boolean ownsExternalBoundary(@NotNull UUID accountId, @NotNull UUID boundaryToken) {
        return boundaryToken.equals(unresolvedExternalOperations.get(accountId));
    }

    private static <T> @NotNull CompletableFuture<T> rejectedExternalOperation(@NotNull UUID accountId) {
        CompletableFuture<T> rejected = new CompletableFuture<>();
        rejected.completeExceptionally(new ExternalOperationPendingException(accountId));
        return rejected;
    }

    /**
     * ログアウト保存に失敗して保持中の state を世代付き lease として取得します。
     * <p>
     * state 自体は保持一覧から外さず、同一アカウントの後続ログイン試行にも同じ state を渡します。
     * 後続試行が取得した時点で lease 世代が進むため、旧試行の解放処理は後続 lease を変更できません。
     *
     * @param accountId 対象アカウント ID
     * @return 引き継げる state の lease。保持中でなければ {@code null}
     */
    public @Nullable RetainedStateLease claimRetainedState(@NotNull UUID accountId) {
        synchronized (retainedStateLock) {
            RetainedStateSlot slot = retainedStates.get(accountId);
            if (slot == null) {
                return null;
            }
            if (stateRegistry.get(accountId) != slot.state) {
                retainedStates.remove(accountId);
                return null;
            }
            slot.generation++;
            slot.claimed = true;
            return new RetainedStateLease(accountId, slot.state, slot.generation);
        }
    }

    /**
     * 中断されたログイン試行の retained-state lease を解放します。
     * より新しい世代が取得済みの場合は何も変更しません。
     *
     * @param lease 解放する lease
     * @return 現在世代の lease を解放できた場合は {@code true}
     */
    public boolean releaseRetainedStateLease(@NotNull RetainedStateLease lease) {
        synchronized (retainedStateLock) {
            RetainedStateSlot slot = retainedStates.get(lease.accountId());
            if (!matchesClaimedLease(slot, lease)) {
                return false;
            }
            slot.claimed = false;
            return true;
        }
    }

    /**
     * ログイン反映に成功した retained-state lease を確定し、保持対象から外します。
     * registry 上の state はオンラインセッションの正本として維持します。
     *
     * @param lease 確定する lease
     * @return 現在世代の lease を確定できた場合は {@code true}
     */
    public boolean commitRetainedStateLease(@NotNull RetainedStateLease lease) {
        synchronized (retainedStateLock) {
            RetainedStateSlot slot = retainedStates.get(lease.accountId());
            if (!matchesClaimedLease(slot, lease)
                || stateRegistry.get(lease.accountId()) != lease.state()) {
                return false;
            }
            retainedStates.remove(lease.accountId());
            return true;
        }
    }

    private void retainAfterFailure(
        @NotNull UUID accountId,
        @NotNull PlayerInventoryState state
    ) {
        synchronized (retainedStateLock) {
            if (stateRegistry.get(accountId) != state) {
                return;
            }
            RetainedStateSlot current = retainedStates.get(accountId);
            if (current == null || current.state != state) {
                retainedStates.put(accountId, new RetainedStateSlot(state));
            }
        }
    }

    private void releasePersistedState(
        @NotNull UUID accountId,
        @NotNull PlayerInventoryState state
    ) {
        boolean removed;
        synchronized (retainedStateLock) {
            RetainedStateSlot slot = retainedStates.get(accountId);
            if (slot != null && (slot.state != state || slot.claimed)) {
                return;
            }
            if (slot != null) {
                retainedStates.remove(accountId);
            }
            removed = stateRegistry.remove(accountId, state);
        }
        if (removed) {
            persistence.clearAccount(accountId);
        }
    }

    private boolean matchesClaimedLease(
        @Nullable RetainedStateSlot slot,
        @NotNull RetainedStateLease lease
    ) {
        return slot != null
            && slot.claimed
            && slot.state == lease.state()
            && slot.generation == lease.generation();
    }

    /**
     * プラグイン停止時の保存 drain を開始し、以後の新規保存要求を拒否します。
     * <p>
     * 本メソッドより前に受理した要求はそのままキューで完了します。以後に各 enqueue API が
     * 受け取った要求は完了済み {@code false} の future を返し、新しい lane を作りません。
     * producer を停止してから本メソッドを呼び、その後に {@link #awaitPendingWrites(long)} で
     * 受理済み要求の完了を待機してください。複数回呼び出しても状態は変わりません。
     */
    public void beginClosing() {
        synchronized (laneLock) {
            closing = true;
        }
    }

    /**
     * プラグイン停止前に、登録済みの保存キューが空になるまで待機します。
     *
     * @param timeoutMillis 最大待機時間
     * @return 期限内に全キューが完了した場合 {@code true}
     */
    public boolean awaitPendingWrites(long timeoutMillis) {
        long deadlineNanos = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeoutMillis));
        List<UUID> accountIds;
        synchronized (laneLock) {
            accountIds = List.copyOf(lanes.keySet());
        }
        if (accountIds.isEmpty()) {
            return true;
        }
        CompletableFuture<?>[] barriers = accountIds.stream()
            .map(this::awaitQueuedSaves)
            .toArray(CompletableFuture[]::new);
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            accountIds.forEach(accountId ->
                Logger.warn(LogId.W_5257, accountId)
            );
            return false;
        }
        try {
            CompletableFuture.allOf(barriers).get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            accountIds.forEach(accountId ->
                Logger.warn(LogId.W_5257, accountId)
            );
            return false;
        } catch (Exception failure) {
            accountIds.forEach(accountId ->
                Logger.warn(LogId.W_5252, accountId, failureReason(failure))
            );
            return false;
        }
        return true;
    }

    private @NotNull CompletableFuture<Boolean> enqueue(
        @NotNull UUID accountId,
        boolean coalesced,
        @NotNull Supplier<Boolean> operation
    ) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        boolean startDrain = false;
        SaveLane lane;
        synchronized (laneLock) {
            if (closing) {
                result.complete(false);
                return result;
            }
            lane = lanes.computeIfAbsent(accountId, ignored -> new SaveLane());
            SaveJob coalescingTarget = coalesced ? lane.pendingCoalescedJob() : null;
            if (coalescingTarget == null) {
                lane.jobs.addLast(new SaveJob(coalesced, operation, result));
            } else {
                coalescingTarget.results.add(result);
            }
            if (!lane.running) {
                lane.running = true;
                startDrain = true;
            }
        }
        if (startDrain) {
            SaveLane scheduledLane = lane;
            try {
                asyncExecutor.execute(() -> drain(accountId, scheduledLane));
            } catch (Throwable throwable) {
                failLane(accountId, scheduledLane, throwable);
            }
        }
        return result;
    }

    private void failLane(@NotNull UUID accountId, @NotNull SaveLane lane, @NotNull Throwable throwable) {
        List<SaveJob> failedJobs;
        synchronized (laneLock) {
            failedJobs = new ArrayList<>(lane.jobs);
            lane.jobs.clear();
            lane.running = false;
            lanes.remove(accountId, lane);
        }
        Logger.warn(LogId.W_5252, accountId, failureReason(throwable));
        failedJobs.forEach(job ->
            job.results.forEach(result -> result.completeExceptionally(throwable))
        );
    }

    private void drain(@NotNull UUID accountId, @NotNull SaveLane lane) {
        while (true) {
            SaveJob job;
            synchronized (laneLock) {
                job = lane.jobs.pollFirst();
                if (job == null) {
                    lane.running = false;
                    lanes.remove(accountId, lane);
                    return;
                }
                lane.inFlight = job;
            }
            try {
                boolean succeeded = job.operation.get();
                job.results.forEach(result -> result.complete(succeeded));
            } catch (Throwable throwable) {
                Logger.warn(LogId.W_5252, accountId, failureReason(throwable));
                job.results.forEach(result -> result.completeExceptionally(throwable));
            } finally {
                synchronized (laneLock) {
                    if (lane.inFlight == job) {
                        lane.inFlight = null;
                    }
                }
            }
        }
    }

    private static @NotNull String failureReason(@NotNull Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private static final class SaveLane {
        private final ArrayDeque<SaveJob> jobs = new ArrayDeque<>();
        private boolean running;
        private SaveJob inFlight;

        private SaveJob pendingCoalescedJob() {
            SaveJob tail = jobs.peekLast();
            return tail != null && tail.coalesced ? tail : null;
        }
    }

    /**
     * 保存失敗後に保持した inventory state の所有世代です。
     *
     * @param accountId 対象アカウント ID
     * @param state 引き継ぐ inventory state
     * @param generation lease 世代
     */
    public record RetainedStateLease(
        @NotNull UUID accountId,
        @NotNull PlayerInventoryState state,
        long generation
    ) {
    }

    /**
     * API 呼び出し前の保存済み baseline と、その間に保持する state 世代です。
     *
     * @param accountId 対象アカウント ID
     * @param state 事前保存時から同一である必要がある state
     * @param baseline API 正本照合に使う保存済み entry
     * @param boundaryToken 外部操作境界の所有 token
     */
    public record PreparedExternalOperation(
        @NotNull UUID accountId,
        @NotNull PlayerInventoryState state,
        @NotNull InventoryPersistence.PersistedInventoryBaseline baseline,
        @NotNull UUID boundaryToken
    ) {
    }

    /** 同一 account に未確定の外部操作が残っているため、新規操作を拒否したことを表します。 */
    public static final class ExternalOperationPendingException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private ExternalOperationPendingException(@NotNull UUID accountId) {
            super("An external operation is still unresolved for account " + accountId);
        }
    }

    private static final class RetainedStateSlot {
        private final PlayerInventoryState state;
        private long generation;
        private boolean claimed;

        private RetainedStateSlot(@NotNull PlayerInventoryState state) {
            this.state = state;
        }
    }

    private static final class SaveJob {
        private final boolean coalesced;
        private final Supplier<Boolean> operation;
        private final List<CompletableFuture<Boolean>> results = new ArrayList<>();

        private SaveJob(
            boolean coalesced,
            @NotNull Supplier<Boolean> operation,
            @NotNull CompletableFuture<Boolean> result
        ) {
            this.coalesced = coalesced;
            this.operation = operation;
            this.results.add(result);
        }
    }
}
