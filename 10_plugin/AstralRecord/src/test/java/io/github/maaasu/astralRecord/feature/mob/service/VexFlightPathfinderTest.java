package io.github.maaasu.astralRecord.feature.mob.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VexFlightPathfinderTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-実体Mob制御.md
     * 章・見出し: # 12_3-実体Mob制御 > ## 4. Pathfinder 移動要求 > ### Vex 三次元経路
     * 検証契約: 障害物のない三次元空間では目標ノードまでの経路を返す。
     */
    @Test
    void reachesGoalInOpenThreeDimensionalSpace() {
        VexFlightPathfinder.GridPoint start = point(0, 0, 0);
        VexFlightPathfinder.GridPoint goal = point(4, 2, -3);

        List<VexFlightPathfinder.GridPoint> path = VexFlightPathfinder.findPath(
                start, goal, ignored -> true, 8, 4, 256
        );

        assertFalse(path.isEmpty());
        assertEquals(goal, path.getLast());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-実体Mob制御.md
     * 章・見出し: # 12_3-実体Mob制御 > ## 4. Pathfinder 移動要求 > ### Vex 三次元経路
     * 検証契約: 直線上の壁を通過せず、壁の上または横を通る安全な経路を返す。
     */
    @Test
    void routesAroundWallWithoutUsingBlockedNodes() {
        VexFlightPathfinder.GridPoint start = point(0, 0, 0);
        VexFlightPathfinder.GridPoint goal = point(4, 0, 0);
        Set<VexFlightPathfinder.GridPoint> blocked = new HashSet<>();
        for (int y = -1; y <= 1; y++) {
            for (int z = -1; z <= 1; z++) {
                blocked.add(point(2, y, z));
            }
        }

        List<VexFlightPathfinder.GridPoint> path = VexFlightPathfinder.findPath(
                start, goal, candidate -> !blocked.contains(candidate), 8, 4, 512
        );

        assertEquals(goal, path.getLast());
        assertTrue(path.stream().noneMatch(blocked::contains));
        assertTrue(path.stream().anyMatch(candidate -> Math.abs(candidate.y()) >= 2 || Math.abs(candidate.z()) >= 2));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-実体Mob制御.md
     * 章・見出し: # 12_3-実体Mob制御 > ## 4. Pathfinder 移動要求 > ### Vex 三次元経路
     * 検証契約: 複数軸の斜め移動では、塞がれた直交中間ノードを切り抜けない。
     */
    @Test
    void diagonalMoveDoesNotCutBlockedCorner() {
        VexFlightPathfinder.GridPoint start = point(0, 0, 0);
        VexFlightPathfinder.GridPoint goal = point(1, 1, 0);
        Set<VexFlightPathfinder.GridPoint> blocked = Set.of(point(1, 0, 0), point(0, 1, 0));

        List<VexFlightPathfinder.GridPoint> path = VexFlightPathfinder.findPath(
                start, goal, candidate -> !blocked.contains(candidate), 4, 4, 256
        );

        assertEquals(goal, path.getLast());
        assertNotEquals(goal, path.getFirst());
        assertTrue(path.stream().noneMatch(blocked::contains));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-実体Mob制御.md
     * 章・見出し: # 12_3-実体Mob制御 > ## 4. Pathfinder 移動要求 > ### Vex 三次元経路
     * 検証契約: 目標が探索範囲外でも無制限探索せず、範囲内で最も近い部分経路を返す。
     */
    @Test
    void returnsBoundedPartialPathWhenGoalIsOutsideSearchRadius() {
        VexFlightPathfinder.GridPoint start = point(0, 0, 0);
        VexFlightPathfinder.GridPoint goal = point(20, 0, 0);

        List<VexFlightPathfinder.GridPoint> path = VexFlightPathfinder.findPath(
                start, goal, ignored -> true, 3, 2, 256
        );

        assertFalse(path.isEmpty());
        assertEquals(3, path.getLast().x());
        assertTrue(path.stream().allMatch(candidate -> Math.abs(candidate.x()) <= 3
                && Math.abs(candidate.z()) <= 3
                && Math.abs(candidate.y()) <= 2));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-実体Mob制御.md
     * 章・見出し: # 12_3-実体Mob制御 > ## 4. Pathfinder 移動要求 > ### Vex 三次元経路
     * 検証契約: 展開上限へ達した場合は開始ノードから先を推測せず安全に終了する。
     */
    @Test
    void expansionLimitStopsSearch() {
        List<VexFlightPathfinder.GridPoint> path = VexFlightPathfinder.findPath(
                point(0, 0, 0), point(10, 0, 0), ignored -> true, 12, 4, 1
        );

        assertTrue(path.isEmpty());
    }

    private static VexFlightPathfinder.GridPoint point(int x, int y, int z) {
        return new VexFlightPathfinder.GridPoint(x, y, z);
    }
}
