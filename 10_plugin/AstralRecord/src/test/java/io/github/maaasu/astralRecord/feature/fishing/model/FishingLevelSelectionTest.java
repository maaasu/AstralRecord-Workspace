package io.github.maaasu.astralRecord.feature.fishing.model;

import io.github.maaasu.astralRecord.feature.fishing.model.FishingMasters.Area;
import io.github.maaasu.astralRecord.feature.fishing.model.FishingMasters.FishChance;
import io.github.maaasu.astralRecord.feature.fishing.model.FishingMasters.LevelRange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FishingLevelSelectionTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### 釣りマスタレベルの選択
     * 検証契約: 単一の下位定義は上限を超える竿でも選択され、定義より低い竿へ上位を解放しない。
     */
    @Test
    void singleDefinitionFallsBackWithoutUnlockingHigherLevel() {
        Area area = area(List.of(range(1, 1)));
        assertNull(area.resolveLevelRange(0));
        assertEquals(1, area.resolveLevelRange(1).minLevel());
        assertEquals(1, area.resolveLevelRange(2).minLevel());
        assertEquals(1, area.resolveLevelRange(Integer.MAX_VALUE).minLevel());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### 釣りマスタレベルの選択
     * 検証契約: 入力順と定義間の空白に依存せず、竿以下の最も高い開始レベルを適用する。
     */
    @Test
    void selectsHighestLowerDefinitionAcrossGapsAndInputOrder() {
        Area area = area(List.of(range(7, 7), range(1, 3)));
        assertEquals(1, area.resolveLevelRange(3).minLevel());
        assertEquals(1, area.resolveLevelRange(4).minLevel());
        assertEquals(1, area.resolveLevelRange(6).minLevel());
        assertEquals(7, area.resolveLevelRange(7).minLevel());
        assertEquals(7, area.resolveLevelRange(8).minLevel());
    }

    private Area area(List<LevelRange> ranges) {
        return new Area(1, "area", "検証用", "world", "rules", true, ranges);
    }

    private LevelRange range(int min, int max) {
        return new LevelRange(min, max, List.of(new FishChance("fish", 100)));
    }
}
