package io.github.maaasu.astralRecord.feature.world.repository;

import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockStatic;

class WorldRepositoryTest {

    @Test
    void summaryListRowsAreHydratedThroughDetailEndpoint() {
        WorldMasterData skillTree = world("skill_tree", WorldType.HUB);
        WorldMasterData base = world("starlit_nox", WorldType.BASE);
        Map<String, WorldMasterData> details = new LinkedHashMap<>();
        details.put("skill_tree", skillTree);
        details.put("starlit_nox", base);
        WorldRepository repository = new StubWorldRepository(details);

        var result = repository.resolveListPayload(JsonParser.parseString("""
                [
                  {"id":"skill_tree","displayName":"Skill Tree","autoLoad":true},
                  {"id":"starlit_nox","displayName":"Base","autoLoad":true}
                ]
                """).getAsJsonArray());

        assertEquals(2, result.size());
        assertSame(skillTree, result.get(0));
        assertSame(base, result.get(1));
    }

    @Test
    void missingDetailFailsInsteadOfPublishingIncompleteWorld() {
        WorldRepository repository = new StubWorldRepository(Map.of());

        var summaries = JsonParser.parseString("[{\"id\":\"skill_tree\",\"autoLoad\":true}]")
                .getAsJsonArray();

        assertThrows(IllegalStateException.class, () -> repository.resolveListPayload(summaries));
    }

    @Test
    void detailedListRowsRemainBackwardCompatible() {
        WorldRepository repository = new StubWorldRepository(Map.of());
        var detailed = JsonParser.parseString("""
                [{
                  "schemaVersion":1,
                  "id":"skill_tree",
                  "displayName":"Skill Tree",
                  "worldType":"HUB",
                  "baseWorldPath":"plugins/AstralRecord/worlds/hub/skill_tree",
                  "instanceRootPath":"plugins/AstralRecord/_world_instances/skill_tree",
                  "autoLoad":true,
                  "instanceEnabled":false,
                  "maxPlayers":8,
                  "spawnLocation":{"x":1000.5,"y":68.0,"z":1000.5,"yaw":0.0,"pitch":90.0},
                  "description":"Skill Tree"
                }]
                """).getAsJsonArray();

        try (MockedStatic<Logger> logger = mockStatic(Logger.class)) {
            var result = repository.resolveListPayload(detailed);
            assertEquals(1, result.size());
            assertEquals("plugins/AstralRecord/worlds/hub/skill_tree", result.getFirst().baseWorldPath());
        }
    }

    private static final class StubWorldRepository extends WorldRepository {
        private final Map<String, WorldMasterData> details;

        private StubWorldRepository(Map<String, WorldMasterData> details) {
            this.details = details;
        }

        @Override
        public WorldMasterData findById(String worldId) {
            return details.get(worldId);
        }
    }

    private static WorldMasterData world(String id, WorldType type) {
        return new WorldMasterData(
                1,
                id,
                id,
                type,
                "plugins/AstralRecord/worlds/" + id,
                "plugins/AstralRecord/_world_instances/" + id,
                true,
                false,
                8,
                false,
                false,
                false,
                true,
                WorldSpawnLocation.defaultLocation(),
                id,
                null,
                null,
                null
        );
    }
}
