package io.github.maaasu.astralRecord.feature.hud.view;

import io.github.maaasu.astralRecord.feature.hud.model.QuestHudLayout.Kind;
import io.github.maaasu.astralRecord.feature.hud.model.QuestHudLayout.Row;
import io.github.maaasu.astralRecord.feature.hud.view.QuestHudGlyphs.Format;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QuestHudPixelFontTest {
    private static final Key PIXEL = Key.key("astralrecord", "quest_hud_pixel_text");
    private static final Key OLD = Key.key("astralrecord", "quest_hud");
    private static final Key SPACE = Key.key("astralrecord", "quest_hud_space");

    @Test
    void revisionFourSelectsOnlyTheNewTextFace() {
        assertEquals(Format.QUEST_V2, Format.forPack(false, false));
        assertEquals(Format.QUEST_V2, Format.forPack(false, true));
        assertEquals(Format.CHAPTER_V3, Format.forPack(true, false));
        assertEquals(Format.PIXEL_V4, Format.forPack(true, true));
        var rows = List.of(new Row(Kind.TITLE, "iW", "", -1));
        for (var old : List.of(Format.QUEST_V2, Format.CHAPTER_V3)) {
            var children = QuestHudGlyphs.render(rows, old).children();
            assertTrue(children.stream().anyMatch(c -> OLD.equals(c.font()) && value(c).equals("i")));
            assertFalse(children.stream().anyMatch(c -> PIXEL.equals(c.font())));
        }
        var children = QuestHudGlyphs.render(rows, Format.PIXEL_V4).children();
        assertTrue(children.stream().anyMatch(c -> OLD.equals(c.font()) && value(c).equals("\uE011")));
        var first = children.stream().filter(c -> PIXEL.equals(c.font()) && value(c).equals("i")).findFirst().orElseThrow();
        var second = children.stream().filter(c -> PIXEL.equals(c.font()) && value(c).equals("W")).findFirst().orElseThrow();
        assertEquals(2, second.color().green() - first.color().green());
        var rewind = children.get(children.indexOf(first) + 1);
        assertEquals(SPACE, rewind.font());
        assertEquals("\uE002", value(rewind));
    }

    @Test
    void wrapsByTheSelectedPackAndKeepsJapaneseAndRequiredFallbackGlyphs() {
        var widths = ClassNameGlyphs.loadAdvances("/quest-hud-pixel-advances.txt");
        assertEquals(2, widths.get((int) 'i'));
        assertEquals(6, widths.get((int) 'W'));
        assertEquals(4, widths.get((int) ' '));
        assertEquals(8, widths.get(0x3000));
        assertTrue("第章草ガイド?…".codePoints().allMatch(c -> widths.getOrDefault(c, 0) > 0));
        assertTrue(widths.values().stream().allMatch(w -> w > 0 && w <= 11));
        String narrow = "i".repeat(60);
        var current = QuestHudGlyphs.guideRows("章", 1, 6, 2, narrow, Format.PIXEL_V4);
        var old = QuestHudGlyphs.guideRows("章", 1, 6, 2, narrow, Format.CHAPTER_V3);
        assertEquals(narrow, current.getLast().label());
        assertTrue(old.size() > current.size());
        var longJapanese = List.of(new Row(Kind.TITLE, "ガイド".repeat(25), "1/6", -1));
        var modern = QuestHudGlyphs.render(longJapanese, Format.PIXEL_V4);
        var letters = modern.children().stream().filter(c -> PIXEL.equals(c.font())).toList();
        assertTrue(letters.stream().anyMatch(c -> value(c).equals("…")));
        var last = letters.getLast();
        assertEquals("6", value(last));
        assertEquals(175, last.color().green() + widths.get((int) '6'));
    }

    private static String value(Component component) {
        return ((TextComponent) component).content();
    }
}
