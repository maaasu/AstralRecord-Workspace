package io.github.maaasu.astralRecord.feature.guide.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.guide.model.GuideAction;
import io.github.maaasu.astralRecord.feature.guide.model.GuideActionType;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.service.NpcPlacementService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.skill.event.SkillBindGuiEventHandler;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuideActionServiceTest {

    /**
     * OPEN_MENU(skill_bind) はスキルマネージャー GUI の open へ委譲する。
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
            mock(PlayerMessageService.class), mock(ParticleDisplayService.class)
        );

        assertTrue(service.execute(
            player,
            new GuideAction(GuideActionType.OPEN_MENU, "open", null, "skill_bind")
        ));
        verify(skillBindGuiEventHandler).open(player);
    }
}
