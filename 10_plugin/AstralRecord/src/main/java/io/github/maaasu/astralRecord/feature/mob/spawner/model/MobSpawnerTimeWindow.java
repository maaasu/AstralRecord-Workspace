package io.github.maaasu.astralRecord.feature.mob.spawner.model;

/**
 * Minecraft ワールド時間で表すスポーン可能時間帯です。
 *
 * @param startTick 開始 tick（0-23999）
 * @param endTick   終了 tick（0-23999）。開始より小さい場合は日跨ぎとして扱います
 */
public record MobSpawnerTimeWindow(long startTick, long endTick) {

    private static final long DAY_TICKS = 24000L;

    /**
     * 指定ワールド時刻が時間帯に含まれるか判定します。
     *
     * @param time Minecraft ワールド時刻
     * @return 含まれる場合は true
     */
    public boolean contains(long time) {
        long normalized = Math.floorMod(time, DAY_TICKS);
        long start = Math.floorMod(startTick, DAY_TICKS);
        long end = Math.floorMod(endTick, DAY_TICKS);
        if (start <= end) {
            return normalized >= start && normalized <= end;
        }
        return normalized >= start || normalized <= end;
    }

    /**
     * 終日許可の時間帯を返します。
     *
     * @return 終日時間帯
     */
    public static MobSpawnerTimeWindow allDay() {
        return new MobSpawnerTimeWindow(0L, DAY_TICKS - 1L);
    }
}
