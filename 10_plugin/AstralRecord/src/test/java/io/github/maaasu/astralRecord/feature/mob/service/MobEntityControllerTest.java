package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Material;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.util.Transformation;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobEntityControllerTest extends MockBukkitTestBase {

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
}
