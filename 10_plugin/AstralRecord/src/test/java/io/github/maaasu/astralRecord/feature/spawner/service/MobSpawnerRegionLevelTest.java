package io.github.maaasu.astralRecord.feature.spawner.service;

import io.github.maaasu.astralRecord.feature.spawner.model.MobSpawnerDefinition;
import io.github.maaasu.astralRecord.feature.spawner.model.MobSpawnerEntry;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MobSpawnerRegionLevelTest {

    @Test
    void calculatesWeightedAverageAcrossSpawnerDefinitionsInSameRegion() {
        var definitions = List.of(
                definition("grassland-1", "風待ち草原", List.of(
                        new MobSpawnerEntry("slime", 3),
                        new MobSpawnerEntry("wolf", 1)
                )),
                definition("grassland-2", "風待ち草原", List.of(
                        new MobSpawnerEntry("goblin", 1)
                )),
                definition("unknown", "未開地域", List.of(
                        new MobSpawnerEntry("missing", 5)
                )),
                definition("regionless", null, List.of(
                        new MobSpawnerEntry("wolf", 10)
                ))
        );
        Map<String, Integer> mobLevels = Map.of(
                "slime", 10,
                "wolf", 30,
                "goblin", 20
        );

        Map<String, Integer> regionLevels = MobSpawnerService.calculateRegionLevels(
                definitions,
                mobLevels::get
        );

        assertEquals(16, regionLevels.get("風待ち草原"));
        assertEquals(0, regionLevels.get("未開地域"));
        assertFalse(regionLevels.containsKey("regionless"));
    }

    private MobSpawnerDefinition definition(String id, String region, List<MobSpawnerEntry> entries) {
        return new MobSpawnerDefinition(
                id,
                region,
                16.0D,
                entries,
                List.of(),
                Material.SPAWNER,
                20L,
                10,
                20,
                2
        );
    }
}
