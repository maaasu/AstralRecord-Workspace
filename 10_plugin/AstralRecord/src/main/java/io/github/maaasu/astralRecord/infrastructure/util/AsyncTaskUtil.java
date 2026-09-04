package io.github.maaasu.astralRecord.infrastructure.util;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Bukkit scheduler 上で非同期処理とメインスレッド復帰を安全に構成します。
 */
public final class AsyncTaskUtil {
    private static final long MAIN_THREAD_RETRY_INITIAL_MILLIS = 50L;
    private static final long MAIN_THREAD_RETRY_MAX_MILLIS = 1_000L;

    private AsyncTaskUtil() {
    }

    /**
     * plugin 管理下の非同期 task で値を生成します。
     *
     * @param plugin plugin 本体
     * @param supplier 非同期で実行する処理
     * @return 完了 future
     * @param <T> 結果型
     */
    public static <T> @NotNull CompletableFuture<T> supplyAsync(
        @NotNull Plugin plugin,
        @NotNull Supplier<T> supplier
    ) {
        CompletableFuture<T> future = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    /**
     * plugin 管理下のメインスレッド task を予約します。
     *
     * @param plugin plugin 本体
     * @param action 実行処理
     */
    public static void runSync(@NotNull Plugin plugin, @NotNull Runnable action) {
        plugin.getServer().getScheduler().runTask(plugin, action);
    }

    /**
     * main task の受付が一時的に拒否された場合も、Bukkit APIを非main threadから呼ばずに再受付します。
     * <p>
     * scheduler が停止済みの場合は最終的に実行されませんが、受付拒否直後に状態を破棄せず、
     * 一時的な scheduler 圧迫から復帰する余地を残します。action は高々一度だけ実行します。
     *
     * @param plugin task所有プラグイン
     * @param action main threadで実行する処理
     */
    public static void runSyncEventually(@NotNull Plugin plugin, @NotNull Runnable action) {
        AtomicBoolean executed = new AtomicBoolean();
        scheduleSyncEventually(plugin, action, executed, MAIN_THREAD_RETRY_INITIAL_MILLIS);
    }

    private static void scheduleSyncEventually(
        @NotNull Plugin plugin,
        @NotNull Runnable action,
        @NotNull AtomicBoolean executed,
        long retryDelayMillis
    ) {
        if (executed.get()) return;
        try {
            runSync(plugin, () -> {
                if (!executed.compareAndSet(false, true)) return;
                action.run();
            });
        } catch (Throwable schedulingFailure) {
            if (executed.get()) return;
            long nextDelayMillis = Math.min(MAIN_THREAD_RETRY_MAX_MILLIS, retryDelayMillis * 2L);
            try {
                CompletableFuture.delayedExecutor(retryDelayMillis, TimeUnit.MILLISECONDS).execute(
                    () -> scheduleSyncEventually(plugin, action, executed, nextDelayMillis)
                );
            } catch (Throwable ignored) {
                // 代替executorも停止済みの場合、Bukkit APIを非main threadから呼ばず終了する。
            }
        }
    }
}
