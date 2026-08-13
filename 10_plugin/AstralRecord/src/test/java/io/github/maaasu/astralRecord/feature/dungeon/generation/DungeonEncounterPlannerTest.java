package io.github.maaasu.astralRecord.feature.dungeon.generation;

import io.github.maaasu.astralRecord.feature.dungeon.DungeonTestFixtures;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonDefinition;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonEncounterPlannerTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 3. 遭遇 Mob と部屋進行
     * 検証契約: START直後の最初の戦闘部屋ではweightに関係なくfirstCombatRoomMaxMobLevelを超える通常Mobを抽選候補から除外する。
     */
    @Test
    void excludesOverLevelNormalMobsFromTheFirstCombatRoom() {
        DungeonDefinition definition = DungeonTestFixtures.definition();
        MobTemplate weak = DungeonTestFixtures.mob("weak", 5, MobCategory.ENEMY);
        MobTemplate strongest = DungeonTestFixtures.mob("strongest", 99, MobCategory.ENEMY);
        List<DungeonEncounterPlanner.WeightedTemplate> pool = List.of(
                new DungeonEncounterPlanner.WeightedTemplate(weak, 1),
                new DungeonEncounterPlanner.WeightedTemplate(strongest, 10_000)
        );
        DungeonEncounterPlanner planner = new DungeonEncounterPlanner();

        for (long seed = 0; seed < 100; seed++) {
            List<MobTemplate> selected = planner.planNormalRoom(
                    definition, pool, true, seed, 0);
            assertTrue(selected.stream().allMatch(mob ->
                    mob.level() <= definition.encounter().firstCombatRoomMaxMobLevel()));
            assertTrue(selected.size() >= definition.encounter().mobsPerRoom().min());
            assertTrue(selected.size() <= definition.encounter().mobsPerRoom().max());
        }
    }

}
