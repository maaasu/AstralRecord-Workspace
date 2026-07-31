package io.github.maaasu.astralRecord.feature.trainingdummy.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.trainingdummy.model.TrainingDummyDefinition;
import io.github.maaasu.astralRecord.feature.trainingdummy.repository.TrainingDummyRepository;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrainingDummyServiceTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/31-training-dummy/31_1-モデル定義.md
     * 章・見出し: # 31_1-モデル定義 > ## 3. 実行時 Mob
     * 検証契約: 実行時templateをARMOR_STAND、最大HP Integer.MAX_VALUE、ノックバック耐性100%で構築する。
     */
    @Test
    void templateUsesArmorStandFixedHealthAndFullKnockbackResistance() {
        TrainingDummyDefinition definition = definition("template", "world");

        MobTemplate template = TrainingDummyService.template(definition);

        assertEquals(EntityType.ARMOR_STAND, template.entityType());
        assertEquals((double) Integer.MAX_VALUE, template.statValue(StatusType.MAX_HEALTH.name(), 0.0D));
        assertEquals(100.0D, template.statValue(StatusType.KNOCKBACK_RESISTANCE.name(), 0.0D));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/31-training-dummy/31_3-処理契約.md
     * 章・見出し: # 31_3-処理契約 > ## 1. 初期化と停止
     * 検証契約: spawn失敗IDを同じ読込状態では再試行せず、reload後の次tickでだけ再試行する。
     */
    @Test
    void failedSpawnIsNotRetriedUntilConfigurationIsReloaded() {
        World world = server().addSimpleWorld("retry_world");
        TrainingDummyDefinition definition = definition("retry", world.getName());
        MobService mobService = mock(MobService.class);
        TrainingDummyRepository repository = mock(TrainingDummyRepository.class);
        when(repository.loadAll()).thenReturn(List.of(definition));
        when(mobService.spawn(any(MobTemplate.class), any())).thenReturn(null);
        TrainingDummyService service = new TrainingDummyService(
                PluginMock.builder().withPluginName("AstralRecordTest").build(),
                mobService,
                repository,
                noOpChunkTicketGateway()
        );
        service.loadAll();

        service.tick();
        service.tick();

        verify(mobService, times(1)).spawn(any(MobTemplate.class), any());

        service.loadAll();
        service.tick();

        verify(mobService, times(2)).spawn(any(MobTemplate.class), any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/31-training-dummy/31_3-処理契約.md
     * 章・見出し: # 31_3-処理契約 > ## 1. 初期化と停止
     * 検証契約: 配置IDに有効な追跡instanceがある間は後続tickで重複spawnしない。
     */
    @Test
    void trackedInstanceIsNotSpawnedAgainOnFollowingTicks() {
        World world = server().addSimpleWorld("tracked_world");
        TrainingDummyDefinition definition = definition("tracked", world.getName());
        MobTemplate template = TrainingDummyService.template(definition);
        MobInstance instance = new MobInstance(
                UUID.randomUUID(),
                template,
                new Location(world, definition.x(), definition.y(), definition.z())
        );
        MobService mobService = mock(MobService.class);
        TrainingDummyRepository repository = mock(TrainingDummyRepository.class);
        when(repository.loadAll()).thenReturn(List.of(definition));
        when(mobService.spawn(any(MobTemplate.class), any())).thenReturn(instance);
        when(mobService.getInstance(instance.instanceId())).thenReturn(instance);
        TrainingDummyService service = new TrainingDummyService(
                PluginMock.builder().withPluginName("AstralRecordTest").build(),
                mobService,
                repository,
                noOpChunkTicketGateway()
        );
        service.loadAll();

        service.tick();
        service.tick();

        verify(mobService, times(1)).spawn(any(MobTemplate.class), any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/31-training-dummy/31_3-処理契約.md
     * 章・見出し: # 31_3-処理契約 > ## 1. 初期化と停止
     * 検証契約: 同一chunkを共有する複数カカシはplugin chunk ticketを一回だけ追加する。
     */
    @Test
    void sharedDummyChunkAddsOnlyOnePluginTicket() {
        PluginMock plugin = PluginMock.builder().withPluginName("AstralRecordTest").build();
        MobService mobService = mock(MobService.class);
        TrainingDummyRepository repository = mock(TrainingDummyRepository.class);
        TrainingDummyService.ChunkTicketGateway gateway = mock(TrainingDummyService.ChunkTicketGateway.class);
        World world = mock(World.class);
        Chunk chunk = mock(Chunk.class);
        Location first = mock(Location.class);
        Location second = mock(Location.class);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.getX()).thenReturn(4);
        when(chunk.getZ()).thenReturn(-3);
        when(world.getName()).thenReturn("ticket_world");
        when(first.getChunk()).thenReturn(chunk);
        when(second.getChunk()).thenReturn(chunk);
        TrainingDummyService service = new TrainingDummyService(plugin, mobService, repository, gateway);

        assertTrue(service.retainChunkTicket("first", first));
        assertTrue(service.retainChunkTicket("second", second));

        verify(gateway, times(1)).retain(chunk);
    }

    private TrainingDummyService.ChunkTicketGateway noOpChunkTicketGateway() {
        return new TrainingDummyService.ChunkTicketGateway() {
            @Override
            public void retain(Chunk chunk) {}

            @Override
            public void release(World world, int chunkX, int chunkZ) {}
        };
    }

    private TrainingDummyDefinition definition(String id, String worldName) {
        return new TrainingDummyDefinition(
                id, worldName, 0.0D, 64.0D, 0.0D, 0.0F,
                100.0D, 0.0D, 0.0D, false, 10.0D, 40L
        );
    }
}
