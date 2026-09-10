package io.github.maaasu.astralRecord.feature.item.castdisk;

import org.jetbrains.annotations.NotNull;

/** ドッジキャストの遅延実行を、Bukkit schedulerから分離する境界です。 */
@FunctionalInterface
interface CastDiskTaskScheduler {
    @NotNull CastDiskTask schedule(@NotNull Runnable action, long delayTicks);

    interface CastDiskTask {
        void cancel();
    }
}
