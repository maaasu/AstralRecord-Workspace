package io.github.maaasu.astralRecord.feature.skilltree.service;

import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePointType;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

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
