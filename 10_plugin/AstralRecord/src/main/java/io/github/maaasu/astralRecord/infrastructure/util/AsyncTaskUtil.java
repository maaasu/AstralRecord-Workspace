package io.github.maaasu.astralRecord.infrastructure.util;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Bukkit scheduler 上で非同期処理とメインスレッド復帰を安全に構成します。
 */
public final class AsyncTaskUtil {
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
}
