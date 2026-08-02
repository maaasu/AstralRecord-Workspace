package io.github.maaasu.astralRecord.feature.skill.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemSigil;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.gui.SkillBindGui;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindInventoryHolder;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindScreen;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindSession;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillMutationException;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillMutationFailure;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.skill.model.SkillSigilSlotDefinition;
import io.github.maaasu.astralRecord.feature.skill.registry.SkillRegistry;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillBindPresetService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillOwnershipService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillPermissionService;
import io.github.maaasu.astralRecord.feature.skill.service.LearnedSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import org.bukkit.Server;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.PlayerInventory;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
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
     * 検証契約: APIのシジル拒否理由は合成素材の汎用エラーへ畳み込まず、理由別メッセージIDへ対応付ける。
     */
    @Test
    void synthesisMutationFailuresKeepSpecificPlayerMessages() throws ReflectiveOperationException {
        SkillBindGuiEventHandler handler = newHandler();
        Method method = SkillBindGuiEventHandler.class.getDeclaredMethod("mutationFailureMessage", Throwable.class);
        method.setAccessible(true);

        assertEquals(PlayerMsgId.P_5859, method.invoke(handler,
            new LearnedSkillMutationException(LearnedSkillMutationFailure.SIGIL_NOT_ALLOWED, "not allowed")));
        assertEquals(PlayerMsgId.P_5860, method.invoke(handler,
            new LearnedSkillMutationException(LearnedSkillMutationFailure.NO_SIGIL_SLOT, "no slot")));
        assertEquals(PlayerMsgId.P_5861, method.invoke(handler,
            new LearnedSkillMutationException(LearnedSkillMutationFailure.DUPLICATE_SIGIL_GROUP, "duplicate")));
        assertEquals(PlayerMsgId.P_5864, method.invoke(handler, new IllegalStateException("inventory sync failed")));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 1. スキルマネージャー表示・操作
     * 検証契約: 非許可シジルはプレイヤーインベントリでクリックしても選択・非表示予約・合成service呼出を行わず、理由だけを結果枠へプレビューする。
     */
    @Test
    void unsupportedSigilClickDoesNotHideMaterialOrStartSynthesis() throws ReflectiveOperationException {
        AstralRecord plugin = mock(AstralRecord.class);
        SkillBindGui gui = mock(SkillBindGui.class);
        SkillService skillService = mock(SkillService.class);
        SkillOwnershipService ownershipService = mock(SkillOwnershipService.class);
        LearnedSkillService learnedSkillService = mock(LearnedSkillService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        SkillBindGuiEventHandler handler = new SkillBindGuiEventHandler(
            plugin, gui, skillService, mock(SkillBindPresetService.class), ownershipService,
            mock(SkillPermissionService.class), learnedSkillService, mock(PassiveSkillService.class), inventoryService
        );
        UUID accountId = UUID.randomUUID();
        UUID learnedSkillId = UUID.randomUUID();
        Player player = mock(Player.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        LearnedSkillInstance learned = new LearnedSkillInstance(
            learnedSkillId, accountId, "mage_fireball", 1, List.of(), 1, null, null);
        SkillDefinition definition = skillDefinition();
        SkillRegistry registry = new SkillRegistry();
        registry.replaceDefinitions(Map.of(definition.getId(), definition));
        InventoryEntryModel inventoryEntry = mock(InventoryEntryModel.class);
        ItemModel unsupportedSigil = mock(ItemModel.class);
        PlayerInventory playerInventory = mock(PlayerInventory.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        InventoryView view = mock(InventoryView.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(mock(Inventory.class));
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);
        when(skillService.registry()).thenReturn(registry);
        when(ownershipService.findInstance(astPlayer, learnedSkillId.toString())).thenReturn(learned);
        when(event.getClickedInventory()).thenReturn(playerInventory);
        when(event.getSlot()).thenReturn(12);
        when(inventoryService.getOwnedEntryAtBukkitSlot(astPlayer, 12)).thenReturn(inventoryEntry);
        when(inventoryService.getOwnedItemModelAtBukkitSlot(astPlayer, 12)).thenReturn(unsupportedSigil);
        when(unsupportedSigil.getId()).thenReturn("unsupported_sigil");
        when(unsupportedSigil.getSigil()).thenReturn(new ItemSigil("other_group", List.of()));

        try (
            MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
            MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)
        ) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            Method method = SkillBindGuiEventHandler.class.getDeclaredMethod(
                "handleSynthesisClick", Player.class, SkillBindSession.class,
                SkillBindInventoryHolder.class, InventoryClickEvent.class
            );
            method.setAccessible(true);
            method.invoke(handler, player, new SkillBindSession(presets(accountId)),
                new SkillBindInventoryHolder(SkillBindScreen.SYNTHESIS, 1, 0, learnedSkillId.toString()), event);
        }

        verify(inventoryService, never()).hideOwnedEntryFromGui(any(), any());
        verify(learnedSkillService, never()).levelUpAsync(any(), any(), any(), any(), any(), any());
        verify(learnedSkillService, never()).attachSigilAsync(any(), any(), any(), any(), any(), any(), any());
        assertFalse(mapValue(handler, "synthesisSelections").containsKey(player.getUniqueId()));
        assertTrue(mapValue(handler, "synthesisPreviews").containsKey(player.getUniqueId()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 1. スキルマネージャー表示・操作
     * 検証契約: 合成素材を一時非表示にした状態でスキルマネージャーを開き直しても、素材表示を復元してから新しいセッションを開く。
     */
    @Test
    @SuppressWarnings("unchecked")
    void reopeningSkillManagerRestoresTemporarilyHiddenSynthesisMaterial() throws ReflectiveOperationException {
        AstralRecord plugin = mock(AstralRecord.class);
        SkillBindGui gui = mock(SkillBindGui.class);
        SkillService skillService = mock(SkillService.class);
        SkillBindPresetService presetService = mock(SkillBindPresetService.class);
        SkillOwnershipService ownershipService = mock(SkillOwnershipService.class);
        LearnedSkillService learnedSkillService = mock(LearnedSkillService.class);
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        SkillBindGuiEventHandler handler = new SkillBindGuiEventHandler(
            plugin, gui, skillService, presetService, ownershipService, mock(SkillPermissionService.class),
            learnedSkillService, passiveSkillService, inventoryService
        );
        UUID accountId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        InventoryView view = mock(InventoryView.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(mock(Inventory.class));
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);
        when(presetService.hasLoadedPresets(accountId)).thenReturn(true);
        when(learnedSkillService.hasLoadedSkills(accountId)).thenReturn(true);
        when(presetService.selectedPresetIndex(accountId)).thenReturn(1);
        when(presetService.getPresets(accountId)).thenReturn(presets(accountId));
        when(skillService.registry()).thenReturn(new SkillRegistry());
        when(ownershipService.learnedSkills(astPlayer)).thenReturn(List.of());
        when(passiveSkillService.activePassiveSlotCount(astPlayer)).thenReturn(0);
        Class<?> selectionType = Class.forName(SkillBindGuiEventHandler.class.getName() + "$SynthesisSelection");
        var constructor = selectionType.getDeclaredConstructor(UUID.class, UUID.class, ItemModel.class);
        constructor.setAccessible(true);
        ((Map<UUID, Object>) fieldValue(handler, "synthesisSelections")).put(
            playerId, constructor.newInstance(accountId, UUID.randomUUID(), mock(ItemModel.class))
        );

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            handler.open(player);
        }

        verify(inventoryService).applyInventoriesToGui(astPlayer);
        verify(inventoryService).restoreHiddenEntryToGui(any(), any());
        verify(player).updateInventory();
        assertFalse(mapValue(handler, "synthesisSelections").containsKey(playerId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 1. スキルマネージャー表示・操作
     * 検証契約: 有効枠が減った後の超過パッシブ枠は既存維持・解除だけを許可し、新規設定を保存しない。
     */
    @Test
    void passiveOverflowGuardRestoresNewBindingBeforeSave() throws ReflectiveOperationException {
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        SkillBindGuiEventHandler handler = newHandler(passiveSkillService);
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
     * 検証契約: 保存中にログアウトしても、再ログイン後へ保存ロック・編集セッション・合成素材の表示予約を持ち越さない。
     */
    @Test
    void quitClearsSavingTokenAndEditingSession() throws ReflectiveOperationException {
        InventoryService inventoryService = mock(InventoryService.class);
        SkillBindGuiEventHandler handler = new SkillBindGuiEventHandler(
            mock(AstralRecord.class), mock(SkillBindGui.class), mock(SkillService.class),
            mock(SkillBindPresetService.class), mock(SkillOwnershipService.class),
            mock(SkillPermissionService.class), mock(LearnedSkillService.class),
            mock(PassiveSkillService.class), inventoryService
        );
        UUID playerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        putMapValue(handler, "sessions", playerId, new SkillBindSession(presets(playerId)));
        putSavingToken(handler, playerId);
        setValue(handler, "suppressClose").add(playerId);
        putSynthesisSelection(handler, playerId, accountId);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        PlayerQuitEvent event = mock(PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(player);

        handler.onPlayerQuit(event);

        assertFalse(mapValue(handler, "sessions").containsKey(playerId));
        assertFalse(mapValue(handler, "savingSessions").containsKey(playerId));
        assertFalse(mapValue(handler, "synthesisSelections").containsKey(playerId));
        assertFalse(setValue(handler, "suppressClose").contains(playerId));
        verify(inventoryService).clearHiddenEntriesFromGui(accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/4-統合フロー/13_4-スキルバインドGUI.md
     * 章・見出し: # 13_4-スキルバインドGUI > ## 6. 自動保存中の close 再表示
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

    private static SkillDefinition skillDefinition() {
        return new SkillDefinition(
            "mage_fireball", "mage_fireball", "火焔弾", null, "FIRE_CHARGE", List.of(),
            60L, 18.0D, 0L, 1, null, Map.of(), List.of(), SkillKind.ACTIVE, true,
            SkillResourceType.MANA, 18.0D, "mage_fireball", 3, List.of(),
            List.of(new SkillSigilSlotDefinition(1, 1)), List.of("allowed_sigil")
        );
    }

    private static SkillBindGuiEventHandler newHandler() {
        return newHandler(mock(PassiveSkillService.class));
    }

    private static SkillBindGuiEventHandler newHandler(PassiveSkillService passiveSkillService) {
        return new SkillBindGuiEventHandler(
            mock(AstralRecord.class), mock(SkillBindGui.class), mock(SkillService.class),
            mock(SkillBindPresetService.class), mock(SkillOwnershipService.class),
            mock(SkillPermissionService.class), mock(LearnedSkillService.class),
            passiveSkillService, mock(InventoryService.class)
        );
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
    private static void putSynthesisSelection(SkillBindGuiEventHandler handler, UUID playerId, UUID accountId)
        throws ReflectiveOperationException {
        Class<?> selectionType = Class.forName(SkillBindGuiEventHandler.class.getName() + "$SynthesisSelection");
        var constructor = selectionType.getDeclaredConstructor(UUID.class, UUID.class, ItemModel.class);
        constructor.setAccessible(true);
        ((Map<UUID, Object>) fieldValue(handler, "synthesisSelections")).put(
            playerId, constructor.newInstance(accountId, UUID.randomUUID(), mock(ItemModel.class))
        );
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
