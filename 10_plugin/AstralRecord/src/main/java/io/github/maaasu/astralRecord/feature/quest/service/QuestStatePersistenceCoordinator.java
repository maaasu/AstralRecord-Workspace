package io.github.maaasu.astralRecord.feature.quest.service;

import io.github.maaasu.astralRecord.feature.quest.model.QuestPlayerState;
import io.github.maaasu.astralRecord.feature.quest.repository.QuestPlayerStateRepository;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * クエスト状態の世代管理と、アカウント単位の直列保存を担当します。
 */
final class QuestStatePersistenceCoordinator {
    private final StateStorage storage;
    private final Executor executor;
    private final Map<UUID, AccountChannel> channels = new ConcurrentHashMap<>();
    private volatile boolean shuttingDown;

    QuestStatePersistenceCoordinator(@NotNull StateStorage storage, @NotNull Executor executor) {
        this.storage = storage;
        this.executor = executor;
    }

    /**
     * ロード中に保存世代が進んだ場合、保持中の最新スナップショットを優先して返します。
     *
     * @param accountId 対象アカウント ID
     * @return 適用時検証用トークンを含むロード結果
     */
    @NotNull LoadedState load(@NotNull UUID accountId) {
        AccountChannel channel = channel(accountId);
        long loadToken;
        long observedGeneration;
        synchronized (channel) {
            loadToken = ++channel.latestLoadToken;
            channel.pendingLoadTokens.add(loadToken);
            if (channel.latestSnapshot != null) {
                return loaded(accountId, loadToken, channel.latestGeneration, channel.latestSnapshot);
            }
            observedGeneration = channel.latestGeneration;
        }

        try {
            while (true) {
                QuestPlayerState diskState = storage.load(accountId);
                synchronized (channel) {
                    if (channel.latestSnapshot != null) {
                        return loaded(accountId, loadToken, channel.latestGeneration, channel.latestSnapshot);
                    }
                    if (channel.latestGeneration == observedGeneration) {
                        return loaded(accountId, loadToken, observedGeneration, diskState);
                    }
                    observedGeneration = channel.latestGeneration;
                }
            }
        } catch (RuntimeException exception) {
            synchronized (channel) {
                channel.pendingLoadTokens.remove(loadToken);
            }
            evictReleasedPersisted(accountId);
            throw exception;
        }
    }

    /**
     * ロード結果を現在世代と照合してセッションへ適用します。
     *
     * @param loadedState ロード結果
     * @return 適用可能な最新状態。より新しいロードが存在する場合は {@code null}
     */
    @Nullable QuestPlayerState apply(@NotNull LoadedState loadedState) {
        AccountChannel channel = channels.get(loadedState.accountId());
        if (channel == null) {
            return null;
        }
        synchronized (channel) {
            if (!channel.pendingLoadTokens.remove(loadedState.loadToken())
                || loadedState.loadToken() != channel.latestLoadToken) {
                return null;
            }
            channel.released = false;
            QuestPlayerState selected = channel.latestSnapshot != null
                && channel.latestGeneration >= loadedState.generation()
                ? channel.latestSnapshot
                : loadedState.state();
            return selected.snapshot();
        }
    }

    /**
     * 適用されなかったロードトークンを破棄します。
     *
     * @param loadedState 破棄するロード結果
     */
    void discard(@NotNull LoadedState loadedState) {
        AccountChannel channel = channels.get(loadedState.accountId());
        if (channel == null) {
            return;
        }
        synchronized (channel) {
            channel.pendingLoadTokens.remove(loadedState.loadToken());
        }
        evictReleasedPersisted(loadedState.accountId());
    }

    void activate(@NotNull UUID accountId) {
        AccountChannel channel = channel(accountId);
        synchronized (channel) {
            channel.released = false;
        }
    }

    long recordLatest(@NotNull QuestPlayerState state) {
        AccountChannel channel = channel(state.accountId());
        synchronized (channel) {
            channel.latestGeneration++;
            channel.latestSnapshot = state.snapshot();
            return channel.latestGeneration;
        }
    }

    void markReleased(@NotNull UUID accountId) {
        AccountChannel channel = channel(accountId);
        synchronized (channel) {
            channel.released = true;
        }
    }

    /**
     * サーバ停止時の最終保存を開始し、非同期保存の一時的な失敗を同期リトライへ委ねます。
     */
    void beginShutdown() {
        shuttingDown = true;
    }

    boolean hasPendingSave(@NotNull UUID accountId) {
        AccountChannel channel = channels.get(accountId);
        if (channel == null) {
            return false;
        }
        synchronized (channel) {
            return channel.latestGeneration > channel.persistedGeneration;
        }
    }

    boolean isLatestPersisted(@NotNull UUID accountId) {
        return !hasPendingSave(accountId);
    }

    @NotNull Set<UUID> accountIds() {
        return Set.copyOf(channels.keySet());
    }

    /**
     * 最新未保存世代を、同一アカウントの直前保存へ連結して保存します。
     *
     * @param accountId 対象アカウント ID
     * @return 今回連結した保存試行の成否を保持する Future。保存失敗時は例外完了する
     */
    @NotNull CompletableFuture<Void> flushLatest(@NotNull UUID accountId) {
        AccountChannel channel = channels.get(accountId);
        if (channel == null) {
            return CompletableFuture.completedFuture(null);
        }
        synchronized (channel) {
            if (channel.latestSnapshot == null
                || channel.latestGeneration <= channel.scheduledGeneration) {
                return channel.tail;
            }
            long generation = channel.latestGeneration;
            QuestPlayerState snapshot = channel.latestSnapshot.snapshot();
            CompletableFuture<Void> previous = channel.tail;
            channel.scheduledGeneration = generation;
            CompletableFuture<Void> attempt;
            try {
                attempt = previous.thenRunAsync(() -> storage.save(snapshot), executor);
            } catch (RuntimeException exception) {
                // Bukkit scheduler が停止処理に入ると、新規タスク登録を拒否することがある。
                // scheduledGeneration を戻し、stop() の同期リトライ対象として残す。
                channel.scheduledGeneration = channel.persistedGeneration;
                CompletableFuture<Void> rejected = new CompletableFuture<>();
                rejected.completeExceptionally(exception);
                return rejected;
            }
            CompletableFuture<Void> outcome = new CompletableFuture<>();
            channel.tail = attempt.handle((ignored, failure) -> {
                finishSave(accountId, channel, generation, failure);
                Throwable cause = unwrap(failure);
                if (cause == null) {
                    outcome.complete(null);
                } else {
                    outcome.completeExceptionally(cause);
                }
                return null;
            });
            return outcome;
        }
    }

    /**
     * 連結済みの全保存処理が終了するまで待機します。
     */
    void awaitAll() {
        while (true) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (AccountChannel channel : channels.values()) {
                synchronized (channel) {
                    futures.add(channel.tail);
                }
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

            boolean settled = true;
            for (AccountChannel channel : channels.values()) {
                synchronized (channel) {
                    if (!channel.tail.isDone()) {
                        settled = false;
                        break;
                    }
                }
            }
            if (settled) {
                return;
            }
        }
    }

    /**
     * 非同期キュー終了後に残った最新世代を、競合のない状態で同期再試行します。
     */
    void retryOutstandingSynchronously() {
        for (Map.Entry<UUID, AccountChannel> entry : channels.entrySet()) {
            UUID accountId = entry.getKey();
            AccountChannel channel = entry.getValue();
            QuestPlayerState snapshot;
            long generation;
            synchronized (channel) {
                if (channel.latestSnapshot == null
                    || channel.latestGeneration <= channel.persistedGeneration) {
                    continue;
                }
                snapshot = channel.latestSnapshot.snapshot();
                generation = channel.latestGeneration;
            }
            try {
                storage.save(snapshot);
                synchronized (channel) {
                    channel.persistedGeneration = Math.max(channel.persistedGeneration, generation);
                    channel.scheduledGeneration = Math.max(channel.scheduledGeneration, generation);
                }
            } catch (RuntimeException exception) {
                logSaveFailure(accountId, exception);
            }
        }
    }

    void evictReleasedPersisted(@NotNull UUID accountId) {
        AccountChannel channel = channels.get(accountId);
        if (channel == null) {
            return;
        }
        boolean removable;
        synchronized (channel) {
            removable = channel.released
                && channel.pendingLoadTokens.isEmpty()
                && channel.latestGeneration <= channel.persistedGeneration
                && channel.tail.isDone();
        }
        if (removable) {
            channels.remove(accountId, channel);
        }
    }

    void clear() {
        channels.clear();
    }

    private void finishSave(
        @NotNull UUID accountId,
        @NotNull AccountChannel channel,
        long generation,
        @Nullable Throwable failure
    ) {
        Throwable cause = unwrap(failure);
        synchronized (channel) {
            if (cause == null) {
                channel.persistedGeneration = Math.max(channel.persistedGeneration, generation);
            } else if (channel.scheduledGeneration == generation) {
                channel.scheduledGeneration = channel.persistedGeneration;
            }
        }
        if (cause != null && !shuttingDown) {
            logSaveFailure(accountId, cause);
        }
    }

    private void logSaveFailure(@NotNull UUID accountId, @NotNull Throwable cause) {
        if (cause instanceof QuestPlayerStateRepository.SaveException saveException) {
            LogId logId = saveException.failure()
                == QuestPlayerStateRepository.SaveFailure.DIRECTORY_CREATE
                ? LogId.W_6602
                : LogId.W_6603;
            Logger.log(logId, saveException, accountId, saveException.path());
            return;
        }
        Logger.log(LogId.W_6600, cause, accountId, cause.getClass().getSimpleName());
    }

    private @NotNull LoadedState loaded(
        @NotNull UUID accountId,
        long loadToken,
        long generation,
        @NotNull QuestPlayerState state
    ) {
        return new LoadedState(accountId, loadToken, generation, state.snapshot());
    }

    private @NotNull AccountChannel channel(@NotNull UUID accountId) {
        return channels.computeIfAbsent(accountId, ignored -> new AccountChannel());
    }

    private @Nullable Throwable unwrap(@Nullable Throwable failure) {
        if (failure == null) {
            return null;
        }
        return failure.getCause() == null ? failure : failure.getCause();
    }

    interface StateStorage {
        @NotNull QuestPlayerState load(@NotNull UUID accountId);

        void save(@NotNull QuestPlayerState state);
    }

    record LoadedState(
        @NotNull UUID accountId,
        long loadToken,
        long generation,
        @NotNull QuestPlayerState state
    ) {
    }

    private static final class AccountChannel {
        private long latestGeneration;
        private long scheduledGeneration;
        private long persistedGeneration;
        private long latestLoadToken;
        private QuestPlayerState latestSnapshot;
        private boolean released;
        private final Set<Long> pendingLoadTokens = new HashSet<>();
        private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    }
}
