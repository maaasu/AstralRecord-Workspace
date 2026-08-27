package io.github.maaasu.astralRecord.feature.dungeon.view;

import io.github.maaasu.astralRecord.feature.dungeon.DungeonTestFixtures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonEntryTextTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 4. 開始・生成・転送
     * 検証契約: 受付地点の TextDisplay はダンジョン表示名、推奨レベル、スニークによる挑戦開始案内を表示する。
     */
    @Test
    void rendersDungeonNameRecommendedLevelAndStartGuide() {
        String text = DungeonEntryText.render(DungeonTestFixtures.definition());

        assertTrue(text.contains("Test Dungeon"));
        assertTrue(text.contains("推奨レベル: Lv.5"));
        assertTrue(text.contains("中でスニークして挑戦開始"));
    }
}
