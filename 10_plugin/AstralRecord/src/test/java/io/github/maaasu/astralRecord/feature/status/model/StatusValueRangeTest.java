package io.github.maaasu.astralRecord.feature.status.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusValueRangeTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/07_1-モデル定義.md
     * 章・見出し: # 07_1-モデル定義 > ## 3. ステータス値
     * 検証契約: 上下限同値のstatusは単一値を返す。
     */
    @Test
    void fixedValueReturnsSingleTotal() {
        StatusValue value = new StatusValue(8.0D, 4.0D);

        assertEquals(12.0D, value.getMinValue(), 0.0001D);
        assertEquals(12.0D, value.getMaxValue(), 0.0001D);
        assertEquals(12.0D, value.rollValue(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/07_1-モデル定義.md
     * 章・見出し: # 07_1-モデル定義 > ## 3. ステータス値
     * 検証契約: range statusのroll値を合計min/max閉区間内に収める。
     */
    @Test
    void rangeValueRollsWithinTotalBounds() {
        StatusValue value = new StatusValue(8.0D, 8.0D, 2.0D, 12.0D);

        assertEquals(10.0D, value.getMinValue(), 0.0001D);
        assertEquals(20.0D, value.getMaxValue(), 0.0001D);
        for (int i = 0; i < 1_000; i++) {
            double rolled = value.rollValue();
            assertTrue(rolled >= 10.0D && rolled <= 20.0D);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/07_1-モデル定義.md
     * 章・見出し: # 07_1-モデル定義 > ## 3. ステータス値
     * 検証契約: min=maxのrange表示を単一値へ畳み込む。
     */
    @Test
    void statusFormattingCollapsesEqualRange() {
        assertEquals("26", StatusType.ATTACK.formatRange(26.0D, 26.0D));
        assertEquals("20 ～ 30", StatusType.ATTACK.formatRange(20.0D, 30.0D));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/07_1-モデル定義.md
     * 章・見出し: # 07_1-モデル定義 > ## 4. ステータス種別
     * 検証契約: percentage statusを0〜1比率へ再換算せず保存済みpercentage pointで表示する。
     */
    @Test
    void percentageStatusFormattingUsesStoredPercentagePoints() {
        assertEquals("5.0%", StatusType.CRITICAL_RATE.formatValue(5.0D));
        assertEquals("150.0%", StatusType.CRITICAL_DAMAGE.formatValue(150.0D));
        assertEquals("95.0%", StatusType.ACCURACY.formatValue(95.0D));
        assertEquals("100%", StatusType.ATTACK_SPEED.formatValue(100.0D));
        assertEquals("100", StatusType.MOVEMENT_SPEED.formatValue(100.0D));
        assertTrue(StatusType.byCategory(StatusType.Category.UTILITY).contains(StatusType.MOVEMENT_SPEED));
    }
}
