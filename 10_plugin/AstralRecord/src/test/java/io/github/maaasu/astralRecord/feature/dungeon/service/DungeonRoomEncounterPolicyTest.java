package io.github.maaasu.astralRecord.feature.dungeon.service;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomShape;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonRoomEncounterPolicyTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 3. 遭遇 Mob と部屋進行
     * 検証契約: START部屋は安全地帯として遭遇戦を持たない。
     */
    @Test
    void startRoomNeverHasAnEncounter() {
        assertFalse(DungeonRoomEncounterPolicy.hasEncounter(room(DungeonLayout.RoomRole.START, 0)));
        assertTrue(DungeonRoomEncounterPolicy.hasEncounter(room(DungeonLayout.RoomRole.NORMAL, 1)));
        assertTrue(DungeonRoomEncounterPolicy.hasEncounter(room(DungeonLayout.RoomRole.BOSS, 2)));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 3. 遭遇 Mob と部屋進行
     * 検証契約: 初戦レベル上限はSTARTではなく、START直後のNORMAL部屋へ適用する。
     */
    @Test
    void firstCombatRoomIsANormalRoomOneStepFromStart() {
        assertFalse(DungeonRoomEncounterPolicy.isFirstCombatRoom(room(DungeonLayout.RoomRole.START, 0)));
        assertTrue(DungeonRoomEncounterPolicy.isFirstCombatRoom(room(DungeonLayout.RoomRole.NORMAL, 1)));
        assertFalse(DungeonRoomEncounterPolicy.isFirstCombatRoom(room(DungeonLayout.RoomRole.NORMAL, 2)));
        assertFalse(DungeonRoomEncounterPolicy.isFirstCombatRoom(room(DungeonLayout.RoomRole.BOSS, 1)));
    }

    private DungeonLayout.Room room(DungeonLayout.RoomRole role, int distanceFromStart) {
        return new DungeonLayout.Room(
                distanceFromStart,
                new DungeonLayout.Rect(0, 0, 8, 8),
                DungeonRoomShape.RECTANGLE,
                role,
                distanceFromStart
        );
    }
}
