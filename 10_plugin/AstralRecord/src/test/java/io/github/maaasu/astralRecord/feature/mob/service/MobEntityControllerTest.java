package io.github.maaasu.astralRecord.feature.mob.service;

import org.bukkit.Material;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.util.Transformation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobEntityControllerTest {

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
}
