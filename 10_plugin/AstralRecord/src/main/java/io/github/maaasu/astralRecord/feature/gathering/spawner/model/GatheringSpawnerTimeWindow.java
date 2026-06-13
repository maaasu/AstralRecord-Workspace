package io.github.maaasu.astralRecord.feature.gathering.spawner.model;

public record GatheringSpawnerTimeWindow(long startTick, long endTick) {
    private static final long DAY_TICKS = 24000L;

    public boolean contains(long worldTime) {
        long time = Math.floorMod(worldTime, DAY_TICKS);
        long start = Math.floorMod(startTick, DAY_TICKS);
        long end = Math.floorMod(endTick, DAY_TICKS);
        if (start <= end) {
            return time >= start && time <= end;
        }
        return time >= start || time <= end;
    }

    public static GatheringSpawnerTimeWindow allDay() {
        return new GatheringSpawnerTimeWindow(0L, DAY_TICKS - 1L);
    }
}
