package io.github.maaasu.astralRecord.feature.trainingdummy.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.trainingdummy.model.TrainingDummyDefinition;
import io.github.maaasu.astralRecord.feature.trainingdummy.repository.TrainingDummyRepository;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrainingDummyServiceTest extends MockBukkitTestBase {

    @Test
    void templateUsesArmorStandFixedHealthAndFullKnockbackResistance() {
        TrainingDummyDefinition definition = definition("template", "world");

        MobTemplate template = TrainingDummyService.template(definition);

        assertEquals(EntityType.ARMOR_STAND, template.entityType());
        assertEquals((double) Integer.MAX_VALUE, template.statValue(StatusType.MAX_HEALTH.name(), 0.0D));
        assertEquals(100.0D, template.statValue(StatusType.KNOCKBACK_RESISTANCE.name(), 0.0D));
    }

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
                repository
        );
        service.loadAll();

        service.tick();
        service.tick();

        verify(mobService, times(1)).spawn(any(MobTemplate.class), any());

        service.loadAll();
        service.tick();

        verify(mobService, times(2)).spawn(any(MobTemplate.class), any());
    }

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
                repository
        );
        service.loadAll();

        service.tick();
        service.tick();

        verify(mobService, times(1)).spawn(any(MobTemplate.class), any());
    }

    private TrainingDummyDefinition definition(String id, String worldName) {
        return new TrainingDummyDefinition(
                id, worldName, 0.0D, 64.0D, 0.0D, 0.0F,
                100.0D, 0.0D, 0.0D, false, 10.0D, 40L
        );
    }
}
