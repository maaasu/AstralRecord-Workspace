package io.github.maaasu.astralRecord.feature.skilltree.service;

import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePointType;
import org.bukkit.Location;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillTreeVisualizerTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 10. skill tree 設定・master snapshot
     * 検証契約: 同一node IDでもdisplay/icon/lore/tag/point/condition/effect内容差を変更として検出する。
     */
    @Test
    void nodeDefinitionComparisonDetectsContentChangeWithSameNodeId() {
        SkillTreeNodeDefinition current = node("Before");
        SkillTreeNodeDefinition updated = node("After");

        assertTrue(SkillTreeVisualizer.nodeDefinitionsDiffer(current, updated));
        assertFalse(SkillTreeVisualizer.nodeDefinitionsDiffer(current, node("Before")));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 9. スキルツリー表示フォールバック
     * 検証契約: Bedrock向けedge粒子は中点1点ではなく、edge内部へ等間隔に複数配置する。
     */
    @Test
    void bedrockEdgeParticlesAreDistributedAcrossTheEdge() {
        List<Location> locations = SkillTreeVisualizer.bedrockEdgeParticleLocations(
                new Location(null, 0.0D, 10.0D, 0.0D),
                new Location(null, 4.0D, 10.0D, 0.0D)
        );

        assertEquals(3, locations.size());
        assertEquals(1.0D, locations.get(0).getX(), 0.0001D);
        assertEquals(2.0D, locations.get(1).getX(), 0.0001D);
        assertEquals(3.0D, locations.get(2).getX(), 0.0001D);
        assertEquals(10.02D, locations.get(1).getY(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 9. スキルツリー表示フォールバック
     * 検証契約: edge粒子数は短いedgeでも最低数を確保し、極端に長いedgeでは上限を超えない。
     */
    @Test
    void bedrockEdgeParticleCountIsBounded() {
        assertEquals(0, SkillTreeVisualizer.bedrockEdgeParticleCount(0.0D));
        assertEquals(3, SkillTreeVisualizer.bedrockEdgeParticleCount(0.5D));
        assertEquals(8, SkillTreeVisualizer.bedrockEdgeParticleCount(100.0D));
    }

    private SkillTreeNodeDefinition node(String name) {
        return new SkillTreeNodeDefinition(
                "1000",
                name,
                Material.NETHER_STAR,
                List.of("Lore"),
                List.of("root"),
                SkillTreePointType.PASSIVE_POINT,
                0,
                List.of()
        );
    }
}
