package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * インベントリ書き込みの debounce / coalesce 用ヘルパー。
 * <p>
 * 同一キーへの書き込み要求を一定時間だけ蓄積し、最終 payload で 1 回だけ flushAction を実行します。
 * キャッシュ更新は呼び出し側で即時行い、本クラスは API 呼び出しなど高コスト処理の発火だけを遅延します。
 * <ul>
 *   <li>初回 submit でスケジュールを 1 本だけ立てる（後発 submit はスケジュールを延長しない）</li>
 *   <li>後発 submit は最新 payload と flushAction でアトミックに上書きする</li>
 *   <li>flush 完了時にエントリを除去し、次回 submit から新規スケジュールを立て直す</li>
 *   <li>{@link #flushNow(Object)} / {@link #flushAll()} は予定をキャンセルして即時実行する</li>
 *   <li>{@link #shutdown()} は flushAll を行ったうえでスケジューラを停止する</li>
 * </ul>
 */
public final class InventoryWriteCoalescer {
    /** 既定 1 秒。装備入れ替えや entry 編集など、短時間に連続発火する書き込みをまとめる窓。 */
    public static final long DEFAULT_DELAY_MS = 1000L;

    private final ScheduledExecutorService scheduler;
    private final long delayMs;
    private final String name;
    private final Map<Object, PendingWrite<?>> pending = new ConcurrentHashMap<>();

    /**
     * 既定遅延でコアレッサを生成します。
     *
     * @param name スレッド名・ログ識別子
     */
    public InventoryWriteCoalescer(@NotNull String name) {
        this(name, DEFAULT_DELAY_MS);
    }

    /**
     * 指定遅延でコアレッサを生成します。
     *
     * @param name スレッド名・ログ識別子
     * @param delayMs 初回 submit から flush 実行までの遅延（ミリ秒）
     */
    public InventoryWriteCoalescer(@NotNull String name, long delayMs) {
        this.name = name;
        this.delayMs = delayMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "AstralRecord-" + name);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 指定キーへ書き込みを登録します。
     * 既存の保留がある場合は payload と flushAction を最新で上書きし、新たなスケジュールは立てません。
     *
     * @param key 直列化キー（例: inventoryId / accountId）
     * @param payload 最新書き込み内容（参照のみ保持。呼び出し側で immutable にしてください）
     * @param flushAction flush 時に最新 payload を引数として呼び出すアクション
     * @param <T> payload 型
     */
    public <T> void submit(
        @NotNull Object key,
        @NotNull T payload,
        @NotNull Consumer<T> flushAction
    ) {
        pending.compute(key, (k, existing) -> {
            if (existing != null) {
                @SuppressWarnings("unchecked")
                PendingWrite<T> typed = (PendingWrite<T>) existing;
                typed.latest.set(payload);
                typed.flushAction = flushAction;
                return existing;
            }
            PendingWrite<T> created = new PendingWrite<>(payload, flushAction);
            created.future = scheduler.schedule(
                () -> flushKey(k),
                delayMs,
                TimeUnit.MILLISECONDS
            );
            return created;
        });
    }

    /**
     * 指定キーに保留が存在するかを返します。
     *
     * @param key 直列化キー
     * @return 保留中なら true
     */
    public boolean hasPending(@NotNull Object key) {
        return pending.containsKey(key);
    }

    /**
     * 指定キーに保留があれば即時 flush します。スケジュールはキャンセルされます。
     *
     * @param key 直列化キー
     * @return flush が走った場合 true
     */
    public boolean flushNow(@NotNull Object key) {
        PendingWrite<?> removed = pending.remove(key);
        if (removed == null) {
            return false;
        }
        ScheduledFuture<?> future = removed.future;
        if (future != null) {
            future.cancel(false);
        }
        invokeSafely(key, removed);
        return true;
    }

    /**
     * 現在保留中の全キーを即時 flush します。
     */
    public void flushAll() {
        for (Object key : new ArrayList<>(pending.keySet())) {
            flushNow(key);
        }
    }

    /**
     * 保留分を flush したうえでスケジューラを停止します。
     * プラグイン停止時などに呼び出してください。
     */
    public void shutdown() {
        flushAll();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5L, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }

    private void flushKey(@NotNull Object key) {
        PendingWrite<?> removed = pending.remove(key);
        if (removed == null) {
            return;
        }
        invokeSafely(key, removed);
    }

    private void invokeSafely(@NotNull Object key, @NotNull PendingWrite<?> pendingWrite) {
        try {
            pendingWrite.invoke();
        } catch (RuntimeException e) {
            Logger.warn(LogId.W_5252, name + ":" + key, e.getMessage());
        }
    }

    private static final class PendingWrite<T> {
        final AtomicReference<T> latest;
        volatile Consumer<T> flushAction;
        volatile ScheduledFuture<?> future;

        PendingWrite(@NotNull T initial, @NotNull Consumer<T> flushAction) {
            this.latest = new AtomicReference<>(initial);
            this.flushAction = flushAction;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void invoke() {
            T payload = latest.get();
            Consumer action = flushAction;
            action.accept(payload);
        }
    }
}
