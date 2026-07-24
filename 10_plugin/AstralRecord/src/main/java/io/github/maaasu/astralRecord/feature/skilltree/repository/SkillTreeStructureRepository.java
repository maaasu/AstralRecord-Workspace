package io.github.maaasu.astralRecord.feature.skilltree.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.feature.skilltree.config.SkillTreePluginConfig;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeEdge;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePosition;
import io.github.maaasu.astralRecord.infrastructure.database.file.FileDatabaseManager;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** filebase の読取専用スキルツリー構造JSONリポジトリです。 */
public class SkillTreeStructureRepository {
    private static final String RELATIVE_PATH = "35.features.skilltree" + File.separator + "structures";
    private static final String SCHEMA_REFERENCE = "../schemas/structure.v1.schema.json";
    private static final Set<String> STRUCTURE_KEYS = Set.of(
            "$schema", "schemaVersion", "structureId", "name", "rootNodeId", "nodes", "edges"
    );
    private static final Set<String> NODE_KEYS = Set.of("nodeId", "x", "y", "z");
    private static final Set<String> EDGE_KEYS = Set.of("sourceNodeId", "targetNodeId");

    private final File fixedRootDirectory;

    public SkillTreeStructureRepository() {
        this.fixedRootDirectory = null;
    }

    SkillTreeStructureRepository(@NotNull File rootDirectory) {
        this.fixedRootDirectory = rootDirectory;
    }

    /**
     * 選択された構造JSONを読み込み、相対座標を絶対座標へ変換します。
     *
     * @param config 構造選択と配置中心のplugin設定
     * @return 検証済み構造スナップショット
     * @throws IllegalStateException JSON、座標、参照または到達性が不正な場合
     */
    public @NotNull StructureSnapshot load(@NotNull SkillTreePluginConfig config) {
        File root = fixedRootDirectory == null
                ? FileDatabaseManager.getInstance().getRootDirectory()
                : fixedRootDirectory;
        if (root == null) {
            throw new IllegalStateException("filebase root is not configured");
        }
        File file = new File(new File(root, RELATIVE_PATH), config.structureId() + ".json");
        if (!file.isFile()) {
            throw new IllegalStateException("Skilltree structure is missing: " + file.getAbsolutePath());
        }
        JsonObject structure = SkillTreeJsonReader.readObject(file);
        SkillTreeJsonReader.requireOnlyKeys(structure, STRUCTURE_KEYS, file, "structure");
        String schemaReference = SkillTreeJsonReader.requiredString(structure, "$schema", file, "structure");
        if (!SCHEMA_REFERENCE.equals(schemaReference)) {
            throw SkillTreeJsonReader.invalid(file, "unsupported structure.$schema '" + schemaReference + "'");
        }
        int schemaVersion = SkillTreeJsonReader.requiredInt(structure, "schemaVersion", file, "structure");
        if (schemaVersion != 1) {
            throw SkillTreeJsonReader.invalid(file, "unsupported schemaVersion " + schemaVersion);
        }
        String structureId = SkillTreeJsonReader.requiredString(structure, "structureId", file, "structure").trim();
        if (!structureId.matches("[a-z0-9][a-z0-9_-]*")) {
            throw SkillTreeJsonReader.invalid(file, "structure.structureId has an invalid format");
        }
        if (!config.structureId().equals(structureId)) {
            throw SkillTreeJsonReader.invalid(file, "structureId does not match plugin config");
        }
        SkillTreeJsonReader.requiredString(structure, "name", file, "structure");
        String rootNodeId = digitId(structure, "rootNodeId", file, "structure");
        Map<String, SkillTreePosition> positions = parsePositions(structure, file, config);
        if (!positions.containsKey(rootNodeId)) {
            throw SkillTreeJsonReader.invalid(file, "rootNodeId is not placed: " + rootNodeId);
        }
        Map<String, SkillTreeEdge> edges = parseEdges(structure, file, positions.keySet());
        validateReachability(file, rootNodeId, positions.keySet(), edges.values());
        return new StructureSnapshot(
                structureId,
                rootNodeId,
                List.copyOf(positions.values()),
                List.copyOf(edges.values())
        );
    }

    private Map<String, SkillTreePosition> parsePositions(
            JsonObject structure,
            File file,
            SkillTreePluginConfig config
    ) {
        JsonArray nodes = SkillTreeJsonReader.requiredArray(structure, "nodes", file, "structure");
        Map<String, SkillTreePosition> positions = new LinkedHashMap<>();
        Set<String> coordinates = new LinkedHashSet<>();
        for (int index = 0; index < nodes.size(); index++) {
            String path = "structure.nodes[" + index + "]";
            JsonObject row = SkillTreeJsonReader.requiredObject(nodes.get(index), file, path);
            SkillTreeJsonReader.requireOnlyKeys(row, NODE_KEYS, file, path);
            String nodeId = digitId(row, "nodeId", file, path);
            int relativeX = SkillTreeJsonReader.requiredInt(row, "x", file, path);
            int relativeY = SkillTreeJsonReader.requiredInt(row, "y", file, path);
            int relativeZ = SkillTreeJsonReader.requiredInt(row, "z", file, path);
            int x;
            int y;
            int z;
            try {
                x = Math.addExact(config.center().x(), relativeX);
                y = Math.addExact(config.center().y(), relativeY);
                z = Math.addExact(config.center().z(), relativeZ);
            } catch (ArithmeticException e) {
                throw SkillTreeJsonReader.invalid(file, path + " overflows absolute coordinates");
            }
            SkillTreePosition position = new SkillTreePosition(nodeId, config.worldName(), x, y, z);
            if (positions.putIfAbsent(nodeId, position) != null) {
                throw SkillTreeJsonReader.invalid(file, "duplicate placed nodeId '" + nodeId + "'");
            }
            if (!coordinates.add(position.locationKey())) {
                throw SkillTreeJsonReader.invalid(file, "duplicate node coordinate '" + position.locationKey() + "'");
            }
        }
        return positions;
    }

    private Map<String, SkillTreeEdge> parseEdges(
            JsonObject structure,
            File file,
            Set<String> placedNodeIds
    ) {
        JsonArray edgeRows = SkillTreeJsonReader.requiredArray(structure, "edges", file, "structure");
        Map<String, SkillTreeEdge> edges = new LinkedHashMap<>();
        for (int index = 0; index < edgeRows.size(); index++) {
            String path = "structure.edges[" + index + "]";
            JsonObject row = SkillTreeJsonReader.requiredObject(edgeRows.get(index), file, path);
            SkillTreeJsonReader.requireOnlyKeys(row, EDGE_KEYS, file, path);
            String source = digitId(row, "sourceNodeId", file, path);
            String target = digitId(row, "targetNodeId", file, path);
            if (source.equals(target)) {
                throw SkillTreeJsonReader.invalid(file, path + " must not connect a node to itself");
            }
            if (!placedNodeIds.contains(source) || !placedNodeIds.contains(target)) {
                throw SkillTreeJsonReader.invalid(file, path + " references an unplaced node");
            }
            SkillTreeEdge edge = new SkillTreeEdge(source, target);
            if (edges.putIfAbsent(edge.key(), edge) != null) {
                throw SkillTreeJsonReader.invalid(file, "duplicate undirected edge '" + edge.key() + "'");
            }
        }
        return edges;
    }

    private void validateReachability(
            File file,
            String rootNodeId,
            Set<String> placedNodeIds,
            Iterable<SkillTreeEdge> edges
    ) {
        Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        for (String nodeId : placedNodeIds) {
            adjacency.put(nodeId, new LinkedHashSet<>());
        }
        for (SkillTreeEdge edge : edges) {
            adjacency.get(edge.sourceNodeId()).add(edge.targetNodeId());
            adjacency.get(edge.targetNodeId()).add(edge.sourceNodeId());
        }
        Set<String> reached = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(rootNodeId);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (reached.add(current)) {
                queue.addAll(adjacency.getOrDefault(current, Set.of()));
            }
        }
        if (!reached.containsAll(placedNodeIds)) {
            Set<String> unreachable = new LinkedHashSet<>(placedNodeIds);
            unreachable.removeAll(reached);
            throw SkillTreeJsonReader.invalid(file, "nodes unreachable from root: " + unreachable);
        }
    }

    private @NotNull String digitId(JsonObject object, String key, File file, String path) {
        String value = SkillTreeJsonReader.requiredString(object, key, file, path).trim();
        if (value.length() > 100 || !value.matches("0|[1-9][0-9]*")) {
            throw SkillTreeJsonReader.invalid(
                    file,
                    path + "." + key + " must be '0' or a non-zero-leading digit string of at most 100 characters"
            );
        }
        return value;
    }

    /** 読込済みスキルツリー構造です。 */
    public record StructureSnapshot(
            @NotNull String structureId,
            @NotNull String rootNodeId,
            @NotNull List<SkillTreePosition> positions,
            @NotNull List<SkillTreeEdge> edges
    ) {
    }
}
