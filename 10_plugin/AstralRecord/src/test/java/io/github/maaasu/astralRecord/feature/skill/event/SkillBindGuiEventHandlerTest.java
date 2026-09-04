package io.github.maaasu.astralRecord.feature.skill.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.guide.service.GuideService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemSigil;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.skill.gui.SkillBindGui;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindInventoryHolder;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindScreen;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindSession;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindType;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillMutationException;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillMutationFailure;
import io.github.maaasu.astralRecord.feature.skill.model.SkillManagerEntry;
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
import io.github.maaasu.astralRecord.shared.gui.session.GuiSessionContinuationRequestEvent;
import io.github.maaasu.astralRecord.shared.gui.session.GuiSessionEndEvent;
import io.github.maaasu.astralRecord.shared.gui.session.GuiSessionEndReason;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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
            learnedSkillId, accountId, "adventurer_smash", 1, List.of(), 1, null, null);
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
        verify(learnedSkillService, never()).levelUpFromManagerAsync(any(), any(), any(), any(), any(), any(), any());
        verify(learnedSkillService, never()).attachSigilAsync(
            any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
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
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/4-統合フロー/13_4-スキルバインドGUI.md
     * 章・見出し: # 13_4-スキルバインドGUI > ## 2. バインド
     * 検証契約: パッシブ枠にはバインド必須パッシブだけを設定でき、同一プリセット内で既に設定済みの個体は重複設定できない。
     */
    @Test
    void passiveSlotSelectionRejectsUnrequiredAndAlreadyBoundSkills() throws ReflectiveOperationException {
        SkillBindGuiEventHandler handler = newHandler();
        UUID accountId = UUID.randomUUID();
        UUID learnedSkillId = UUID.randomUUID();
        SkillBindSession session = new SkillBindSession(presets(accountId));
        session.selectBindSlot(SkillBindType.PASSIVE, 0);
        LearnedSkillInstance learned = new LearnedSkillInstance(
            learnedSkillId, accountId, "passive_unrequired", 1, List.of(), 1, null, null
        );
        SkillManagerEntry entry = new SkillManagerEntry(
            learned, passiveSkillDefinition("passive_unrequired", false), true
        );
        Method method = SkillBindGuiEventHandler.class.getDeclaredMethod(
            "canBindToSelected", SkillBindSession.class, SkillManagerEntry.class
        );
        method.setAccessible(true);

        assertFalse((boolean) method.invoke(handler, session, entry));

        session.setSlot(SkillBindType.PASSIVE, 1, entry.bindingId().toUpperCase(Locale.ROOT));
        assertFalse((boolean) method.invoke(handler, session, new SkillManagerEntry(
            learned, passiveSkillDefinition("passive_required", true), true
        )));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 3. スキルマネージャーの習得表示
     * 検証契約: パッシブ枠へ設定済みの個体は、UUID表記の大文字小文字が異なっても設定候補から除外する。
     */
    @Test
    @SuppressWarnings("unchecked")
    void passiveCandidatesExcludeAlreadyBoundSkillWithDifferentUuidCase() throws ReflectiveOperationException {
        SkillService skillService = mock(SkillService.class);
        SkillOwnershipService ownershipService = mock(SkillOwnershipService.class);
        SkillPermissionService permissionService = mock(SkillPermissionService.class);
        SkillBindGuiEventHandler handler = new SkillBindGuiEventHandler(
            mock(AstralRecord.class), mock(SkillBindGui.class), skillService,
            mock(SkillBindPresetService.class), ownershipService, permissionService,
            mock(LearnedSkillService.class), mock(PassiveSkillService.class), mock(InventoryService.class)
        );
        UUID accountId = UUID.randomUUID();
        UUID learnedSkillId = UUID.randomUUID();
        AstPlayer player = mock(AstPlayer.class);
        LearnedSkillInstance learned = new LearnedSkillInstance(
            learnedSkillId, accountId, "passive_required", 1, List.of(), 1, null, null
        );
        SkillDefinition definition = passiveSkillDefinition("passive_required", true);
        SkillRegistry registry = new SkillRegistry();
        registry.replaceDefinitions(Map.of(definition.getId(), definition));
        when(skillService.registry()).thenReturn(registry);
        when(ownershipService.learnedSkills(player)).thenReturn(List.of(learned));
        when(permissionService.isPermitted(player, learned.getSkillId())).thenReturn(true);

        SkillBindSession session = new SkillBindSession(presets(accountId));
        session.setSlot(SkillBindType.PASSIVE, 0, learnedSkillId.toString().toUpperCase(Locale.ROOT));
        session.selectBindSlot(SkillBindType.PASSIVE, 1);
        Method method = SkillBindGuiEventHandler.class.getDeclaredMethod(
            "visibleEntries", AstPlayer.class, SkillBindSession.class
        );
        method.setAccessible(true);

        List<SkillManagerEntry> visible = (List<SkillManagerEntry>) method.invoke(handler, player, session);

        assertTrue(visible.isEmpty());
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
        putSynthesisSelection(handler, playerId, accountId);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        PlayerQuitEvent event = mock(PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(player);

        handler.onPlayerQuit(event);

        assertFalse(mapValue(handler, "sessions").containsKey(playerId));
        assertFalse(mapValue(handler, "savingSessions").containsKey(playerId));
        assertFalse(mapValue(handler, "synthesisSelections").containsKey(playerId));
        verify(inventoryService).clearHiddenEntriesFromGui(accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/4-統合フロー/13_4-スキルバインドGUI.md
     * 章・見出し: # 13_4-スキルバインドGUI > ## 6. 自動保存中の close 再表示
     * 検証契約: 自動保存中の再表示は shared continuation が担当し、終了確定後に SkillBind handler 自身が古い Runnable を予約しない。
     */
    @Test
    void savingSessionEndDoesNotScheduleAStaleReopen() throws ReflectiveOperationException {
        SkillBindGui gui = mock(SkillBindGui.class);
        InventoryService inventoryService = mock(InventoryService.class);
        SkillBindGuiEventHandler handler = new SkillBindGuiEventHandler(
            mock(AstralRecord.class), gui, mock(SkillService.class), mock(SkillBindPresetService.class),
            mock(SkillOwnershipService.class), mock(SkillPermissionService.class), mock(LearnedSkillService.class),
            mock(PassiveSkillService.class), inventoryService
        );
        Player player = mock(Player.class);
        Inventory closingInventory = mock(Inventory.class);
        UUID playerId = UUID.randomUUID();
        SkillBindSession session = new SkillBindSession(presets(playerId));

        when(player.getUniqueId()).thenReturn(playerId);
        when(gui.holder(closingInventory)).thenReturn(new SkillBindInventoryHolder(SkillBindScreen.MAIN, 1, 0));
        putMapValue(handler, "sessions", playerId, session);
        putSavingToken(handler, playerId);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(null);
            handler.onGuiSessionEnd(new GuiSessionEndEvent(player, closingInventory, GuiSessionEndReason.MANUAL_CLOSE));
        }

        assertFalse(mapValue(handler, "sessions").containsKey(playerId));
        assertFalse(mapValue(handler, "savingSessions").containsKey(playerId));
        verify(gui, never()).open(any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/4-統合フロー/13_4-スキルバインドGUI.md
     * 章・見出し: # 13_4-スキルバインドGUI > ## 7. 未保存変更の close 確認
     * 検証契約: 未保存変更がある一覧画面の手動 close は、終了確定後の再表示ではなく shared continuation として破棄確認 GUI を予約する。
     */
    @Test
    void dirtyMainCloseRequestsTheDiscardConfirmationThroughSharedContinuation() throws ReflectiveOperationException {
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        PluginManager pluginManager = mock(PluginManager.class);
        SkillBindGui gui = mock(SkillBindGui.class);
        SkillBindGuiEventHandler handler = new SkillBindGuiEventHandler(
            plugin, gui, mock(SkillService.class), mock(SkillBindPresetService.class),
            mock(SkillOwnershipService.class), mock(SkillPermissionService.class), mock(LearnedSkillService.class),
            mock(PassiveSkillService.class), mock(InventoryService.class)
        );
        Player player = mock(Player.class);
        Inventory source = mock(Inventory.class);
        Inventory confirm = mock(Inventory.class);
        InventoryCloseEvent closeEvent = mock(InventoryCloseEvent.class);
        UUID playerId = UUID.randomUUID();
        SkillBindSession session = new SkillBindSession(presets(playerId));
        session.setSlot(SkillBindType.ACTIVE, 0, UUID.randomUUID().toString());

        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("player");
        when(closeEvent.getPlayer()).thenReturn(player);
        when(closeEvent.getInventory()).thenReturn(source);
        when(gui.holder(source)).thenReturn(new SkillBindInventoryHolder(SkillBindScreen.MAIN, 1, 3));
        when(gui.createConfirmInventory(anyInt(), anyInt(), any(), anyInt(), any())).thenReturn(confirm);
        putMapValue(handler, "sessions", playerId, session);

        handler.onInventoryClose(closeEvent);

        ArgumentCaptor<org.bukkit.event.Event> eventCaptor = ArgumentCaptor.forClass(org.bukkit.event.Event.class);
        verify(pluginManager).callEvent(eventCaptor.capture());
        GuiSessionContinuationRequestEvent request = (GuiSessionContinuationRequestEvent) eventCaptor.getValue();
        assertSame(player, request.getPlayer());
        assertSame(source, request.getSourceInventory());
        assertSame(confirm, request.getTargetInventorySupplier().get());
        verify(gui).createConfirmInventory(
            session.selectedPresetIndex(),
            3,
            "close",
            -1,
            Component.text("変更を破棄して閉じますか", net.kyori.adventure.text.format.NamedTextColor.YELLOW)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 1. スキルマネージャー表示・操作
     * 検証契約: 遷移先表示がcancelされて共有基盤がセッション終了を確定した場合、編集session・合成素材の予約・プレイヤーinventoryを通常どおり後片付けする。
     */
    @Test
    void cancelledTransitionLetsManualCloseCleanUpSessionAndSynthesisMaterial() throws ReflectiveOperationException {
        AstralRecord plugin = mock(AstralRecord.class);
        SkillBindGui gui = mock(SkillBindGui.class);
        InventoryService inventoryService = mock(InventoryService.class);
        SkillBindGuiEventHandler handler = new SkillBindGuiEventHandler(
            plugin, gui, mock(SkillService.class), mock(SkillBindPresetService.class),
            mock(SkillOwnershipService.class), mock(SkillPermissionService.class), mock(LearnedSkillService.class),
            mock(PassiveSkillService.class), inventoryService
        );
        UUID playerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Player player = mock(Player.class);
        Inventory inventory = mock(Inventory.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(player.getUniqueId()).thenReturn(playerId);

        SkillBindSession session = new SkillBindSession(presets(accountId));
        putMapValue(handler, "sessions", playerId, session);
        putSynthesisSelection(handler, playerId, accountId);
        when(gui.holder(inventory)).thenReturn(new SkillBindInventoryHolder(SkillBindScreen.SYNTHESIS, 1, 0));

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            handler.onGuiSessionEnd(new GuiSessionEndEvent(player, inventory, GuiSessionEndReason.MANUAL_CLOSE));
        }

        assertFalse(mapValue(handler, "sessions").containsKey(playerId));
        assertFalse(mapValue(handler, "synthesisSelections").containsKey(playerId));
        verify(inventoryService).restoreHiddenEntryToGui(eq(astPlayer), any());
        verify(inventoryService).applyInventoriesToGui(astPlayer);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 1. スキルマネージャー表示・操作
     * 検証契約: 前ページへ移動できる操作では、画面再表示前に PAGE 音を再生する。
     */
    @Test
    void previousPageMovePlaysPageSound() throws ReflectiveOperationException {
        SkillBindGui gui = mock(SkillBindGui.class);
        SkillService skillService = mock(SkillService.class);
        SkillOwnershipService ownershipService = mock(SkillOwnershipService.class);
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        SkillBindGuiEventHandler handler = new SkillBindGuiEventHandler(
            mock(AstralRecord.class), gui, skillService, mock(SkillBindPresetService.class), ownershipService,
            mock(SkillPermissionService.class), mock(LearnedSkillService.class), passiveSkillService, mock(InventoryService.class)
        );
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        InventoryView view = mock(InventoryView.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(location);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(mock(Inventory.class));
        when(gui.isInventory(any())).thenReturn(false);
        when(skillService.registry()).thenReturn(new SkillRegistry());
        when(ownershipService.learnedSkills(astPlayer)).thenReturn(List.of());
        when(event.getRawSlot()).thenReturn(SkillBindGui.PREVIOUS_PAGE_SLOT);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            invoke(handler, "handleMainClick",
                new Class<?>[] {Player.class, SkillBindSession.class, SkillBindInventoryHolder.class, InventoryClickEvent.class},
                player, new SkillBindSession(presets(UUID.randomUUID())),
                new SkillBindInventoryHolder(SkillBindScreen.MAIN, 1, 1), event);
        }

        verifySound(player, location, Sound.ITEM_BOOK_PAGE_TURN);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 5. 識別とページング
     * 検証契約: 一覧のバインド枠選択で再描画しても、表示可能な範囲で現在ページを引き継ぎ、現在の使用許可スキル定義を GUI へ渡す。
     */
    @Test
    void bindSlotSelectionKeepsCurrentPage() throws ReflectiveOperationException {
        SkillBindGui gui = mock(SkillBindGui.class);
        SkillService skillService = mock(SkillService.class);
        SkillOwnershipService ownershipService = mock(SkillOwnershipService.class);
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        SkillPermissionService permissionService = mock(SkillPermissionService.class);
        SkillBindGuiEventHandler handler = new SkillBindGuiEventHandler(
            mock(AstralRecord.class), gui, skillService, mock(SkillBindPresetService.class), ownershipService,
            permissionService, mock(LearnedSkillService.class), passiveSkillService, mock(InventoryService.class)
        );
        Player player = mock(Player.class);
        InventoryView view = mock(InventoryView.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        SkillBindSession session = new SkillBindSession(presets(UUID.randomUUID()));
        SkillDefinition permittedDefinition = skillDefinition();
        SkillRegistry registry = new SkillRegistry();
        registry.replaceDefinitions(Map.of(permittedDefinition.getId(), permittedDefinition));
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(mock(Inventory.class));
        when(gui.isInventory(any())).thenReturn(false);
        when(skillService.registry()).thenReturn(registry);
        when(permissionService.permittedSkillIds(astPlayer)).thenReturn(Set.of(permittedDefinition.getId()));
        when(ownershipService.learnedSkills(astPlayer)).thenReturn(List.of());
        when(passiveSkillService.activePassiveSlotCount(astPlayer)).thenReturn(0);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            invoke(handler, "handleBindSlotClick",
                new Class<?>[] {Player.class, SkillBindSession.class, SkillBindType.class, int.class, int.class, int.class},
                player, session, SkillBindType.ACTIVE, 0, 1, SkillBindPreset.ACTION_RING_SLOT_COUNT);
        }

        ArgumentCaptor<Integer> page = ArgumentCaptor.forClass(Integer.class);
        verify(gui).createMainInventory(
            any(), any(), any(), any(), eq(List.of(permittedDefinition)), anyInt(), page.capture()
        );
        assertEquals(1, page.getValue());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 1. スキルマネージャー表示・操作
     * 検証契約: 選択済みの active 枠へ通常攻撃を設定する操作では、保存開始前に SELECT 音を再生する。
     */
    @Test
    void normalAttackBindingPlaysSelectSound() throws ReflectiveOperationException {
        SkillBindGui gui = mock(SkillBindGui.class);
        SkillBindPresetService presetService = mock(SkillBindPresetService.class);
        SkillBindGuiEventHandler handler = new SkillBindGuiEventHandler(
            mock(AstralRecord.class), gui, mock(SkillService.class), presetService, mock(SkillOwnershipService.class),
            mock(SkillPermissionService.class), mock(LearnedSkillService.class), mock(PassiveSkillService.class), mock(InventoryService.class)
        );
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        UUID accountId = UUID.randomUUID();
        SkillBindSession session = new SkillBindSession(presets(accountId));
        session.selectBindSlot(SkillBindType.ACTIVE, 0);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(location);
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);
        when(event.getRawSlot()).thenReturn(SkillBindGui.NORMAL_ATTACK_SLOT);
        when(event.isLeftClick()).thenReturn(true);
        when(presetService.saveAsync(any(), anyInt(), any(), any(), any(), any(), any(), any())).thenReturn(true);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            invoke(handler, "handleMainClick",
                new Class<?>[] {Player.class, SkillBindSession.class, SkillBindInventoryHolder.class, InventoryClickEvent.class},
                player, session, new SkillBindInventoryHolder(SkillBindScreen.MAIN, 1, 0), event);
        }

        verifySound(player, location, Sound.UI_BUTTON_CLICK);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
    * 章・見出し: # 13_3-イベント > ## 1. スキルマネージャー表示・操作
    * 検証契約: 最大レベルの active スキルをアクションリングへ設定して保存が成功したときだけ、設定したスキル ID をガイドへ通知する。
    */
    @SuppressWarnings("unchecked")
    @Test
    void successfulMaxLevelActiveBindingNotifiesGuideCondition() throws ReflectiveOperationException {
        SkillBindGui gui = mock(SkillBindGui.class);
        SkillService skillService = mock(SkillService.class);
        SkillBindPresetService presetService = mock(SkillBindPresetService.class);
        SkillOwnershipService ownershipService = mock(SkillOwnershipService.class);
        SkillPermissionService permissionService = mock(SkillPermissionService.class);
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        SkillBindGuiEventHandler handler = new SkillBindGuiEventHandler(
            mock(AstralRecord.class), gui, skillService, presetService, ownershipService,
            permissionService, mock(LearnedSkillService.class), passiveSkillService, mock(InventoryService.class)
        );
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        InventoryView view = mock(InventoryView.class);
        Inventory topInventory = mock(Inventory.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        UUID playerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID learnedSkillId = UUID.randomUUID();
        SkillBindSession session = new SkillBindSession(presets(accountId));
        LearnedSkillInstance learned = new LearnedSkillInstance(
            learnedSkillId, accountId, "adventurer_smash", 3, List.of(), 1, null, null
        );
        SkillDefinition definition = skillDefinition();
        SkillRegistry registry = new SkillRegistry();
        registry.replaceDefinitions(Map.of(definition.getId(), definition));
        List<String> notifiedSkillIds = new ArrayList<>();
        handler.setSkillBoundListener((current, skillId) -> notifiedSkillIds.add(skillId));
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getLocation()).thenReturn(location);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(topInventory);
        when(gui.isInventory(topInventory)).thenReturn(false);
        when(gui.learnedSkillId(any())).thenReturn(learnedSkillId.toString());
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);
        when(skillService.registry()).thenReturn(registry);
        when(ownershipService.findInstance(astPlayer, learnedSkillId.toString())).thenReturn(learned);
        when(permissionService.isPermitted(astPlayer, definition.getId())).thenReturn(true);
        when(passiveSkillService.activePassiveSlotCount(astPlayer)).thenReturn(0);
        when(event.getRawSlot()).thenReturn(10);
        when(event.isLeftClick()).thenReturn(true);
        when(presetService.saveAsync(any(), anyInt(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        putMapValue(handler, "sessions", playerId, session);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            invoke(handler, "handleMainClick",
                new Class<?>[] {Player.class, SkillBindSession.class, SkillBindInventoryHolder.class, InventoryClickEvent.class},
                player, session, new SkillBindInventoryHolder(SkillBindScreen.MAIN, 1, 0), event);

            ArgumentCaptor<Consumer<SkillBindPreset>> success = ArgumentCaptor.forClass(Consumer.class);
            verify(presetService).saveAsync(
                eq(accountId), eq(1), any(), any(), any(), eq(accountId), success.capture(), any()
            );
            success.getValue().accept(new SkillBindPreset(
                null, accountId, 1, List.of(learnedSkillId.toString()),
                SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID, List.of(), true, true, 2
            ));
        }

        assertEquals(List.of("adventurer_smash"), notifiedSkillIds);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 1. スキルマネージャー表示・操作
     * 検証契約: 最大レベル未満の習得済みスキルは、クリック種別によらず詳細画面へ遷移し、選択中バインド枠を保持する。
     */
    @Test
    void learnedSkillBelowMaxOpensDetailAndKeepsSelectedBindSlot() throws ReflectiveOperationException {
        SkillBindGui gui = mock(SkillBindGui.class);
        SkillService skillService = mock(SkillService.class);
        SkillOwnershipService ownershipService = mock(SkillOwnershipService.class);
        SkillPermissionService permissionService = mock(SkillPermissionService.class);
        LearnedSkillService learnedSkillService = mock(LearnedSkillService.class);
        SkillBindGuiEventHandler handler = new SkillBindGuiEventHandler(
            mock(AstralRecord.class), gui, skillService, mock(SkillBindPresetService.class), ownershipService,
            permissionService, learnedSkillService, mock(PassiveSkillService.class), mock(InventoryService.class)
        );
        Player player = mock(Player.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        ItemStack skillItem = mock(ItemStack.class);
        UUID accountId = UUID.randomUUID();
        UUID learnedSkillId = UUID.randomUUID();
        SkillBindSession session = new SkillBindSession(presets(accountId));
        session.selectBindSlot(SkillBindType.ACTIVE, 2);
        LearnedSkillInstance learned = new LearnedSkillInstance(
            learnedSkillId, accountId, "adventurer_smash", 1, List.of(), 1, null, null
        );
        SkillDefinition definition = skillDefinition();
        SkillRegistry registry = new SkillRegistry();
        registry.replaceDefinitions(Map.of(definition.getId(), definition));
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(skillService.registry()).thenReturn(registry);
        when(ownershipService.findInstance(astPlayer, learnedSkillId.toString())).thenReturn(learned);
        when(permissionService.isPermitted(astPlayer, definition.getId())).thenReturn(true);
        when(event.getCurrentItem()).thenReturn(skillItem);
        when(gui.learnedSkillId(skillItem)).thenReturn(learnedSkillId.toString());
        when(event.getRawSlot()).thenReturn(1);
        when(event.isLeftClick()).thenReturn(false);
        when(event.isRightClick()).thenReturn(true);
        when(gui.createDetailInventory(any(), any(), eq(2), eq(false))).thenReturn(null);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            invoke(handler, "handleMainClick",
                new Class<?>[] {Player.class, SkillBindSession.class, SkillBindInventoryHolder.class, InventoryClickEvent.class},
                player, session, new SkillBindInventoryHolder(SkillBindScreen.MAIN, 1, 2), event);
        }

        verify(gui).createDetailInventory(eq(session), any(SkillManagerEntry.class), eq(2), eq(false));
        verify(learnedSkillService, never()).levelUpFromManagerAsync(any(), any(), any(), any(), any(), any(), any());
        assertEquals(SkillBindType.ACTIVE, session.selectedBindType());
        assertEquals(2, session.selectedBindSlotIndex());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 2. スキルマネージャーの習得・レベルアップ
     * 検証契約: 未習得スキルの左クリックは詳細画面を開かず、習得処理を直接実行する。
     */
    @Test
    void unlearnedSkillLeftClickLearnsWithoutOpeningDetail() throws ReflectiveOperationException {
        AstralRecord plugin = mock(AstralRecord.class);
        SkillBindGui gui = mock(SkillBindGui.class);
        SkillService skillService = mock(SkillService.class);
        SkillPermissionService permissionService = mock(SkillPermissionService.class);
        LearnedSkillService learnedSkillService = mock(LearnedSkillService.class);
        SkillDefinition definition = skillDefinition();
        SkillRegistry registry = new SkillRegistry();
        registry.replaceDefinitions(Map.of(definition.getId(), definition));
        SkillBindGuiEventHandler handler = new SkillBindGuiEventHandler(
            plugin, gui, skillService, mock(SkillBindPresetService.class), mock(SkillOwnershipService.class),
            permissionService, learnedSkillService, mock(PassiveSkillService.class), mock(InventoryService.class)
        );
        Player player = mock(Player.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        ItemStack skillItem = mock(ItemStack.class);
        UUID accountId = UUID.randomUUID();
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);
        when(skillService.registry()).thenReturn(registry);
        when(permissionService.isPermitted(astPlayer, definition.getId())).thenReturn(true);
        when(gui.unlearnedSkillId(skillItem)).thenReturn(definition.getId());
        when(event.getCurrentItem()).thenReturn(skillItem);
        when(event.getRawSlot()).thenReturn(1);
        when(event.isLeftClick()).thenReturn(true);
        when(event.isRightClick()).thenReturn(false);
        when(learnedSkillService.learnFromManagerAsync(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            invoke(handler, "handleMainClick",
                new Class<?>[] {Player.class, SkillBindSession.class, SkillBindInventoryHolder.class, InventoryClickEvent.class},
                player, new SkillBindSession(presets(accountId)),
                new SkillBindInventoryHolder(SkillBindScreen.MAIN, 1, 0), event);
        }

        verify(learnedSkillService).learnFromManagerAsync(
            eq(accountId), eq(definition.getId()), eq(accountId), any(), any(), any(), any()
        );
        verify(gui, never()).createDetailInventory(any(), any(), anyInt());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 1. スキルマネージャー表示・操作
     * 検証契約: 詳細画面のバインド操作は、一覧で選択した active 枠へ習得個体を保存し、選択状態を解除する。
     */
    @SuppressWarnings("unchecked")
    @Test
    void detailBindUsesPreviouslySelectedSlot() throws ReflectiveOperationException {
        SkillBindGui gui = mock(SkillBindGui.class);
        SkillService skillService = mock(SkillService.class);
        SkillBindPresetService presetService = mock(SkillBindPresetService.class);
        SkillOwnershipService ownershipService = mock(SkillOwnershipService.class);
        SkillPermissionService permissionService = mock(SkillPermissionService.class);
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        SkillBindGuiEventHandler handler = new SkillBindGuiEventHandler(
            mock(AstralRecord.class), gui, skillService, presetService, ownershipService,
            permissionService, mock(LearnedSkillService.class), passiveSkillService, mock(InventoryService.class)
        );
        Player player = mock(Player.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        Inventory topInventory = mock(Inventory.class);
        UUID accountId = UUID.randomUUID();
        UUID learnedSkillId = UUID.randomUUID();
        SkillBindSession session = new SkillBindSession(presets(accountId));
        session.selectBindSlot(SkillBindType.ACTIVE, 2);
        LearnedSkillInstance learned = new LearnedSkillInstance(
            learnedSkillId, accountId, "adventurer_smash", 1, List.of(), 1, null, null
        );
        SkillDefinition definition = skillDefinition();
        SkillRegistry registry = new SkillRegistry();
        registry.replaceDefinitions(Map.of(definition.getId(), definition));
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(skillService.registry()).thenReturn(registry);
        when(ownershipService.findInstance(astPlayer, learnedSkillId.toString())).thenReturn(learned);
        when(permissionService.isPermitted(astPlayer, definition.getId())).thenReturn(true);
        when(passiveSkillService.activePassiveSlotCount(astPlayer)).thenReturn(0);
        when(event.getClickedInventory()).thenReturn(topInventory);
        when(event.getRawSlot()).thenReturn(SkillBindGui.DETAIL_BIND_SLOT);
        when(event.isLeftClick()).thenReturn(true);
        when(event.isRightClick()).thenReturn(false);
        when(presetService.saveAsync(any(), anyInt(), any(), any(), any(), any(), any(), any())).thenReturn(true);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer, astPlayer);
            invoke(handler, "handleDetailClick",
                new Class<?>[] {Player.class, SkillBindSession.class, SkillBindInventoryHolder.class, InventoryClickEvent.class},
                player, session, new SkillBindInventoryHolder(SkillBindScreen.DETAIL, 1, 0, learnedSkillId.toString()), event);
        }

        ArgumentCaptor<List<String>> activeSlots = ArgumentCaptor.forClass(List.class);
        verify(presetService).saveAsync(
            eq(accountId), eq(1), activeSlots.capture(), any(), any(), eq(accountId), any(), any()
        );
        assertEquals(learnedSkillId.toString(), activeSlots.getValue().get(2));
        assertNull(session.selectedBindType());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 1. スキルマネージャー表示・操作
     * 検証契約: 詳細画面から一覧へ戻るとき、選択中バインド枠と遷移前ページを保持する。
     */
    @Test
    void detailBackReturnsToOriginalPageAndKeepsSelectedBindSlot() throws ReflectiveOperationException {
        SkillBindGui gui = mock(SkillBindGui.class);
        SkillService skillService = mock(SkillService.class);
        SkillOwnershipService ownershipService = mock(SkillOwnershipService.class);
        SkillPermissionService permissionService = mock(SkillPermissionService.class);
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        SkillBindGuiEventHandler handler = new SkillBindGuiEventHandler(
            mock(AstralRecord.class), gui, skillService, mock(SkillBindPresetService.class), ownershipService,
            permissionService, mock(LearnedSkillService.class), passiveSkillService, mock(InventoryService.class)
        );
        Player player = mock(Player.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        Inventory topInventory = mock(Inventory.class);
        UUID accountId = UUID.randomUUID();
        SkillBindSession session = new SkillBindSession(presets(accountId));
        session.selectBindSlot(SkillBindType.ACTIVE, 2);
        when(skillService.registry()).thenReturn(new SkillRegistry());
        when(ownershipService.learnedSkills(astPlayer)).thenReturn(List.of());
        when(permissionService.permittedSkillIds(astPlayer)).thenReturn(Set.of());
        when(passiveSkillService.activePassiveSlotCount(astPlayer)).thenReturn(0);
        when(event.getClickedInventory()).thenReturn(topInventory);
        when(event.getRawSlot()).thenReturn(SkillBindGui.DETAIL_BACK_SLOT);
        when(event.isLeftClick()).thenReturn(true);
        when(event.isRightClick()).thenReturn(false);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            invoke(handler, "handleDetailClick",
                new Class<?>[] {Player.class, SkillBindSession.class, SkillBindInventoryHolder.class, InventoryClickEvent.class},
                player, session, new SkillBindInventoryHolder(SkillBindScreen.DETAIL, 1, 3, "skill"), event);
        }

        ArgumentCaptor<Integer> page = ArgumentCaptor.forClass(Integer.class);
        verify(gui).createMainInventory(any(), any(), any(), any(), any(), anyInt(), page.capture());
        assertEquals(3, page.getValue());
        assertEquals(SkillBindType.ACTIVE, session.selectedBindType());
        assertEquals(2, session.selectedBindSlotIndex());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 2. スキルマネージャーの習得・レベルアップ
     * 検証契約: 詳細画面のレベルアップ受付後は、処理中表示へ遷移して二重操作を防ぐ。
     */
    @Test
    void detailLevelUpShowsProcessingStateAfterScheduling() throws ReflectiveOperationException {
        SkillBindGui gui = mock(SkillBindGui.class);
        SkillService skillService = mock(SkillService.class);
        SkillOwnershipService ownershipService = mock(SkillOwnershipService.class);
        SkillPermissionService permissionService = mock(SkillPermissionService.class);
        LearnedSkillService learnedSkillService = mock(LearnedSkillService.class);
        SkillBindGuiEventHandler handler = new SkillBindGuiEventHandler(
            mock(AstralRecord.class), gui, skillService, mock(SkillBindPresetService.class), ownershipService,
            permissionService, learnedSkillService, mock(PassiveSkillService.class), mock(InventoryService.class)
        );
        Player player = mock(Player.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        Inventory topInventory = mock(Inventory.class);
        UUID playerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID learnedSkillId = UUID.randomUUID();
        SkillBindSession session = new SkillBindSession(presets(accountId));
        LearnedSkillInstance learned = new LearnedSkillInstance(
            learnedSkillId, accountId, "adventurer_smash", 1, List.of(), 1, null, null
        );
        SkillDefinition definition = skillDefinition();
        SkillRegistry registry = new SkillRegistry();
        registry.replaceDefinitions(Map.of(definition.getId(), definition));
        when(player.getUniqueId()).thenReturn(playerId);
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);
        when(skillService.registry()).thenReturn(registry);
        when(ownershipService.findInstance(astPlayer, learnedSkillId.toString())).thenReturn(learned);
        when(permissionService.isPermitted(astPlayer, definition.getId())).thenReturn(true);
        when(event.getClickedInventory()).thenReturn(topInventory);
        when(event.getRawSlot()).thenReturn(SkillBindGui.DETAIL_LEVEL_UP_SLOT);
        when(event.isLeftClick()).thenReturn(true);
        when(event.isRightClick()).thenReturn(false);
        when(learnedSkillService.hasMutationInProgress(accountId)).thenReturn(false, true);
        when(learnedSkillService.levelUpFromManagerAsync(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(gui.createDetailInventory(any(), any(), eq(0), eq(true))).thenReturn(null);
        putMapValue(handler, "sessions", playerId, session);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer, astPlayer);
            invoke(handler, "handleDetailClick",
                new Class<?>[] {Player.class, SkillBindSession.class, SkillBindInventoryHolder.class, InventoryClickEvent.class},
                player, session, new SkillBindInventoryHolder(SkillBindScreen.DETAIL, 1, 0, learnedSkillId.toString()), event);
        }

        verify(learnedSkillService).levelUpFromManagerAsync(
            eq(accountId), eq(learnedSkillId), eq(accountId), any(), any(), any(), any()
        );
        verify(gui).createDetailInventory(eq(session), any(SkillManagerEntry.class), eq(0), eq(true));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
    * 章・見出し: # 13_3-イベント > ## 1. スキルマネージャー表示・操作
    * 検証契約: active 以外のバインド保存では、ガイドの SKILL_BOUND 通知を発生させない。
    */
    @SuppressWarnings("unchecked")
    @Test
    void nonActiveBindingSaveDoesNotNotifyGuideCondition() throws ReflectiveOperationException {
        SkillBindGui gui = mock(SkillBindGui.class);
        SkillBindPresetService presetService = mock(SkillBindPresetService.class);
        SkillBindGuiEventHandler handler = new SkillBindGuiEventHandler(
            mock(AstralRecord.class), gui, mock(SkillService.class), presetService, mock(SkillOwnershipService.class),
            mock(SkillPermissionService.class), mock(LearnedSkillService.class), mock(PassiveSkillService.class), mock(InventoryService.class)
        );
        Player player = mock(Player.class);
        InventoryView view = mock(InventoryView.class);
        Inventory topInventory = mock(Inventory.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        UUID playerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        SkillBindSession session = new SkillBindSession(presets(accountId));
        session.setLeftClickSkillId("adventurer_smash");
        List<String> notifiedSkillIds = new ArrayList<>();
        handler.setSkillBoundListener((current, skillId) -> notifiedSkillIds.add(skillId));
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(topInventory);
        when(gui.isInventory(topInventory)).thenReturn(false);
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);
        when(presetService.saveAsync(any(), anyInt(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        putMapValue(handler, "sessions", playerId, session);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            invoke(handler, "saveCurrentPreset",
                new Class<?>[] {Player.class, SkillBindSession.class, int.class}, player, session, 0);

            ArgumentCaptor<Consumer<SkillBindPreset>> success = ArgumentCaptor.forClass(Consumer.class);
            verify(presetService).saveAsync(
                eq(accountId), eq(1), any(), any(), any(), eq(accountId), success.capture(), any()
            );
            success.getValue().accept(new SkillBindPreset(
                null, accountId, 1, List.of(), "adventurer_smash", List.of(), true, true, 2
            ));
        }

        assertTrue(notifiedSkillIds.isEmpty());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 1. スキルマネージャー表示・操作
     * 検証契約: 合成成功後は、更新された合成画面またはメイン画面を開く前に UPGRADE 音を再生する。
     */
    @Test
    void completedSynthesisPlaysUpgradeSound() throws ReflectiveOperationException {
        SkillBindGui gui = mock(SkillBindGui.class);
        SkillService skillService = mock(SkillService.class);
        SkillOwnershipService ownershipService = mock(SkillOwnershipService.class);
        SkillBindGuiEventHandler handler = new SkillBindGuiEventHandler(
            mock(AstralRecord.class), gui, skillService, mock(SkillBindPresetService.class), ownershipService,
            mock(SkillPermissionService.class), mock(LearnedSkillService.class), mock(PassiveSkillService.class), mock(InventoryService.class)
        );
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        InventoryView view = mock(InventoryView.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        UUID playerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID learnedSkillId = UUID.randomUUID();
        SkillBindSession session = new SkillBindSession(presets(accountId));
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getLocation()).thenReturn(location);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(mock(Inventory.class));
        when(gui.isInventory(any())).thenReturn(false);
        when(skillService.registry()).thenReturn(new SkillRegistry());
        when(ownershipService.learnedSkills(astPlayer)).thenReturn(List.of());
        putMapValue(handler, "sessions", playerId, session);
        UUID operationToken = UUID.randomUUID();
        putSavingToken(handler, playerId, operationToken);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            invoke(handler, "completeSynthesis",
                new Class<?>[] {Player.class, SkillBindSession.class, int.class, LearnedSkillInstance.class, UUID.class},
                player, session, 0, new LearnedSkillInstance(
                    learnedSkillId, accountId, "adventurer_smash", 2, List.of(), 1, null, null
                ), operationToken);
        }

        verifySound(player, location, Sound.BLOCK_ANVIL_USE);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 1. スキルマネージャー表示・操作
     * 検証契約: 変更破棄の確認画面を開く操作では、画面表示と同時に CONFIRM 音を再生する。
     */
    @Test
    void openingDiscardConfirmationPlaysConfirmSound() throws ReflectiveOperationException {
        SkillBindGui gui = mock(SkillBindGui.class);
        SkillBindGuiEventHandler handler = new SkillBindGuiEventHandler(
            mock(AstralRecord.class), gui, mock(SkillService.class), mock(SkillBindPresetService.class),
            mock(SkillOwnershipService.class), mock(SkillPermissionService.class), mock(LearnedSkillService.class),
            mock(PassiveSkillService.class), mock(InventoryService.class)
        );
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        InventoryView view = mock(InventoryView.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(location);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(mock(Inventory.class));
        when(gui.isInventory(any())).thenReturn(false);

        invoke(handler, "openConfirm",
            new Class<?>[] {Player.class, SkillBindSession.class, String.class, int.class, int.class, Component.class},
            player, new SkillBindSession(presets(UUID.randomUUID())), "close", -1, 1, Component.empty());

        verifySound(player, location, Sound.BLOCK_NOTE_BLOCK_PLING);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 1. スキルマネージャー表示・操作
     * 検証契約: スキルマネージャーを直開きで閉じる操作は inventory close だけを要求し、CLOSE 音は共有 GUI lifecycle が確定時に再生する。
     */
    @Test
    void directCloseDelegatesCloseSoundToSharedLifecycle() throws ReflectiveOperationException {
        SkillBindGuiEventHandler handler = newHandler();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        invoke(handler, "restoreAndClose", new Class<?>[] {Player.class}, player);

        verify(player).closeInventory();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 1. スキルマネージャー表示・操作
     * 検証契約: 素材を消費する初回習得が成功したら、現在のアカウントを再取得して常時パッシブを再評価する。
     */
    @SuppressWarnings("unchecked")
    @Test
    void managerLearningSuccessMarksCurrentPlayerPassivesDirty() throws ReflectiveOperationException {
        AstralRecord plugin = mock(AstralRecord.class);
        SkillService skillService = mock(SkillService.class);
        SkillPermissionService permissionService = mock(SkillPermissionService.class);
        LearnedSkillService learnedSkillService = mock(LearnedSkillService.class);
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        SkillOwnershipService ownershipService = mock(SkillOwnershipService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        SkillBindGuiEventHandler handler = new SkillBindGuiEventHandler(
            plugin, mock(SkillBindGui.class), skillService, mock(SkillBindPresetService.class),
            ownershipService, permissionService, learnedSkillService, passiveSkillService,
            inventoryService
        );
        Player player = mock(Player.class);
        AstPlayer original = mock(AstPlayer.class);
        AstPlayer current = mock(AstPlayer.class);
        AccountModel originalAccount = mock(AccountModel.class);
        AccountModel currentAccount = mock(AccountModel.class);
        GuideService guideService = mock(GuideService.class);
        UUID accountId = UUID.randomUUID();
        UUID learnedSkillId = UUID.randomUUID();
        SkillDefinition definition = skillDefinition();
        SkillRegistry registry = new SkillRegistry();
        registry.replaceDefinitions(Map.of(definition.getId(), definition));
        LearnedSkillInstance learned = new LearnedSkillInstance(
            learnedSkillId, accountId, definition.getId(), 1, List.of(), 1, null, null
        );
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(original.getAccount()).thenReturn(originalAccount);
        when(current.getAccount()).thenReturn(currentAccount);
        when(originalAccount.getUuid()).thenReturn(accountId);
        when(currentAccount.getUuid()).thenReturn(accountId);
        when(original.getBukkit()).thenReturn(player);
        when(current.getBukkit()).thenReturn(player);
        when(permissionService.isPermitted(original, definition.getId())).thenReturn(true);
        when(permissionService.permittedSkillIds(current)).thenReturn(Set.of(definition.getId()));
        when(ownershipService.learnedSkills(current)).thenReturn(List.of(learned));
        when(passiveSkillService.activePassiveSlotCount(current)).thenReturn(0);
        when(skillService.registry()).thenReturn(registry);
        when(plugin.getGuideService()).thenReturn(guideService);
        when(learnedSkillService.learnFromManagerAsync(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(original, current);
            invoke(handler, "learnFromManager", new Class<?>[] {Player.class, SkillBindSession.class, int.class, String.class},
                player, new SkillBindSession(presets(accountId)), 0, definition.getId());

            ArgumentCaptor<Consumer<LearnedSkillInstance>> success = ArgumentCaptor.forClass(Consumer.class);
            verify(learnedSkillService).learnFromManagerAsync(
                eq(accountId), eq(definition.getId()), eq(accountId), any(), success.capture(), any(), any()
            );
            success.getValue().accept(learned);
        }

        verify(passiveSkillService).markDirty(current);
        verify(passiveSkillService, never()).markDirty(original);
        verify(inventoryService).refreshManagedInventoryUi(current);
        verify(guideService).recordConditionSilently(current, io.github.maaasu.astralRecord.feature.guide.model.GuideConditionType.SKILL_LEARNED, definition.getId());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 1. スキルマネージャー表示・操作
     * 検証契約: 素材を消費するレベルアップが成功したら、現在のアカウントを再取得して常時パッシブを再評価する。
     */
    @SuppressWarnings("unchecked")
    @Test
    void managerLevelUpSuccessMarksCurrentPlayerPassivesDirty() throws ReflectiveOperationException {
        AstralRecord plugin = mock(AstralRecord.class);
        SkillService skillService = mock(SkillService.class);
        LearnedSkillService learnedSkillService = mock(LearnedSkillService.class);
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        SkillOwnershipService ownershipService = mock(SkillOwnershipService.class);
        SkillPermissionService permissionService = mock(SkillPermissionService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        SkillBindGuiEventHandler handler = new SkillBindGuiEventHandler(
            plugin, mock(SkillBindGui.class), skillService, mock(SkillBindPresetService.class),
            ownershipService, permissionService, learnedSkillService, passiveSkillService,
            inventoryService
        );
        Player player = mock(Player.class);
        AstPlayer original = mock(AstPlayer.class);
        AstPlayer current = mock(AstPlayer.class);
        AccountModel originalAccount = mock(AccountModel.class);
        AccountModel currentAccount = mock(AccountModel.class);
        GuideService guideService = mock(GuideService.class);
        UUID playerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID learnedSkillId = UUID.randomUUID();
        SkillDefinition definition = skillDefinition();
        SkillRegistry registry = new SkillRegistry();
        registry.replaceDefinitions(Map.of(definition.getId(), definition));
        LearnedSkillInstance before = new LearnedSkillInstance(
            learnedSkillId, accountId, definition.getId(), 1, List.of(), 1, null, null
        );
        LearnedSkillInstance updated = new LearnedSkillInstance(
            learnedSkillId, accountId, definition.getId(), 2, List.of(), 2, null, null
        );
        SkillBindSession session = new SkillBindSession(presets(accountId));
        when(player.getUniqueId()).thenReturn(playerId);
        when(original.getAccount()).thenReturn(originalAccount);
        when(current.getAccount()).thenReturn(currentAccount);
        when(originalAccount.getUuid()).thenReturn(accountId);
        when(currentAccount.getUuid()).thenReturn(accountId);
        when(original.getBukkit()).thenReturn(player);
        when(current.getBukkit()).thenReturn(player);
        when(skillService.registry()).thenReturn(registry);
        when(permissionService.permittedSkillIds(current)).thenReturn(Set.of(definition.getId()));
        when(ownershipService.learnedSkills(current)).thenReturn(List.of(updated));
        when(passiveSkillService.activePassiveSlotCount(current)).thenReturn(0);
        when(plugin.getGuideService()).thenReturn(guideService);
        when(learnedSkillService.levelUpFromManagerAsync(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        putMapValue(handler, "sessions", playerId, session);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(original, current);
            invoke(handler, "levelUpFromManager", new Class<?>[] {Player.class, SkillBindSession.class, int.class, SkillManagerEntry.class},
                player, session, 0, new SkillManagerEntry(before, definition, true));

            ArgumentCaptor<Consumer<LearnedSkillInstance>> success = ArgumentCaptor.forClass(Consumer.class);
            verify(learnedSkillService).levelUpFromManagerAsync(
                eq(accountId), eq(learnedSkillId), eq(accountId), any(), success.capture(), any(), any()
            );
            success.getValue().accept(updated);
        }

        verify(passiveSkillService).markDirty(current);
        verify(passiveSkillService, never()).markDirty(original);
        verify(inventoryService).refreshManagedInventoryUi(current);
        verify(guideService).recordConditionSilently(current, io.github.maaasu.astralRecord.feature.guide.model.GuideConditionType.SKILL_ENHANCED, definition.getId());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 2. スキルマネージャーの習得・レベルアップ
     * 検証契約: 詳細画面でのレベルアップが最大未満なら、更新後個体を再取得して詳細画面を保持する。
     */
    @SuppressWarnings("unchecked")
    @Test
    void managerLevelUpBelowMaxKeepsRefreshedDetail() throws ReflectiveOperationException {
        AstralRecord plugin = mock(AstralRecord.class);
        SkillBindGui gui = mock(SkillBindGui.class);
        SkillService skillService = mock(SkillService.class);
        LearnedSkillService learnedSkillService = mock(LearnedSkillService.class);
        SkillOwnershipService ownershipService = mock(SkillOwnershipService.class);
        SkillPermissionService permissionService = mock(SkillPermissionService.class);
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        SkillBindGuiEventHandler handler = new SkillBindGuiEventHandler(
            plugin, gui, skillService, mock(SkillBindPresetService.class), ownershipService,
            permissionService, learnedSkillService, passiveSkillService, inventoryService
        );
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        AstPlayer original = mock(AstPlayer.class);
        AstPlayer current = mock(AstPlayer.class);
        AccountModel originalAccount = mock(AccountModel.class);
        AccountModel currentAccount = mock(AccountModel.class);
        GuideService guideService = mock(GuideService.class);
        UUID playerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID learnedSkillId = UUID.randomUUID();
        SkillDefinition definition = skillDefinition();
        SkillRegistry registry = new SkillRegistry();
        registry.replaceDefinitions(Map.of(definition.getId(), definition));
        LearnedSkillInstance before = new LearnedSkillInstance(
            learnedSkillId, accountId, definition.getId(), 1, List.of(), 1, null, null
        );
        LearnedSkillInstance updated = new LearnedSkillInstance(
            learnedSkillId, accountId, definition.getId(), 2, List.of(), 2, null, null
        );
        SkillBindSession session = new SkillBindSession(presets(accountId));
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getLocation()).thenReturn(location);
        when(original.getAccount()).thenReturn(originalAccount);
        when(current.getAccount()).thenReturn(currentAccount);
        when(originalAccount.getUuid()).thenReturn(accountId);
        when(currentAccount.getUuid()).thenReturn(accountId);
        when(original.getBukkit()).thenReturn(player);
        when(current.getBukkit()).thenReturn(player);
        when(skillService.registry()).thenReturn(registry);
        when(ownershipService.findInstance(current, learnedSkillId.toString())).thenReturn(updated);
        when(permissionService.isPermitted(current, definition.getId())).thenReturn(true);
        when(plugin.getGuideService()).thenReturn(guideService);
        when(learnedSkillService.levelUpFromManagerAsync(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(gui.createDetailInventory(any(), any(), eq(4), eq(false))).thenReturn(null);
        putMapValue(handler, "sessions", playerId, session);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(original, current);
            invoke(handler, "levelUpFromManager",
                new Class<?>[] {Player.class, SkillBindSession.class, int.class, SkillManagerEntry.class, boolean.class},
                player, session, 4, new SkillManagerEntry(before, definition, true), true);

            ArgumentCaptor<Consumer<LearnedSkillInstance>> success = ArgumentCaptor.forClass(Consumer.class);
            verify(learnedSkillService).levelUpFromManagerAsync(
                eq(accountId), eq(learnedSkillId), eq(accountId), any(), success.capture(), any(), any()
            );
            success.getValue().accept(updated);
        }

        verify(gui).createDetailInventory(eq(session), any(SkillManagerEntry.class), eq(4), eq(false));
        verify(inventoryService).refreshManagedInventoryUi(current);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 2. スキルマネージャーの習得・レベルアップ
     * 検証契約: 詳細画面でのレベルアップが最大に到達したら、更新後個体を表示するスキルマネージャーへ戻る。
     */
    @SuppressWarnings("unchecked")
    @Test
    void managerLevelUpAtMaxReturnsToRefreshedMain() throws ReflectiveOperationException {
        AstralRecord plugin = mock(AstralRecord.class);
        SkillBindGui gui = mock(SkillBindGui.class);
        SkillService skillService = mock(SkillService.class);
        LearnedSkillService learnedSkillService = mock(LearnedSkillService.class);
        SkillOwnershipService ownershipService = mock(SkillOwnershipService.class);
        SkillPermissionService permissionService = mock(SkillPermissionService.class);
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        SkillBindGuiEventHandler handler = new SkillBindGuiEventHandler(
            plugin, gui, skillService, mock(SkillBindPresetService.class), ownershipService,
            permissionService, learnedSkillService, passiveSkillService, inventoryService
        );
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        AstPlayer original = mock(AstPlayer.class);
        AstPlayer current = mock(AstPlayer.class);
        AccountModel originalAccount = mock(AccountModel.class);
        AccountModel currentAccount = mock(AccountModel.class);
        GuideService guideService = mock(GuideService.class);
        UUID playerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID learnedSkillId = UUID.randomUUID();
        SkillDefinition definition = skillDefinition();
        SkillRegistry registry = new SkillRegistry();
        registry.replaceDefinitions(Map.of(definition.getId(), definition));
        LearnedSkillInstance before = new LearnedSkillInstance(
            learnedSkillId, accountId, definition.getId(), 2, List.of(), 1, null, null
        );
        LearnedSkillInstance updated = new LearnedSkillInstance(
            learnedSkillId, accountId, definition.getId(), 3, List.of(), 2, null, null
        );
        SkillBindSession session = new SkillBindSession(presets(accountId));
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getLocation()).thenReturn(location);
        when(original.getAccount()).thenReturn(originalAccount);
        when(current.getAccount()).thenReturn(currentAccount);
        when(originalAccount.getUuid()).thenReturn(accountId);
        when(currentAccount.getUuid()).thenReturn(accountId);
        when(original.getBukkit()).thenReturn(player);
        when(current.getBukkit()).thenReturn(player);
        when(skillService.registry()).thenReturn(registry);
        when(ownershipService.findInstance(current, learnedSkillId.toString())).thenReturn(updated);
        when(ownershipService.learnedSkills(current)).thenReturn(List.of(updated));
        when(permissionService.isPermitted(current, definition.getId())).thenReturn(true);
        when(permissionService.permittedSkillIds(current)).thenReturn(Set.of(definition.getId()));
        when(passiveSkillService.activePassiveSlotCount(current)).thenReturn(0);
        when(plugin.getGuideService()).thenReturn(guideService);
        when(learnedSkillService.levelUpFromManagerAsync(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(gui.createMainInventory(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(null);
        putMapValue(handler, "sessions", playerId, session);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(original, current, current);
            invoke(handler, "levelUpFromManager",
                new Class<?>[] {Player.class, SkillBindSession.class, int.class, SkillManagerEntry.class, boolean.class},
                player, session, 4, new SkillManagerEntry(before, definition, true), true);

            ArgumentCaptor<Consumer<LearnedSkillInstance>> success = ArgumentCaptor.forClass(Consumer.class);
            verify(learnedSkillService).levelUpFromManagerAsync(
                eq(accountId), eq(learnedSkillId), eq(accountId), any(), success.capture(), any(), any()
            );
            success.getValue().accept(updated);
        }

        ArgumentCaptor<Integer> page = ArgumentCaptor.forClass(Integer.class);
        verify(gui).createMainInventory(any(), any(), any(), any(), any(), anyInt(), page.capture());
        verify(gui, never()).createDetailInventory(any(), any(), anyInt());
        assertEquals(4, page.getValue());
        verify(inventoryService).refreshManagedInventoryUi(current);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 1. スキルマネージャー表示・操作
     * 検証契約: スキルマネージャーのセッション終了後に到着したレベルアップcallbackはGUIを再表示しない。
     */
    @SuppressWarnings("unchecked")
    @Test
    void levelUpCallbackDoesNotReopenAfterSessionEnds() throws ReflectiveOperationException {
        AstralRecord plugin = mock(AstralRecord.class);
        SkillBindGui gui = mock(SkillBindGui.class);
        SkillService skillService = mock(SkillService.class);
        LearnedSkillService learnedSkillService = mock(LearnedSkillService.class);
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        SkillBindGuiEventHandler handler = new SkillBindGuiEventHandler(
            plugin, gui, skillService, mock(SkillBindPresetService.class), mock(SkillOwnershipService.class),
            mock(SkillPermissionService.class), learnedSkillService, passiveSkillService, inventoryService
        );
        Player player = mock(Player.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        UUID playerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID learnedSkillId = UUID.randomUUID();
        SkillDefinition definition = skillDefinition();
        SkillBindSession session = new SkillBindSession(presets(accountId));
        LearnedSkillInstance before = new LearnedSkillInstance(
            learnedSkillId, accountId, definition.getId(), 1, List.of(), 1, null, null
        );
        LearnedSkillInstance updated = new LearnedSkillInstance(
            learnedSkillId, accountId, definition.getId(), 2, List.of(), 2, null, null
        );
        when(player.getUniqueId()).thenReturn(playerId);
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);
        when(learnedSkillService.levelUpFromManagerAsync(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        putMapValue(handler, "sessions", playerId, session);

        Consumer<LearnedSkillInstance> success;
        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            invoke(handler, "levelUpFromManager",
                new Class<?>[] {Player.class, SkillBindSession.class, int.class, SkillManagerEntry.class},
                player, session, 0, new SkillManagerEntry(before, definition, true));

            ArgumentCaptor<Consumer<LearnedSkillInstance>> successCaptor = ArgumentCaptor.forClass(Consumer.class);
            verify(learnedSkillService).levelUpFromManagerAsync(
                eq(accountId), eq(learnedSkillId), eq(accountId), any(), successCaptor.capture(), any(), any()
            );
            success = successCaptor.getValue();
            mapValue(handler, "sessions").remove(playerId);
            success.accept(updated);
        }

        verify(gui, never()).createMainInventory(any(), any(), any(), any(), any(), anyInt(), anyInt());
        verify(gui, never()).createDetailInventory(any(), any(), anyInt());
        verify(inventoryService, never()).refreshManagedInventoryUi(any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 1. スキルマネージャー表示・操作
     * 検証契約: プリセット未ロード時のスキルマネージャー表示は false を返し、画面を開かず DENY 音だけを再生する。
     */
    @Test
    void unloadedPresetsRejectOpeningWithOnlyDenySound() {
        SkillBindGui gui = mock(SkillBindGui.class);
        SkillBindPresetService presetService = mock(SkillBindPresetService.class);
        SkillBindGuiEventHandler handler = new SkillBindGuiEventHandler(
            mock(AstralRecord.class), gui, mock(SkillService.class), presetService, mock(SkillOwnershipService.class),
            mock(SkillPermissionService.class), mock(LearnedSkillService.class), mock(PassiveSkillService.class), mock(InventoryService.class)
        );
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        UUID accountId = UUID.randomUUID();
        when(player.getLocation()).thenReturn(location);
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);
        when(presetService.hasLoadedPresets(accountId)).thenReturn(false);

        try (
            MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
            MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)
        ) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            assertFalse(handler.open(player));
        }

        verify(gui, never()).open(any(), any(), any(), any(), any(), anyInt(), anyInt());
        verifySound(player, location, Sound.BLOCK_NOTE_BLOCK_BASS);
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

    private static void invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... arguments)
        throws ReflectiveOperationException {
        Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        method.invoke(target, arguments);
    }

    private static void verifySound(Player player, Location location, Sound sound) {
        verify(player).playSound(eq(location), eq(sound), eq(SoundCategory.PLAYERS), anyFloat(), anyFloat());
    }

    private static SkillDefinition skillDefinition() {
        return new SkillDefinition(
            "adventurer_smash", "adventurer_smash", "スマッシュ", null, "IRON_SWORD", List.of(),
            60L, 18.0D, 0L, 1, null, Map.of(), List.of(), SkillKind.ACTIVE, true,
            SkillResourceType.ENERGY, 18.0D, "adventurer_smash", 3, List.of(),
            List.of(new SkillSigilSlotDefinition(1, 1)), List.of("allowed_sigil")
        );
    }

    private static SkillDefinition passiveSkillDefinition(String id, boolean bindRequired) {
        return new SkillDefinition(
            id, id, id, null, "IRON_SWORD", List.of(),
            0L, 0.0D, 0L, 1, null, Map.of(), List.of(), SkillKind.PASSIVE, bindRequired,
            SkillResourceType.MANA, 0.0D, id, 1, List.of(), List.of(), List.of()
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
        putSavingToken(target, playerId, UUID.randomUUID());
    }

    @SuppressWarnings("unchecked")
    private static void putSavingToken(Object target, UUID playerId, UUID operationToken) throws ReflectiveOperationException {
        ((Map<UUID, UUID>) fieldValue(target, "savingSessions")).put(playerId, operationToken);
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
    private static Map<UUID, ?> mapValue(Object target, String fieldName) throws ReflectiveOperationException {
        return (Map<UUID, ?>) fieldValue(target, fieldName);
    }

    private static Object fieldValue(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
