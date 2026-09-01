package io.github.maaasu.astralRecord.feature.guide.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.guide.model.GuideAction;
import io.github.maaasu.astralRecord.feature.guide.model.GuideActionType;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.service.NpcPlacementService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.skill.event.SkillBindGuiEventHandler;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuideActionServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 9. ガイド案内アクション
     * 検証契約: OPEN_MENU(skill_bind) はスキルマネージャー GUI の open へ委譲する。
     */
    @Test
    void executeOpensSkillManagerMenu() {
        AstralRecord plugin = mock(AstralRecord.class);
        SkillBindGuiEventHandler skillBindGuiEventHandler = mock(SkillBindGuiEventHandler.class);
        Player player = mock(Player.class);
        when(plugin.getSkillBindGuiEventHandler()).thenReturn(skillBindGuiEventHandler);
        when(skillBindGuiEventHandler.open(player)).thenReturn(true);

        GuideActionService service = new GuideActionService(
            plugin,
            mock(MobService.class),
            mock(NpcPlacementService.class),
            mock(PlayerMessageService.class)
        );

        assertTrue(service.execute(
            player,
            new GuideAction(GuideActionType.OPEN_MENU, "open", null, "skill_bind")
        ));
        verify(skillBindGuiEventHandler).open(player);
    }
}
