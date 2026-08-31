package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrb;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbEffect;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbEffectType;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbRankMode;
import io.github.maaasu.astralRecord.feature.item.model.ItemSigil;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.gui.SkillSigilOrbGuiHolder;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillSigil;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillSigilSlotDefinition;
import io.github.maaasu.astralRecord.feature.skill.registry.SkillRegistry;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillSigilOrbServiceTest extends MockBukkitTestBase {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 2. 合成画面 > ### 2.1 シジルオーブ操作画面
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 8. ガイド進捗評価
     * 検証契約: 装着オーブは起点entryを再検証し、対象個体と選択シジルでAPIを開始して、成功時にパッシブ・所持品表示を更新し使用したオーブIDをガイド進捗へ通知し、失敗時は通知しない。
     */
    @Test
    void attachOrbShowsLearnedSkillsAndUsesSelectedSigilEntry() {
        PluginMock plugin = MockBukkit.createMockPlugin("SkillSigilOrbServiceTest");
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        UUID accountId = astPlayer.getAccount().getUuid();
        UUID learnedSkillId = UUID.randomUUID();
        UUID sigilEntryId = UUID.randomUUID();
        LearnedSkillInstance learned = new LearnedSkillInstance(
            learnedSkillId,
            accountId,
            "adventurer_smash",
            1,
            List.of(),
            1,
            LocalDateTime.now(),
            LocalDateTime.now()
        );
        SkillDefinition definition = definition();
        ItemModel orb = orb();
        ItemModel sigil = sigil();
        UUID orbEntryId = UUID.randomUUID();
        InventoryEntryModel orbEntry = entry(accountId, orbEntryId, ItemCategory.ORB, orb.getId());
        InventoryEntryModel sigilEntry = entry(accountId, sigilEntryId, ItemCategory.SIGIL, sigil.getId());

        InventoryService inventoryService = mock(InventoryService.class);
        ItemService itemService = mock(ItemService.class);
        ItemStackFactory itemStackFactory = mock(ItemStackFactory.class);
        SkillService skillService = mock(SkillService.class);
        LearnedSkillService learnedSkillService = mock(LearnedSkillService.class);
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        SkillRegistry registry = mock(SkillRegistry.class);
        when(skillService.registry()).thenReturn(registry);
        when(registry.getDefinition("adventurer_smash")).thenReturn(definition);
        when(learnedSkillService.getLearnedSkills(accountId)).thenReturn(List.of(learned));
        when(learnedSkillService.findInstance(accountId, learnedSkillId)).thenReturn(learned);
        when(inventoryService.findOwnedEntry(accountId, orbEntryId)).thenReturn(orbEntry);
        when(inventoryService.getOwnedEntryAtBukkitSlot(astPlayer, 9)).thenReturn(sigilEntry);
        when(itemService.findLoadedById(orb.getId())).thenReturn(orb);
        when(itemService.findLoadedById(sigil.getId())).thenReturn(sigil);
        when(itemStackFactory.create(eq(sigil), eq(1))).thenReturn(new ItemStack(Material.AMETHYST_SHARD));
        AtomicReference<Consumer<LearnedSkillInstance>> success = new AtomicReference<>();
        AtomicReference<Consumer<Throwable>> failure = new AtomicReference<>();
        when(learnedSkillService.attachSigilAsync(
            eq(accountId), eq(learnedSkillId), eq(orbEntryId), eq(sigil.getId()),
            eq(sigilEntryId), eq(accountId), any(), any()
        )).thenAnswer(invocation -> {
            success.set(invocation.getArgument(6));
            failure.set(invocation.getArgument(7));
            return true;
        });

        SkillSigilOrbService service = new SkillSigilOrbService(
            plugin,
            inventoryService,
            itemService,
            itemStackFactory,
            skillService,
            learnedSkillService,
            passiveSkillService,
            (targetPlayer, inventory, onOpened, ignored) -> {
                targetPlayer.openInventory(inventory);
                onOpened.run();
            }
        );
        AtomicReference<String> guideOrbId = new AtomicReference<>();
        service.setUseSuccessListener((ignored, orbItemId) -> guideOrbId.set(orbItemId));
        service.start(player, astPlayer, orbEntryId, orb, false, () -> { });

        SkillSigilOrbGuiHolder listHolder = assertInstanceOf(
            SkillSigilOrbGuiHolder.class,
            player.getOpenInventory().getTopInventory().getHolder()
        );
        assertEquals(SkillSigilOrbGuiHolder.Screen.LIST, listHolder.screen());
        service.handleGuiClick(click(player, player.getOpenInventory().getTopInventory(), 0, true));
        SkillSigilOrbGuiHolder attachHolder = assertInstanceOf(
            SkillSigilOrbGuiHolder.class,
            player.getOpenInventory().getTopInventory().getHolder()
        );
        assertEquals(SkillSigilOrbGuiHolder.Screen.ATTACH, attachHolder.screen());

        service.handleGuiClick(click(player, player.getOpenInventory().getTopInventory(), 9, false));
        service.handleGuiClick(click(player, player.getOpenInventory().getTopInventory(), 16, true));

        verify(learnedSkillService).attachSigilAsync(
            eq(accountId), eq(learnedSkillId), eq(orbEntryId), eq(sigil.getId()),
            eq(sigilEntryId), eq(accountId), any(), any()
        );
        verify(inventoryService, atLeastOnce()).findOwnedEntry(accountId, orbEntryId);

        success.get().accept(learned);

        verify(passiveSkillService).markDirty(astPlayer);
        verify(inventoryService).refreshManagedInventoryUi(astPlayer);
        assertEquals(orb.getId(), guideOrbId.get());
        assertFalse(service.isSkillSigilInventory(player.getOpenInventory().getTopInventory()));

        guideOrbId.set(null);
        service.start(player, astPlayer, orbEntryId, orb, false, () -> { });
        service.handleGuiClick(click(player, player.getOpenInventory().getTopInventory(), 0, true));
        service.handleGuiClick(click(player, player.getOpenInventory().getTopInventory(), 9, false));
        service.handleGuiClick(click(player, player.getOpenInventory().getTopInventory(), 16, true));
        failure.get().accept(new IllegalStateException("test failure"));

        assertNull(guideOrbId.get());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 2. 合成画面 > ### 2.1 シジルオーブ操作画面
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 8. ガイド進捗評価
     * 検証契約: 脱着オーブは唯一の装着行UUIDでAPIを開始し、成功時にパッシブ・所持品表示を更新してGUIを閉じ、使用したオーブIDをガイド進捗へ通知する。
     */
    @Test
    void detachOrbUsesSelectedAttachmentId() {
        PluginMock plugin = MockBukkit.createMockPlugin("SkillSigilDetachOrbServiceTest");
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        UUID accountId = astPlayer.getAccount().getUuid();
        UUID learnedSkillId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        LearnedSkillInstance learned = new LearnedSkillInstance(
            learnedSkillId,
            accountId,
            "adventurer_smash",
            1,
            List.of(new LearnedSkillSigil(attachmentId, "cooldown_sigil", "cooldown", 0)),
            2,
            LocalDateTime.now(),
            LocalDateTime.now()
        );
        SkillDefinition definition = definition();
        ItemModel orb = detachOrb();
        ItemModel sigil = sigil();
        UUID orbEntryId = UUID.randomUUID();
        InventoryEntryModel orbEntry = entry(accountId, orbEntryId, ItemCategory.ORB, orb.getId());

        InventoryService inventoryService = mock(InventoryService.class);
        ItemService itemService = mock(ItemService.class);
        ItemStackFactory itemStackFactory = mock(ItemStackFactory.class);
        SkillService skillService = mock(SkillService.class);
        LearnedSkillService learnedSkillService = mock(LearnedSkillService.class);
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        SkillRegistry registry = mock(SkillRegistry.class);
        when(skillService.registry()).thenReturn(registry);
        when(registry.getDefinition("adventurer_smash")).thenReturn(definition);
        when(learnedSkillService.getLearnedSkills(accountId)).thenReturn(List.of(learned));
        when(learnedSkillService.findInstance(accountId, learnedSkillId)).thenReturn(learned);
        when(inventoryService.findOwnedEntry(accountId, orbEntryId)).thenReturn(orbEntry);
        when(itemService.findLoadedById(orb.getId())).thenReturn(orb);
        when(itemService.findLoadedById(sigil.getId())).thenReturn(sigil);
        when(itemStackFactory.create(eq(sigil), eq(1))).thenReturn(new ItemStack(Material.AMETHYST_SHARD));
        AtomicReference<Consumer<LearnedSkillInstance>> success = new AtomicReference<>();
        when(learnedSkillService.detachSigilAsync(
            eq(accountId), eq(learnedSkillId), eq(orbEntryId), eq(attachmentId), eq(accountId), any(), any()
        )).thenAnswer(invocation -> {
            success.set(invocation.getArgument(5));
            return true;
        });

        SkillSigilOrbService service = new SkillSigilOrbService(
            plugin,
            inventoryService,
            itemService,
            itemStackFactory,
            skillService,
            learnedSkillService,
            passiveSkillService,
            (targetPlayer, inventory, onOpened, ignored) -> {
                targetPlayer.openInventory(inventory);
                onOpened.run();
            }
        );
        AtomicReference<String> guideOrbId = new AtomicReference<>();
        service.setUseSuccessListener((ignored, orbItemId) -> guideOrbId.set(orbItemId));
        service.start(player, astPlayer, orbEntryId, orb, false, () -> { });
        service.handleGuiClick(click(player, player.getOpenInventory().getTopInventory(), 0, true));
        SkillSigilOrbGuiHolder detachHolder = assertInstanceOf(
            SkillSigilOrbGuiHolder.class,
            player.getOpenInventory().getTopInventory().getHolder()
        );
        assertEquals(SkillSigilOrbGuiHolder.Screen.DETACH, detachHolder.screen());

        service.handleGuiClick(click(player, player.getOpenInventory().getTopInventory(), 16, true));

        verify(learnedSkillService).detachSigilAsync(
            eq(accountId), eq(learnedSkillId), eq(orbEntryId), eq(attachmentId), eq(accountId), any(), any()
        );

        success.get().accept(learned);

        verify(passiveSkillService).markDirty(astPlayer);
        verify(inventoryService).refreshManagedInventoryUi(astPlayer);
        assertEquals(orb.getId(), guideOrbId.get());
        assertFalse(service.isSkillSigilInventory(player.getOpenInventory().getTopInventory()));
    }

    private static InventoryClickEvent click(PlayerMock player, Inventory top, int slot, boolean topClick) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getView()).thenReturn(player.getOpenInventory());
        when(event.getClickedInventory()).thenReturn(topClick ? top : player.getInventory());
        when(event.getRawSlot()).thenReturn(slot);
        when(event.getSlot()).thenReturn(slot);
        when(event.getClick()).thenReturn(ClickType.LEFT);
        return event;
    }

    private static SkillDefinition definition() {
        return new SkillDefinition(
            "adventurer_smash", "adventurer_smash", "スマッシュ", "説明", "IRON_SWORD", List.of(),
            0L, 0.0D, 0L, 1, null, Map.of(), List.of(), SkillKind.ACTIVE, true,
            null, null, "adventurer_smash", 3, List.of(),
            List.of(new SkillSigilSlotDefinition(1, 1)), List.of("cooldown_sigil")
        );
    }

    private static ItemModel orb() {
        ItemOrbEffect effect = new ItemOrbEffect(
            ItemOrbEffectType.SIGIL_ATTACH,
            List.of(),
            null,
            ItemOrbRankMode.EXACT,
            null,
            false,
            null,
            null
        );
        return item("bragi_orb", ItemCategory.ORB, null, new ItemOrb(effect));
    }

    private static ItemModel detachOrb() {
        ItemOrbEffect effect = new ItemOrbEffect(
            ItemOrbEffectType.SIGIL_DETACH,
            List.of(),
            null,
            ItemOrbRankMode.EXACT,
            null,
            false,
            null,
            null
        );
        return item("mimir_orb", ItemCategory.ORB, null, new ItemOrb(effect));
    }

    private static ItemModel sigil() {
        return item("cooldown_sigil", ItemCategory.SIGIL, new ItemSigil("cooldown", List.of()), null);
    }

    private static ItemModel item(
        String id,
        ItemCategory category,
        ItemSigil sigil,
        ItemOrb orb
    ) {
        return new ItemModel(
            1, id, category.getApiValue(), id, "AMETHYST_SHARD", "common", 64, 0,
            null, null, List.of(), false, false, null, null, null, null, null, null,
            sigil, orb
        );
    }

    private static InventoryEntryModel entry(
        UUID accountId,
        UUID entryId,
        ItemCategory category,
        String itemId
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
            entryId,
            UUID.randomUUID(),
            null,
            category.getApiValue(),
            itemId,
            null,
            null,
            1,
            null,
            now,
            now,
            accountId,
            accountId,
            false
        );
    }
}
