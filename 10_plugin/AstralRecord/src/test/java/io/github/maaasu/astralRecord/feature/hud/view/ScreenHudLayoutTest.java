package io.github.maaasu.astralRecord.feature.hud.view;

import io.github.maaasu.astralRecord.feature.hud.model.SidebarBuffPageState;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCooldownHudEntry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ScreenHudLayoutTest {
    // HUD design 10-hud: visibility filters do not reserve disabled display rows.
    @Test
    void hidingEveryOrdinaryRowProducesNoOverlay() {
        var rows = layout(null, null, null, List.of());
        assertEquals(List.of(), rows);
        assertEquals(Component.empty(), LocationHudGlyphs.render(rows, true));
    }

    @Test
    void worldAndAreaAreIndependentAndEquippedSkillsNeedNoLeadingBlankRow() {
        var worldOnly = layout("World", null, null, List.of());
        var areaOnly = layout(null, "Area", null, List.of());
        assertEquals(1, worldOnly.size());
        assertEquals(1, areaOnly.size());
        assertNotEquals(worldOnly, areaOnly);
        var equipped = new SkillCooldownHudEntry(3, "Skill", 30, SkillCooldownHudEntry.State.COOLDOWN);
        var onlySkills = layout(null, null, null, List.of(equipped));
        assertEquals(2, onlySkills.size());
        assertEquals(2, onlySkills.getFirst().labels().size());
        assertEquals(4, layout("World", null, null, List.of(equipped)).size());
    }

    @Test
    void eachPerformanceValueCanBeRenderedAlone() {
        var mspt = perf(true, false);
        var ping = perf(false, true);
        var both = perf(true, true);
        assertEquals(2, layout(null, null, mspt, List.of()).getFirst().labels().size());
        assertEquals(2, layout(null, null, ping, List.of()).getFirst().labels().size());
        assertEquals(4, layout(null, null, both, List.of()).getFirst().labels().size());
        assertNotEquals(layout(null, null, mspt, List.of()), layout(null, null, ping, List.of()));
    }

    private static LocationHudGlyphs.Performance perf(boolean mspt, boolean ping) {
        return new LocationHudGlyphs.Performance(7.0, 36, NamedTextColor.GREEN, NamedTextColor.GREEN, mspt, ping);
    }

    private static List<LocationHudGlyphs.Row> layout(String world, String area, LocationHudGlyphs.Performance performance,
            List<SkillCooldownHudEntry> skills) {
        return LocationHudGlyphs.layout(world, area, 10, performance, null, null, new SidebarBuffPageState(), List.of(),
                false, null, LocalDateTime.of(2026, 11, 16, 0, 0), skills, true);
    }
}
