package io.github.maaasu.astralRecord.feature.hud.view;

import io.github.maaasu.astralRecord.feature.hud.model.QuestHudLayout;
import io.github.maaasu.astralRecord.feature.hud.model.QuestHudLayout.Kind;
import io.github.maaasu.astralRecord.feature.hud.model.QuestHudLayout.Row;
import io.github.maaasu.astralRecord.feature.quest.service.QuestService.HudObjective;
import io.github.maaasu.astralRecord.feature.quest.service.QuestService.HudQuest;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class GuideChapterHudTest {
    @Test
    void showsChapterNumberAndProgressSeparatelyFromTheCurrentStepAndItsAction() {
        var rows = QuestHudGlyphs.guideRows("グラスボアを1体倒そう", 2, 6, 3, "バンドルをホットバーへ");
        assertEquals(List.of(new Row(Kind.GUIDE_TITLE, "第1章 グラスボアを1体倒そう", "2/6", -1),
            new Row(Kind.GUIDE_ACTION, "次のステップ 3", "", -1),
            new Row(Kind.GUIDE_CONTINUATION, "バンドルをホットバーへ", "", -1)), rows);
        assertTrue(QuestHudGlyphs.guideRows("章", -1, 6, 1, "操作").isEmpty());
        assertTrue(QuestHudGlyphs.guideRows("章", 6, 6, 6, "操作").isEmpty());
    }

    @Test
    void preservesQuestValuesAndOverflowWhenGuideTextWraps() {
        var guide = QuestHudGlyphs.guideRows("とても長い章タイトル".repeat(4), 1, 6, 4,
            "雑貨商ミナで簡素な武器を買う\n(12, 34, 56)");
        assertEquals(5, guide.size());
        assertEquals("次のステップ 4", guide.get(2).label());
        assertEquals("(12, 34, 56)", guide.getLast().label());
        var goals = IntStream.range(0, 8).mapToObj(i -> new HudObjective("草花" + i, 1, 2)).toList();
        List<HudQuest> quests = List.of(new HudQuest("採集", goals, null), new HudQuest("調査", goals, null));
        var questRows = QuestHudLayout.rows(quests, QuestHudLayout.MAX_ROWS - guide.size());
        assertEquals(10, guide.size() + questRows.size());
        assertEquals("1/2", questRows.get(1).value());
        assertEquals(Kind.OVERFLOW, questRows.getLast().kind());
        assertEquals(10, QuestHudLayout.rows(quests).size());
    }

    @Test
    void encodesNewGuideAndShadowBitsOnlyOnCapableConnections() {
        var guide = QuestHudGlyphs.guideRows("案内", 1, 6, 2, "メール");
        var modern = QuestHudGlyphs.render(guide, true);
        assertTrue(modern.children().stream().anyMatch(c -> isText(c) && (c.color().blue() & 16) != 0
            && c.shadowColor() != null && (c.shadowColor().value() & 48) == 48));
        var quest = List.of(new Row(Kind.TITLE, "依頼", "", -1));
        var legacy = QuestHudGlyphs.render(quest, false);
        assertTrue(legacy.children().stream().filter(GuideChapterHudTest::isText)
            .allMatch(c -> (c.color().blue() & 48) == 0 && (c.shadowColor().value() & 48) == 0));
        assertTrue(QuestHudLayout.bedrockText(quest).contains("依頼"));
        assertEquals(" ", QuestHudLayout.bedrockText(List.of()));
    }

    private static boolean isText(Component component) {
        return component.color() != null && component.color().red() >= 180 && component.color().red() < 220;
    }
}
