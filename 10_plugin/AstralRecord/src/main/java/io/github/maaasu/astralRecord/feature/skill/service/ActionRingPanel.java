package io.github.maaasu.astralRecord.feature.skill.service;

import java.util.ArrayList;
import java.util.List;

/** 8枠のスプライトとゲージを、両版の一体型モデルへ渡す表示状態です。 */
record ActionRingPanel(long icons, long gauges) {
    static final int SLOTS = 8;
    static final int GAUGE_STEPS = 10;
    static final int TIMER_STEPS = 24;

    /** アイコン8件、中央の確定アイコン、ゲージ8件、操作時間ゲージ、選択番号の順です。 */
    List<Float> modelValues(int selected) {
        List<Float> values = new ArrayList<>(19);
        int confirmed = (int) ((gauges >>> 37) & 0xf);
        for (int slot = 0; slot < SLOTS; slot++) {
            values.add(confirmed == 0 ? (float) ((icons >>> (8 * slot)) & 0xff) : 0f);
        }
        values.add((float) ((gauges >>> 41) & 0xff));
        for (int slot = 0; slot < SLOTS; slot++) {
            values.add((float) ((gauges >>> (4 * slot)) & 0xf));
        }
        values.add((float) ((gauges >>> 32) & 0x1f));
        values.add((float) selected);
        return values;
    }
}
