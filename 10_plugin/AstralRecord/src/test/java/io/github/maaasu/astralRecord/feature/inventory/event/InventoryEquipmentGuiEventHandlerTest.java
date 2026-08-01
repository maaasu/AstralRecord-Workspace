package io.github.maaasu.astralRecord.feature.inventory.event;

import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.service.EquipmentEnhancementService;
import io.github.maaasu.astralRecord.feature.item.service.EquipmentRepairService;
import io.github.maaasu.astralRecord.feature.menu.event.MenuOpenEventHandler;
import io.github.maaasu.astralRecord.feature.menu.service.MenuGuiTransitionService;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skill.event.SkillGemLearnEventHandler;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
            mock(EquipmentEnhancementService.class),
            mock(EquipmentRepairService.class),
            mock(MenuGuiTransitionService.class),
            mock(MenuOpenEventHandler.class),
            mock(SkillGemLearnEventHandler.class)
        );

        handler.onPlayerSwapHandItems(event);

        verify(event).setCancelled(true);
    }
}
