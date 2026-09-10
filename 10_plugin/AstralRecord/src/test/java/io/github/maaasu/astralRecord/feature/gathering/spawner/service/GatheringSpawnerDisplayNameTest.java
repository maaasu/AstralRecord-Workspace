package io.github.maaasu.astralRecord.feature.gathering.spawner.service;

import io.github.maaasu.astralRecord.feature.gathering.model.GatheringDefinition;
import io.github.maaasu.astralRecord.feature.gathering.service.GatheringService;
import io.github.maaasu.astralRecord.feature.gathering.spawner.model.GatheringSpawnerDefinition;
import io.github.maaasu.astralRecord.feature.gathering.spawner.model.GatheringSpawnerEntry;
import io.github.maaasu.astralRecord.feature.gathering.spawner.repository.GatheringSpawnerDefinitionRepository;
import io.github.maaasu.astralRecord.feature.gathering.spawner.repository.GatheringSpawnerLocationRepository;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropConfig;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatheringSpawnerDisplayNameTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 8. GatheringSpawnerService メソッド仕様 > ### 設置 item・配置管理
     * 検証契約: 採集スポナーの管理表示名は、対象採集定義の日本語表示名を使う。
     */
    @Test
    void usesJapaneseGatheringDisplayNameForSpawner() {
        GatheringService gatheringService = mock(GatheringService.class);
        GatheringDefinition definition = new GatheringDefinition(
                1,
                "box_cave_copper_vein",
                "MINING",
                "&6銅鉱脈",
                100,
                Material.COAL_ORE,
                new org.joml.Vector3f(1.0F, 1.0F, 1.0F),
                List.of(),
                new MobDropConfig(0, null, List.of(), null),
                GatheringDefinition.GatheringSoundConfig.empty()
        );
        when(gatheringService.findDefinition("box_cave_copper_vein")).thenReturn(definition);

        GatheringSpawnerService service = service(gatheringService);
        service.replaceMasterDataSnapshot(new GatheringSpawnerService.MasterDataSnapshot(
                List.of(spawnerDefinition(new GatheringSpawnerEntry("box_cave_copper_vein", 2))),
                List.of()
        ));

        assertEquals("§6銅鉱脈", service.getSpawnerDisplayName("mining_spawner"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 8. GatheringSpawnerService メソッド仕様 > ### 設置 item・配置管理
     * 検証契約: 採集定義を解決できないスポナーの管理表示名は、内部IDではなく汎用表示へ置き換える。
     */
    @Test
    void doesNotExposeUnknownGatheringIdInSpawnerDisplay() {
        GatheringService gatheringService = mock(GatheringService.class);
        GatheringSpawnerService service = service(gatheringService);
        service.replaceMasterDataSnapshot(new GatheringSpawnerService.MasterDataSnapshot(
                List.of(spawnerDefinition(new GatheringSpawnerEntry("missing_gathering", 1))),
                List.of()
        ));

        assertEquals("未登録の採集物", service.getSpawnerDisplayName("mining_spawner"));
    }

    private GatheringSpawnerService service(GatheringService gatheringService) {
        return new GatheringSpawnerService(
                PluginMock.builder().withPluginName("AstralRecordTest").build(),
                gatheringService,
                mock(GatheringSpawnerDefinitionRepository.class),
                mock(GatheringSpawnerLocationRepository.class)
        );
    }

    private GatheringSpawnerDefinition spawnerDefinition(GatheringSpawnerEntry entry) {
        return new GatheringSpawnerDefinition(
                "mining_spawner",
                3.0D,
                List.of(entry),
                List.of(),
                Material.SPAWNER,
                20L,
                16,
                24,
                4,
                List.of(Material.STONE)
        );
    }
}
