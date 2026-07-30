package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionsConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Transformation;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobEntityControllerTest extends MockBukkitTestBase {

    @Test
    void freshArmorStandRemainsUsableBeforePaperMarksItValid() {
        ArmorStand armorStand = mock(ArmorStand.class);
        when(armorStand.isDead()).thenReturn(false);
        when(armorStand.isValid()).thenReturn(false);

        assertTrue(MobEntityController.isManagedEntityUsable(armorStand));

        verify(armorStand, never()).isValid();
    }

    @Test
    void chestBlockNpcMaterialsUseItemDisplay() {
        assertTrue(MobEntityController.usesItemDisplayBlockMaterial(Material.CHEST));
        assertTrue(MobEntityController.usesItemDisplayBlockMaterial(Material.TRAPPED_CHEST));
        assertTrue(MobEntityController.usesItemDisplayBlockMaterial(Material.ENDER_CHEST));
    }

    @Test
    void regularBlockNpcMaterialsKeepBlockDisplay() {
        assertFalse(MobEntityController.usesItemDisplayBlockMaterial(Material.BARREL));
        assertFalse(MobEntityController.usesItemDisplayBlockMaterial(Material.ANVIL));
    }

    @Test
    void chestItemDisplayUsesFullModelTransformAboveGround() {
        Transformation transformation = MobEntityController.itemDisplayTransformation();

        assertEquals(ItemDisplay.ItemDisplayTransform.NONE, MobEntityController.itemDisplayTransform());
        assertEquals(0.0F, transformation.getTranslation().x);
        assertEquals(0.375F, transformation.getTranslation().y);
        assertEquals(0.0F, transformation.getTranslation().z);
        assertEquals(0.75F, transformation.getScale().x);
        assertEquals(0.75F, transformation.getScale().y);
        assertEquals(0.75F, transformation.getScale().z);
    }

    @Test
    void standardMaterialsAreAppliedToConfiguredEquipmentSlots() {
        EntityEquipment equipment = mock(EntityEquipment.class);

        MobEntityController.applyEquipment(
                equipment,
                new MobEquipmentConfig(
                        "IRON_SWORD",
                        "minecraft:SHIELD",
                        "LEATHER_HELMET",
                        "IRON_CHESTPLATE",
                        "IRON_LEGGINGS",
                        "IRON_BOOTS"
                )
        );

        verify(equipment).setItemInMainHand(argThat(item -> item.getType() == Material.IRON_SWORD));
        verify(equipment).setItemInOffHand(argThat(item -> item.getType() == Material.SHIELD));
        verify(equipment).setHelmet(argThat(item -> item.getType() == Material.LEATHER_HELMET));
        verify(equipment).setChestplate(argThat(item -> item.getType() == Material.IRON_CHESTPLATE));
        verify(equipment).setLeggings(argThat(item -> item.getType() == Material.IRON_LEGGINGS));
        verify(equipment).setBoots(argThat(item -> item.getType() == Material.IRON_BOOTS));
    }

    @Test
    void itemReferencesAreIgnoredWhenTheyAreNotMaterials() {
        EntityEquipment equipment = mock(EntityEquipment.class);

        MobEntityController.applyEquipment(
                equipment,
                new MobEquipmentConfig("traveler_sword", null, null, null, null, null)
        );

        verify(equipment, never()).setItemInMainHand(any());
    }

    @Test
    void armorStandTemplateSpawnsFixedVisibleEquipmentCarrier() {
        World world = server().addSimpleWorld("training_dummy_world");
        PluginMock plugin = PluginMock.builder().withPluginName("AstralRecordTest").build();
        MobTemplate template = new MobTemplate(
                1, "training_dummy:test", MobCategory.ENEMY, "Training Dummy", null,
                1, EntityType.ARMOR_STAND, true, "ARMOR_STAND", List.of(), List.of(), null,
                new MobEquipmentConfig(null, null, "LEATHER_HELMET", "LEATHER_CHESTPLATE", "LEATHER_LEGGINGS", "LEATHER_BOOTS"),
                List.of(), MobShieldConfig.EMPTY, MobIdleConfig.defaults(), false,
                MobInteractionsConfig.EMPTY, null, null, null
        );
        MobInstance instance = new MobInstance(UUID.randomUUID(), template, new Location(world, 1.5D, 64.0D, 2.5D));

        Entity entity = new MobEntityController(plugin).spawn(instance, instance.currentLocation());

        assertTrue(entity instanceof ArmorStand);
        ArmorStand armorStand = (ArmorStand) entity;
        assertFalse(armorStand.hasGravity());
        assertFalse(armorStand.isCollidable());
        assertTrue(armorStand.isVisible());
        assertTrue(armorStand.hasArms());
        assertTrue(armorStand.hasBasePlate());
        assertFalse(armorStand.isMarker());
        assertEquals(Set.of(EquipmentSlot.values()), armorStand.getDisabledSlots());
        assertEquals(Material.LEATHER_HELMET, armorStand.getEquipment().getHelmet().getType());
        assertEquals(entity.getUniqueId(), instance.bukkitEntityId());
    }
}
