package io.github.maaasu.astralRecord.feature.hud.view;

import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeSidebarInfo;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerHudViewTest extends MockBukkitTestBase {

    @Test
    void rendersWorldRegionAndRegionLevelWithinSidebarLineLimit() {
        Player player = mock(Player.class);
        Scoreboard scoreboard = mock(Scoreboard.class);
        Objective objective = mock(Objective.class);
        Score score = mock(Score.class);
        when(player.getScoreboard()).thenReturn(scoreboard);
        when(player.getPing()).thenReturn(25);
        when(scoreboard.getObjective("astral_info")).thenReturn(objective);
        when(scoreboard.getEntries()).thenReturn(Collections.emptySet());
        when(objective.getScoreboard()).thenReturn(scoreboard);
        when(objective.getScore(anyString())).thenReturn(score);
        PlayerHudView view = new PlayerHudView();
        BossChallengeSidebarInfo bossInfo = new BossChallengeSidebarInfo(
                "星喰らい",
                42,
                1,
                3,
                30L,
                180L,
                List.of("Player")
        );

        view.renderSidebar(
                player,
                20.0D,
                10,
                0.5D,
                5,
                "剣士",
                "試練の大地",
                "風待ち草原",
                42,
                true,
                bossInfo
        );

        ArgumentCaptor<String> entries = ArgumentCaptor.forClass(String.class);
        verify(objective, org.mockito.Mockito.times(15)).getScore(entries.capture());
        assertEquals(15, entries.getAllValues().size());
        assertTrue(entries.getAllValues().stream().anyMatch(entry -> entry.contains("ワールド") && entry.contains("試練の大地")));
        assertTrue(entries.getAllValues().stream().anyMatch(entry -> entry.contains("地域") && entry.contains("風待ち草原")));
        assertTrue(entries.getAllValues().stream().anyMatch(entry -> entry.contains("地域レベル") && entry.contains("Lv.") && entry.contains("42")));
    }

    @Test
    void clearsOnlyScoresOwnedByAstralObjective() {
        Player player = mock(Player.class);
        Scoreboard scoreboard = mock(Scoreboard.class);
        Objective objective = mock(Objective.class);
        Score astralScore = mock(Score.class);
        when(player.getScoreboard()).thenReturn(scoreboard);
        when(player.getPing()).thenReturn(25);
        when(scoreboard.getObjective("astral_info")).thenReturn(objective);
        when(scoreboard.getEntries()).thenReturn(java.util.Set.of("shared-entry"));
        when(objective.getScoreboard()).thenReturn(scoreboard);
        when(objective.getScore("shared-entry")).thenReturn(astralScore);
        when(astralScore.isScoreSet()).thenReturn(true);
        when(objective.getScore(org.mockito.ArgumentMatchers.<String>argThat(value -> !"shared-entry".equals(value))))
            .thenReturn(mock(Score.class));

        new PlayerHudView().renderSidebar(
            player, 20.0D, 1, 0.0D, 1, "冒険者", "拠点", "拠点", 0, false, null
        );

        verify(astralScore).resetScore();
        verify(scoreboard, never()).resetScores("shared-entry");
    }
}
