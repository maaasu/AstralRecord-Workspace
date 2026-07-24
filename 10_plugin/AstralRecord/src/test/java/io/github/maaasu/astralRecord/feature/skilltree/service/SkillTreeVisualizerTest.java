package io.github.maaasu.astralRecord.feature.skilltree.service;

import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePointType;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillTreeVisualizerTest {

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
