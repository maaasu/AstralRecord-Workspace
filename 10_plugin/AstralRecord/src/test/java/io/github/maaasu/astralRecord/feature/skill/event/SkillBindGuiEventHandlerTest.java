package io.github.maaasu.astralRecord.feature.skill.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.gui.SkillBindGui;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindInventoryHolder;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindScreen;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindSession;
import io.github.maaasu.astralRecord.feature.skill.registry.SkillRegistry;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillBindPresetService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillOwnershipService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillPermissionService;
import io.github.maaasu.astralRecord.feature.skill.service.LearnedSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillBindGuiEventHandlerTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 1. スキルマネージャー表示・操作
     * 検証契約: 有効枠が減った後の超過パッシブ枠は既存維持・解除だけを許可し、新規設定を保存しない。
     */
    @Test
    void passiveOverflowGuardRestoresNewBindingBeforeSave() throws ReflectiveOperationException {
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        SkillBindGuiEventHandler handler = new SkillBindGuiEventHandler(
            mock(AstralRecord.class), mock(SkillBindGui.class), mock(SkillService.class),
            mock(SkillBindPresetService.class), mock(SkillOwnershipService.class),
            mock(SkillPermissionService.class), mock(LearnedSkillService.class),
            passiveSkillService, mock(InventoryService.class)
        );
        UUID accountId = UUID.randomUUID();
        SkillBindSession session = new SkillBindSession(presets(accountId));
        session.setSlot(io.github.maaasu.astralRecord.feature.skill.model.SkillBindType.PASSIVE, 6, UUID.randomUUID().toString());
        AstPlayer player = mock(AstPlayer.class);
        when(passiveSkillService.activePassiveSlotCount(player)).thenReturn(5);

        Method method = SkillBindGuiEventHandler.class.getDeclaredMethod(
            "restoreInvalidPassiveOverflowChanges", AstPlayer.class, SkillBindSession.class
        );
        method.setAccessible(true);
        boolean restored = (boolean) method.invoke(handler, player, session);

        assertTrue(restored);
        assertNull(session.passiveDraft().get(6));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 1. スキルマネージャー表示・操作
     * 検証契約: 保存中にログアウトしても、再ログイン後へ保存ロックと編集セッションを持ち越さない。
     */
    @Test
    void quitClearsSavingTokenAndEditingSession() throws ReflectiveOperationException {
        SkillBindGuiEventHandler handler = new SkillBindGuiEventHandler(
            mock(AstralRecord.class), mock(SkillBindGui.class), mock(SkillService.class),
            mock(SkillBindPresetService.class), mock(SkillOwnershipService.class),
            mock(SkillPermissionService.class), mock(LearnedSkillService.class),
            mock(PassiveSkillService.class), mock(InventoryService.class)
        );
        UUID playerId = UUID.randomUUID();
        putMapValue(handler, "sessions", playerId, new SkillBindSession(presets(playerId)));
        putSavingToken(handler, playerId);
        setValue(handler, "suppressClose").add(playerId);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        PlayerQuitEvent event = mock(PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(player);

        handler.onPlayerQuit(event);

        assertFalse(mapValue(handler, "sessions").containsKey(playerId));
        assertFalse(mapValue(handler, "savingSessions").containsKey(playerId));
        assertFalse(setValue(handler, "suppressClose").contains(playerId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/4-統合フロー/13_4-スキルバインドGUI.md
     * 章・見出し: # 13_4-スキルマネージャーGUI > ## 6. 自動保存中の close 再表示
     * 検証契約: 自動保存中のclose後の再表示は、次のユーザー起点closeを抑止しない。
     */
    @Test
    void closingWhileSavingReopensWithoutSuppressingTheNextClose() throws ReflectiveOperationException {
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        SkillBindGui gui = mock(SkillBindGui.class);
        SkillService skillService = mock(SkillService.class);
        SkillBindPresetService presetService = mock(SkillBindPresetService.class);
        SkillOwnershipService ownershipService = mock(SkillOwnershipService.class);
        SkillPermissionService permissionService = mock(SkillPermissionService.class);
        LearnedSkillService learnedSkillService = mock(LearnedSkillService.class);
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        SkillBindGuiEventHandler handler = new SkillBindGuiEventHandler(
            plugin, gui, skillService, presetService, ownershipService, permissionService,
            learnedSkillService, passiveSkillService, inventoryService
        );
        Player player = mock(Player.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        InventoryView inventoryView = mock(InventoryView.class);
        Inventory closingInventory = mock(Inventory.class);
        Inventory reappearedInventory = mock(Inventory.class);
        UUID playerId = UUID.randomUUID();
        List<Runnable> scheduledTasks = new ArrayList<>();
        SkillBindSession session = new SkillBindSession(presets(playerId));

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getOpenInventory()).thenReturn(inventoryView);
        when(inventoryView.getTopInventory()).thenReturn(reappearedInventory);
        when(gui.holder(closingInventory)).thenReturn(new SkillBindInventoryHolder(SkillBindScreen.MAIN, 1, 0));
        when(skillService.registry()).thenReturn(new SkillRegistry());
        when(ownershipService.learnedSkills(astPlayer)).thenReturn(List.of());
        doAnswer(invocation -> {
            scheduledTasks.add(invocation.getArgument(1));
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(any(Plugin.class), any(Runnable.class));
        putMapValue(handler, "sessions", playerId, session);
        putSavingToken(handler, playerId);

        InventoryCloseEvent closeEvent = mock(InventoryCloseEvent.class);
        when(closeEvent.getPlayer()).thenReturn(player);
        when(closeEvent.getInventory()).thenReturn(closingInventory);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            handler.onInventoryClose(closeEvent);
            scheduledTasks.getFirst().run();
        }

        assertFalse(setValue(handler, "suppressClose").contains(playerId));
        verify(gui, never()).isInventory(reappearedInventory);
        verify(gui).open(any(), any(), any(), any(), anyInt(), anyInt());
    }

    private static List<SkillBindPreset> presets(UUID accountId) {
        List<SkillBindPreset> presets = new ArrayList<>();
        for (int index = 1; index <= 6; index++) {
            presets.add(new SkillBindPreset(
                null, accountId, index, List.of(), List.of(), true, true, 1
            ));
        }
        return presets;
    }

    @SuppressWarnings("unchecked")
    private static void putMapValue(Object target, String fieldName, UUID key, SkillBindSession value)
        throws ReflectiveOperationException {
        ((Map<UUID, SkillBindSession>) fieldValue(target, fieldName)).put(key, value);
    }

    @SuppressWarnings("unchecked")
    private static void putSavingToken(Object target, UUID playerId) throws ReflectiveOperationException {
        ((Map<UUID, UUID>) fieldValue(target, "savingSessions")).put(playerId, UUID.randomUUID());
    }

    @SuppressWarnings("unchecked")
    private static Set<UUID> setValue(Object target, String fieldName) throws ReflectiveOperationException {
        return (Set<UUID>) fieldValue(target, fieldName);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, ?> mapValue(Object target, String fieldName) throws ReflectiveOperationException {
        return (Map<UUID, ?>) fieldValue(target, fieldName);
    }

    private static Object fieldValue(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
