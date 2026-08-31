package io.github.maaasu.astralRecord.feature.adventurerecord.event;

import io.github.maaasu.astralRecord.feature.adventurerecord.gui.AdventureRecordGui;
import io.github.maaasu.astralRecord.feature.adventurerecord.model.AdventureRecordListType;
import io.github.maaasu.astralRecord.feature.adventurerecord.service.AdventureRecordService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionsConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.shared.gui.paging.PagedGuiView;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdventureRecordGuiEventHandlerTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/21-adventurerecord/21_3-メソッド仕様.md
     * 章・見出し: # 21_3-メソッド仕様 > ## 表示 entry 生成 > ### Mob 詳細・ステータス表示
     * 検証契約: Mob 一覧の content slot click は対応する Entry を Mob 情報画面へ渡す。
     */
    @Test
    void opensMobDetailWhenMobListEntryIsClicked() {
        AdventureRecordGui gui = mock(AdventureRecordGui.class);
        AdventureRecordService service = mock(AdventureRecordService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        AdventureRecordGuiEventHandler handler = new AdventureRecordGuiEventHandler(gui, service, inventoryService);
        Player player = mock(Player.class);
        Inventory inventory = mock(Inventory.class);
        InventoryView view = mock(InventoryView.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        AdventureRecordService.Entry entry = new AdventureRecordService.Entry(testMob(), null, true);

        when(view.getTopInventory()).thenReturn(inventory);
        when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getRawSlot()).thenReturn(0);
        when(gui.isInventory(inventory)).thenReturn(true);
        when(gui.getScreen(inventory)).thenReturn(AdventureRecordGui.Screen.MOB_LIST);
        when(gui.getListType(inventory)).thenReturn(AdventureRecordListType.ALL);
        when(gui.getEntryAtSlot(inventory, 0)).thenReturn(entry);

        handler.onInventoryClick(event);

        verify(event).setCancelled(true);
        verify(gui).openMobDetail(player, entry);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/21-adventurerecord/21_3-メソッド仕様.md
     * 章・見出し: # 21_3-メソッド仕様 > ## 表示 entry 生成 > ### Mob 詳細・ステータス表示
     * 検証契約: Mob 情報画面のステータスカテゴリ click は同じ Entry とカテゴリを詳細画面へ渡す。
     */
    @Test
    void opensMobStatusDetailWhenMobStatusCategoryIsClicked() {
        AdventureRecordGui gui = mock(AdventureRecordGui.class);
        AdventureRecordService service = mock(AdventureRecordService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        AdventureRecordGuiEventHandler handler = new AdventureRecordGuiEventHandler(gui, service, inventoryService);
        Player player = mock(Player.class);
        Inventory inventory = mock(Inventory.class);
        InventoryView view = mock(InventoryView.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        AdventureRecordService.Entry entry = new AdventureRecordService.Entry(testMob(), null, true);

        stubClick(gui, event, view, inventory, player, AdventureRecordGui.MOB_OFFENSE_SLOT, AdventureRecordGui.Screen.MOB_DETAIL);
        when(gui.getMobEntry(inventory)).thenReturn(entry);
        when(gui.getMobStatusCategoryAtSlot(AdventureRecordGui.MOB_OFFENSE_SLOT))
            .thenReturn(StatusType.Category.OFFENSE);

        handler.onInventoryClick(event);

        verify(event).setCancelled(true);
        verify(gui).openMobStatusDetail(player, entry, StatusType.Category.OFFENSE, 0);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/21-adventurerecord/21_3-メソッド仕様.md
     * 章・見出し: # 21_3-メソッド仕様 > ## 表示 entry 生成 > ### Mob 詳細・ステータス表示
     * 検証契約: ステータス詳細画面の次ページ click は同じ Entry とカテゴリを保持して次ページを開く。
     */
    @Test
    void opensNextMobStatusDetailPage() {
        AdventureRecordGui gui = mock(AdventureRecordGui.class);
        AdventureRecordService service = mock(AdventureRecordService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        AdventureRecordGuiEventHandler handler = new AdventureRecordGuiEventHandler(gui, service, inventoryService);
        Player player = mock(Player.class);
        Inventory inventory = mock(Inventory.class);
        InventoryView view = mock(InventoryView.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        AdventureRecordService.Entry entry = new AdventureRecordService.Entry(testMob(), null, true);

        stubClick(gui, event, view, inventory, player, PagedGuiView.NEXT_SLOT, AdventureRecordGui.Screen.MOB_STATUS_DETAIL);
        when(gui.getMobEntry(inventory)).thenReturn(entry);
        when(gui.getMobStatusCategory(inventory)).thenReturn(StatusType.Category.OFFENSE);
        when(gui.getPageIndex(inventory)).thenReturn(0);
        when(gui.getMobStatusDetailItemCount(inventory)).thenReturn(46);
        when(gui.hasNextPage(0, 46)).thenReturn(true);

        handler.onInventoryClick(event);

        verify(event).setCancelled(true);
        verify(gui).openMobStatusDetail(player, entry, StatusType.Category.OFFENSE, 1);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/21-adventurerecord/21_4-統合フロー.md
     * 章・見出し: # 21_4-統合フロー > ## 5. GUI event 保護
     * 検証契約: Mob 情報画面のカテゴリ以外の click は遷移せず、イベント取消を維持する。
     */
    @Test
    void rejectsInvalidMobDetailSlot() {
        AdventureRecordGui gui = mock(AdventureRecordGui.class);
        AdventureRecordService service = mock(AdventureRecordService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        AdventureRecordGuiEventHandler handler = new AdventureRecordGuiEventHandler(gui, service, inventoryService);
        Player player = mock(Player.class);
        Inventory inventory = mock(Inventory.class);
        InventoryView view = mock(InventoryView.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        AdventureRecordService.Entry entry = new AdventureRecordService.Entry(testMob(), null, true);

        stubClick(gui, event, view, inventory, player, 0, AdventureRecordGui.Screen.MOB_DETAIL);
        when(gui.getMobEntry(inventory)).thenReturn(entry);
        when(gui.getMobStatusCategoryAtSlot(0)).thenReturn(null);

        handler.onInventoryClick(event);

        verify(event).setCancelled(true);
        verify(gui, never()).openMobStatusDetail(any(), any(), any(), anyInt());
    }

    private void stubClick(
        AdventureRecordGui gui,
        InventoryClickEvent event,
        InventoryView view,
        Inventory inventory,
        Player player,
        int rawSlot,
        AdventureRecordGui.Screen screen
    ) {
        when(view.getTopInventory()).thenReturn(inventory);
        when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getRawSlot()).thenReturn(rawSlot);
        when(gui.isInventory(inventory)).thenReturn(true);
        when(gui.getScreen(inventory)).thenReturn(screen);
    }

    private MobTemplate testMob() {
        return new MobTemplate(
            1,
            "click_mob",
            MobCategory.ENEMY,
            "click_mob",
            null,
            1,
            EntityType.ZOMBIE,
            false,
            null,
            List.of(),
            List.of(),
            null,
            MobEquipmentConfig.EMPTY,
            List.of(),
            MobShieldConfig.EMPTY,
            MobIdleConfig.defaults(),
            false,
            MobInteractionsConfig.EMPTY,
            null,
            null,
            null
        );
    }
}
