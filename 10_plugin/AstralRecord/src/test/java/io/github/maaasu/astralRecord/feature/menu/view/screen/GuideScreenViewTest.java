package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.guide.model.GuideCondition;
import io.github.maaasu.astralRecord.feature.guide.model.GuideConditionType;
import io.github.maaasu.astralRecord.feature.guide.model.GuideEntry;
import io.github.maaasu.astralRecord.feature.guide.model.GuideStep;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GuideScreenViewTest {
    @Test
    void chapterLabelsRouteToTheirMatchingPhysicalButtons() {
        String active = GuideScreenView.TUTORIAL_PREFIX + "receive_welcome_mail";
        for (int row = 0; row < 4; row++) {
            for (int col = 1; col <= 7; col++) {
                assertEquals(22, GuideScreenView.panelControlSlot(row * 9 + col, null));
            }
        }
        for (int slot = 19; slot <= 25; slot++) {
            assertEquals(22, GuideScreenView.panelControlSlot(slot, active));
        }
        assertEquals(45, GuideScreenView.panelControlSlot(46, active));
        assertEquals(49, GuideScreenView.panelControlSlot(50, active));
        assertEquals(51, GuideScreenView.panelControlSlot(51, active));
        assertEquals(29, GuideScreenView.panelControlSlot(29, active));
        assertEquals(38, GuideScreenView.panelControlSlot(38, active));
        assertEquals(19, GuideScreenView.panelControlSlot(19, GuideScreenView.TUTORIAL_LOADING));
        assertEquals(54, GuideScreenView.panelControlSlot(54, active));
    }

    @Test
    void titleSeparatesEarnedCountFromNumberOfFirstIncompleteStep() {
        var condition = new GuideCondition(GuideConditionType.MAIN_MENU_OPENED, null);
        var chapter = new GuideEntry(3, GuideEntry.FIRST_CHAPTER_ID, "beginner", 10, "章", null, null,
            List.of(new GuideStep("open_menu", "開く", List.of(), condition, null),
                    new GuideStep("receive_welcome_mail", "受取", List.of(), condition, null)));
        var current = GuideScreenView.TUTORIAL_PREFIX + "receive_welcome_mail";
        assertEquals("第1章 4/6 ▶ 2 メールを開く", GuideScreenView.title(false, current, 4, chapter));
        assertTrue(GuideScreenView.isCurrentStepSlot(29, chapter, current));
        assertFalse(GuideScreenView.isCurrentStepSlot(28, chapter, current));
        assertEquals("第1章 6/6 チャプター完了",
            GuideScreenView.title(false, GuideScreenView.TUTORIAL_COMPLETE, 6, chapter));
    }
}
