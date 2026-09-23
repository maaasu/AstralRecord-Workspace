package io.github.maaasu.astralRecord.feature.status.service;

import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.model.StatusValue;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatusRateCalculatorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/07_1-モデル定義.md
     * 章・見出し: # 07_1-モデル定義 > ## 4. ステータス種別 > ### 4.7 回復・ユーティリティ系 > #### 4.7.1 基準100%の率ステータス
     * 検証契約: 200%のEXP獲得量増加率は50の基準EXPを100へ増やす。
     */
    @Test
    void doublesExperienceAtTwoHundredPercent() {
        StatusSnapshot snapshot = snapshot(StatusType.EXPERIENCE_GAIN_RATE, 200.0D);

        assertEquals(100, StatusRateCalculator.applyRate(
            snapshot,
            StatusType.EXPERIENCE_GAIN_RATE,
            50
        ));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/07_1-モデル定義.md
     * 章・見出し: # 07_1-モデル定義 > ## 4. ステータス種別 > ### 4.7 回復・ユーティリティ系 > #### 4.7.1 基準100%の率ステータス
     * 検証契約: ステータス値のないスナップショットでは100%を使い、基準量を維持する。
     */
    @Test
    void usesNeutralRateWhenSnapshotHasNoRateValue() {
        assertEquals(50, StatusRateCalculator.applyRate(
            StatusSnapshot.empty(),
            StatusType.EXPERIENCE_GAIN_RATE,
            50
        ));
    }

    private StatusSnapshot snapshot(StatusType type, double ratePercent) {
        return new StatusSnapshot(
            Map.of(type, new StatusValue(ratePercent, 0.0D)),
            0.0D,
            0.0D,
            0.0D,
            0.0D,
            0L,
            LocalDateTime.now()
        );
    }
}
