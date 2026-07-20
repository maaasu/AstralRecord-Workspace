package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentClassRequirement;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentHandType;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EquipmentRequirementServiceTest extends MockBukkitTestBase {

    @Test
    void checksPlayerLevelCurrentClassAndCurrentClassLevel() {
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);

        var playerLevelResult = EquipmentRequirementService.check(
            player,
            equipment(2, List.of())
        );
        assertEquals(EquipmentRequirementService.Failure.PLAYER_LEVEL, playerLevelResult.failure());

        var classResult = EquipmentRequirementService.check(
            player,
            equipment(0, List.of(new ItemEquipmentClassRequirement("swordsman", 3)))
        );
        assertEquals(EquipmentRequirementService.Failure.CLASS, classResult.failure());

        player.selectClass("swordsman");
        player.setClassLevel(2);
        var classLevelResult = EquipmentRequirementService.check(
            player,
            equipment(0, List.of(new ItemEquipmentClassRequirement("swordsman", 3)))
        );
        assertEquals(EquipmentRequirementService.Failure.CLASS_LEVEL, classLevelResult.failure());

        player.setClassLevel(3);
        assertTrue(EquipmentRequirementService.check(
            player,
            equipment(0, List.of(new ItemEquipmentClassRequirement("swordsman", 3)))
        ).allowed());
    }

    private ItemEquipment equipment(
        int requiredLevel,
        List<ItemEquipmentClassRequirement> requiredClasses
    ) {
        return new ItemEquipment(
            ItemEquipmentSlot.CHEST,
            ItemEquipmentHandType.ONE,
            null,
            requiredLevel,
            requiredClasses,
            null,
            List.of(),
            null,
            null,
            List.of(),
            null,
            null,
            null,
            List.of()
        );
    }
}
