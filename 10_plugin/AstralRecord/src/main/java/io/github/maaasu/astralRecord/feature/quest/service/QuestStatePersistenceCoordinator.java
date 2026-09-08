package io.github.maaasu.astralRecord.feature.quest.service;

import io.github.maaasu.astralRecord.feature.quest.model.QuestPlayerState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 非同期ロードと、SQL ACK 待ちの runtime quest state の世代を調停します。
 * 保存処理は inventory の account lane と player-state snapshot だけが担当します。
 */
final class QuestStatePersistenceCoordinator {
    private final Function<UUID, QuestPlayerState> loader;
    private final Map<UUID, AccountChannel> channels = new ConcurrentHashMap<>();

    QuestStatePersistenceCoordinator(@NotNull Function<UUID, QuestPlayerState> loader) {
        this.loader = loader;
    }

    /** ロード中にローカル世代が進んだ場合は、DBから読んだ古い状態より保持中の状態を優先します。 */
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
                QuestPlayerState databaseState = loader.apply(accountId);
                synchronized (channel) {
                    if (channel.latestSnapshot != null) {
                        return loaded(accountId, loadToken, channel.latestGeneration, channel.latestSnapshot);
                    }
                    if (channel.latestGeneration == observedGeneration) {
                        return loaded(accountId, loadToken, observedGeneration, databaseState);
                    }
                    observedGeneration = channel.latestGeneration;
                }
            }
        } catch (RuntimeException failure) {
            synchronized (channel) {
                channel.pendingLoadTokens.remove(loadToken);
            }
            evictReleased(accountId);
            throw failure;
        }
    }

    @Nullable QuestPlayerState apply(@NotNull LoadedState loadedState) {
        AccountChannel channel = channels.get(loadedState.accountId());
        if (channel == null) return null;
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

    void discard(@NotNull LoadedState loadedState) {
        AccountChannel channel = channels.get(loadedState.accountId());
        if (channel == null) return;
        synchronized (channel) {
            channel.pendingLoadTokens.remove(loadedState.loadToken());
        }
        evictReleased(loadedState.accountId());
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

    /** 呼出元が SQL ACK 済みと確認した released state だけを破棄します。 */
    void evictReleased(@NotNull UUID accountId) {
        AccountChannel channel = channels.get(accountId);
        if (channel == null) return;
        boolean removable;
        synchronized (channel) {
            removable = channel.released && channel.pendingLoadTokens.isEmpty();
        }
        if (removable) channels.remove(accountId, channel);
    }

    void clear() {
        channels.clear();
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

    record LoadedState(
        @NotNull UUID accountId,
        long loadToken,
        long generation,
        @NotNull QuestPlayerState state
    ) {
    }

    private static final class AccountChannel {
        private long latestGeneration;
        private long latestLoadToken;
        private QuestPlayerState latestSnapshot;
        private boolean released;
        private final Set<Long> pendingLoadTokens = new HashSet<>();
    }
}
