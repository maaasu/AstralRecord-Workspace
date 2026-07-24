package io.github.maaasu.astralRecord.feature.skilltree.repository;

import io.github.maaasu.astralRecord.feature.skilltree.config.SkillTreePluginConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillTreeStructureRepositoryTest {
    @TempDir
    Path filebaseRoot;

    @Test
    void loadsRelativeCoordinatesAndCanonicalUndirectedEdges() throws IOException {
        writeStructure(structureJson(
                "1000",
                """
                        [
                          {"nodeId": "1000", "x": 0, "y": 0, "z": 0},
                          {"nodeId": "1001", "x": -2, "y": 3, "z": 4}
                        ]
                        """,
                "[{\"sourceNodeId\": \"1001\", \"targetNodeId\": \"1000\"}]"
        ));

        var snapshot = new SkillTreeStructureRepository(filebaseRoot.toFile()).load(config());

        assertEquals("1000", snapshot.rootNodeId());
        assertEquals(100, snapshot.positions().getFirst().x());
        assertEquals(64, snapshot.positions().getFirst().y());
        assertEquals(-20, snapshot.positions().getFirst().z());
        assertEquals(98, snapshot.positions().get(1).x());
        assertEquals(67, snapshot.positions().get(1).y());
        assertEquals(-16, snapshot.positions().get(1).z());
        assertEquals("1000", snapshot.edges().getFirst().sourceNodeId());
        assertEquals("1001", snapshot.edges().getFirst().targetNodeId());
    }

    @Test
    void rejectsDuplicateCoordinates() throws IOException {
        assertInvalid(
                """
                        [
                          {"nodeId": "1000", "x": 0, "y": 0, "z": 0},
                          {"nodeId": "1001", "x": 0, "y": 0, "z": 0}
                        ]
                        """,
                "[{\"sourceNodeId\": \"1000\", \"targetNodeId\": \"1001\"}]",
                "duplicate node coordinate"
        );
    }

    @Test
    void rejectsSelfConnections() throws IOException {
        assertInvalid(
                "[{\"nodeId\": \"1000\", \"x\": 0, \"y\": 0, \"z\": 0}]",
                "[{\"sourceNodeId\": \"1000\", \"targetNodeId\": \"1000\"}]",
                "must not connect a node to itself"
        );
    }

    @Test
    void rejectsDuplicateUndirectedEdges() throws IOException {
        assertInvalid(
                connectedNodes(),
                """
                        [
                          {"sourceNodeId": "1000", "targetNodeId": "1001"},
                          {"sourceNodeId": "1001", "targetNodeId": "1000"}
                        ]
                        """,
                "duplicate undirected edge"
        );
    }

    @Test
    void rejectsReferencesToUnplacedNodes() throws IOException {
        assertInvalid(
                "[{\"nodeId\": \"1000\", \"x\": 0, \"y\": 0, \"z\": 0}]",
                "[{\"sourceNodeId\": \"1000\", \"targetNodeId\": \"9999\"}]",
                "references an unplaced node"
        );
    }

    @Test
    void rejectsNodesUnreachableFromRoot() throws IOException {
        assertInvalid(
                """
                        [
                          {"nodeId": "1000", "x": 0, "y": 0, "z": 0},
                          {"nodeId": "1001", "x": 1, "y": 0, "z": 0},
                          {"nodeId": "1002", "x": 2, "y": 0, "z": 0}
                        ]
                        """,
                "[{\"sourceNodeId\": \"1000\", \"targetNodeId\": \"1001\"}]",
                "unreachable from root"
        );
    }

    private void assertInvalid(String nodes, String edges, String message) throws IOException {
        writeStructure(structureJson("1000", nodes, edges));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new SkillTreeStructureRepository(filebaseRoot.toFile()).load(config())
        );

        assertTrue(error.getMessage().contains(message));
    }

    private String connectedNodes() {
        return """
                [
                  {"nodeId": "1000", "x": 0, "y": 0, "z": 0},
                  {"nodeId": "1001", "x": 1, "y": 0, "z": 0}
                ]
                """;
    }

    private SkillTreePluginConfig config() {
        return new SkillTreePluginConfig(
                "skill_tree",
                "starter",
                new SkillTreePluginConfig.Center(100, 64, -20)
        );
    }

    private void writeStructure(String json) throws IOException {
        Path directory = filebaseRoot.resolve("35.features.skilltree").resolve("structures");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("starter.json"), json, StandardCharsets.UTF_8);
    }

    private String structureJson(String rootNodeId, String nodes, String edges) {
        return """
                {
                  "$schema": "../schemas/structure.v1.schema.json",
                  "schemaVersion": 1,
                  "structureId": "starter",
                  "name": "Starter",
                  "rootNodeId": "%s",
                  "nodes": %s,
                  "edges": %s
                }
                """.formatted(rootNodeId, nodes, edges);
    }
}
