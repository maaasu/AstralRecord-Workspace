package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.guide.model.GuideAction;
import io.github.maaasu.astralRecord.feature.guide.model.GuideActionType;
import io.github.maaasu.astralRecord.feature.guide.model.GuideCondition;
import io.github.maaasu.astralRecord.feature.guide.model.GuideConditionType;
import io.github.maaasu.astralRecord.feature.guide.model.GuideEntry;
import io.github.maaasu.astralRecord.feature.guide.model.GuideStep;
import io.github.maaasu.astralRecord.feature.guide.service.GuideService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuideScreenViewTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-GUI・View.md
     * 章・見出し: # 09_3-GUI・View > ## 1. menu facade
     * 検証契約: step本文は白、detailsは灰色、カラー指定済みaction.descriptionは指定色で表示し、物理スロットから同じstepを解決する。
     */
    @Test
    void rendersStepTextDetailsAndActionWithExpectedColors() {
        GuideService guideService = mock(GuideService.class);
        when(guideService.resolveText(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(guideService.isStepCompleted(any(), anyString(), anyString())).thenReturn(false);

        GuideStep step = new GuideStep(
            "claim_login_bonus",
            "&fログインボーナスを受け取る",
            List.of("&7案内人に話しかける。"),
            new GuideCondition(GuideConditionType.LOGIN_BONUS_CLAIMED, null),
            new GuideAction(
                GuideActionType.NAVIGATE_NPC,
                "&dクリックで案内する。",
                "login_bonus_clerk",
                null
            )
        );
        GuideEntry guide = new GuideEntry(
            3,
            "beginner_onboarding",
            "beginner",
            10,
            "&b冒険を始めよう",
            null,
            null,
            List.of(step)
        );
        Inventory inventory = Bukkit.createInventory(null, GuideScreenView.SIZE, Component.text("ガイド"));

        GuideScreenView view = new GuideScreenView();
        view.renderDetail(inventory, guide, guideService, null);

        ItemStack item = inventory.getItem(19);
        List<Component> lore = item.getItemMeta().lore();
        assertEquals(3, lore.size());
        assertEquals(NamedTextColor.WHITE, lore.get(0).color());
        assertEquals(NamedTextColor.GRAY, lore.get(1).color());
        assertEquals(NamedTextColor.LIGHT_PURPLE, lore.get(2).color());
        assertSame(step, view.getStepAtSlot(guide, 19));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-GUI・View.md
     * 章・見出し: # 09_3-GUI・View > ## 1. menu facade
     * 検証契約: カラーコードのないaction.descriptionは灰色へフォールバックする。
     */
    @Test
    void uncoloredActionDescriptionFallsBackToGray() {
        GuideService guideService = mock(GuideService.class);
        when(guideService.resolveText(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(guideService.isStepCompleted(any(), anyString(), anyString())).thenReturn(false);

        GuideStep step = new GuideStep(
            "claim_login_bonus",
            "ログインボーナスを受け取る",
            List.of("案内人に話しかける。"),
            new GuideCondition(GuideConditionType.LOGIN_BONUS_CLAIMED, null),
            new GuideAction(
                GuideActionType.NAVIGATE_NPC,
                "クリックで案内する。",
                "login_bonus_clerk",
                null
            )
        );
        GuideEntry guide = new GuideEntry(
            3,
            "beginner_onboarding",
            "beginner",
            10,
            "冒険を始めよう",
            null,
            null,
            List.of(step)
        );
        Inventory inventory = Bukkit.createInventory(null, GuideScreenView.SIZE, Component.text("ガイド"));

        new GuideScreenView().renderDetail(inventory, guide, guideService, null);

        List<Component> lore = inventory.getItem(19).getItemMeta().lore();
        assertEquals(NamedTextColor.WHITE, lore.get(0).color());
        assertEquals(NamedTextColor.GRAY, lore.get(1).color());
        assertEquals(NamedTextColor.GRAY, lore.get(2).color());
    }
}
