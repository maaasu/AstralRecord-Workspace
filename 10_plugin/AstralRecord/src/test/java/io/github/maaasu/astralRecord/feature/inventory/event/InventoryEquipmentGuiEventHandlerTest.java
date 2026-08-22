package io.github.maaasu.astralRecord.feature.inventory.event;

import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.service.OrbService;
import io.github.maaasu.astralRecord.feature.menu.event.MenuOpenEventHandler;
import io.github.maaasu.astralRecord.feature.menu.service.MenuGuiTransitionService;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skill.event.SkillGemLearnEventHandler;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryEquipmentGuiEventHandlerTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-イベント.md
     * 章・見出し: # 08_3-イベント > ## 5. 手持ちアイテム入れ替え抑止
     * 検証契約: vanilla offhand swap操作をcancelする。
     */
    @Test
    void cancelsVanillaOffhandSwap() {
        PlayerSwapHandItemsEvent event = mock(PlayerSwapHandItemsEvent.class);
        InventoryEquipmentGuiEventHandler handler = new InventoryEquipmentGuiEventHandler(
            mock(MenuView.class),
            mock(InventoryService.class),
            mock(CurrencyService.class),
            mock(StatusService.class),
            mock(PassiveSkillService.class),
            mock(OrbService.class),
            mock(MenuGuiTransitionService.class),
            mock(MenuOpenEventHandler.class),
            mock(SkillGemLearnEventHandler.class)
        );

        handler.onPlayerSwapHandItems(event);

        verify(event).setCancelled(true);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-イベント.md
     * 章・見出し: # 08_3-イベント > ## 2. インベントリクローズ受付
     * 検証契約: 他プレイヤー装備の参照専用画面では、装備や閲覧者の所持品を変更する入力を受け付けない。
     */
    @Test
    void cancelsItemDropWhileViewingOtherPlayerEquipment() {
        MenuView menuView = mock(MenuView.class);
        InventoryService inventoryService = mock(InventoryService.class);
        OrbService orbService = mock(OrbService.class);
        InventoryEquipmentGuiEventHandler handler = new InventoryEquipmentGuiEventHandler(
            menuView,
            inventoryService,
            mock(CurrencyService.class),
            mock(StatusService.class),
            mock(PassiveSkillService.class),
            orbService,
            mock(MenuGuiTransitionService.class),
            mock(MenuOpenEventHandler.class),
            mock(SkillGemLearnEventHandler.class)
        );
        Player player = mock(Player.class);
        InventoryView view = mock(InventoryView.class);
        Inventory inventory = mock(Inventory.class);
        PlayerDropItemEvent event = mock(PlayerDropItemEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(inventory);
        when(menuView.isEquipmentReadOnly(inventory)).thenReturn(true);
        when(orbService.isLocked(player)).thenReturn(false);

        handler.onPlayerDropItem(event);

        verify(event).setCancelled(true);
    }
}
