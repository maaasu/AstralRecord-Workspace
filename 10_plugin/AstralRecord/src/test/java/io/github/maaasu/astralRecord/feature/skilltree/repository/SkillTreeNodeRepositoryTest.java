package io.github.maaasu.astralRecord.feature.skilltree.repository;

import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeSkillEffect;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeStatusEffect;
import io.github.maaasu.astralRecord.feature.status.model.StatusModifierType;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillTreeNodeRepositoryTest {
    @TempDir
    Path filebaseRoot;

    @Test
    void loadsSchemaV1TypedEffects() throws IOException {
        writeNode("1000.json", nodeJson(
                "1000",
                """
                        [
                          {"type": "skill", "skillId": "passive-test"},
                          {"type": "status", "status": "ATTACK", "modifierType": "FLAT", "value": 5}
                        ]
                        """
        ));

        var nodes = repository().findAll();

        assertEquals(1, nodes.size());
        assertEquals("1000", nodes.getFirst().nodeId());
        assertEquals(Material.NETHER_STAR, nodes.getFirst().icon());
        SkillTreeSkillEffect skill = assertInstanceOf(
                SkillTreeSkillEffect.class,
                nodes.getFirst().effects().getFirst()
        );
        assertEquals("passive-test", skill.skillId());
        SkillTreeStatusEffect status = assertInstanceOf(
                SkillTreeStatusEffect.class,
                nodes.getFirst().effects().get(1)
        );
        assertEquals(StatusType.ATTACK, status.statusType());
        assertEquals(StatusModifierType.FLAT, status.modifierType());
        assertEquals(5.0D, status.value());
    }

    @Test
    void rejectsDuplicateNodeIdsAcrossFiles() throws IOException {
        writeNode("first.json", nodeJson("1000", "[]"));
        writeNode("second.json", nodeJson("1000", "[]"));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> repository().findAll()
        );

        assertTrue(error.getMessage().contains("duplicate nodeId"));
    }

    @Test
    void rejectsLegacyPropertiesAndLeadingZeroIds() throws IOException {
        writeNode("legacy.json", nodeJson("01000", "[]").replace(
                "\"name\": \"Root\"",
                "\"positionId\": \"root\",\n  \"name\": \"Root\""
        ));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> repository().findAll()
        );

        assertTrue(error.getMessage().contains("positionId"));
    }

    @Test
    void rejectsNodeIdWithLeadingZero() throws IOException {
        writeNode("invalid-id.json", nodeJson("01000", "[]"));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> repository().findAll()
        );

        assertTrue(error.getMessage().contains("non-zero-leading"));
    }

    private void writeNode(String fileName, String json) throws IOException {
        Path directory = filebaseRoot.resolve("35.features.skilltree").resolve("nodes");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(fileName), json, StandardCharsets.UTF_8);
    }

    private SkillTreeNodeRepository repository() {
        return new SkillTreeNodeRepository(
                filebaseRoot.toFile(),
                material -> material != Material.AIR
        );
    }

    private String nodeJson(String nodeId, String effects) {
        return """
                {
                  "$schema": "../schemas/node.v1.schema.json",
                  "schemaVersion": 1,
                  "nodeId": "%s",
                  "name": "Root",
                  "icon": "NETHER_STAR",
                  "lore": ["Lore"],
                  "tags": ["root"],
                  "pointType": "PP",
                  "pointCost": 0,
                  "effects": %s
                }
                """.formatted(nodeId, effects);
    }
}
