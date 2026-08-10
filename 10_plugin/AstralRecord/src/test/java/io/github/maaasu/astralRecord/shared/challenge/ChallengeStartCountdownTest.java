package io.github.maaasu.astralRecord.shared.challenge;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChallengeStartCountdownTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 4. 開始・生成・転送
     * 検証契約: 初回転送後の10 tickは10から1を表示し、その次のtickでだけ戦闘開始を指示する。
     */
    @Test
    void startsOnlyAfterTenCountdownTicks() {
        ChallengeStartCountdown countdown = new ChallengeStartCountdown();
        List<Integer> displayed = new ArrayList<>();

        for (int tickIndex = 0; tickIndex < 10; tickIndex++) {
            ChallengeStartCountdown.Tick tick = countdown.advance();
            assertEquals(ChallengeStartCountdown.Phase.COUNTDOWN, tick.phase());
            displayed.add(tick.remainingSeconds());
        }

        assertEquals(List.of(10, 9, 8, 7, 6, 5, 4, 3, 2, 1), displayed);
        assertEquals(ChallengeStartCountdown.Phase.START, countdown.advance().phase());
        assertEquals(ChallengeStartCountdown.Phase.COMPLETED, countdown.advance().phase());
    }
}
