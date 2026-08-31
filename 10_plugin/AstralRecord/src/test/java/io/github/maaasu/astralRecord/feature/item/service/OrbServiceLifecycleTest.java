package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.inventory.event.InventoryEquipmentGuiEventHandler;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryClickGuard;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.item.gui.OrbGuiHolder;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentOrbOperationResult;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentOrbOperationResultType;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentRune;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentDurability;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceMaterial;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentHandType;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentRuneDef;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentTranscendence;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrb;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbEffect;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbEffectType;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbRankMode;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.item.model.ItemRune;
import io.github.maaasu.astralRecord.feature.menu.event.MenuOpenEventHandler;
import io.github.maaasu.astralRecord.feature.menu.service.MenuGuiTransitionService;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrbServiceLifecycleTest extends MockBukkitTestBase {

    @AfterEach
    void clearPlayerCache() {
        AstPlayerCache.clear();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 7. プレイヤーがオーブから装備操作を開始する
     * 検証契約: 装備IDのpreloadは非同期で完了し、token再検証後のmain thread候補収集はcache-onlyで装備中を先頭かつ重複なしに描画する。
     */
    @Test
    void asyncPreloadReturnsToCurrentTokenAndRendersCacheOnlyEquippedFirst() {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);

        InventoryClickEvent openEvent = harness.openOrbList();

        verify(openEvent).setCancelled(true);
        assertFalse(harness.preloadRanOnPrimaryThread.get());
        verify(harness.itemService, never()).findEquipmentInstanceById(anyString());
        verify(harness.itemService, atLeastOnce())
            .findLoadedEquipmentInstanceById(harness.equippedInstanceId.toString());
        Inventory top = harness.player.getOpenInventory().getTopInventory();
        assertTrue(top.getHolder() instanceof OrbGuiHolder);
        assertEquals(Material.DIAMOND_SWORD, top.getItem(0).getType());
        assertEquals(Material.IRON_SWORD, top.getItem(1).getType());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 1.5. インベントリ内オーブ一覧
     * 検証契約: 情報アイコンから54スロットの枠付きオーブ一覧を開き、同一オーブの複数entryを合算した数量を表示し、クリックしたオーブの装備候補GUIへ遷移する。
     */
    @Test
    void inventoryInfoOpensAggregatedOrbListAndSelectingOrbStartsOperation() {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        harness.orbQuantity.set(64);
        harness.additionalOrbQuantity = 32;

        InventoryClickEvent infoClick = harness.normalInventoryClick(26);
        harness.handler.onInventoryClick(infoClick);

        verify(infoClick).setCancelled(true);
        Inventory orbList = harness.player.getOpenInventory().getTopInventory();
        OrbGuiHolder holder = (OrbGuiHolder) orbList.getHolder();
        assertEquals(OrbGuiHolder.Screen.INVENTORY_ORB_LIST, holder.screen());
        assertEquals(OrbGuiHolder.SIZE, orbList.getSize());
        assertEquals(Material.BLACK_STAINED_GLASS_PANE, orbList.getItem(0).getType());
        assertNotNull(orbList.getItem(10));
        assertTrue(orbList.getItem(10).getItemMeta().lore().stream()
            .anyMatch(line -> line.toString().contains("所持数: 96")));

        harness.handler.onInventoryClick(harness.guiClick(10));
        harness.awaitOrbScreen(OrbGuiHolder.Screen.LIST);
        verify(harness.inventoryService, atLeastOnce()).findOwnedNormalItemEntryForConsumption(
            harness.accountId,
            harness.orbModel.getId()
        );
        assertEquals(Material.DIAMOND_SWORD,
            harness.player.getOpenInventory().getTopInventory().getItem(0).getType());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 1.5. インベントリ内オーブ一覧
     * 検証契約: 集約一覧からオーブ操作を開始した場合、共通消費順で解決した後方slotのentry IDをAPI操作へ渡す。
     */
    @Test
    void inventoryOrbListSelectionUsesBackmostEntryForOrbPayment() {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        harness.orbQuantity.set(64);
        harness.additionalOrbQuantity = 32;

        harness.handler.onInventoryClick(harness.normalInventoryClick(26));
        harness.handler.onInventoryClick(harness.guiClick(10));
        harness.awaitOrbScreen(OrbGuiHolder.Screen.LIST);
        harness.handler.onInventoryClick(harness.guiClick(0));
        harness.laneExecutor.runAll();

        verify(harness.inventoryService).reserveOrbOperationPayment(
            eq(harness.accountId),
            any(UUID.class),
            any(),
            anyLong()
        );
        verify(harness.itemService, atLeastOnce()).applyEquipmentOrbOperation(
            anyString(),
            eq(harness.accountId.toString()),
            anyString(),
            eq(harness.additionalOrbEntryId.toString()),
            eq(harness.orbModel.getId()),
            any(),
            any()
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 7. プレイヤーがオーブから装備操作を開始する
     * 検証契約: 通常インベントリでクリックしたentryが消えていても、同じitem IDの通常stackがあれば共通消費順で再解決して操作する。
     */
    @Test
    void directInventoryOrbSelectionSwitchesToTheCommonConsumptionStack() {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        harness.additionalOrbQuantity = 32;

        harness.openOrbList();
        InventoryClickEvent targetClick = harness.guiClick(0);
        harness.handler.onInventoryClick(targetClick);
        harness.laneExecutor.runAll();

        verify(targetClick).setCancelled(true);
        verify(harness.inventoryService, atLeastOnce()).findOwnedNormalItemEntryForConsumption(
            harness.accountId,
            harness.orbModel.getId()
        );
        verify(harness.inventoryService).reserveOrbOperationPayment(
            eq(harness.accountId), any(UUID.class), any(), anyLong());
        verify(harness.itemService, atLeastOnce()).applyEquipmentOrbOperation(
            anyString(),
            eq(harness.accountId.toString()),
            anyString(),
            eq(harness.additionalOrbEntryId.toString()),
            eq(harness.orbModel.getId()),
            any(),
            any()
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 1.5. インベントリ内オーブ一覧
     * 検証契約: 対象装備がないオーブを選んでも一覧へ戻り、次のオーブ選択を受け付ける。
     */
    @Test
    void inventoryOrbWithoutEligibleTargetReturnsToListForNextSelection() {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        harness.handler.onInventoryClick(harness.normalInventoryClick(26));
        Inventory firstList = harness.player.getOpenInventory().getTopInventory();
        when(harness.itemService.findLoadedEquipmentInstanceById(anyString())).thenReturn(null);

        InventoryClickEvent unavailableOrbClick = harness.guiClick(10);
        harness.handler.onInventoryClick(unavailableOrbClick);
        harness.awaitOrbScreenAfter(firstList, OrbGuiHolder.Screen.INVENTORY_ORB_LIST);
        Inventory restoredList = harness.player.getOpenInventory().getTopInventory();

        verify(unavailableOrbClick).setCancelled(true);
        assertTrue(restoredList.getHolder() instanceof OrbGuiHolder holder
            && holder.screen() == OrbGuiHolder.Screen.INVENTORY_ORB_LIST);

        when(harness.itemService.findLoadedEquipmentInstanceById(harness.equippedInstanceId.toString()))
            .thenReturn(harness.equippedInstance.get());
        InventoryClickEvent retryClick = harness.guiClick(10);
        harness.handler.onInventoryClick(retryClick);
        harness.awaitOrbScreenAfter(restoredList, OrbGuiHolder.Screen.LIST);

        verify(retryClick).setCancelled(true);
        assertEquals(Material.DIAMOND_SWORD,
            harness.player.getOpenInventory().getTopInventory().getItem(0).getType());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 10. ホットバー操作
     * 検証契約: オーブの対象装備一覧画面では上段クリックを専用処理しつつ、下段のBAGスクロールクリックを共通ショートカットへ委譲する。
     */
    @Test
    void operationOrbGuiDelegatesBagScrollClicksToSharedShortcutSupport() {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        harness.openOrbList();
        when(harness.inventoryService.isHotbarShortcutMode(harness.astPlayer)).thenReturn(true);

        for (int slot : List.of(17, 35)) {
            when(harness.inventoryService.handleInventoryControlClick(harness.astPlayer, slot))
                .thenReturn(true);
            InventoryClickEvent scrollClick = harness.guiPlayerInventoryClick(slot);
            harness.handler.onInventoryClick(scrollClick);

            verify(scrollClick).setCancelled(true);
            verify(harness.inventoryService).handleInventoryControlClick(harness.astPlayer, slot);
        }
        assertTrue(harness.service.isOrbInventory(
            harness.player.getOpenInventory().getTopInventory()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 10. ホットバー操作
     * 検証契約: オーブの対象装備一覧画面では、下段ホットバークリックを共通ショートカットへ委譲する。
     */
    @Test
    void operationOrbGuiDelegatesHotbarClickToSharedShortcutSupport() {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        harness.openOrbList();
        when(harness.inventoryService.isHotbarShortcutMode(harness.astPlayer)).thenReturn(true);
        when(harness.inventoryService.getClickGuard()).thenReturn(new InventoryClickGuard());
        when(harness.inventoryService.handleHotbarSlotClick(harness.astPlayer, 1))
            .thenReturn(true);

        InventoryClickEvent hotbarClick = harness.guiPlayerInventoryClick(0);
        harness.handler.onInventoryClick(hotbarClick);

        verify(hotbarClick).setCancelled(true);
        verify(harness.inventoryService).handleHotbarSlotClick(harness.astPlayer, 1);
        assertTrue(harness.service.isOrbInventory(
            harness.player.getOpenInventory().getTopInventory()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 10. ホットバー操作
     * 検証契約: インベントリ内オーブ一覧画面では、下段ホットバークリックを共通ショートカットへ委譲し、オーブ一覧の上段処理で握り潰さない。
     */
    @Test
    void inventoryOrbListDelegatesHotbarClickToSharedShortcutSupport() {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        harness.handler.onInventoryClick(harness.normalInventoryClick(26));
        when(harness.inventoryService.isHotbarShortcutMode(harness.astPlayer)).thenReturn(true);
        when(harness.inventoryService.getClickGuard()).thenReturn(new InventoryClickGuard());
        when(harness.inventoryService.handleHotbarSlotClick(harness.astPlayer, 1))
            .thenReturn(true);

        InventoryClickEvent hotbarClick = harness.guiPlayerInventoryClick(0);
        harness.handler.onInventoryClick(hotbarClick);

        verify(hotbarClick).setCancelled(true);
        verify(harness.inventoryService).handleHotbarSlotClick(harness.astPlayer, 1);
        assertEquals(OrbGuiHolder.Screen.INVENTORY_ORB_LIST,
            ((OrbGuiHolder) harness.player.getOpenInventory().getTopInventory().getHolder()).screen());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 10. ホットバー操作
     * 検証契約: インベントリ内オーブ一覧画面では、上下のBAGスクロールクリックを共通ショートカットへ委譲する。
     */
    @Test
    void inventoryOrbListDelegatesBagScrollClicksToSharedShortcutSupport() {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        harness.handler.onInventoryClick(harness.normalInventoryClick(26));
        when(harness.inventoryService.isHotbarShortcutMode(harness.astPlayer)).thenReturn(true);

        for (int slot : List.of(17, 35)) {
            when(harness.inventoryService.handleInventoryControlClick(harness.astPlayer, slot))
                .thenReturn(true);
            InventoryClickEvent scrollClick = harness.guiPlayerInventoryClick(slot);
            harness.handler.onInventoryClick(scrollClick);

            verify(scrollClick).setCancelled(true);
            verify(harness.inventoryService).handleInventoryControlClick(harness.astPlayer, slot);
        }
        assertEquals(OrbGuiHolder.Screen.INVENTORY_ORB_LIST,
            ((OrbGuiHolder) harness.player.getOpenInventory().getTopInventory().getHolder()).screen());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 1.5. インベントリ内オーブ一覧
     * 検証契約: オーブ操作GUIの下段中央の起点オーブをクリックすると、所持オーブ一覧へ戻る。
     */
    @Test
    void operationInfoOrbReturnsToInventoryOrbList() {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        harness.openOrbList();
        Inventory operationInventory = harness.player.getOpenInventory().getTopInventory();
        long openSoundCountBefore = heardSoundCount(harness.player, Sound.BLOCK_CHEST_OPEN);
        long selectSoundCountBefore = heardSoundCount(harness.player, Sound.UI_BUTTON_CLICK);

        harness.handler.onInventoryClick(harness.guiClick(49));
        InventoryCloseEvent oldOperationClose = mock(InventoryCloseEvent.class);
        when(oldOperationClose.getPlayer()).thenReturn(harness.player);
        when(oldOperationClose.getInventory()).thenReturn(operationInventory);
        harness.handler.onInventoryClose(oldOperationClose);

        assertEquals(
            OrbGuiHolder.Screen.INVENTORY_ORB_LIST,
            ((OrbGuiHolder) harness.player.getOpenInventory().getTopInventory().getHolder()).screen()
        );
        assertEquals(OrbGuiHolder.SIZE, harness.player.getOpenInventory().getTopInventory().getSize());
        assertEquals(openSoundCountBefore, heardSoundCount(harness.player, Sound.BLOCK_CHEST_OPEN));
        assertEquals(selectSoundCountBefore + 1, heardSoundCount(harness.player, Sound.UI_BUTTON_CLICK));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 1.5. インベントリ内オーブ一覧
     * 検証契約: 一覧から操作GUIへ切り替えた後に旧一覧のclose eventが届いても操作GUIを維持する。
     */
    @Test
    void inventoryOrbListSelectionKeepsOperationAfterOldListClose() {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        harness.handler.onInventoryClick(harness.normalInventoryClick(26));
        Inventory oldList = harness.player.getOpenInventory().getTopInventory();

        harness.handler.onInventoryClick(harness.guiClick(10));
        harness.awaitOrbScreen(OrbGuiHolder.Screen.LIST);
        Inventory operationInventory = harness.player.getOpenInventory().getTopInventory();

        InventoryCloseEvent oldListClose = mock(InventoryCloseEvent.class);
        when(oldListClose.getPlayer()).thenReturn(harness.player);
        when(oldListClose.getInventory()).thenReturn(oldList);
        harness.handler.onInventoryClose(oldListClose);

        assertSame(operationInventory, harness.player.getOpenInventory().getTopInventory());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 1.5. インベントリ内オーブ一覧
     * 検証契約: 29種類以上のオーブを28種類単位でページ分割し、前後ページボタンを状態に応じて描画する。
     */
    @Test
    void inventoryOrbListPagesAtTwentyEightOrbTypes() {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        harness.addOrbTypesForPaging(28);
        harness.handler.onInventoryClick(harness.normalInventoryClick(26));
        Inventory list = harness.player.getOpenInventory().getTopInventory();

        assertEquals(Material.ARROW, list.getItem(53).getType());
        assertEquals(Material.GRAY_DYE, list.getItem(45).getType());

        harness.handler.onInventoryClick(harness.guiClick(53));

        assertEquals(Material.ARROW, list.getItem(45).getType());
        assertEquals(Material.GRAY_DYE, list.getItem(53).getType());
        assertNotNull(list.getItem(10));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 1.5. インベントリ内オーブ一覧
     * 検証契約: 空一覧と表示後に消費された stale entry のクリックを安全に拒否し、一覧を再描画する。
     */
    @Test
    void emptyOrStaleInventoryOrbListIsRedrawnWithoutStartingOperation() {
        Harness emptyHarness = new Harness(ItemOrbEffectType.REPAIR);
        emptyHarness.orbQuantity.set(0);
        emptyHarness.handler.onInventoryClick(emptyHarness.normalInventoryClick(26));
        Inventory emptyList = emptyHarness.player.getOpenInventory().getTopInventory();
        assertEquals(Material.AIR, emptyList.getItem(10).getType());
        assertEquals(Material.GRAY_DYE, emptyList.getItem(45).getType());
        assertEquals(Material.GRAY_DYE, emptyList.getItem(53).getType());

        Harness staleHarness = new Harness(ItemOrbEffectType.REPAIR);
        staleHarness.handler.onInventoryClick(staleHarness.normalInventoryClick(26));
        Inventory staleList = staleHarness.player.getOpenInventory().getTopInventory();
        staleHarness.orbQuantity.set(0);
        staleHarness.handler.onInventoryClick(staleHarness.guiClick(10));

        assertSame(staleList, staleHarness.player.getOpenInventory().getTopInventory());
        assertEquals(Material.AIR, staleList.getItem(10).getType());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 7. プレイヤーがオーブから装備操作を開始する
     * 検証契約: preload完了前にログイン世代tokenが変わった場合、遅延したmain thread継続は旧世代GUIを開かず候補収集も実行しない。
     */
    @Test
    void staleLoginGenerationRejectsDelayedPreloadCompletion() throws Exception {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        CountDownLatch preloadStarted = new CountDownLatch(1);
        CountDownLatch releasePreload = new CountDownLatch(1);
        when(harness.itemService.preloadEquipmentInstances(any())).thenAnswer(invocation -> {
            preloadStarted.countDown();
            assertTrue(releasePreload.await(2, TimeUnit.SECONDS));
            return ItemService.EquipmentPreloadResult.COMPLETE;
        });

        harness.handler.onInventoryClick(harness.normalInventoryClick());
        assertTrue(preloadStarted.await(2, TimeUnit.SECONDS));
        AstPlayer replacement = DesignTestFixtures.astPlayer(harness.player, AccountMode.PLAYER);
        AstPlayerCache.put(replacement);
        releasePreload.countDown();
        server().getScheduler().waitAsyncTasksFinished();

        assertFalse(harness.service.isOrbInventory(
            harness.player.getOpenInventory().getTopInventory()));
        verify(harness.itemService, never()).findLoadedEquipmentInstanceById(anyString());
        harness.service.prepareForPlayerSave(harness.player);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_2-ユースケース.md
     * 章・見出し: # 04_2-ユースケース > ## 9. オーブで装備を更新する
     * 検証契約: 一覧表示後に対象装備が条件外へ変化した場合、クリック時のcache正本再検証でAPI操作を開始せず全候補を再描画する。
     */
    @Test
    void targetClickRevalidatesEligibilityBeforeStartingApiOperation() {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        harness.openOrbList();
        harness.equippedInstance.set(harness.instance(
            harness.equippedInstanceId,
            "equipped_sword",
            0,
            100
        ));

        InventoryClickEvent targetClick = harness.guiClick(0);
        harness.handler.onInventoryClick(targetClick);

        verify(targetClick).setCancelled(true);
        assertEquals(0, harness.laneExecutor.pendingCount());
        verify(harness.itemService, never()).applyEquipmentOrbOperation(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());
        assertEquals(Material.IRON_SWORD,
            harness.player.getOpenInventory().getTopInventory().getItem(0).getType());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_2-ユースケース.md
     * 章・見出し: # 04_2-ユースケース > ## 9. オーブで装備を更新する
     * 検証契約: ルーン装着GUIで装備のslotとtagに一致する所持ルーンを選択すると、完成プレビューが有効になり、RUNE_ATTACH API要求へルーンIDを渡す。
     */
    @Test
    void runeAttachGuiAcceptsRuneMatchingEquipmentSlotAndTag() {
        Harness harness = new Harness(ItemOrbEffectType.RUNE_ATTACH);
        harness.terminalApplyCall.set(1);
        when(harness.inventoryService.isHotbarShortcutMode(harness.astPlayer)).thenReturn(true);
        when(harness.inventoryService.handleInventoryControlClick(harness.astPlayer, 17)).thenReturn(true);
        harness.openOrbList();
        harness.handler.onInventoryClick(harness.guiClick(0));
        harness.awaitOrbScreen(OrbGuiHolder.Screen.RUNE_ATTACH);

        InventoryClickEvent scrollClick = harness.guiPlayerInventoryClick(17);
        harness.handler.onInventoryClick(scrollClick);
        verify(scrollClick).setCancelled(true);
        verify(harness.inventoryService).handleInventoryControlClick(harness.astPlayer, 17);

        when(harness.inventoryService.handleHotbarSlotClick(harness.astPlayer, 5)).thenReturn(true);
        when(harness.inventoryService.getClickGuard()).thenReturn(new InventoryClickGuard());
        InventoryClickEvent hotbarClick = harness.guiPlayerInventoryClick(4);
        harness.handler.onInventoryClick(hotbarClick);
        verify(hotbarClick).setCancelled(true);
        verify(harness.inventoryService).handleHotbarSlotClick(harness.astPlayer, 5);

        InventoryClickEvent runeClick = harness.guiPlayerInventoryClick(10);
        harness.handler.onInventoryClick(runeClick);
        verify(runeClick).setCancelled(true);
        verify(harness.inventoryService).getOwnedEntryAtBukkitSlot(eq(harness.astPlayer), eq(10));
        verify(harness.itemService, atLeastOnce()).findLoadedById(harness.runeModel.getId());
        assertEquals(Material.AMETHYST_SHARD,
            harness.player.getOpenInventory().getTopInventory().getItem(13).getType());
        assertNotEquals(Material.BARRIER,
            harness.player.getOpenInventory().getTopInventory().getItem(16).getType());

        harness.handler.onInventoryClick(harness.guiClick(13));
        assertEquals(Material.CHEST,
            harness.player.getOpenInventory().getTopInventory().getItem(13).getType());
        assertEquals(Material.BARRIER,
            harness.player.getOpenInventory().getTopInventory().getItem(16).getType());
        harness.handler.onInventoryClick(harness.guiPlayerInventoryClick(10));

        harness.handler.onInventoryClick(harness.guiClick(16));
        assertEquals(Material.CLOCK,
            harness.player.getOpenInventory().getTopInventory().getItem(16).getType());
        harness.laneExecutor.runAll();
        server().getScheduler().performOneTick();
        assertFalse(harness.service.isOrbInventory(
            harness.player.getOpenInventory().getTopInventory()));

        verify(harness.itemService).applyEquipmentOrbOperation(
            anyString(),
            eq(harness.accountId.toString()),
            eq(harness.equippedInstanceId.toString()),
            eq(harness.orbEntryId.toString()),
            eq(harness.orbModel.getId()),
            eq(harness.runeModel.getId()),
            isNull()
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_2-ユースケース.md
     * 章・見出し: # 04_2-ユースケース > ## 9. オーブで装備を更新する
     * 検証契約: 複数ルーンの脱着GUIはルーン選択とBAGスクロールを受け付け、完成形slot 16へ時計を表示し、正本照合完了時に自動で閉じる。
     */
    @Test
    void runeDetachGuiSelectsRuneDelegatesScrollAndClosesAfterReconciliation() {
        Harness harness = new Harness(ItemOrbEffectType.RUNE_DETACH);
        harness.terminalApplyCall.set(1);
        List<EquipmentRune> attachedRunes = new ArrayList<>();
        for (int index = 0; index < 27; index++) {
            attachedRunes.add(new EquipmentRune(
                "rune-" + index,
                harness.equippedInstanceId.toString(),
                index,
                harness.runeModel.getId()
            ));
        }
        harness.equippedInstance.set(harness.instance(
            harness.equippedInstanceId,
            harness.equippedModel.getId(),
            0,
            100,
            attachedRunes
        ));
        when(harness.inventoryService.isHotbarShortcutMode(harness.astPlayer)).thenReturn(true);
        when(harness.inventoryService.handleInventoryControlClick(harness.astPlayer, 17)).thenReturn(true);

        harness.openOrbList();
        harness.handler.onInventoryClick(harness.guiClick(0));
        harness.awaitOrbScreen(OrbGuiHolder.Screen.RUNE_DETACH);
        assertEquals(Material.SPECTRAL_ARROW,
            harness.player.getOpenInventory().getTopInventory().getItem(22).getType());

        harness.handler.onInventoryClick(harness.guiClick(22));
        harness.awaitOrbScreen(OrbGuiHolder.Screen.LIST);
        harness.handler.onInventoryClick(harness.guiClick(0));
        harness.awaitOrbScreen(OrbGuiHolder.Screen.RUNE_DETACH);

        InventoryClickEvent scrollClick = harness.guiPlayerInventoryClick(17);
        harness.handler.onInventoryClick(scrollClick);
        verify(scrollClick).setCancelled(true);
        verify(harness.inventoryService).handleInventoryControlClick(harness.astPlayer, 17);

        harness.handler.onInventoryClick(harness.guiClick(13));
        harness.awaitOrbScreen(OrbGuiHolder.Screen.RUNE_DETACH_SELECT);
        assertEquals(OrbGuiHolder.RUNE_SIZE,
            harness.player.getOpenInventory().getTopInventory().getSize());
        assertEquals(Material.SPECTRAL_ARROW,
            harness.player.getOpenInventory().getTopInventory().getItem(22).getType());

        harness.handler.onInventoryClick(harness.guiClick(22));
        harness.awaitOrbScreen(OrbGuiHolder.Screen.RUNE_DETACH);
        harness.handler.onInventoryClick(harness.guiClick(13));
        harness.awaitOrbScreen(OrbGuiHolder.Screen.RUNE_DETACH_SELECT);

        harness.handler.onInventoryClick(harness.guiClick(18));
        assertTrue(harness.player.getOpenInventory().getTopInventory().getHolder() instanceof OrbGuiHolder previousHolder
            && previousHolder.screen() == OrbGuiHolder.Screen.RUNE_DETACH_SELECT);
        harness.handler.onInventoryClick(harness.guiClick(19));
        assertTrue(harness.player.getOpenInventory().getTopInventory().getHolder() instanceof OrbGuiHolder fillerHolder
            && fillerHolder.screen() == OrbGuiHolder.Screen.RUNE_DETACH_SELECT);

        harness.handler.onInventoryClick(harness.guiClick(0));
        harness.awaitOrbScreen(OrbGuiHolder.Screen.RUNE_DETACH);
        assertEquals(Material.AMETHYST_SHARD,
            harness.player.getOpenInventory().getTopInventory().getItem(13).getType());
        assertNotEquals(Material.BARRIER,
            harness.player.getOpenInventory().getTopInventory().getItem(16).getType());

        harness.handler.onInventoryClick(harness.guiClick(16));
        assertEquals(Material.CLOCK,
            harness.player.getOpenInventory().getTopInventory().getItem(16).getType());
        InventoryClickEvent lockedScrollClick = harness.guiPlayerInventoryClick(17);
        harness.handler.onInventoryClick(lockedScrollClick);
        verify(lockedScrollClick).setCancelled(true);
        verify(harness.inventoryService, times(1)).handleInventoryControlClick(harness.astPlayer, 17);
        harness.laneExecutor.runAll();
        server().getScheduler().performOneTick();
        verify(harness.itemService).applyEquipmentOrbOperation(
            anyString(),
            eq(harness.accountId.toString()),
            eq(harness.equippedInstanceId.toString()),
            eq(harness.orbEntryId.toString()),
            eq(harness.orbModel.getId()),
            eq(harness.runeModel.getId()),
            eq(0)
        );

        assertFalse(harness.service.isOrbInventory(
            harness.player.getOpenInventory().getTopInventory()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_2-ユースケース.md
     * 章・見出し: # 04_2-ユースケース > ## 9. オーブで装備を更新する
     * 検証契約: 装着済みルーンが1件だけなら脱着確認GUIでそのルーンを自動選択し、追加の選択画面を要求しない。
     */
    @Test
    void runeDetachGuiAutomaticallySelectsTheOnlyAttachedRune() {
        Harness harness = new Harness(ItemOrbEffectType.RUNE_DETACH);
        harness.equippedInstance.set(harness.instance(
            harness.equippedInstanceId,
            harness.equippedModel.getId(),
            0,
            70,
            List.of(new EquipmentRune(
                "rune-0",
                harness.equippedInstanceId.toString(),
                0,
                harness.runeModel.getId()
            ))
        ));

        harness.openOrbList();
        harness.handler.onInventoryClick(harness.guiClick(0));
        harness.awaitOrbScreen(OrbGuiHolder.Screen.RUNE_DETACH);

        assertEquals(Material.AMETHYST_SHARD,
            harness.player.getOpenInventory().getTopInventory().getItem(13).getType());
        assertNotEquals(Material.BARRIER,
            harness.player.getOpenInventory().getTopInventory().getItem(16).getType());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_2-ユースケース.md
     * 章・見出し: # 04_2-ユースケース > ## 9. オーブで装備を更新する
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 7. プレイヤーがオーブから装備操作を開始する
     * 検証契約: 修理は処理中に時計を一度表示して全入力をロックし、pre-save後の同一operationIdによるPOST・GET・再送・affected entry照合完了と同じtickで候補を更新し、オーブitem IDをガイド進捗へ通知する。
     */
    @Test
    void appliedRepairShowsClockAndRefreshesImmediatelyAfterReconciliation() {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        harness.openOrbList();
        assertEquals(Material.DIAMOND_SWORD,
            harness.player.getOpenInventory().getTopInventory().getItem(0).getType());

        harness.handler.onInventoryClick(harness.guiClick(0));

        assertEquals(2, harness.orbQuantity.get());
        assertTrue(harness.service.isLocked(harness.player));
        harness.assertAllInputsLocked();
        assertEquals(Material.CLOCK,
            harness.player.getOpenInventory().getTopInventory().getItem(0).getType());
        harness.laneExecutor.runAll();

        assertEquals(List.of("pre-save", "post", "get", "retry", "reconcile"), harness.order);
        assertEquals(1, harness.orbQuantity.get());
        assertEquals(3, harness.operationIds.size());
        assertEquals(1, harness.operationIds.stream().distinct().count());
        server().getScheduler().performOneTick();
        verify(harness.statusService).refreshStatus(harness.astPlayer);

        assertFalse(harness.service.isLocked(harness.player));
        assertTrue(harness.service.isOrbInventory(
            harness.player.getOpenInventory().getTopInventory()));
        assertEquals(Material.IRON_SWORD,
            harness.player.getOpenInventory().getTopInventory().getItem(0).getType());
        assertEquals(List.of(harness.orbModel.getId()), harness.usedOrbIds);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_2-ユースケース.md
     * 章・見出し: # 04_2-ユースケース > ## 9. オーブで装備を更新する
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 7. プレイヤーがオーブから装備操作を開始する
     * 検証契約: APIがNO_CANDIDATEを確定した場合はpaymentConsumed=falseのままオーブ数量と装備を変更せず、時計表示を戻して一覧操作を再開し、ガイド進捗へ通知しない。
     */
    @Test
    void noCandidateResultDoesNotConsumeOrbOrMutateEquipment() {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        harness.apiResultType.set(EquipmentOrbOperationResultType.NO_CANDIDATE);
        EquipmentInstance before = harness.equippedInstance.get();
        harness.openOrbList();

        harness.handler.onInventoryClick(harness.guiClick(0));
        harness.laneExecutor.runAll();
        server().getScheduler().performOneTick();

        assertEquals(2, harness.orbQuantity.get());
        assertSame(before, harness.equippedInstance.get());
        assertFalse(harness.service.isLocked(harness.player));
        assertTrue(harness.service.isOrbInventory(
            harness.player.getOpenInventory().getTopInventory()));
        verify(harness.inventoryService).reconcileOrbOperationEntries(
            eq(harness.accountId),
            org.mockito.ArgumentMatchers.argThat(ids -> ids.contains(harness.orbEntryId)),
            any(InventoryPersistence.PersistedInventoryBaseline.class)
        );
        assertEquals(List.of(), harness.usedOrbIds);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: 台帳再生でpayment消費済みAPPLIEDかつ装備が削除・所有者変更済みなら、affected entryを照合してterminal完了し、旧target cache・managed表示・statusを再構築して別所有者装備を残さない。
     */
    @Test
    void terminalAppliedWithoutEquipmentReconcilesPaymentAndCompletesSafely() {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        harness.ledgerTerminal.set(true);
        harness.terminalEquipmentMissing.set(true);
        harness.openOrbList();

        harness.handler.onInventoryClick(harness.guiClick(0));
        var followingSave = harness.coordinator.saveAuto(harness.state);
        harness.laneExecutor.runAll();
        server().getScheduler().performOneTick();

        assertEquals(1, harness.applyCount.get());
        assertEquals(1, harness.reconcileAttempts.get());
        assertEquals(1, harness.orbQuantity.get());
        assertNull(harness.equippedInstance.get());
        assertTrue(followingSave.join());
        assertEquals(List.of(
            "pre-save", "post", "get", "reconcile", "evict", "discard",
            "auto-save"
        ), harness.order);
        assertFalse(harness.service.isLocked(harness.player));
        assertFalse(harness.coordinator.hasUnresolvedExternalOperation(harness.accountId));
        verify(harness.itemService).evictEquipmentInstanceFromCache(
            harness.equippedInstanceId.toString());
        verify(harness.inventoryService).discardUnavailableEquipmentInstance(
            harness.accountId, harness.equippedInstanceId);
        verify(harness.persistence).saveNow(harness.state);
        verify(harness.inventoryService).refreshManagedInventoryUi(harness.astPlayer);
        verify(harness.inventoryService).refreshEquipmentDisplaysForSave(harness.astPlayer);
        verify(harness.statusService).refreshStatus(harness.astPlayer);
        assertEquals(Material.IRON_SWORD,
            harness.player.getOpenInventory().getTopInventory().getItem(0).getType());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: 初回NOT_ELIGIBLEが所有・削除・membership不在をtargetAvailable=falseで返した場合、旧cache/state参照を破棄し、同account lane内のcleanup保存が成功するまで操作を確定しない。
     */
    @Test
    void notEligibleUnavailableTargetIsTombstonedAndPersistedInsideTheLane() {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        harness.apiResultType.set(EquipmentOrbOperationResultType.NOT_ELIGIBLE);
        harness.terminalEquipmentMissing.set(true);
        harness.openOrbList();

        harness.handler.onInventoryClick(harness.guiClick(0));
        var followingSave = harness.coordinator.saveAuto(harness.state);
        harness.laneExecutor.runAll();
        server().getScheduler().performOneTick();

        assertTrue(followingSave.join());
        assertEquals(2, harness.orbQuantity.get());
        assertNull(harness.equippedInstance.get());
        assertEquals(List.of(
            "pre-save", "post", "reconcile", "evict", "discard",
            "auto-save"
        ), harness.order);
        verify(harness.itemService).evictEquipmentInstanceFromCache(
            harness.equippedInstanceId.toString());
        verify(harness.inventoryService).discardUnavailableEquipmentInstance(
            harness.accountId, harness.equippedInstanceId);
        assertFalse(harness.coordinator.hasUnresolvedExternalOperation(harness.accountId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_2-ユースケース.md
     * 章・見出し: # 04_2-ユースケース > ## 9. オーブで装備を更新する
     * 検証契約: NOT_ELIGIBLEでもtargetAvailable=trueかつ現行装備が返る場合は破棄せず、現行値を使って一覧を再構築し、オーブは消費しない。
     */
    @Test
    void notEligibleOwnedTargetRefreshesCurrentEquipmentWithoutTombstone() {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        EquipmentInstance current = new EquipmentInstance(
            harness.equippedInstanceId.toString(),
            harness.accountId.toString(),
            harness.equippedModel.getId(),
            2,
            0,
            0,
            100,
            85,
            "2026-08-11T00:00:00",
            "2026-08-11T00:00:00",
            List.of(),
            List.of(),
            List.of()
        );
        harness.apiResultType.set(EquipmentOrbOperationResultType.NOT_ELIGIBLE);
        harness.nonAppliedCurrent.set(current);
        harness.openOrbList();

        harness.handler.onInventoryClick(harness.guiClick(0));
        harness.laneExecutor.runAll();
        server().getScheduler().performOneTick();

        assertEquals(2, harness.orbQuantity.get());
        assertSame(current, harness.equippedInstance.get());
        assertEquals(Material.NETHERITE_SWORD,
            harness.player.getOpenInventory().getTopInventory().getItem(0).getType());
        verify(harness.itemService, never()).evictEquipmentInstanceFromCache(anyString());
        verify(harness.inventoryService, never()).discardUnavailableEquipmentInstance(
            any(UUID.class), any(UUID.class));
        verify(harness.inventoryService, never()).persistReconciledStateNow(any(UUID.class));
        assertFalse(harness.service.isLocked(harness.player));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 8. 装備・ホットバー・アクセサリのスナップショット保存
     * 検証契約: 保存済みNO_CANDIDATE/PAYMENT_UNAVAILABLEの再生時に対象が削除・譲渡済みなら、business resultよりtargetAvailable=falseを優先し、cache/state破棄とmanaged表示/status再構築を実行する。
     */
    @Test
    void storedNoCandidateWithUnavailableTargetRefreshesManagedStateAndStatus() {
        assertStoredBusinessFailureWithUnavailableTarget(
            EquipmentOrbOperationResultType.NO_CANDIDATE);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 8. 装備・ホットバー・アクセサリのスナップショット保存
     * 検証契約: 保存済みPAYMENT_UNAVAILABLEの再生時に対象が削除・譲渡済みなら、business resultよりtargetAvailable=falseを優先し、cache/state破棄とmanaged表示/status再構築を実行する。
     */
    @Test
    void storedPaymentUnavailableWithUnavailableTargetRefreshesManagedStateAndStatus() {
        assertStoredBusinessFailureWithUnavailableTarget(
            EquipmentOrbOperationResultType.PAYMENT_UNAVAILABLE);
    }

    private void assertStoredBusinessFailureWithUnavailableTarget(
        EquipmentOrbOperationResultType resultType
    ) {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        harness.apiResultType.set(resultType);
        harness.terminalEquipmentMissing.set(true);
        harness.openOrbList();

        harness.handler.onInventoryClick(harness.guiClick(0));
        harness.laneExecutor.runAll();
        server().getScheduler().performOneTick();

        assertEquals(2, harness.orbQuantity.get());
        assertNull(harness.equippedInstance.get());
        assertEquals(List.of(
            "pre-save", "post", "reconcile", "evict", "discard"
        ), harness.order);
        verify(harness.persistence).saveNow(harness.state);
        verify(harness.itemService).evictEquipmentInstanceFromCache(
            harness.equippedInstanceId.toString());
        verify(harness.inventoryService).discardUnavailableEquipmentInstance(
            harness.accountId, harness.equippedInstanceId);
        verify(harness.inventoryService).refreshManagedInventoryUi(harness.astPlayer);
        verify(harness.inventoryService).refreshEquipmentDisplaysForSave(harness.astPlayer);
        verify(harness.statusService).refreshStatus(harness.astPlayer);
        assertFalse(harness.service.isLocked(harness.player));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: affected-entry GETのIOExceptionは同operationId・unresolved lane・未消費local entryを維持して再試行し、次の200/404確定後だけ照合して後続saveを解放する。
     */
    @Test
    void reconcileIOExceptionRetriesBeforeLocalConsumptionAndFollowingSave() {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        harness.terminalApplyCall.set(1);
        harness.reconcileFailuresRemaining.set(1);
        harness.retryBehavior.set((operationId, delayMillis) -> {
            harness.order.add("retry-wait");
            assertTrue(harness.coordinator.hasUnresolvedExternalOperation(harness.accountId));
            assertEquals(2, harness.orbQuantity.get());
            verify(harness.inventoryService, never()).releaseOrbOperationPayment(
                eq(harness.accountId), any(UUID.class));
        });
        harness.openOrbList();

        harness.handler.onInventoryClick(harness.guiClick(0));
        var autoSave = harness.coordinator.saveAuto(harness.state);
        harness.laneExecutor.runAll();

        assertTrue(autoSave.join());
        assertEquals(2, harness.reconcileAttempts.get());
        assertEquals(1, harness.orbQuantity.get());
        assertEquals(List.of(
            "pre-save", "post", "reconcile-failed", "retry-wait",
            "reconcile", "auto-save"
        ), harness.order);
        assertEquals(1, harness.operationIds.stream().distinct().count());
        assertFalse(harness.coordinator.hasUnresolvedExternalOperation(harness.accountId));
        verify(harness.inventoryService).releaseOrbOperationPayment(
            eq(harness.accountId), any(UUID.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_2-ユースケース.md
     * 章・見出し: # 04_2-ユースケース > ## 9. オーブで装備を更新する
     * 検証契約: 成功結果の反映時に同種オーブが0個なら即座にGUIを閉じ、1個だけだった起点オーブを再利用可能な一覧として残さない。
     */
    @Test
    void refreshClosesGuiWhenTheSingleOrbWasConsumed() {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        harness.orbQuantity.set(1);
        harness.openOrbList();

        harness.handler.onInventoryClick(harness.guiClick(0));
        harness.laneExecutor.runAll();
        server().getScheduler().performOneTick();

        assertEquals(0, harness.orbQuantity.get());
        assertFalse(harness.service.isOrbInventory(
            harness.player.getOpenInventory().getTopInventory()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_2-ユースケース.md
     * 章・見出し: # 04_2-ユースケース > ## 9. オーブで装備を更新する
     * 検証契約: 状態変化は一覧から専用確認画面を経て実行し、正本照合完了時にオーブ残数にかかわらずGUIを即座に閉じる。
     */
    @Test
    void transcendenceConfirmationAlwaysClosesImmediatelyAfterSuccess() {
        Harness harness = new Harness(ItemOrbEffectType.TRANSCENDENCE);
        harness.openOrbList();

        harness.handler.onInventoryClick(harness.guiClick(0));
        OrbGuiHolder confirmationHolder = (OrbGuiHolder) harness.player
            .getOpenInventory().getTopInventory().getHolder();
        assertEquals(OrbGuiHolder.Screen.TRANSCENDENCE_CONFIRM, confirmationHolder.screen());
        Inventory confirmation = harness.player.getOpenInventory().getTopInventory();
        assertEquals(OrbGuiHolder.TRANSCENDENCE_CONFIRM_SIZE, confirmation.getSize());
        assertNotNull(confirmation.getItem(11));
        assertEquals(Material.CHEST, confirmation.getItem(13).getType());
        assertEquals(Material.LIME_CONCRETE, confirmation.getItem(15).getType());
        assertEquals(Material.ARROW, confirmation.getItem(22).getType());

        harness.handler.onInventoryClick(harness.guiClick(15));
        assertEquals(Material.CLOCK,
            harness.player.getOpenInventory().getTopInventory().getItem(11).getType());
        harness.laneExecutor.runAll();
        server().getScheduler().performOneTick();

        assertEquals(1, harness.orbQuantity.get());
        assertFalse(harness.service.isOrbInventory(
            harness.player.getOpenInventory().getTopInventory()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: 状態変化確認画面の消費アイテム一覧は54枠で、45枠を超える素材をページ移動でき、ゴールドと確認画面への戻る操作を下段へ表示する。
     */
    @Test
    void transcendenceMaterialListUsesPagingAndShowsGoldAndBackControls() {
        List<ItemEquipmentEnhanceMaterial> materials = new ArrayList<>();
        for (int index = 0; index < 46; index++) {
            materials.add(new ItemEquipmentEnhanceMaterial("material_" + index, 1));
        }
        Harness harness = new Harness(ItemOrbEffectType.TRANSCENDENCE, materials, 123);
        harness.openOrbList();
        harness.handler.onInventoryClick(harness.guiClick(0));
        harness.handler.onInventoryClick(harness.guiClick(13));

        Inventory materialList = harness.player.getOpenInventory().getTopInventory();
        OrbGuiHolder materialHolder = (OrbGuiHolder) materialList.getHolder();
        assertEquals(OrbGuiHolder.Screen.TRANSCENDENCE_MATERIAL_LIST, materialHolder.screen());
        assertEquals(OrbGuiHolder.SIZE, materialList.getSize());
        assertEquals(Material.GRAY_DYE, materialList.getItem(45).getType());
        assertEquals(Material.PAPER, materialList.getItem(46).getType());
        assertEquals(Material.GOLD_INGOT, materialList.getItem(47).getType());
        assertEquals(Material.ARROW, materialList.getItem(49).getType());
        assertEquals(Material.ARROW, materialList.getItem(53).getType());

        harness.handler.onInventoryClick(harness.guiClick(53));
        assertEquals(Material.ARROW, materialList.getItem(45).getType());
        harness.handler.onInventoryClick(harness.guiClick(49));
        assertEquals(
            OrbGuiHolder.Screen.TRANSCENDENCE_CONFIRM,
            ((OrbGuiHolder) harness.player.getOpenInventory().getTopInventory().getHolder()).screen()
        );
        assertEquals(OrbGuiHolder.TRANSCENDENCE_CONFIRM_SIZE,
            harness.player.getOpenInventory().getTopInventory().getSize());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: API操作・正本照合中のEscape closeは次tickまでplayer単位入力lockを維持して同じtokenのGUIを再表示し、更新結果を反映する。
     */
    @Test
    void escapeDuringMutationReopensSameGuiAndCompletesLifecycle() {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        harness.openOrbList();
        harness.handler.onInventoryClick(harness.guiClick(0));
        Inventory closing = harness.player.getOpenInventory().getTopInventory();

        harness.player.closeInventory();
        InventoryCloseEvent closeEvent = mock(InventoryCloseEvent.class);
        when(closeEvent.getPlayer()).thenReturn(harness.player);
        when(closeEvent.getInventory()).thenReturn(closing);
        harness.handler.onInventoryClose(closeEvent);

        assertTrue(harness.service.isLocked(harness.player));
        harness.assertNormalInventoryInputsLockedDuringReopenGap();
        server().getScheduler().performOneTick();
        assertSame(closing, harness.player.getOpenInventory().getTopInventory());
        assertTrue(harness.service.isLocked(harness.player));

        harness.laneExecutor.runAll();
        server().getScheduler().performOneTick();

        assertEquals(1, harness.orbQuantity.get());
        assertFalse(harness.service.isLocked(harness.player));
        assertTrue(harness.service.isOrbInventory(
            harness.player.getOpenInventory().getTopInventory()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: 全transport応答が一時不明でも同一operationIdとaccount laneを保持し、commit済みledger照会または未commit同POST再送のterminal後だけ照合・autosave・logoutを解放する。
     */
    @Test
    void committedTransportAmbiguityRecoversByLedgerReplay() throws Exception {
        assertTransportAmbiguityRecovery(TransportRecovery.LEDGER_REPLAY);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: 未commitのtransport失敗でも同一operationIdのPOST再送とaccount laneをterminalまで保持し、照合後だけautosave・logoutを解放する。
     */
    @Test
    void uncommittedTransportAmbiguityRetriesSamePostAndId() throws Exception {
        assertTransportAmbiguityRecovery(TransportRecovery.SAME_POST_RETRY);
    }

    private void assertTransportAmbiguityRecovery(TransportRecovery recovery) throws Exception {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        harness.terminalApplyCall.set(Integer.MAX_VALUE);
        CountDownLatch retryReached = new CountDownLatch(1);
        CountDownLatch releaseRetry = new CountDownLatch(1);
        harness.retryBehavior.set((operationId, delayMillis) -> {
            retryReached.countDown();
            try {
                assertTrue(releaseRetry.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        });
        harness.openOrbList();
        harness.handler.onInventoryClick(harness.guiClick(0));
        ExecutorService laneRunner = Executors.newSingleThreadExecutor();

        try {
            var runningLane = laneRunner.submit(harness.laneExecutor::runAll);
            assertTrue(retryReached.await(2, TimeUnit.SECONDS));
            assertTrue(harness.coordinator.hasUnresolvedExternalOperation(harness.accountId));
            assertEquals(2, harness.orbQuantity.get());
            assertEquals(1, harness.operationIds.stream().distinct().count());
            verify(harness.inventoryService, never()).releaseOrbOperationPayment(
                eq(harness.accountId), any(UUID.class));

            var autoSave = harness.coordinator.saveAuto(harness.state);
            PlayerQuitEvent quitEvent = mock(PlayerQuitEvent.class);
            when(quitEvent.getPlayer()).thenReturn(harness.player);
            harness.handler.onPlayerQuit(quitEvent);
            AstPlayer relogged = DesignTestFixtures.astPlayer(harness.player, AccountMode.PLAYER);
            AstPlayerCache.put(relogged);
            var logout = harness.coordinator.saveOnLogout(
                harness.accountId,
                harness.state,
                () -> harness.order.add("logout")
            );
            assertFalse(autoSave.isDone());
            assertFalse(logout.isDone());

            if (recovery == TransportRecovery.LEDGER_REPLAY) {
                harness.ledgerTerminal.set(true);
            } else {
                harness.terminalApplyCall.set(harness.applyCount.get() + 1);
            }
            releaseRetry.countDown();
            runningLane.get(2, TimeUnit.SECONDS);

            assertTrue(autoSave.join());
            assertTrue(logout.join());
            assertFalse(harness.coordinator.hasUnresolvedExternalOperation(harness.accountId));
            assertEquals(1, harness.orbQuantity.get());
            assertEquals(1, harness.operationIds.stream().distinct().count());
            verify(harness.inventoryService).releaseOrbOperationPayment(
                eq(harness.accountId), any(UUID.class));
            assertTrue(harness.order.indexOf("reconcile") < harness.order.indexOf("auto-save"));
            assertTrue(harness.order.indexOf("auto-save") < harness.order.indexOf("logout"));
            assertSame(relogged, AstPlayerCache.get(harness.player));
        } finally {
            releaseRetry.countDown();
            laneRunner.shutdownNow();
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: stable baselineへの支払い割当が失敗してPOST未開始の場合は予約を解放し、外部操作を送信しない。
     */
    @Test
    void baselinePaymentFinalizationFailureReleasesReservationBeforePost() {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        when(harness.inventoryService.finalizeOrbOperationPaymentReservation(
            eq(harness.accountId),
            any(UUID.class),
            any(InventoryPersistence.PersistedInventoryBaseline.class)
        )).thenReturn(false);
        harness.openOrbList();

        harness.handler.onInventoryClick(harness.guiClick(0));
        harness.laneExecutor.runAll();

        verify(harness.inventoryService).releaseOrbOperationPayment(
            eq(harness.accountId), any(UUID.class));
        verify(harness.itemService, never()).applyEquipmentOrbOperation(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());
        assertFalse(harness.coordinator.hasUnresolvedExternalOperation(harness.accountId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: lane開始前にstate世代が変わりPOSTへ到達できない場合はinitial予約を解放し、unresolved境界を残さない。
     */
    @Test
    void stateGenerationChangeBeforePreSaveReleasesInitialReservation() {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        harness.openOrbList();
        harness.handler.onInventoryClick(harness.guiClick(0));
        harness.registry.put(new PlayerInventoryState(harness.accountId));

        harness.laneExecutor.runAll();

        verify(harness.inventoryService).releaseOrbOperationPayment(
            eq(harness.accountId), any(UUID.class));
        verify(harness.inventoryService, never()).finalizeOrbOperationPaymentReservation(
            eq(harness.accountId),
            any(UUID.class),
            any(InventoryPersistence.PersistedInventoryBaseline.class)
        );
        verify(harness.itemService, never()).applyEquipmentOrbOperation(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());
        assertFalse(harness.coordinator.hasUnresolvedExternalOperation(harness.accountId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: POST応答不明後にlane threadがinterruptされても予約とunresolved境界を保持し、API消費をローカルへ再支出可能にしない。
     */
    @Test
    void interruptAfterPostAmbiguityRetainsReservationAndUnresolvedBoundary() throws Exception {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        harness.terminalApplyCall.set(Integer.MAX_VALUE);
        CountDownLatch retryReached = new CountDownLatch(1);
        harness.retryBehavior.set((operationId, delayMillis) -> {
            retryReached.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        });
        harness.openOrbList();
        harness.handler.onInventoryClick(harness.guiClick(0));
        ExecutorService laneRunner = Executors.newSingleThreadExecutor();

        try {
            laneRunner.submit(harness.laneExecutor::runAll);
            assertTrue(retryReached.await(2, TimeUnit.SECONDS));
            assertTrue(harness.coordinator.hasUnresolvedExternalOperation(harness.accountId));

            laneRunner.shutdownNow();
            assertTrue(laneRunner.awaitTermination(2, TimeUnit.SECONDS));

            verify(harness.inventoryService, never()).releaseOrbOperationPayment(
                eq(harness.accountId), any(UUID.class));
            assertTrue(harness.coordinator.hasUnresolvedExternalOperation(harness.accountId));
        } finally {
            laneRunner.shutdownNow();
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-イベント.md
     * 章・見出し: # 08_3-イベント > ## 3. ログアウト受付
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: 通信中quitは旧sessionを切り離し、API確定とaffected entry照合を後続logout保存より先に完了して再login世代へUI完了処理を適用しない。
     */
    @Test
    void quitDuringCommunicationReconcilesBeforeLogoutAndDoesNotTouchReloginGeneration() {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        harness.openOrbList();
        harness.handler.onInventoryClick(harness.guiClick(0));
        PlayerQuitEvent quitEvent = mock(PlayerQuitEvent.class);
        when(quitEvent.getPlayer()).thenReturn(harness.player);
        harness.handler.onPlayerQuit(quitEvent);
        AstPlayer relogged = DesignTestFixtures.astPlayer(harness.player, AccountMode.PLAYER);
        AstPlayerCache.put(relogged);
        var logout = harness.coordinator.saveOnLogout(
            harness.accountId,
            harness.state,
            () -> harness.order.add("logout")
        );

        harness.laneExecutor.runAll();

        assertTrue(logout.join());
        assertEquals(List.of(
            "pre-save", "post", "get", "retry", "reconcile", "logout"
        ), harness.order);
        assertSame(relogged, AstPlayerCache.get(harness.player));
        verify(harness.statusService, never()).refreshStatus(harness.astPlayer);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: shutdownは全UI sessionを切り離して処理中表示を止める一方、closing前に受理したオーブ操作とaffected entry照合をlane drainまで完了する。
     */
    @Test
    void shutdownDetachesUiButDrainsAcceptedMutation() {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        harness.openOrbList();
        harness.handler.onInventoryClick(harness.guiClick(0));

        harness.service.prepareAllForShutdown();
        harness.coordinator.beginClosing();
        harness.laneExecutor.runAll();

        assertEquals(1, harness.orbQuantity.get());
        assertEquals(List.of("pre-save", "post", "get", "retry", "reconcile"), harness.order);
        assertTrue(harness.coordinator.awaitPendingWrites(100));
        verify(harness.statusService, never()).refreshStatus(harness.astPlayer);
    }

    /**
     * MockBukkit が記録した指定音の再生回数を返します。
     *
     * @param player 音声再生を確認するプレイヤー
     * @param sound 確認対象の音
     * @return 記録された再生回数
     */
    private static long heardSoundCount(PlayerMock player, Sound sound) {
        String soundKey = Registry.SOUND_EVENT.getKeyOrThrow(sound).getKey();
        return player.getHeardSounds().stream()
            .filter(heardSound -> soundKey.equals(heardSound.getSound()))
            .count();
    }

    private final class Harness {
        private final PluginMock plugin = MockBukkit.createMockPlugin("OrbServiceLifecycleTest");
        private final PlayerMock player = server().addPlayer();
        private final AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        private final UUID accountId = astPlayer.getAccount().getUuid();
        private final UUID orbEntryId = UUID.randomUUID();
        private final UUID runeEntryId = UUID.randomUUID();
        private final UUID additionalOrbEntryId = UUID.randomUUID();
        private final UUID equippedEntryId = UUID.randomUUID();
        private final UUID bagEntryId = UUID.randomUUID();
        private final UUID equippedInstanceId = UUID.randomUUID();
        private final UUID bagInstanceId = UUID.randomUUID();
        private final InventoryModel bag = DesignTestFixtures.inventory(accountId, InventoryType.BAG, 27);
        private final PlayerInventoryState state = new PlayerInventoryState(accountId);
        private final PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        private final InventoryService inventoryService = mock(InventoryService.class);
        private final ItemService itemService = mock(ItemService.class);
        private final ItemStackFactory itemStackFactory = mock(ItemStackFactory.class);
        private final InventoryPersistence persistence = mock(InventoryPersistence.class);
        private final ManualExecutor laneExecutor = new ManualExecutor();
        private final InventorySaveCoordinator coordinator;
        private final StatusService statusService = mock(StatusService.class);
        private final OrbService service;
        private final InventoryEquipmentGuiEventHandler handler;
        private final ItemOrbEffectType effectType;
        private final ItemModel orbModel;
        private final ItemModel runeModel;
        private final Map<String, ItemModel> loadedOrbModels = new LinkedHashMap<>();
        private final List<InventoryEntryModel> pagingOrbEntries = new ArrayList<>();
        private final ItemModel equippedModel;
        private final ItemModel bagModel;
        private final AtomicReference<EquipmentInstance> equippedInstance;
        private final AtomicReference<EquipmentInstance> bagInstance;
        private final AtomicInteger orbQuantity = new AtomicInteger(2);
        private int additionalOrbQuantity;
        private final AtomicReference<EquipmentOrbOperationResultType> apiResultType =
            new AtomicReference<>(EquipmentOrbOperationResultType.APPLIED);
        private final AtomicBoolean preloadRanOnPrimaryThread = new AtomicBoolean(true);
        private final AtomicInteger applyCount = new AtomicInteger();
        private final AtomicInteger terminalApplyCall = new AtomicInteger(2);
        private final AtomicBoolean ledgerTerminal = new AtomicBoolean();
        private final AtomicBoolean terminalEquipmentMissing = new AtomicBoolean();
        private final AtomicReference<EquipmentInstance> nonAppliedCurrent = new AtomicReference<>();
        private final AtomicInteger reconcileFailuresRemaining = new AtomicInteger();
        private final AtomicInteger reconcileAttempts = new AtomicInteger();
        private final AtomicReference<OrbService.OrbRetryWaiter> retryBehavior =
            new AtomicReference<>((operationId, delayMillis) -> {
            });
        private final List<String> order = new ArrayList<>();
        private final List<String> operationIds = new ArrayList<>();
        private final List<String> usedOrbIds = new ArrayList<>();

        private Harness(ItemOrbEffectType effectType) {
            this(effectType, List.of(), 0);
        }

        private Harness(
            ItemOrbEffectType effectType,
            List<ItemEquipmentEnhanceMaterial> transcendenceMaterials,
            int transcendenceCurrency
        ) {
            this.effectType = effectType;
            this.orbModel = orbModel(effectType);
            this.runeModel = runeModel();
            loadedOrbModels.put(orbModel.getId(), orbModel);
            loadedOrbModels.put(runeModel.getId(), runeModel);
            this.equippedModel = equipmentModel(
                "equipped_sword", effectType, transcendenceMaterials, transcendenceCurrency);
            this.bagModel = equipmentModel(
                "bag_sword", effectType, transcendenceMaterials, transcendenceCurrency);
            this.equippedInstance = new AtomicReference<>(instance(
                equippedInstanceId, "equipped_sword", 0, 70));
            this.bagInstance = new AtomicReference<>(instance(
                bagInstanceId, "bag_sword", 0, 60));
            AstPlayerCache.put(astPlayer);
            state.putInventory(bag);
            registry.put(state);
            configureMocks();
            coordinator = new InventorySaveCoordinator(persistence, registry, laneExecutor);
            service = new OrbService(
                plugin,
                inventoryService,
                coordinator,
                registry,
                itemService,
                itemStackFactory,
                (targetPlayer, inventory, onOpened, onCancelled) -> {
                    targetPlayer.openInventory(inventory);
                    if (targetPlayer.getOpenInventory().getTopInventory() == inventory) {
                        onOpened.run();
                    } else {
                        onCancelled.run();
                    }
                },
                (operationId, delayMillis) -> retryBehavior.get().await(operationId, delayMillis)
            );
            service.setStatusService(statusService);
            service.setUseSuccessListener((player, orbItemId) -> usedOrbIds.add(orbItemId));
            handler = new InventoryEquipmentGuiEventHandler(
                mock(MenuView.class),
                inventoryService,
                mock(CurrencyService.class),
                statusService,
                mock(PassiveSkillService.class),
                service,
                mock(MenuGuiTransitionService.class),
                mock(MenuOpenEventHandler.class)
            );
        }

        private void configureMocks() {
            when(inventoryService.getOwnedEntryAtBukkitSlot(eq(astPlayer), anyInt()))
                .thenAnswer(invocation -> switch (invocation.getArgument(1, Integer.class)) {
                    case 9 -> orbEntry();
                    case 10 -> runeEntry();
                    default -> null;
                });
            when(inventoryService.getEquippedItemReferences(astPlayer)).thenReturn(List.of(
                new ItemReference(
                    equippedModel.getId(),
                    ItemCategory.EQUIPMENT.getApiValue(),
                    equippedInstanceId.toString()
                )
            ));
            when(inventoryService.getInventories(accountId)).thenReturn(List.of(bag));
            when(inventoryService.getEntries(bag.getInventoryId())).thenAnswer(invocation -> {
                List<InventoryEntryModel> entries = new ArrayList<>(List.of(
                    orbEntry(),
                    runeEntry(),
                    equipmentEntry(
                        equippedEntryId, equippedInstanceId, equippedModel.getId(), 1),
                    equipmentEntry(bagEntryId, bagInstanceId, bagModel.getId(), 2)
                ));
                if (additionalOrbQuantity > 0) {
                    entries.add(entry(
                        additionalOrbEntryId,
                        3,
                        ItemCategory.ORB,
                        orbModel.getId(),
                        null,
                        additionalOrbQuantity
                    ));
                }
                entries.addAll(pagingOrbEntries);
                return entries;
            });
            when(inventoryService.findOwnedEntry(accountId, orbEntryId))
                .thenAnswer(invocation -> orbEntry());
            when(inventoryService.findOwnedEntry(accountId, additionalOrbEntryId))
                .thenAnswer(invocation -> additionalOrbEntry());
            when(inventoryService.findOwnedNormalItemEntryForConsumption(
                eq(accountId),
                eq(orbModel.getId())
            )).thenAnswer(invocation -> additionalOrbQuantity > 0 ? additionalOrbEntry() : orbEntry());
            when(inventoryService.isInventoryInfoSlot(26)).thenReturn(true);
            when(inventoryService.reserveOrbOperationPayment(
                eq(accountId),
                any(UUID.class),
                any(),
                anyLong()
            )).thenReturn(true);
            when(inventoryService.finalizeOrbOperationPaymentReservation(
                eq(accountId),
                any(UUID.class),
                any(InventoryPersistence.PersistedInventoryBaseline.class)
            )).thenReturn(true);
            when(itemService.findLoadedById(anyString())).thenAnswer(invocation -> {
                String id = invocation.getArgument(0, String.class);
                ItemModel loadedOrb = loadedOrbModels.get(id);
                if (loadedOrb != null) return loadedOrb;
                if (orbModel.getId().equalsIgnoreCase(id)) return orbModel;
                if (equippedModel.getId().equalsIgnoreCase(id)) return equippedModel;
                if (bagModel.getId().equalsIgnoreCase(id)) return bagModel;
                return null;
            });
            when(itemService.preloadEquipmentInstances(any())).thenAnswer(invocation -> {
                preloadRanOnPrimaryThread.set(Bukkit.isPrimaryThread());
                return ItemService.EquipmentPreloadResult.COMPLETE;
            });
            when(itemService.findLoadedEquipmentInstanceById(anyString())).thenAnswer(invocation -> {
                String id = invocation.getArgument(0, String.class);
                if (equippedInstanceId.toString().equalsIgnoreCase(id)) return equippedInstance.get();
                if (bagInstanceId.toString().equalsIgnoreCase(id)) return bagInstance.get();
                return null;
            });
            when(itemStackFactory.create(any(ItemModel.class), anyInt())).thenAnswer(invocation ->
                new ItemStack(Material.AMETHYST_SHARD));
            when(itemStackFactory.create(
                any(ItemModel.class), any(EquipmentInstance.class), eq(1)
            )).thenAnswer(invocation -> {
                EquipmentInstance target = invocation.getArgument(1, EquipmentInstance.class);
                Material material = !target.getEquipmentInstanceId().equalsIgnoreCase(
                    equippedInstanceId.toString())
                    ? Material.IRON_SWORD
                    : target.getEnhanceLevel() >= 2
                        ? Material.NETHERITE_SWORD
                        : Material.DIAMOND_SWORD;
                return new ItemStack(material);
            });
            when(persistence.saveNowWithBaseline(state)).thenAnswer(invocation -> {
                order.add("pre-save");
                state.takeAndClearDirty();
                return new InventoryPersistence.PersistedInventoryBaseline(
                    accountId,
                    Map.of(bag.getInventoryId(), List.of(
                        orbEntry(),
                        equipmentEntry(equippedEntryId, equippedInstanceId, equippedModel.getId(), 1),
                        equipmentEntry(bagEntryId, bagInstanceId, bagModel.getId(), 2)
                    ))
                );
            });
            when(persistence.saveNow(state)).thenAnswer(invocation -> {
                state.takeAndClearDirty();
                return true;
            });
            when(persistence.hasPendingChanges(state)).thenReturn(false);
            when(persistence.save(state, InventoryPersistence.SaveTrigger.AUTO)).thenAnswer(invocation -> {
                order.add("auto-save");
                return true;
            });
            doAnswer(invocation -> {
                order.add("evict");
                equippedInstance.set(null);
                return null;
            }).when(itemService).evictEquipmentInstanceFromCache(equippedInstanceId.toString());
            doAnswer(invocation -> {
                order.add("discard");
                return null;
            }).when(inventoryService).discardUnavailableEquipmentInstance(accountId, equippedInstanceId);
            when(itemService.applyEquipmentOrbOperation(
                anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()
            )).thenAnswer(invocation -> {
                String operationId = invocation.getArgument(0, String.class);
                operationIds.add(operationId);
                int call = applyCount.incrementAndGet();
                EquipmentOrbOperationResultType type = apiResultType.get();
                order.add(call == 1 ? "post" : "retry");
                if (type == EquipmentOrbOperationResultType.APPLIED
                    && call < terminalApplyCall.get()) {
                    return null;
                }
                if (type != EquipmentOrbOperationResultType.APPLIED) {
                    EquipmentInstance current = nonAppliedCurrent.get();
                    if (current != null) {
                        equippedInstance.set(current);
                    }
                    return operationResult(operationId, type, current);
                }
                return appliedOperation(operationId);
            });
            when(itemService.findEquipmentOrbOperation(anyString(), anyString())).thenAnswer(invocation -> {
                String operationId = invocation.getArgument(0, String.class);
                operationIds.add(operationId);
                order.add("get");
                return ledgerTerminal.get() ? appliedOperation(operationId) : null;
            });
            doAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                Collection<UUID> entryIds = invocation.getArgument(1, Collection.class);
                reconcileAttempts.incrementAndGet();
                if (reconcileFailuresRemaining.getAndUpdate(
                    value -> Math.max(0, value - 1)
                ) > 0) {
                    order.add("reconcile-failed");
                    throw new IOException("temporary affected-entry transport failure");
                }
                order.add("reconcile");
                if (entryIds.contains(orbEntryId)
                    && apiResultType.get() == EquipmentOrbOperationResultType.APPLIED) {
                    orbQuantity.decrementAndGet();
                }
                return null;
            }).when(inventoryService).reconcileOrbOperationEntries(
                eq(accountId),
                any(),
                any(InventoryPersistence.PersistedInventoryBaseline.class)
            );
        }

        private InventoryClickEvent openOrbList() {
            InventoryClickEvent event = normalInventoryClick();
            handler.onInventoryClick(event);
            // MockBukkit は新しい非同期 task の開始前に worker pool が空と判定することがある。
            // 非同期完了とその sync callback を bounded timeout 内でまとめて drain する。
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!service.isOrbInventory(player.getOpenInventory().getTopInventory())
                && System.nanoTime() < deadlineNanos) {
                server().getScheduler().performOneTick();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                assertFalse(Thread.currentThread().isInterrupted(),
                    "Interrupted while waiting for the orb inventory to open");
            }
            assertTrue(
                service.isOrbInventory(player.getOpenInventory().getTopInventory()),
                "Orb inventory did not open within 2 seconds after the preload request"
            );
            return event;
        }

        private void addOrbTypesForPaging(int count) {
            for (int index = 1; index <= count; index++) {
                String itemId = "orb.page_test_" + index;
                ItemModel model = orbModel(itemId, ItemOrbEffectType.REPAIR);
                loadedOrbModels.put(itemId, model);
                pagingOrbEntries.add(entry(
                    UUID.randomUUID(),
                    3 + index,
                    ItemCategory.ORB,
                    itemId,
                    null,
                    1
                ));
            }
        }

        private InventoryClickEvent normalInventoryClick() {
            return normalInventoryClick(9);
        }

        private InventoryClickEvent normalInventoryClick(int slot) {
            InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getWhoClicked()).thenReturn(player);
            when(event.getView()).thenReturn(player.getOpenInventory());
            when(event.getClickedInventory()).thenReturn(player.getInventory());
            when(event.getSlot()).thenReturn(slot);
            return event;
        }

        private void awaitOrbScreen(OrbGuiHolder.Screen expected) {
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!(player.getOpenInventory().getTopInventory().getHolder() instanceof OrbGuiHolder holder)
                || holder.screen() != expected) {
                if (System.nanoTime() >= deadlineNanos) {
                    break;
                }
                server().getScheduler().performOneTick();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            assertTrue(player.getOpenInventory().getTopInventory().getHolder() instanceof OrbGuiHolder holder
                    && holder.screen() == expected,
                "Orb GUI did not reach expected screen within 2 seconds: " + expected);
        }

        private void awaitOrbScreenAfter(Inventory previous, OrbGuiHolder.Screen expected) {
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (player.getOpenInventory().getTopInventory() == previous
                || !(player.getOpenInventory().getTopInventory().getHolder() instanceof OrbGuiHolder holder)
                || holder.screen() != expected) {
                if (System.nanoTime() >= deadlineNanos) {
                    break;
                }
                server().getScheduler().performOneTick();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            assertTrue(player.getOpenInventory().getTopInventory() != previous
                    && player.getOpenInventory().getTopInventory().getHolder() instanceof OrbGuiHolder holder
                    && holder.screen() == expected,
                "Orb GUI did not transition to expected screen within 2 seconds: " + expected);
        }

        private InventoryClickEvent guiClick(int rawSlot) {
            InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getWhoClicked()).thenReturn(player);
            when(event.getView()).thenReturn(player.getOpenInventory());
            when(event.getRawSlot()).thenReturn(rawSlot);
            when(event.getClick()).thenReturn(ClickType.LEFT);
            return event;
        }

        private InventoryClickEvent guiPlayerInventoryClick(int slot) {
            InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getWhoClicked()).thenReturn(player);
            when(event.getView()).thenReturn(player.getOpenInventory());
            when(event.getClickedInventory()).thenReturn(player.getInventory());
            when(event.getRawSlot()).thenReturn(
                player.getOpenInventory().getTopInventory().getSize() + slot);
            when(event.getSlot()).thenReturn(slot);
            when(event.getClick()).thenReturn(ClickType.LEFT);
            return event;
        }

        private void assertAllInputsLocked() {
            InventoryClickEvent click = guiClick(0);
            handler.onInventoryClick(click);
            verify(click).setCancelled(true);

            InventoryDragEvent drag = mock(InventoryDragEvent.class);
            when(drag.getView()).thenReturn(player.getOpenInventory());
            handler.onInventoryDrag(drag);
            verify(drag).setCancelled(true);

            PlayerItemHeldEvent held = mock(PlayerItemHeldEvent.class);
            when(held.getPlayer()).thenReturn(player);
            handler.onPlayerItemHeld(held);
            verify(held).setCancelled(true);

            PlayerDropItemEvent drop = mock(PlayerDropItemEvent.class);
            when(drop.getPlayer()).thenReturn(player);
            handler.onPlayerDropItem(drop);
            verify(drop).setCancelled(true);
        }

        private void assertNormalInventoryInputsLockedDuringReopenGap() {
            InventoryClickEvent normalClick = normalInventoryClick();
        handler.onInventoryClick(normalClick);
        verify(normalClick).setCancelled(true);

        InventoryDragEvent drag = mock(InventoryDragEvent.class);
        when(drag.getWhoClicked()).thenReturn(player);
        when(drag.getView()).thenReturn(player.getOpenInventory());
        handler.onInventoryDrag(drag);
        verify(drag).setCancelled(true);

        PlayerItemHeldEvent held = mock(PlayerItemHeldEvent.class);
            when(held.getPlayer()).thenReturn(player);
            handler.onPlayerItemHeld(held);
            verify(held).setCancelled(true);

            PlayerDropItemEvent drop = mock(PlayerDropItemEvent.class);
            when(drop.getPlayer()).thenReturn(player);
            handler.onPlayerDropItem(drop);
            verify(drop).setCancelled(true);
        }

        private EquipmentOrbOperationResult appliedOperation(String operationId) {
            if (terminalEquipmentMissing.get()) {
                return operationResult(
                    operationId,
                    EquipmentOrbOperationResultType.APPLIED,
                    null
                );
            }
            EquipmentInstance updated = effectType == ItemOrbEffectType.TRANSCENDENCE
                ? instance(equippedInstanceId, equippedModel.getId(), 1, 70)
                : effectType == ItemOrbEffectType.RUNE_DETACH
                    ? instance(equippedInstanceId, equippedModel.getId(), 0, 100, List.of())
                    : instance(equippedInstanceId, equippedModel.getId(), 0, 100);
            equippedInstance.set(updated);
            return operationResult(operationId, EquipmentOrbOperationResultType.APPLIED, updated);
        }

        private EquipmentOrbOperationResult operationResult(
            String operationId,
            EquipmentOrbOperationResultType resultType,
            EquipmentInstance equipment
        ) {
            String operationType = switch (effectType) {
                case TRANSCENDENCE -> "TRANSCENDENCE";
                case RUNE_ATTACH -> "RUNE_ATTACH";
                case RUNE_DETACH -> "RUNE_DETACH";
                default -> "REPAIR";
            };
            return new EquipmentOrbOperationResult(
                operationId,
                resultType,
                operationType,
                equipment,
                !terminalEquipmentMissing.get(),
                List.of(orbEntryId.toString()),
                resultType == EquipmentOrbOperationResultType.APPLIED,
                false,
                null,
                null,
                effectType == ItemOrbEffectType.REPAIR ? 30 : null,
                effectType == ItemOrbEffectType.TRANSCENDENCE ? "星鋼化" : null
            );
        }

        private InventoryEntryModel orbEntry() {
            return entry(
                orbEntryId,
                0,
                ItemCategory.ORB,
                orbModel.getId(),
                null,
                orbQuantity.get()
            );
        }

        private InventoryEntryModel runeEntry() {
            return entry(
                runeEntryId,
                4,
                ItemCategory.RUNE,
                runeModel.getId(),
                null,
                1L
            );
        }

        private InventoryEntryModel additionalOrbEntry() {
            return entry(
                additionalOrbEntryId,
                3,
                ItemCategory.ORB,
                orbModel.getId(),
                null,
                additionalOrbQuantity
            );
        }

        private InventoryEntryModel equipmentEntry(
            UUID entryId,
            UUID instanceId,
            String itemId,
            int slot
        ) {
            return entry(entryId, slot, ItemCategory.EQUIPMENT, itemId, instanceId, 1L);
        }

        private InventoryEntryModel entry(
            UUID entryId,
            int slot,
            ItemCategory category,
            String itemId,
            UUID instanceId,
            long quantity
        ) {
            LocalDateTime now = LocalDateTime.now();
            return new InventoryEntryModel(
                entryId,
                bag.getInventoryId(),
                slot,
                category.getApiValue(),
                itemId,
                instanceId == null ? null : "equipment",
                instanceId,
                quantity,
                null,
                now,
                now,
                accountId,
                accountId,
                false
            );
        }

        private ItemModel orbModel(ItemOrbEffectType type) {
            return orbModel(
                switch (type) {
                    case TRANSCENDENCE -> "orb.transcendence_test";
                    case RUNE_ATTACH -> "orb.rune_attach_test";
                    case RUNE_DETACH -> "orb.rune_detach_test";
                    default -> "orb.repair_test";
                },
                type
            );
        }

        private ItemModel orbModel(String itemId, ItemOrbEffectType type) {
            ItemOrbEffect effect = switch (type) {
                case TRANSCENDENCE -> new ItemOrbEffect(
                    type, List.of(), 1, ItemOrbRankMode.EXACT, null, false, null, null);
                case RUNE_ATTACH, RUNE_DETACH -> new ItemOrbEffect(
                    type, List.of(), null, ItemOrbRankMode.EXACT, null, false, null, null);
                default -> new ItemOrbEffect(
                    type, List.of(), null, ItemOrbRankMode.EXACT, null, true, null, null);
            };
            return new ItemModel(
                1,
                itemId,
                ItemCategory.ORB.getApiValue(),
                "テストオーブ",
                "AMETHYST_SHARD",
                "common",
                64,
                0,
                null,
                null,
                List.of(),
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new ItemOrb(effect)
            );
        }

        private ItemModel runeModel() {
            return new ItemModel(
                1,
                "debug_attack_rune",
                ItemCategory.RUNE.getApiValue(),
                "デバッグ攻撃ルーン",
                "REDSTONE",
                "common",
                64,
                0,
                null,
                null,
                List.of(),
                false,
                false,
                null,
                null,
                null,
                new ItemRune(List.of("WEAPON"), 0, List.of(), List.of("SWORD")),
                null,
                null,
                null,
                null
            );
        }

        private ItemModel equipmentModel(String itemId, ItemOrbEffectType type) {
            return equipmentModel(itemId, type, List.of(), 0);
        }

        private ItemModel equipmentModel(
            String itemId,
            ItemOrbEffectType type,
            List<ItemEquipmentEnhanceMaterial> transcendenceMaterials,
            int transcendenceCurrency
        ) {
            List<ItemEquipmentTranscendence> transitions = type == ItemOrbEffectType.TRANSCENDENCE
                ? List.of(new ItemEquipmentTranscendence(
                    "星鋼化", 1, 0, transcendenceMaterials, transcendenceCurrency,
                    null, null, null))
                : List.of();
            ItemEquipment equipment = new ItemEquipment(
                ItemEquipmentSlot.WEAPON,
                ItemEquipmentHandType.ONE,
                type == ItemOrbEffectType.RUNE_ATTACH || type == ItemOrbEffectType.RUNE_DETACH ? "SWORD" : null,
                0,
                List.of(),
                null,
                List.of(),
                new ItemEquipmentDurability(100, 1),
                null,
                null,
                type == ItemOrbEffectType.RUNE_ATTACH || type == ItemOrbEffectType.RUNE_DETACH
                    ? new ItemEquipmentRuneDef("2") : null,
                transitions
            );
            return new ItemModel(
                1,
                itemId,
                ItemCategory.EQUIPMENT.getApiValue(),
                itemId,
                "IRON_SWORD",
                "common",
                1,
                0,
                null,
                null,
                List.of(),
                false,
                false,
                null,
                null,
                equipment,
                null,
                null,
                null,
                null,
                null
            );
        }

        private EquipmentInstance instance(
            UUID instanceId,
            String itemId,
            int rank,
            int durability
        ) {
            List<EquipmentRune> runes = effectType == ItemOrbEffectType.RUNE_DETACH
                ? List.of(
                    new EquipmentRune("rune-0", instanceId.toString(), 0, runeModel.getId()),
                    new EquipmentRune("rune-1", instanceId.toString(), 1, runeModel.getId())
                )
                : List.of();
            return instance(instanceId, itemId, rank, durability, runes);
        }

        private EquipmentInstance instance(
            UUID instanceId,
            String itemId,
            int rank,
            int durability,
            List<EquipmentRune> runes
        ) {
            return new EquipmentInstance(
                instanceId.toString(),
                accountId.toString(),
                itemId,
                0,
                effectType == ItemOrbEffectType.RUNE_ATTACH || effectType == ItemOrbEffectType.RUNE_DETACH ? 2 : 0,
                rank,
                100,
                durability,
                "2026-08-10T00:00:00",
                "2026-08-10T00:00:00",
                List.of(),
                List.of(),
                runes
            );
        }
    }

    private static final class ManualExecutor implements Executor {
        private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private int pendingCount() {
            return tasks.size();
        }

        private void runAll() {
            Runnable task;
            while ((task = tasks.poll()) != null) {
                task.run();
            }
        }
    }

    private enum TransportRecovery {
        LEDGER_REPLAY,
        SAME_POST_RETRY,
    }
}
