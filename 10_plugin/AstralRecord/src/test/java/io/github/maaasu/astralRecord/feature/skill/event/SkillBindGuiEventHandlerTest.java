package io.github.maaasu.astralRecord.feature.skill.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.skill.gui.SkillBindGui;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindInventoryHolder;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindScreen;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindSession;
import io.github.maaasu.astralRecord.feature.skill.registry.SkillRegistry;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillBindPresetService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillOwnershipService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/4-統合フロー/13_4-スキルバインドGUI.md
     * 章・見出し: # 13_4-スキルバインドGUI > ## 4. 自動保存中の close 再表示
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
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        SkillBindGuiEventHandler handler = new SkillBindGuiEventHandler(
            plugin, gui, skillService, presetService, ownershipService, passiveSkillService, inventoryService
        );
        Player player = mock(Player.class);
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
        when(gui.sortedSkills(any())).thenReturn(List.of());
        when(skillService.registry()).thenReturn(new SkillRegistry());
        doAnswer(invocation -> {
            scheduledTasks.add(invocation.getArgument(1));
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(any(Plugin.class), any(Runnable.class));
        putMapValue(handler, "sessions", playerId, session);
        addSetValue(handler, "savingSessions", playerId);

        InventoryCloseEvent closeEvent = mock(InventoryCloseEvent.class);
        when(closeEvent.getPlayer()).thenReturn(player);
        when(closeEvent.getInventory()).thenReturn(closingInventory);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(null);
            handler.onInventoryClose(closeEvent);
            scheduledTasks.getFirst().run();
        }

        assertFalse(setValue(handler, "suppressClose").contains(playerId));
        verify(gui, never()).isInventory(reappearedInventory);
        verify(gui).open(any(), any(), any(), any(), any(), anyInt());
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
    private static void addSetValue(Object target, String fieldName, UUID value) throws ReflectiveOperationException {
        ((Set<UUID>) fieldValue(target, fieldName)).add(value);
    }

    @SuppressWarnings("unchecked")
    private static Set<UUID> setValue(Object target, String fieldName) throws ReflectiveOperationException {
        return (Set<UUID>) fieldValue(target, fieldName);
    }

    private static Object fieldValue(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
