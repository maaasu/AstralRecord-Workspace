package io.github.maaasu.astralRecord.feature.player.service;

import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** サーバーに依存せず、共通の足元接触の幾何条件を検証します。 */
class PlayerGroundSupportTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 1. service メソッド仕様 > ### 移動アクションの共通接地判定
     * 検証契約: 整数境界と異なる高さでも、足の水平範囲が衝突上面と重なっていれば接地とする。
     */
    @Test
    void acceptsContactAtDifferentSurfaceHeights() {
        for (double surfaceY : new double[]{-1.5D, 0.0625D, 0.125D, 0.5D, 1.0D, 1.1875D, 1.5D}) {
            BoundingBox support = new BoundingBox(0.0D, surfaceY - 0.0625D, 0.0D, 1.0D, surfaceY, 1.0D);
            assertTrue(PlayerGroundSupport.hasGroundContact(body(0.5D, surfaceY, 0.5D), support));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 1. service メソッド仕様 > ### 移動アクションの共通接地判定
     * 検証契約: 足の一部が上面に重なれば接地とし、水平範囲の境界接触や離れた面は接地にしない。
     */
    @Test
    void acceptsPartialFootprintButRejectsSideAndCornerTangency() {
        BoundingBox support = new BoundingBox(-1.0D, 0.0D, -1.0D, 0.0D, 0.5D, 0.0D);
        assertTrue(PlayerGroundSupport.hasGroundContact(body(0.2D, 0.5D, 0.2D), support));
        assertFalse(PlayerGroundSupport.hasGroundContact(body(0.3D, 0.5D, -0.5D), support));
        assertFalse(PlayerGroundSupport.hasGroundContact(body(0.3D, 0.5D, 0.3D), support));
        assertFalse(PlayerGroundSupport.hasGroundContact(body(0.4D, 0.5D, -0.5D), support));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 1. service メソッド仕様 > ### 移動アクションの共通接地判定
     * 検証契約: 下方0.05ブロックと座標誤差までを許容し、空中・足より高い上面・空の形状は拒否する。
     */
    @Test
    void rejectsAirborneFeetWallsAndEmptyShapes() {
        BoundingBox support = new BoundingBox(0.0D, 0.0D, 0.0D, 1.0D, 0.5D, 1.0D);
        assertTrue(PlayerGroundSupport.hasGroundContact(body(0.5D, 0.55D, 0.5D), support));
        assertTrue(PlayerGroundSupport.hasGroundContact(body(0.5D, 0.4999995D, 0.5D), support));
        assertFalse(PlayerGroundSupport.hasGroundContact(body(0.5D, 0.55001D, 0.5D), support));
        assertFalse(PlayerGroundSupport.hasGroundContact(body(0.5D, 0.49D, 0.5D), support));
        assertFalse(PlayerGroundSupport.hasGroundContact(body(0.5D, 0.0D, 0.5D), new BoundingBox()));
    }

    private static BoundingBox body(double x, double feetY, double z) {
        return new BoundingBox(x - 0.3D, feetY, z - 0.3D, x + 0.3D, feetY + 1.8D, z + 0.3D);
    }
}
