package io.github.maaasu.astralRecord.feature.boss.service;

/** Java 表示専用。入力の水平距離から、停止へもなめらかに戻る歩行の姿勢を選ぶ。 */
final class MossZombieGait {
    static final int LEVELS = 8;
    private static final double CYCLE_BLOCKS = 1.4D;
    private static final double DEAD_ZONE = 0.008D;
    private double phase;
    private double amplitude;
    private int frame;

    int frame() {
        return frame;
    }

    void advance(double travel, boolean teleport) {
        if (teleport) {
            phase = 0.0D;
            amplitude = 0.0D;
            frame = 0;
            return;
        }
        double stride = Math.max(0.0D, travel - DEAD_ZONE);
        double target = Math.clamp(stride / 0.102D, 0.0D, 1.0D);
        amplitude += (target - amplitude) * (target > amplitude ? 0.30D : 0.40D);
        phase = (phase + Math.TAU * Math.min(0.14D, stride) / CYCLE_BLOCKS) % Math.TAU;
        int wanted = (int) Math.round(Math.sin(phase) * amplitude * LEVELS);
        if (stride == 0.0D) wanted = Math.clamp(wanted, Math.min(frame, 0), Math.max(frame, 0));
        // 速い移動でも中間姿勢を一度に飛び越えすぎない。
        frame = Math.clamp(wanted, frame - 2, frame + 2);
        if (stride == 0.0D && amplitude < 0.035D && frame == 0) phase = 0.0D;
    }
}
