package io.github.maaasu.astralRecord.shared.interaction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * resolver群から候補を収集し、1回の入力につき勝者を最大1件だけ実行します。
 *
 * @param <T> gateway固有snapshotの型
 */
public final class PlayerInputDispatcher<T> {
    private static final Comparator<PlayerInputCandidate> CANDIDATE_ORDER =
        Comparator.comparingInt((PlayerInputCandidate candidate) -> candidate.tier().priority())
            .reversed()
            .thenComparingDouble(PlayerInputCandidate::hitDistance)
            .thenComparingInt(PlayerInputCandidate::stableOrder)
            .thenComparing(PlayerInputCandidate::targetKey)
            .thenComparing(PlayerInputCandidate::id);

    private final List<PlayerInputResolver<T>> resolvers;

    /**
     * 入力dispatcherを生成します。
     * resolverの評価順は候補の勝敗に使用しません。
     *
     * @param resolvers 候補を副作用なしに解決するresolver群
     */
    public PlayerInputDispatcher(Collection<? extends PlayerInputResolver<T>> resolvers) {
        Objects.requireNonNull(resolvers, "resolvers");
        List<PlayerInputResolver<T>> copiedResolvers = new ArrayList<>(resolvers.size());
        for (PlayerInputResolver<T> resolver : resolvers) {
            copiedResolvers.add(Objects.requireNonNull(resolver, "resolver"));
        }
        this.resolvers = List.copyOf(copiedResolvers);
    }

    /**
     * 全resolverを評価し、決定的比較で勝者候補を選択します。
     * executorは実行しません。
     *
     * @param context 入力コンテキスト
     * @return 勝者候補。候補なしの場合は空
     */
    public Optional<PlayerInputCandidate> select(PlayerInputContext<T> context) {
        Objects.requireNonNull(context, "context");
        List<PlayerInputCandidate> candidates = new ArrayList<>();
        Set<CandidateIdentity> identities = new HashSet<>();
        for (PlayerInputResolver<T> resolver : resolvers) {
            Collection<PlayerInputCandidate> resolved = Objects.requireNonNull(
                resolver.resolve(context),
                "resolver result"
            );
            for (PlayerInputCandidate candidate : resolved) {
                PlayerInputCandidate checked = Objects.requireNonNull(candidate, "candidate");
                CandidateIdentity identity = new CandidateIdentity(checked.id(), checked.targetKey());
                if (!identities.add(identity)) {
                    throw new IllegalArgumentException(
                        "duplicate candidate identity: " + checked.id() + "/" + checked.targetKey()
                    );
                }
                candidates.add(checked);
            }
        }
        return candidates.stream().min(CANDIDATE_ORDER);
    }

    /**
     * 候補を選択し、勝者executorだけを1回実行します。
     * 勝者executorが例外を送出した場合はその例外を呼び出し元へ伝播し、敗者へfallbackしません。
     *
     * @param context 入力コンテキスト
     * @return 実行結果。候補なしの場合はpass-through
     */
    public PlayerInputDispatchResult dispatch(PlayerInputContext<T> context) {
        return dispatch(context, candidate -> {
        });
    }

    /**
     * 候補を選択し、勝者の実行直前処理を行ってから executor を一度だけ実行します。
     * 実行直前処理または executor が例外を送出した場合も、敗者候補へはフォールバックしません。
     *
     * @param context 入力コンテキスト
     * @param beforeExecution Bukkit イベントの cancel 反映など、勝者確定後かつ副作用実行前に行う処理
     * @return 実行結果。候補がない場合は pass-through
     */
    public PlayerInputDispatchResult dispatch(
        PlayerInputContext<T> context,
        Consumer<PlayerInputCandidate> beforeExecution
    ) {
        Objects.requireNonNull(beforeExecution, "beforeExecution");
        Optional<PlayerInputCandidate> selected = select(context);
        if (selected.isEmpty()) {
            return PlayerInputDispatchResult.passThrough();
        }
        PlayerInputCandidate winner = selected.orElseThrow();
        beforeExecution.accept(winner);
        winner.executeIfValid();
        return PlayerInputDispatchResult.selected(winner);
    }

    private record CandidateIdentity(String id, String targetKey) {
    }
}
