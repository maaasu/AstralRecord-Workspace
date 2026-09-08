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
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.item.gui.OrbGuiHolder;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentRune;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentDurability;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhance;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceFailAction;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceLevel;
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
import io.github.maaasu.astralRecord.feature.skill.service.SkillSigilOrbService;
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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
     * 検証契約: 集約一覧から選んだオーブは共通消費順で再解決し、API commandを送らず、支払い・装備更新・非同期snapshot保存要求をその場で確定する。
     */
    @Test
    void inventoryOrbListSelectionCommitsLocallyAndQueuesSnapshotSave() {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        harness.orbQuantity.set(64);
        harness.additionalOrbQuantity = 32;

        harness.handler.onInventoryClick(harness.normalInventoryClick(26));
        harness.handler.onInventoryClick(harness.guiClick(10));
        harness.awaitOrbScreen(OrbGuiHolder.Screen.LIST);
        harness.handler.onInventoryClick(harness.guiClick(0));
        verify(harness.inventoryService).reserveOrbOperationPayment(
            eq(harness.accountId),
            any(UUID.class),
            any(),
            anyLong()
        );
        verify(harness.inventoryService).commitLocalOrbOperationPayment(
            eq(harness.accountId), any(UUID.class), any(Runnable.class));
        verify(harness.inventoryService).executeCriticalPlayerMutation(eq(harness.accountId), any());
        assertEquals(63, harness.orbQuantity.get());
        assertEquals(100, harness.equippedInstance.get().getDurabilityValue());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_2-ユースケース.md
     * 章・見出し: # 04_2-ユースケース > ## 9. オーブで装備を更新する
     * 検証契約: 装備候補GUIの下段中央の左右に使用数変更ボタンを表示し、所持数を上限として1個単位で使用数を変更する。
     */
    @Test
    void orbAmountControlsDisplayInventoryAndSelectedAmount() {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        harness.orbQuantity.set(3);
        harness.openOrbList();

        Inventory list = harness.player.getOpenInventory().getTopInventory();
        assertEquals(Material.RED_DYE, list.getItem(48).getType());
        assertEquals(Material.LIME_DYE, list.getItem(50).getType());
        assertLoreContains(list.getItem(49), "所持数: 3", "使用数: 1");

        harness.handler.onInventoryClick(harness.guiClick(50));
        assertLoreContains(list.getItem(49), "所持数: 3", "使用数: 2");

        harness.handler.onInventoryClick(harness.guiClick(48));
        assertLoreContains(list.getItem(49), "所持数: 3", "使用数: 1");
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_2-ユースケース.md
     * 章・見出し: # 04_2-ユースケース > ## 9. オーブで装備を更新する
     * 検証契約: 選択した強化回数を同じ対象へ1回ずつローカル確定し、各回の支払い・完成状態・snapshot保存要求を即時に行って最後に集計する。
     */
    @Test
    void selectedEnhancementAmountRunsSequentiallyAndSummarizesOnce() {
        Harness harness = new Harness(ItemOrbEffectType.ENHANCE);
        harness.orbQuantity.set(3);
        harness.openOrbList();
        harness.handler.onInventoryClick(harness.guiClick(50));
        harness.handler.onInventoryClick(harness.guiClick(50));
        assertLoreContains(
            harness.player.getOpenInventory().getTopInventory().getItem(49),
            "所持数: 3",
            "使用数: 3"
        );

        harness.handler.onInventoryClick(harness.guiClick(0));
        harness.awaitUsedOrbCount(3);
        assertEquals(3, harness.usedOrbIds.size());
        assertEquals(3, harness.equippedInstance.get().getEnhanceLevel());
        assertEquals(0, harness.orbQuantity.get());
        verify(harness.inventoryService, times(3)).commitLocalOrbOperationPayment(
            eq(harness.accountId), any(UUID.class), any(Runnable.class));
        verify(harness.inventoryService, times(3)).executeCriticalPlayerMutation(eq(harness.accountId), any());
        assertFalse(harness.service.isOrbInventory(
            harness.player.getOpenInventory().getTopInventory()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_2-ユースケース.md
     * 章・見出し: # 04_2-ユースケース > ## 9. オーブで装備を更新する
     * 検証契約: 素材・通貨を予約できない状態で状態変化確認を実行しても、ローカル装備・所持オーブを変更せずsnapshot保存を要求しない。
     */
    @Test
    void insufficientTranscendenceMaterialsRollBackWithoutQueueingSave() {
        Harness harness = new Harness(
            ItemOrbEffectType.TRANSCENDENCE,
            List.of(new ItemEquipmentEnhanceMaterial("transcendence_material", 2)),
            100
        );
        harness.reservePaymentAvailable.set(false);
        harness.openOrbList();
        harness.handler.onInventoryClick(harness.guiClick(0));
        harness.awaitOrbScreen(OrbGuiHolder.Screen.TRANSCENDENCE_CONFIRM);
        harness.handler.onInventoryClick(harness.guiClick(15));

        verify(harness.inventoryService, never()).commitLocalOrbOperationPayment(
            any(UUID.class), any(UUID.class), any(Runnable.class));
        verify(harness.inventoryService, never()).executeCriticalPlayerMutation(any(UUID.class), any());
        assertEquals(2, harness.orbQuantity.get());
        assertEquals(0, harness.equippedInstance.get().getTranscendenceRank());
        assertTrue(harness.service.isOrbInventory(harness.player.getOpenInventory().getTopInventory()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 7. プレイヤーがオーブから装備操作を開始する
     * 検証契約: 通常インベントリでクリックしたentryが消えていても、同じitem IDの通常stackがあれば共通消費順で再解決し、ローカル確定へ進む。
     */
    @Test
    void directInventoryOrbSelectionSwitchesToTheCommonConsumptionStack() {
        Harness harness = new Harness(ItemOrbEffectType.REPAIR);
        harness.additionalOrbQuantity = 32;

        harness.openOrbList();
        InventoryClickEvent targetClick = harness.guiClick(0);
        harness.handler.onInventoryClick(targetClick);
        verify(targetClick).setCancelled(true);
        verify(harness.inventoryService, atLeastOnce()).findOwnedNormalItemEntryForConsumption(
            harness.accountId,
            harness.orbModel.getId()
        );
        verify(harness.inventoryService).reserveOrbOperationPayment(
            eq(harness.accountId), any(UUID.class), any(), anyLong());
        verify(harness.inventoryService).commitLocalOrbOperationPayment(
            eq(harness.accountId), any(UUID.class), any(Runnable.class));
        verify(harness.inventoryService).executeCriticalPlayerMutation(eq(harness.accountId), any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 2. 合成画面 > ### 2.1 シジルオーブ操作画面
     * 検証契約: 通常インベントリのクリックはitem IDだけをシジルオーブ操作へ渡し、クリックしたentry IDを消費元として固定しない。
     */
    @Test
    void directInventorySigilOrbSelectionDoesNotFixClickedEntry() {
        Harness harness = new Harness(ItemOrbEffectType.SIGIL_ATTACH);
        SkillSigilOrbService skillSigilOrbService = mock(SkillSigilOrbService.class);
        harness.service.setSkillSigilOrbService(skillSigilOrbService);

        InventoryClickEvent click = harness.normalInventoryClick(9);
        harness.handler.onInventoryClick(click);

        verify(click).setCancelled(true);
        verify(skillSigilOrbService).start(
            eq(harness.player),
            eq(harness.astPlayer),
            eq(harness.orbModel),
            eq(false),
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
        assertEquals(Material.IRON_SWORD,
            harness.player.getOpenInventory().getTopInventory().getItem(0).getType());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_2-ユースケース.md
     * 章・見出し: # 04_2-ユースケース > ## 9. オーブで装備を更新する
     * 検証契約: ルーン装着GUIで装備のslotとtagに一致する所持ルーンを選択すると、支払い・ルーン装着・snapshot保存要求をAPI待機なしで確定する。
     */
    @Test
    void runeAttachGuiAcceptsRuneMatchingEquipmentSlotAndTag() {
        Harness harness = new Harness(ItemOrbEffectType.RUNE_ATTACH);
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
        verify(harness.inventoryService).commitLocalOrbOperationPayment(
            eq(harness.accountId), any(UUID.class), any(Runnable.class));
        verify(harness.inventoryService).executeCriticalPlayerMutation(eq(harness.accountId), any());
        assertEquals(1, harness.equippedInstance.get().getRunes().size());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_2-ユースケース.md
     * 章・見出し: # 04_2-ユースケース > ## 9. オーブで装備を更新する
     * 検証契約: 複数ルーンの脱着GUIはルーン選択とBAGスクロールを受け付け、支払い・ルーン返却・装備完成状態・snapshot保存要求をその場で確定する。
     */
    @Test
    void runeDetachGuiSelectsRuneDelegatesScrollAndClosesAfterReconciliation() {
        Harness harness = new Harness(ItemOrbEffectType.RUNE_DETACH);
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
        verify(harness.inventoryService).commitLocalOrbOperationPayment(
            eq(harness.accountId), any(UUID.class), any(Runnable.class));
        verify(harness.inventoryService).executeCriticalPlayerMutation(eq(harness.accountId), any());
        assertEquals(26, harness.equippedInstance.get().getRunes().size());
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
     * 検証契約: 状態変化は一覧から専用確認画面を経て支払い・ランク更新・snapshot保存要求をその場で確定し、API照合を待たずにGUIを閉じる。
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
        harness.awaitUsedOrbCount(1);
        assertEquals(1, harness.orbQuantity.get());
        assertEquals(1, harness.equippedInstance.get().getTranscendenceRank());
        verify(harness.inventoryService).commitLocalOrbOperationPayment(
            eq(harness.accountId), any(UUID.class), any(Runnable.class));
        verify(harness.inventoryService).executeCriticalPlayerMutation(eq(harness.accountId), any());
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

    /** 指定された文字列がGUIアイテムのloreに含まれることを検証します。 */
    private static void assertLoreContains(ItemStack item, String... expected) {
        assertNotNull(item);
        assertNotNull(item.getItemMeta());
        assertNotNull(item.getItemMeta().lore());
        for (String value : expected) {
            assertTrue(item.getItemMeta().lore().stream()
                .anyMatch(line -> line.toString().contains(value)),
                "Missing lore: " + value);
        }
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
        private final AtomicBoolean reservePaymentAvailable = new AtomicBoolean(true);
        private int additionalOrbQuantity;
        private final AtomicBoolean preloadRanOnPrimaryThread = new AtomicBoolean(true);
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
            service = new OrbService(
                plugin,
                inventoryService,
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
                }
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
            when(inventoryService.getNormalItemAmount(eq(accountId), eq(orbModel.getId())))
                .thenAnswer(invocation -> (long) orbQuantity.get() + Math.max(0, additionalOrbQuantity));
            when(inventoryService.isInventoryInfoSlot(26)).thenReturn(true);
            when(inventoryService.reserveOrbOperationPayment(
                eq(accountId),
                any(UUID.class),
                any(),
                anyLong()
            )).thenAnswer(invocation -> reservePaymentAvailable.get());
            doAnswer(invocation -> {
                try {
                    Object supplied = invocation.<java.util.function.Supplier<?>>getArgument(1).get();
                    InventorySaveCoordinator.CriticalMutation<?> mutation =
                        (InventorySaveCoordinator.CriticalMutation<?>) supplied;
                    return CompletableFuture.completedFuture(mutation.result());
                } catch (Throwable failure) {
                    return CompletableFuture.failedFuture(failure);
                }
            }).when(inventoryService).executeCriticalPlayerMutation(eq(accountId), any());
            when(inventoryService.commitLocalOrbOperationPayment(
                eq(accountId),
                any(UUID.class),
                any(Runnable.class)
            )).thenAnswer(invocation -> {
                if (!reservePaymentAvailable.get()) {
                    return false;
                }
                invocation.getArgument(2, Runnable.class).run();
                orbQuantity.decrementAndGet();
                return true;
            });
            when(itemService.captureEquipmentStateRollback(accountId)).thenReturn(() -> { });
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
            when(itemService.applyLocalEquipmentInstance(any(EquipmentInstance.class)))
                .thenAnswer(invocation -> {
                    EquipmentInstance updated = invocation.getArgument(0, EquipmentInstance.class);
                    if (updated.getEquipmentInstanceId().equalsIgnoreCase(equippedInstanceId.toString())) {
                        equippedInstance.set(updated);
                    } else if (updated.getEquipmentInstanceId().equalsIgnoreCase(bagInstanceId.toString())) {
                        bagInstance.set(updated);
                    }
                    return updated;
                });
            when(inventoryService.addItemToNormalInventoryStateOnly(any(AstPlayer.class), any(ItemModel.class), eq(1), anyString()))
                .thenReturn(1);
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

        private void awaitUsedOrbCount(int expected) {
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (usedOrbIds.size() < expected && System.nanoTime() < deadlineNanos) {
                server().getScheduler().performOneTick();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                assertFalse(Thread.currentThread().isInterrupted(),
                    "Interrupted while waiting for the SQL-acknowledged orb mutation");
            }
            assertEquals(expected, usedOrbIds.size(),
                "Orb mutation did not receive its SQL acknowledgement within 2 seconds");
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
                case ENHANCE -> new ItemOrbEffect(
                    type,
                    List.of(ItemEquipmentSlot.WEAPON),
                    null,
                    ItemOrbRankMode.EXACT,
                    null,
                    false,
                    null,
                    null);
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
                type == ItemOrbEffectType.ENHANCE ? enhancementDefinition() : null,
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
                null
            );
        }

        private ItemEquipmentEnhance enhancementDefinition() {
            List<ItemEquipmentEnhanceLevel> levels = java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(level -> new ItemEquipmentEnhanceLevel(
                    level,
                    List.of(),
                    null,
                    1.0D,
                    ItemEquipmentEnhanceFailAction.NONE,
                    null
                ))
                .toList();
            return new ItemEquipmentEnhance(8, levels);
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
            return instanceWithEnhanceLevel(instanceId, itemId, rank, durability, 0, runes);
        }

        private EquipmentInstance instanceWithEnhanceLevel(
            UUID instanceId,
            String itemId,
            int rank,
            int durability,
            int enhanceLevel,
            List<EquipmentRune> runes
        ) {
            return new EquipmentInstance(
                instanceId.toString(),
                accountId.toString(),
                itemId,
                enhanceLevel,
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

}
