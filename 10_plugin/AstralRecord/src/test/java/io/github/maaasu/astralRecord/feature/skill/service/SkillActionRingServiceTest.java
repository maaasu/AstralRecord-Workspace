package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.EquipmentDurabilityService;
import io.github.maaasu.astralRecord.feature.item.service.ItemWeaponAttackService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.skill.registry.SkillRegistry;
import io.github.maaasu.astralRecord.feature.skill.repository.SkillRepository;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SkillActionRingServiceTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 3. cast 可否
     * 検証契約: アクションリングは発動可否と選択状態に応じた状態色・ラベルを表示する。
     */
    @Test
    void actionRingUsesAvailabilityAndSelectionColorsForSkillLabels() throws ReflectiveOperationException {
        assertSlot(SkillCastResult.succeeded(), NamedTextColor.GREEN, "スキル", false);
        assertSlot(SkillCastResult.failure(PlayerMsgId.P_5802), NamedTextColor.GRAY, "スキル", false);
        assertSlot(SkillCastResult.failure(PlayerMsgId.P_5801), NamedTextColor.RED, "スキル\nMP", false);
        assertSlot(SkillCastResult.failure(PlayerMsgId.P_5806), NamedTextColor.RED, "スキル\nENG", false);
        assertSlot(SkillCastResult.failure(PlayerMsgId.P_5810), NamedTextColor.RED, "スキル\nNG", false);
        assertSlot(SkillCastResult.succeeded(), NamedTextColor.YELLOW, "スキル", true);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/4-統合フロー/13_4-統合フロー.md
     * 章・見出し: # 13_4-統合フロー > ## 2. player skill 発動 > ### 処理要点
     * 検証契約: 耐久値切れの主手 weapon では左クリック bind の候補を返さず、直接発動でもスキルを cast しない。
     */
    @Test
    void brokenWeaponRejectsLeftClickBindBeforeCast() {
        InventoryService inventoryService = mock(InventoryService.class);
        SkillBindPresetService presetService = mock(SkillBindPresetService.class);
        SkillService skillService = mock(SkillService.class);
        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        ItemModel weapon = mock(ItemModel.class);
        ItemEquipment equipment = mock(ItemEquipment.class);
        EquipmentDurabilityService durabilityService = mock(EquipmentDurabilityService.class);
        when(inventoryService.getItemModelInHand(player, EquipmentSlot.HAND)).thenReturn(weapon);
        when(weapon.getEquipment()).thenReturn(equipment);
        when(equipment.getSlot()).thenReturn(ItemEquipmentSlot.WEAPON);
        when(durabilityService.canUseMainHandWeapon(player)).thenReturn(false);
        UUID accountId = UUID.randomUUID();
        when(player.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);
        when(presetService.selectedPresetIndex(accountId)).thenReturn(0);
        when(presetService.getPresets(accountId)).thenReturn(List.of(new SkillBindPreset(
            null, accountId, 0, List.of(), "arc_lance", List.of(), true, true, 1
        )));

        ItemWeaponAttackService weaponAttackService = new ItemWeaponAttackService(inventoryService, skillService);
        weaponAttackService.setEquipmentDurabilityService(durabilityService);
        SkillActionRingService service = new SkillActionRingService(
            mock(AstralRecord.class), presetService, skillService, mock(SkillOwnershipService.class)
        );
        service.setItemWeaponAttackService(weaponAttackService);

        assertFalse(service.hasLeftClickBind(player));
        service.activateLeftClickBind(player);
        verifyNoInteractions(skillService);
    }

    private void assertSlot(SkillCastResult castResult, NamedTextColor expectedColor, String expectedLabel, boolean selected)
            throws ReflectiveOperationException {
        Method availabilityFor = SkillActionRingService.class.getDeclaredMethod("availabilityFor", SkillCastResult.class);
        availabilityFor.setAccessible(true);
        Object availability = availabilityFor.invoke(null, castResult);
        Class<?> availabilityType = Class.forName(SkillActionRingService.class.getName() + "$SlotAvailability");
        Class<?> slotViewType = Class.forName(SkillActionRingService.class.getName() + "$SlotView");
        Constructor<?> constructor = slotViewType.getDeclaredConstructor(
            String.class, SkillDefinition.class, String.class, Material.class, boolean.class, availabilityType
        );
        constructor.setAccessible(true);
        Object slot = constructor.newInstance("test_skill", definition(), "スキル", Material.STONE, true, availability);
        Method color = slotViewType.getDeclaredMethod("color", boolean.class);
        color.setAccessible(true);
        Method label = slotViewType.getDeclaredMethod("label", SkillService.class, PlayerSkillCaster.class);
        label.setAccessible(true);
        Method legacyComponent = SkillActionRingService.class.getDeclaredMethod("legacyComponent", String.class);
        legacyComponent.setAccessible(true);
        String colorCode = (String) color.invoke(slot, selected);
        String labelText = (String) label.invoke(slot,
            new SkillService(mock(SkillRepository.class), new SkillRegistry(), null), mock(PlayerSkillCaster.class));
        Component component = (Component) legacyComponent.invoke(null, colorCode + labelText);
        assertTrue(labelText.startsWith(expectedLabel));
        assertEquals(expectedColor, component.color());
        assertTrue(component.children().isEmpty());
    }

    private SkillDefinition definition() {
        return new SkillDefinition("test_skill", "test_impl", "スキル", null, "STONE", List.of(), 0L, 0.0D,
            0L, 1, null, Map.of(), List.of(), SkillKind.ACTIVE, true, SkillResourceType.MANA, 0.0D);
    }
}
