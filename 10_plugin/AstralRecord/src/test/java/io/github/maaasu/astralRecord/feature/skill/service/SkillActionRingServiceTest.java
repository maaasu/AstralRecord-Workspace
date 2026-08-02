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
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.skill.registry.SkillRegistry;
import io.github.maaasu.astralRecord.feature.skill.repository.SkillRepository;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SkillActionRingServiceTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 3. action ring入力解決
     * 検証契約: action ring は選択前後で指定された案内文と色をリング内の文字 Display に表示する。
     */
    @Test
    void actionRingInstructionResourcesUseRequestedInstructionsAndColors() {
        assertEquals("§e左クリックでスキルを選択", LegacyComponentSerializer.legacySection().serialize(
            PlayerMsgResource.getComponent(PlayerMsgId.P_5854.getId())
        ));
        assertEquals("§a(§2左クリックでスキルを発動§a)", LegacyComponentSerializer.legacySection().serialize(
            PlayerMsgResource.getComponent(PlayerMsgId.P_5855.getId())
        ));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 3. action ring入力解決
     * 検証契約: action ring は開始時と選択確定時に案内文字列をリング内へ表示し、確定後は timeout を停止して
     * timer bar を消去する。終了時に後続の機能が表示した title を消去しない。
     */
    @Test
    void confirmedActionRingKeepsSessionWithoutTimerUntilExplicitClose() throws ReflectiveOperationException {
        Player viewer = mock(Player.class);
        Location eye = new Location(server().addSimpleWorld("skill_ring_world"), 0.0D, 64.0D, 0.0D);
        eye.setDirection(new org.bukkit.util.Vector(0.0D, 0.0D, 1.0D));
        when(viewer.getEyeLocation()).thenReturn(eye);
        when(viewer.isOnline()).thenReturn(true);

        SkillActionRingDisplay display = mock(SkillActionRingDisplay.class);
        SkillActionRingDisplay.DisplayEntity timerLabel = mock(SkillActionRingDisplay.DisplayEntity.class);
        SkillActionRingDisplay.DisplayEntity instructionLabel = mock(SkillActionRingDisplay.DisplayEntity.class);
        Object session = newRingSession(viewer, display);
        setField(session, "timerLabel", timerLabel);
        setField(session, "instructionLabel", instructionLabel);
        populateDisplayEntities(session);

        Method tick = session.getClass().getDeclaredMethod("tick", Player.class);
        tick.setAccessible(true);
        assertTrue((Boolean) tick.invoke(session, viewer));
        verify(display).updateText(same(viewer), same(instructionLabel),
            org.mockito.ArgumentMatchers.eq(PlayerMsgResource.getComponent(PlayerMsgId.P_5854.getId())),
            org.mockito.ArgumentMatchers.eq(0.60F));
        Method confirmSelection = session.getClass().getDeclaredMethod("confirmSelection");
        confirmSelection.setAccessible(true);
        confirmSelection.invoke(session);
        assertTrue((Boolean) tick.invoke(session, viewer));
        verify(display).updateText(same(viewer), same(instructionLabel),
            org.mockito.ArgumentMatchers.eq(PlayerMsgResource.getComponent(PlayerMsgId.P_5855.getId())),
            org.mockito.ArgumentMatchers.eq(0.60F));
        ArgumentCaptor<Location> instructionLocations = ArgumentCaptor.forClass(Location.class);
        verify(instructionLabel, org.mockito.Mockito.atLeast(2)).teleport(same(viewer), instructionLocations.capture());
        assertEquals(64.30D, instructionLocations.getAllValues().getFirst().getY(), 0.001D);
        assertTrue(instructionLocations.getAllValues().get(1).getY() > 64.30D);
        verify(viewer, org.mockito.Mockito.never()).showTitle(
            org.mockito.ArgumentMatchers.any(net.kyori.adventure.title.Title.class)
        );

        verify(display).updateText(same(viewer), same(timerLabel), same(Component.empty()), org.mockito.ArgumentMatchers.eq(0.60F));
        for (int index = 0; index <= 100; index++) {
            assertTrue((Boolean) tick.invoke(session, viewer));
        }
        Field phase = session.getClass().getDeclaredField("phase");
        phase.setAccessible(true);
        assertEquals("WAITING_CAST", phase.get(session).toString());
        verify(display).updateText(same(viewer), same(timerLabel), same(Component.empty()), org.mockito.ArgumentMatchers.eq(0.60F));

        Method destroy = session.getClass().getDeclaredMethod("destroy");
        destroy.setAccessible(true);
        destroy.invoke(session);
        verify(instructionLabel).destroy(viewer);
        verify(viewer, org.mockito.Mockito.never()).resetTitle();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 3. cast 可否
     * 検証契約: アクションリングは発動可否と選択状態に応じた状態色・ラベルを表示する。
     */
    @Test
    void actionRingUsesAvailabilityAndSelectionColorsForSkillLabels() throws ReflectiveOperationException {
        assertSlot(SkillCastResult.succeeded(), ColorCodeUtil.GREEN, "スキル", false);
        assertSlot(SkillCastResult.failure(PlayerMsgId.P_5802), ColorCodeUtil.GRAY, "スキル", false);
        assertSlot(SkillCastResult.failure(PlayerMsgId.P_5801), ColorCodeUtil.RED, "スキル\nMP", false);
        assertSlot(SkillCastResult.failure(PlayerMsgId.P_5806), ColorCodeUtil.RED, "スキル\nENG", false);
        assertSlot(SkillCastResult.failure(PlayerMsgId.P_5810), ColorCodeUtil.RED, "スキル\nNG", false);
        assertSlot(SkillCastResult.succeeded(), ColorCodeUtil.YELLOW, "スキル", true);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 3. action ring入力解決
     * 検証契約: 所持済みで使用許可だけを失った action ring のバインドは、SkillService が P_5863 を通知できる発動経路へ到達する。
     */
    @Test
    void ownedUnavailableRingSlotRemainsSelectableForPermissionFeedback() throws ReflectiveOperationException {
        Class<?> availabilityType = Class.forName(SkillActionRingService.class.getName() + "$SlotAvailability");
        Class<?> slotViewType = Class.forName(SkillActionRingService.class.getName() + "$SlotView");
        Constructor<?> constructor = slotViewType.getDeclaredConstructor(
            String.class, SkillDefinition.class, String.class, Material.class, boolean.class, availabilityType
        );
        constructor.setAccessible(true);
        @SuppressWarnings("unchecked")
        Object unavailable = Enum.valueOf((Class<Enum>) availabilityType.asSubclass(Enum.class), "UNAVAILABLE");
        Method selectable = slotViewType.getDeclaredMethod("selectable");
        selectable.setAccessible(true);

        Object owned = constructor.newInstance("locked_skill", definition(), "未許可", Material.BARRIER, true, unavailable);
        Object unowned = constructor.newInstance("missing_skill", definition(), "未所持", Material.BARRIER, false, unavailable);

        assertTrue((Boolean) selectable.invoke(owned));
        assertFalse((Boolean) selectable.invoke(unowned));
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
            mock(AstralRecord.class), presetService, skillService, mock(SkillOwnershipService.class),
            mock(SkillPermissionService.class)
        );
        service.setItemWeaponAttackService(weaponAttackService);

        assertFalse(service.hasLeftClickBind(player));
        service.activateLeftClickBind(player);
        verifyNoInteractions(skillService);
    }

    private void assertSlot(SkillCastResult castResult, String expectedColorCode, String expectedLabel, boolean selected)
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
        AstPlayer astPlayer = mock(AstPlayer.class);
        Player bukkitPlayer = mock(Player.class);
        when(astPlayer.getBukkit()).thenReturn(bukkitPlayer);
        when(bukkitPlayer.getUniqueId()).thenReturn(UUID.randomUUID());
        String labelText = (String) label.invoke(slot,
            new SkillService(mock(SkillRepository.class), new SkillRegistry(), null), new PlayerSkillCaster(astPlayer));
        Component component = (Component) legacyComponent.invoke(null, colorCode + labelText);
        assertTrue(labelText.startsWith(expectedLabel));
        assertEquals(expectedColorCode, colorCode);
        assertTrue(LegacyComponentSerializer.legacySection().serialize(component)
            .startsWith(expectedColorCode + expectedLabel.split("\\n", 2)[0]));
    }

    private Object newRingSession(Player viewer, SkillActionRingDisplay display) throws ReflectiveOperationException {
        Class<?> availabilityType = Class.forName(SkillActionRingService.class.getName() + "$SlotAvailability");
        Method availabilityFor = SkillActionRingService.class.getDeclaredMethod("availabilityFor", SkillCastResult.class);
        availabilityFor.setAccessible(true);
        Object availability = availabilityFor.invoke(null, SkillCastResult.succeeded());
        Class<?> slotViewType = Class.forName(SkillActionRingService.class.getName() + "$SlotView");
        Constructor<?> slotConstructor = slotViewType.getDeclaredConstructor(
            String.class, SkillDefinition.class, String.class, Material.class, boolean.class, availabilityType
        );
        slotConstructor.setAccessible(true);
        List<Object> slots = new java.util.ArrayList<>(SkillBindPreset.ACTION_RING_SLOT_COUNT);
        for (int index = 0; index < SkillBindPreset.ACTION_RING_SLOT_COUNT; index++) {
            slots.add(slotConstructor.newInstance(
                "test_skill_" + index, definition(), "スキル", Material.STONE, true, availability
            ));
        }
        Class<?> sessionType = Class.forName(SkillActionRingService.class.getName() + "$RingSession");
        Constructor<?> sessionConstructor = sessionType.getDeclaredConstructors()[0];
        sessionConstructor.setAccessible(true);
        Location eye = viewer.getEyeLocation();
        SkillService skillService = mock(SkillService.class);
        when(skillService.canCast(
            org.mockito.ArgumentMatchers.any(PlayerSkillCaster.class),
            org.mockito.ArgumentMatchers.any(SkillDefinition.class)
        )).thenReturn(SkillCastResult.succeeded());
        return sessionConstructor.newInstance(
            eye, eye.clone().add(0.0D, 0.0D, 3.0D), new org.bukkit.util.Vector(0.0D, 0.0D, 1.0D),
            new org.bukkit.util.Vector(-1.0D, 0.0D, 0.0D), new org.bukkit.util.Vector(0.0D, 1.0D, 0.0D),
            slots, viewer, display, skillService, mock(PlayerSkillCaster.class), null, null
        );
    }

    private void populateDisplayEntities(Object session) throws ReflectiveOperationException {
        addDisplayEntities(session, "icons", SkillBindPreset.ACTION_RING_SLOT_COUNT);
        addDisplayEntities(session, "labels", SkillBindPreset.ACTION_RING_SLOT_COUNT);
        addDisplayEntities(session, "circleDots", 24);
    }

    @SuppressWarnings("unchecked")
    private void addDisplayEntities(Object session, String fieldName, int count) throws ReflectiveOperationException {
        Field field = session.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        List<SkillActionRingDisplay.DisplayEntity> entities = (List<SkillActionRingDisplay.DisplayEntity>) field.get(session);
        for (int index = 0; index < count; index++) {
            entities.add(mock(SkillActionRingDisplay.DisplayEntity.class));
        }
    }

    private void setField(Object target, String fieldName, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private SkillDefinition definition() {
        return new SkillDefinition("test_skill", "test_impl", "スキル", null, "STONE", List.of(), 0L, 0.0D,
            0L, 1, null, Map.of(), List.of(), SkillKind.ACTIVE, true, SkillResourceType.MANA, 0.0D);
    }
}
