package io.github.maaasu.astralRecord.feature.skill.active.service;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillTargetingServiceTest {

    private static final BoundingBox STANDARD_MOB = new BoundingBox(
            -0.3D, 0.0D, 4.7D,
            0.3D, 1.8D, 5.3D
    );

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 9. active skill 共通支援
     * 検証契約: 水平eye rayとMob実body bounding boxの交差をhitとする。
     */
    @Test
    void horizontalEyeRayHitsTheMobsActualBodyBounds() {
        assertTrue(SkillTargetingService.intersectsLine(
                STANDARD_MOB,
                new Vector(0.0D, 1.62D, 0.0D),
                new Vector(0.0D, 0.0D, 1.0D),
                10.0D,
                0.45D
        ));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 9. active skill 共通支援
     * 検証契約: line capsule半径で拡張したbounds外のtargetをhitにしない。
     */
    @Test
    void capsuleRadiusDoesNotHitTargetsOutsideItsExpandedBounds() {
        assertFalse(SkillTargetingService.intersectsLine(
                STANDARD_MOB,
                new Vector(0.0D, 2.4D, 0.0D),
                new Vector(0.0D, 0.0D, 1.0D),
                10.0D,
                0.45D
        ));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 9. active skill 共通支援
     * 検証契約: line originより後方のtargetをhitにしない。
     */
    @Test
    void lineDoesNotHitTargetsBehindItsOrigin() {
        assertFalse(SkillTargetingService.intersectsLine(
                STANDARD_MOB,
                new Vector(0.0D, 1.0D, 10.0D),
                new Vector(0.0D, 0.0D, 1.0D),
                10.0D,
                0.5D
        ));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 9. active skill 共通支援
     * 検証契約: target center距離でなくrayがbody前面へ入る交差距離でhit順を決める。
     */
    @Test
    void intersectionDistanceOrdersByFrontFaceInsteadOfTargetCenter() {
        BoundingBox largeFrontTarget = new BoundingBox(-1.0D, 0.0D, 2.0D, 1.0D, 3.0D, 10.0D);
        BoundingBox smallRearTarget = new BoundingBox(-0.3D, 0.0D, 4.0D, 0.3D, 1.8D, 4.5D);
        Vector origin = new Vector(0.0D, 1.0D, 0.0D);
        Vector direction = new Vector(0.0D, 0.0D, 1.0D);

        double largeDistance = SkillTargetingService.lineIntersectionDistance(
                largeFrontTarget, origin, direction, 20.0D, 0.0D
        );
        double smallDistance = SkillTargetingService.lineIntersectionDistance(
                smallRearTarget, origin, direction, 20.0D, 0.0D
        );

        assertEquals(2.0D, largeDistance, 1.0E-9D);
        assertEquals(4.0D, smallDistance, 1.0E-9D);
        assertTrue(largeDistance < smallDistance);
    }
}
