package io.github.maaasu.astralRecord.feature.hud.view;

import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeSidebarInfo;
import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;
import io.github.maaasu.astralRecord.feature.buff.model.BuffType;
import io.github.maaasu.astralRecord.feature.condition.model.ActiveCondition;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerHudViewTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/10-hud/3-メソッド仕様/10_3-View.md
     * 章・見出し: # 10_3-View > ## 4. sidebar 描画・解除
     * 検証契約: world/region/region levelをsidebarへ描き全体を15行以内にする。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/10-hud/3-メソッド仕様/10_3-View.md
     * 章・見出し: # 10_3-View > ## 4. sidebar 描画・解除
     * 検証契約: 解除時にplugin専用astral_info objectiveだけをunregisterする。
     */
    @Test
    void unregistersOnlyAstralObjectiveWhenSidebarIsRemoved() {
        Player player = mock(Player.class);
        Scoreboard scoreboard = mock(Scoreboard.class);
        Objective astralObjective = mock(Objective.class);
        Objective unrelatedObjective = mock(Objective.class);
        when(player.getScoreboard()).thenReturn(scoreboard);
        when(scoreboard.getObjective("astral_info")).thenReturn(astralObjective);
        when(scoreboard.getObjectives()).thenReturn(Set.of(astralObjective, unrelatedObjective));

        new PlayerHudView().removeSidebar(player);

        verify(astralObjective).unregister();
        verify(unrelatedObjective, org.mockito.Mockito.never()).unregister();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/10-hud/3-メソッド仕様/10_3-View.md
     * 章・見出し: # 10_3-View > ## 1. status ActionBar 描画
     * 検証契約: HP/MP/EN/必要時shieldと装飾済みcondition最大3件を同じActionBarへ描く。
     */
    @Test
    void rendersResourcesAndDecoratedConditionsTogetherOnActionBar() {
        Player player = mock(Player.class);
        StatusSnapshot snapshot = mock(StatusSnapshot.class);
        when(snapshot.getMaxValue(StatusType.MAX_HEALTH)).thenReturn(100.0D);
        when(snapshot.getMaxValue(StatusType.MAX_MANA)).thenReturn(80.0D);
        when(snapshot.getMaxValue(StatusType.MAX_ENERGY)).thenReturn(60.0D);
        when(snapshot.getMaxValue(StatusType.MAX_SHIELD)).thenReturn(0.0D);
        when(snapshot.getCurrentHp()).thenReturn(75.0D);
        when(snapshot.getCurrentMp()).thenReturn(40.0D);
        when(snapshot.getCurrentEnergy()).thenReturn(30.0D);
        ActiveCondition burning = mock(ActiveCondition.class);
        when(burning.type()).thenReturn(ConditionType.BURNING);
        when(burning.expiresAtMs()).thenReturn(System.currentTimeMillis() + 5_000L);

        new PlayerHudView().renderActionBar(player, snapshot, List.of(burning));

        ArgumentCaptor<Component> component = ArgumentCaptor.forClass(Component.class);
        verify(player).sendActionBar(component.capture());
        String plainText = PlainTextComponentSerializer.plainText().serialize(component.getValue());
        assertTrue(plainText.contains("HP 75/100"));
        assertTrue(plainText.contains("MP 40/80"));
        assertTrue(plainText.contains("ENG 30/60"));
        assertTrue(plainText.contains("[火] 燃焼"));
        assertTrue(hasDecoratedText(component.getValue(), "燃焼"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/10-hud/3-メソッド仕様/10_3-View.md
     * 章・見出し: # 10_3-View > ## 4. sidebar 描画・解除
     * 検証契約: buffを取得順で最大5件表示し超過数を「ほかN件」で示して15行以内にする。
     */
    @Test
    void rendersFirstFiveBuffsAndOverflowWithinSidebarLimit() {
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
        LocalDateTime now = LocalDateTime.now();
        List<ActiveBuff> buffs = java.util.stream.IntStream.rangeClosed(1, 7)
                .mapToObj(index -> new ActiveBuff(
                        new BuffType("buff_" + index, "TEST", "バフ" + index, 1_200, false, List.of()),
                        now.minusSeconds(index),
                        now.plusMinutes(index)
                ))
                .toList();

        new PlayerHudView().renderSidebar(
                player,
                20.0D,
                1,
                0.0D,
                1,
                "冒険者",
                "拠点",
                "拠点",
                0,
                false,
                null,
                true,
                buffs
        );

        ArgumentCaptor<String> entries = ArgumentCaptor.forClass(String.class);
        verify(objective, org.mockito.Mockito.times(14)).getScore(entries.capture());
        List<String> rendered = entries.getAllValues().stream().map(ColorCodeUtil::stripColor).toList();
        assertTrue(rendered.stream().anyMatch(entry -> entry.contains("バフ1")));
        assertTrue(rendered.stream().anyMatch(entry -> entry.contains("バフ5") && entry.contains("ほか2件")));
        assertTrue(rendered.stream().noneMatch(entry -> entry.contains("バフ6")));
        assertTrue(rendered.size() <= 15);
    }

    private boolean hasDecoratedText(Component component, String text) {
        boolean matches = component instanceof net.kyori.adventure.text.TextComponent textComponent
                && textComponent.content().contains(text)
                && NamedTextColor.RED.equals(component.color())
                && component.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE;
        return matches || component.children().stream().anyMatch(child -> hasDecoratedText(child, text));
    }
}
