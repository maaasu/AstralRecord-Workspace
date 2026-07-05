package io.github.maaasu.astralRecord.feature.mob.event;

import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionsConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.service.MobVanillaEffectProtectionService;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MobVanillaDamageBlockEventHandlerTest {

    @Test
    void cancelsAllVanillaDamageForEnemyMobAndClearsFireVisual() {
        MobService mobService = mock(MobService.class);
        MobVanillaEffectProtectionService protectionService = new MobVanillaEffectProtectionService();
        MobVanillaDamageBlockEventHandler handler = new MobVanillaDamageBlockEventHandler(mobService, protectionService);
        Entity entity = managedEntity(mobService, MobCategory.ENEMY);
        EntityDamageEvent event = mock(EntityDamageEvent.class);

        when(entity.getFireTicks()).thenReturn(80);
        when(event.getEntity()).thenReturn(entity);

        handler.onEntityDamage(event);

        verify(event).setDamage(0.0D);
        verify(event).setCancelled(true);
        verify(entity).setFireTicks(0);
    }

    @Test
    void ignoresNpcMobDamage() {
        MobService mobService = mock(MobService.class);
        MobVanillaEffectProtectionService protectionService = new MobVanillaEffectProtectionService();
        MobVanillaDamageBlockEventHandler handler = new MobVanillaDamageBlockEventHandler(mobService, protectionService);
        Entity entity = managedEntity(mobService, MobCategory.NPC);
        EntityDamageEvent event = mock(EntityDamageEvent.class);

        when(event.getEntity()).thenReturn(entity);

        handler.onEntityDamage(event);

        verify(event, never()).setDamage(0.0D);
        verify(event, never()).setCancelled(true);
    }

    @Test
    void cancelsVanillaCombustionForBossMob() {
        MobService mobService = mock(MobService.class);
        MobVanillaEffectProtectionService protectionService = new MobVanillaEffectProtectionService();
        MobVanillaDamageBlockEventHandler handler = new MobVanillaDamageBlockEventHandler(mobService, protectionService);
        Entity entity = managedEntity(mobService, MobCategory.BOSS);
        EntityCombustEvent event = mock(EntityCombustEvent.class);

        when(entity.getFireTicks()).thenReturn(40);
        when(event.getEntity()).thenReturn(entity);

        handler.onEntityCombust(event);

        verify(event).setCancelled(true);
        verify(entity).setFireTicks(0);
    }

    @Test
    void preservesPluginGrantedFireVisual() {
        MobService mobService = mock(MobService.class);
        MobVanillaEffectProtectionService protectionService = new MobVanillaEffectProtectionService();
        MobVanillaDamageBlockEventHandler handler = new MobVanillaDamageBlockEventHandler(mobService, protectionService);
        Entity entity = managedEntity(mobService, MobCategory.ENEMY);
        EntityDamageEvent damageEvent = mock(EntityDamageEvent.class);
        EntityCombustEvent combustEvent = mock(EntityCombustEvent.class);

        protectionService.applyPluginFireTicks(entity, 100);
        clearInvocations(entity);
        when(entity.getFireTicks()).thenReturn(100);
        when(damageEvent.getEntity()).thenReturn(entity);
        when(combustEvent.getEntity()).thenReturn(entity);

        handler.onEntityCombust(combustEvent);
        handler.onEntityDamage(damageEvent);

        verify(combustEvent).setCancelled(true);
        verify(entity, never()).setFireTicks(0);
        verify(damageEvent).setDamage(0.0D);
        verify(damageEvent).setCancelled(true);
    }

    private Entity managedEntity(MobService mobService, MobCategory category) {
        UUID entityId = UUID.randomUUID();
        Entity entity = mock(Entity.class);
        MobTemplate template = new MobTemplate(
                1,
                "test_" + category.name().toLowerCase(),
                category,
                "Test",
                null,
                1,
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
        );
        MobInstance instance = new MobInstance(UUID.randomUUID(), template, new Location(null, 0.0D, 0.0D, 0.0D));

        when(entity.getUniqueId()).thenReturn(entityId);
        when(entity.getName()).thenReturn("managed-mob");
        when(mobService.getInstanceByEntity(entityId)).thenReturn(instance);
        return entity;
    }
}
