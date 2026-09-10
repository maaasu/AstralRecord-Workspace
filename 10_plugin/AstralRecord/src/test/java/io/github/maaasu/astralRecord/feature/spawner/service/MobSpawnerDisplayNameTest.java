package io.github.maaasu.astralRecord.feature.spawner.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionsConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobLevelProfile;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.model.MobVariantConfig;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerRegionService;
import io.github.maaasu.astralRecord.feature.spawner.model.MobSpawnerDefinition;
import io.github.maaasu.astralRecord.feature.spawner.model.MobSpawnerEntry;
import io.github.maaasu.astralRecord.feature.spawner.repository.MobSpawnerDefinitionRepository;
import io.github.maaasu.astralRecord.feature.spawner.repository.MobSpawnerLocationRepository;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.entity.EntityType;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MobSpawnerDisplayNameTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 7.5. MobSpawnerService メソッド仕様 > ### Mob スポナー表示名
     * 検証契約: 指定レベルを持つスポナーの管理表示名は、対象Mobの指定レベルに対応する日本語表示名を使う。
     */
    @Test
    void usesJapaneseMobDisplayNameForSpawner() {
        MobService mobService = mock(MobService.class);
        MobTemplate template = new MobTemplate(
                1,
                "midgard_grassboar",
                MobCategory.ENEMY,
                "&7グラスボア（基準）",
                null,
                3,
                EntityType.ZOMBIE,
                false,
                null,
                List.of(),
                List.of(),
                null,
                MobEquipmentConfig.EMPTY,
                List.of(),
                MobShieldConfig.EMPTY,
                MobIdleConfig.defaults(),
                false,
                MobInteractionsConfig.EMPTY,
                null,
                null,
                null
        ).withLevelProfiles(List.of(new MobLevelProfile(
                3,
                "&aミズガルズ・グラスボア Lv.3",
                null,
                false,
                null,
                List.of(),
                List.of(),
                null,
                MobVariantConfig.DEFAULT,
                MobEquipmentConfig.EMPTY,
                List.of(),
                MobShieldConfig.EMPTY,
                MobIdleConfig.defaults(),
                false,
                MobInteractionsConfig.EMPTY,
                null,
                null,
                null,
                null
        )));
        when(mobService.findTemplate("midgard_grassboar")).thenReturn(template);

        MobSpawnerService service = service(mobService);
        service.replaceMasterDataSnapshot(new MobSpawnerService.MasterDataSnapshot(
                List.of(definition(new MobSpawnerEntry("midgard_grassboar", 3, 2))),
                List.of()
        ));

        assertEquals("§aミズガルズ・グラスボア Lv.3", service.getSpawnerDisplayName("grassboar_spawner"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 7.5. MobSpawnerService メソッド仕様 > ### Mob スポナー表示名
     * 検証契約: Mob定義を解決できないスポナーの管理表示名は、内部IDではなく汎用表示へ置き換える。
     */
    @Test
    void doesNotExposeUnknownMobIdInSpawnerDisplay() {
        MobSpawnerService service = service(mock(MobService.class));
        service.replaceMasterDataSnapshot(new MobSpawnerService.MasterDataSnapshot(
                List.of(definition(new MobSpawnerEntry("missing_mob", 1))),
                List.of()
        ));

        assertEquals("未登録のモブ", service.getSpawnerDisplayName("grassboar_spawner"));
    }

    private MobSpawnerService service(MobService mobService) {
        return new MobSpawnerService(
                PluginMock.builder().withPluginName("AstralRecordTest").build(),
                mobService,
                mock(PlayerRegionService.class),
                mock(MobSpawnerDefinitionRepository.class),
                mock(MobSpawnerLocationRepository.class)
        );
    }

    private MobSpawnerDefinition definition(MobSpawnerEntry entry) {
        return new MobSpawnerDefinition(
                "grassboar_spawner",
                null,
                16.0D,
                List.of(entry),
                List.of(),
                Material.SPAWNER,
                20L,
                10,
                20,
                2
        );
    }
}
