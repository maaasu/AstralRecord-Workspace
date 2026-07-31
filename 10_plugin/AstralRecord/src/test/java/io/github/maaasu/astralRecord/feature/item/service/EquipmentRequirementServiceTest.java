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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### 装備条件判定
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_1-モデル定義.md
     * 章・見出し: # 04_1-モデル定義 > ## 4. カテゴリ固有定義 > ### 4.3 `ItemEquipment`
     * 検証契約: player level・現在class ID・現在class levelを全て装備条件として判定する。
     */
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
