package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.IdleBehavior;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.model.MobVariantConfig;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class MobEntityControllerTest extends MockBukkitTestBase {

    @Test
    void holdPositionKeepsAnchorWhilePreservingLookDirection() {
        var world = server().addSimpleWorld("npc_world");
        PluginMock plugin = PluginMock.builder()
                .withPluginName("AstralRecordTest")
                .withPluginVersion("1.0.0")
                .build();
        Location anchor = new Location(world, 0.0D, 64.0D, 0.0D);
        MobInstance instance = new MobInstance(
                UUID.randomUUID(),
                new MobTemplate(
                        1,
                        "npc_shopkeeper",
                        MobCategory.NPC,
                        "Shopkeeper",
                        null,
                        1,
                        EntityType.ZOMBIE,
                        false,
                        null,
                        List.of(),
                        List.of(),
                        null,
                        null,
                        List.of(),
                        MobShieldConfig.EMPTY,
                        new MobIdleConfig(IdleBehavior.STATIONARY, 0.0D, 1.0D),
                        true,
                        null,
                        null,
                        null,
                        null
                ),
                anchor
        );
        Location drifted = new Location(world, 1.25D, 65.0D, -0.5D, 47.0F, 12.0F);
        AtomicReference<Location> currentLocation = new AtomicReference<>(drifted.clone());
        AtomicReference<Vector> currentVelocity = new AtomicReference<>(new Vector(0.15D, 0.2D, -0.1D));
        Mob mob = mock(Mob.class);
        when(mob.getWorld()).thenReturn(world);
        when(mob.getLocation()).thenAnswer(invocation -> currentLocation.get().clone());
        when(mob.getVelocity()).thenAnswer(invocation -> currentVelocity.get().clone());
        doAnswer(invocation -> {
            currentVelocity.set(invocation.getArgument(0));
            return null;
        }).when(mob).setVelocity(any(Vector.class));
        doAnswer(invocation -> {
            currentLocation.set(((Location) invocation.getArgument(0)).clone());
            return true;
        }).when(mob).teleport(any(Location.class));
        MobEntityController controller = new MobEntityController(plugin) {
            @Override
            public Mob getMob(MobInstance ignored) {
                return mob;
            }
        };

        controller.holdPosition(instance, anchor);

        Location fixed = currentLocation.get();
        assertEquals(anchor.getX(), fixed.getX(), 0.0001D);
        assertEquals(anchor.getY(), fixed.getY(), 0.0001D);
        assertEquals(anchor.getZ(), fixed.getZ(), 0.0001D);
        assertEquals(drifted.getYaw(), fixed.getYaw(), 0.0001F);
        assertEquals(drifted.getPitch(), fixed.getPitch(), 0.0001F);
        assertEquals(0.0D, currentVelocity.get().getX(), 0.0001D);
        assertEquals(0.0D, currentVelocity.get().getY(), 0.0001D);
        assertEquals(0.0D, currentVelocity.get().getZ(), 0.0001D);
        assertEquals(anchor.getX(), instance.currentLocation().getX(), 0.0001D);
        assertEquals(anchor.getY(), instance.currentLocation().getY(), 0.0001D);
        assertEquals(anchor.getZ(), instance.currentLocation().getZ(), 0.0001D);
    }

    @Test
    void applyStationaryNpcAttributesZerosMovementAndJump() {
        PluginMock plugin = PluginMock.builder()
                .withPluginName("AstralRecordTest")
                .withPluginVersion("1.0.0")
                .build();
        MobEntityController controller = new MobEntityController(plugin);
        Mob mob = mock(Mob.class);
        AttributeInstance movementSpeed = mock(AttributeInstance.class);
        AttributeInstance jumpStrength = mock(AttributeInstance.class);
        MobTemplate template = new MobTemplate(
                1,
                "npc_shopkeeper",
                MobCategory.NPC,
                "Shopkeeper",
                null,
                1,
                EntityType.VILLAGER,
                false,
                null,
                List.of(),
                List.of(),
                null,
                null,
                List.of(),
                MobShieldConfig.EMPTY,
                new MobIdleConfig(IdleBehavior.STATIONARY, 0.0D, 1.0D),
                true,
                null,
                null,
                null,
                null
        );

        when(mob.getAttribute(Attribute.MOVEMENT_SPEED)).thenReturn(movementSpeed);
        when(mob.getAttribute(Attribute.JUMP_STRENGTH)).thenReturn(jumpStrength);

        controller.applyStationaryNpcAttributes(template, mob);

        verify(movementSpeed).setBaseValue(0.0D);
        verify(jumpStrength).setBaseValue(0.0D);
    }

    @Test
    void applyStationaryNpcAttributesSkipsNonStationaryNpc() {
        PluginMock plugin = PluginMock.builder()
                .withPluginName("AstralRecordTest")
                .withPluginVersion("1.0.0")
                .build();
        MobEntityController controller = new MobEntityController(plugin);
        Mob mob = mock(Mob.class);
        AttributeInstance movementSpeed = mock(AttributeInstance.class);
        MobTemplate template = new MobTemplate(
                1,
                "npc_shopkeeper",
                MobCategory.NPC,
                "Shopkeeper",
                null,
                1,
                EntityType.VILLAGER,
                false,
                null,
                List.of(),
                List.of(),
                null,
                null,
                List.of(),
                MobShieldConfig.EMPTY,
                new MobIdleConfig(IdleBehavior.WANDER, 4.0D, 1.0D),
                true,
                null,
                null,
                null,
                null
        );

        when(mob.getAttribute(Attribute.MOVEMENT_SPEED)).thenReturn(movementSpeed);

        controller.applyStationaryNpcAttributes(template, mob);

        verify(movementSpeed, never()).setBaseValue(0.0D);
        verify(mob, never()).getAttribute(Attribute.JUMP_STRENGTH);
    }

    @Test
    void applyVariantLocksDefaultAdultAge() {
        PluginMock plugin = PluginMock.builder()
                .withPluginName("AstralRecordTest")
                .withPluginVersion("1.0.0")
                .build();
        MobEntityController controller = new MobEntityController(plugin);
        Mob mob = mock(Mob.class, withSettings().extraInterfaces(Ageable.class));
        Ageable ageable = (Ageable) mob;
        MobTemplate template = new MobTemplate(
                1,
                "zombie",
                MobCategory.ENEMY,
                "Zombie",
                null,
                1,
                EntityType.ZOMBIE,
                true,
                null,
                List.of(),
                List.of(),
                null,
                MobEquipmentConfig.EMPTY,
                List.of(),
                MobShieldConfig.EMPTY,
                MobIdleConfig.defaults(),
                false,
                null,
                null,
                null,
                null
        );

        controller.applyVariant(template, mob);

        verify(ageable).setAdult();
        verify(ageable).setAgeLock(true);
        verify(ageable, never()).setBaby();
    }

    @Test
    void applyVariantCanLockBabyAgeFromTemplate() {
        PluginMock plugin = PluginMock.builder()
                .withPluginName("AstralRecordTest")
                .withPluginVersion("1.0.0")
                .build();
        MobEntityController controller = new MobEntityController(plugin);
        Mob mob = mock(Mob.class, withSettings().extraInterfaces(Ageable.class));
        Ageable ageable = (Ageable) mob;
        MobTemplate template = new MobTemplate(
                1,
                "baby_zombie",
                MobCategory.ENEMY,
                "Baby Zombie",
                null,
                1,
                EntityType.ZOMBIE,
                true,
                null,
                List.of(),
                List.of(),
                null,
                new MobVariantConfig(MobVariantConfig.Age.BABY),
                MobEquipmentConfig.EMPTY,
                List.of(),
                MobShieldConfig.EMPTY,
                MobIdleConfig.defaults(),
                false,
                null,
                null,
                null,
                null
        );

        controller.applyVariant(template, mob);

        verify(ageable).setBaby();
        verify(ageable).setAgeLock(true);
        verify(ageable, never()).setAdult();
    }
}
