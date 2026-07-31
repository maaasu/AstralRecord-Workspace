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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-リポジトリ.md
     * 章・見出し: # 13_3-リポジトリ > ## 7. skill tree structure 読込
     * 検証契約: 相対座標へcenterを加算し無向edge両端をcanonical順へ正規化する。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-リポジトリ.md
     * 章・見出し: # 13_3-リポジトリ > ## 7. skill tree structure 読込
     * 検証契約: 重複node座標をstructure load失敗にする。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-リポジトリ.md
     * 章・見出し: # 13_3-リポジトリ > ## 7. skill tree structure 読込
     * 検証契約: node自身へ接続するself edgeを拒否する。
     */
    @Test
    void rejectsSelfConnections() throws IOException {
        assertInvalid(
                "[{\"nodeId\": \"1000\", \"x\": 0, \"y\": 0, \"z\": 0}]",
                "[{\"sourceNodeId\": \"1000\", \"targetNodeId\": \"1000\"}]",
                "must not connect a node to itself"
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-リポジトリ.md
     * 章・見出し: # 13_3-リポジトリ > ## 7. skill tree structure 読込
     * 検証契約: 向きだけ違うものを含む同一無向edge重複を拒否する。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-リポジトリ.md
     * 章・見出し: # 13_3-リポジトリ > ## 7. skill tree structure 読込
     * 検証契約: 配置されていないnodeを参照するedgeを拒否する。
     */
    @Test
    void rejectsReferencesToUnplacedNodes() throws IOException {
        assertInvalid(
                "[{\"nodeId\": \"1000\", \"x\": 0, \"y\": 0, \"z\": 0}]",
                "[{\"sourceNodeId\": \"1000\", \"targetNodeId\": \"9999\"}]",
                "references an unplaced node"
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-リポジトリ.md
     * 章・見出し: # 13_3-リポジトリ > ## 7. skill tree structure 読込
     * 検証契約: rootから到達不能な配置nodeを拒否する。
     */
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
