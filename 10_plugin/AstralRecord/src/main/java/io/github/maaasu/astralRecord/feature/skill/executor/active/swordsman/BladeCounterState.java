package io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman;

/** ブレードカウンター1回分の残回数と受付時間を保持する純粋なruntime状態です。 */
final class BladeCounterState {

    private int remainingCounters;
    private final long expiresAtTick;
    private long receptionEndsAtTick = Long.MIN_VALUE;

    BladeCounterState(int remainingCounters, long expiresAtTick) {
        this.remainingCounters = remainingCounters;
        this.expiresAtTick = expiresAtTick;
    }

    boolean isActive(long currentTick) {
        return remainingCounters > 0 && currentTick < expiresAtTick;
    }

    void openReception(long currentTick, long durationTicks) {
        if (isActive(currentTick)) {
            receptionEndsAtTick = Math.min(expiresAtTick, currentTick + durationTicks);
        }
    }

    boolean consumeCounter(long currentTick) {
        if (!isActive(currentTick) || currentTick >= receptionEndsAtTick) {
            return false;
        }
        remainingCounters--;
        return true;
    }

    int remainingCounters() {
        return remainingCounters;
    }
}
