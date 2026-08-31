package io.github.maaasu.astralRecord.feature.skill.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.shop.service.ShopService;
import io.github.maaasu.astralRecord.feature.skill.gui.SkillForgetGui;
import io.github.maaasu.astralRecord.feature.skill.model.SkillForgetInventoryHolder;
import io.github.maaasu.astralRecord.feature.skill.model.SkillForgetScreen;
import io.github.maaasu.astralRecord.feature.skill.service.LearnedSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillOwnershipService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.shared.gui.session.GuiSessionEndEvent;
import io.github.maaasu.astralRecord.shared.gui.session.GuiSessionEndReason;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillForgetGuiEventHandlerTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 1. スキルマネージャー表示・操作
     * 検証契約: スキル忘却 GUI の手動 close は、終了理由が音ありの場合だけ CLOSE 音を一度再生する。
     */
    @Test
    void manualSessionEndPlaysCloseSound() {
        SkillForgetGui gui = mock(SkillForgetGui.class);
        Inventory inventory = mock(Inventory.class);
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        when(gui.holder(inventory)).thenReturn(new SkillForgetInventoryHolder(SkillForgetScreen.LIST, 0));
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(location);

        newHandler(gui).onGuiSessionEnd(new GuiSessionEndEvent(player, inventory, GuiSessionEndReason.MANUAL_CLOSE));

        verify(player).playSound(location, Sound.BLOCK_CHEST_CLOSE, SoundCategory.PLAYERS, 0.6F, 1.16F);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 1. スキルマネージャー表示・操作
     * 検証契約: スキル忘却 GUI のログアウト終了は cleanup を受けるが、無音の終了理由では CLOSE 音を再生しない。
     */
    @Test
    void quitSessionEndDoesNotPlayCloseSound() {
        SkillForgetGui gui = mock(SkillForgetGui.class);
        Inventory inventory = mock(Inventory.class);
        Player player = mock(Player.class);
        when(gui.holder(inventory)).thenReturn(new SkillForgetInventoryHolder(SkillForgetScreen.LIST, 0));
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        newHandler(gui).onGuiSessionEnd(new GuiSessionEndEvent(player, inventory, GuiSessionEndReason.PLAYER_QUIT));

        verify(player, never()).playSound(
            org.mockito.ArgumentMatchers.any(Location.class),
            org.mockito.ArgumentMatchers.any(Sound.class),
            org.mockito.ArgumentMatchers.any(SoundCategory.class),
            org.mockito.ArgumentMatchers.anyFloat(),
            org.mockito.ArgumentMatchers.anyFloat()
        );
    }

    private SkillForgetGuiEventHandler newHandler(SkillForgetGui gui) {
        return new SkillForgetGuiEventHandler(
            mock(AstralRecord.class),
            gui,
            mock(SkillService.class),
            mock(SkillOwnershipService.class),
            mock(LearnedSkillService.class),
            mock(PassiveSkillService.class),
            mock(InventoryService.class),
            mock(ItemService.class),
            mock(ShopService.class)
        );
    }
}
