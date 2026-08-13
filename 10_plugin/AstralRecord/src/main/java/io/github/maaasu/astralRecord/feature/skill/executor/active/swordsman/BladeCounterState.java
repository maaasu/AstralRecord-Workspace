package io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman;

/** ブレードカウンターの残回数と、直近の通常攻撃に対応する受付時間を保持します。 */
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
        if (!isActive(currentTick)) {
            return false;
        }
        remainingCounters--;
        return true;
    }

    boolean consumeReception(long currentTick) {
        if (!isActive(currentTick) || currentTick >= receptionEndsAtTick) {
            return false;
        }
        receptionEndsAtTick = Long.MIN_VALUE;
        return true;
    }

    int remainingCounters() {
        return remainingCounters;
    }
}
