package io.github.maaasu.astralRecord.feature.boss.service;

/** Java 表示専用。入力の水平距離から、停止へもなめらかに戻る歩行の姿勢を選ぶ。 */
final class MossZombieGait {
    static final int LEVELS = 8;
    static final double CYCLE_BLOCKS = 1.0D;
    private static final double DEAD_ZONE = 0.001D;
    private static final int STOP_GRACE_TICKS = 3;
    private static final double WALK_WEIGHT = 0.85D;
    private double phase;
    private double amplitude;
    private int frame;
    private int walkHold;

    int frame() {
        return frame;
    }

    void advance(double travel, boolean teleport) {
        if (teleport) {
            phase = 0.0D;
            amplitude = 0.0D;
            frame = 0;
            walkHold = 0;
            return;
        }
        boolean moved = travel >= DEAD_ZONE;
        if (moved) {
            walkHold = STOP_GRACE_TICKS;
            phase = (phase + Math.TAU * Math.min(0.14D, travel) / CYCLE_BLOCKS) % Math.TAU;
        } else if (walkHold > 0) {
            walkHold--;
        }
        double target = walkHold > 0 ? WALK_WEIGHT : 0.0D;
        amplitude += (target - amplitude) * (target > amplitude ? 0.32D : 0.40D);
        int wanted = (int) Math.round(Math.sin(phase) * amplitude * LEVELS);
        if (!moved) wanted = Math.clamp(wanted, Math.min(frame, 0), Math.max(frame, 0));
        // 速い移動でも中間姿勢を一度に飛び越えすぎない。
        frame = Math.clamp(wanted, frame - 2, frame + 2);
        if (walkHold == 0 && amplitude < 0.035D && frame == 0) phase = 0.0D;
    }
}
