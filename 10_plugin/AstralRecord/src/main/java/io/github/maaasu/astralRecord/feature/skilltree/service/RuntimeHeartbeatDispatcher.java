package io.github.maaasu.astralRecord.feature.skilltree.service;

import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreeRuntimeRepository.BatchUnavailableException;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** bulk成功時も旧API fallback時も、ACK・claim・失敗・inflight解除をaccountごとに独立させる。 */
final class RuntimeHeartbeatDispatcher {
    private RuntimeHeartbeatDispatcher() { }

    /**
     * bulk HTTPを一度だけ非同期実行し、成功後のACK・claim・完了もaccountごとのtaskへ渡す。
     * 未対応の場合は旧APIの送信からaccountごとのtaskへ渡し、個別taskの完了をまとめて待機しない。
     * callbackは捕捉済みaccountだけを扱い、completedは各入力につき一度だけ呼ぶ。
     */
    static <T, R> void dispatch(List<T> items, Consumer<Runnable> executor,
                               Function<List<T>, Map<T, R>> bulk, Function<T, R> legacy,
                               BiConsumer<T, R> accepted, BiConsumer<T, RuntimeException> failed,
                               Consumer<T> completed) {
        try {
            executor.accept(() -> {
                Map<T, R> results;
                try { results = bulk.apply(items); }
                catch (BatchUnavailableException unavailable) {
                    for (T item : items)
                        submit(item, executor, () -> legacy.apply(item), accepted, failed, completed);
                    return;
                } catch (RuntimeException failure) {
                    for (T item : items) {
                        try { failed.accept(item, failure); }
                        finally { completed.accept(item); }
                    }
                    return;
                }
                for (T item : items)
                    submit(item, executor, () -> results.get(item), accepted, failed, completed);
            });
        } catch (RuntimeException rejected) {
            for (T item : items) {
                try { failed.accept(item, rejected); }
                finally { completed.accept(item); }
            }
        }
    }

    /** 1accountのACK処理と失敗処理を非同期実行し、task拒否時もそのaccountのinflightだけを解除する。 */
    private static <T, R> void submit(T item, Consumer<Runnable> executor, Supplier<R> work,
                                      BiConsumer<T, R> accepted, BiConsumer<T, RuntimeException> failed,
                                      Consumer<T> completed) {
        try {
            executor.accept(() -> {
                try {
                    R result = work.get();
                    if (result != null) accepted.accept(item, result);
                } catch (RuntimeException failure) { failed.accept(item, failure); }
                finally { completed.accept(item); }
            });
        } catch (RuntimeException rejected) {
            try { failed.accept(item, rejected); }
            finally { completed.accept(item); }
        }
    }
}
