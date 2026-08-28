package io.github.maaasu.astralRecord.feature.hud.view;

import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeSidebarInfo;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonSidebarInfo;
import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;
import io.github.maaasu.astralRecord.feature.buff.model.BuffType;
import io.github.maaasu.astralRecord.feature.condition.model.ActiveCondition;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.status.model.ShieldRechargeState;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.challenge.ChallengeWaitingStatus;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
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
     * 検証契約: 挑戦準備中の待機状態を表示し、Hub未到着の参加者名だけを灰色で描画する。
     */
    @Test
    void rendersWaitingStatusAndGreysMissingParticipantNames() {
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
        BossChallengeSidebarInfo bossInfo = new BossChallengeSidebarInfo(
                "星喰らい", 42, 0, 3, 0L, 180L,
                List.of("Player", "Absent"),
                ChallengeWaitingStatus.PARTY_MEMBERS_WAITING,
                Set.of("Absent")
        );

        new PlayerHudView().renderSidebar(
                player, 20.0D, 10, 0.5D, 5, "剣士", "坑道", "入口", 10,
                false, bossInfo
        );

        ArgumentCaptor<String> entries = ArgumentCaptor.forClass(String.class);
        verify(objective, org.mockito.Mockito.times(15)).getScore(entries.capture());
        List<String> rendered = entries.getAllValues();
        assertTrue(rendered.stream().anyMatch(entry -> entry.contains("パーティーメンバー待機中")));
        assertTrue(rendered.stream().anyMatch(entry -> entry.contains(ColorCodeUtil.GRAY + "Absent")));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_1-モデル定義.md
     * 章・見出し: # 32_1-モデル定義 > ## 5. Dungeon Sidebar表示モデル
     * 検証契約: Dungeon sidebarへ名称、共有死亡数、部屋進捗、参加者、clear後の強制帰還残秒を15行以内で描画する。
     */
    @Test
    void rendersDungeonChallengeInformationWithinSidebarLineLimit() {
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
        DungeonSidebarInfo dungeonInfo = new DungeonSidebarInfo(
                "黄昏の坑道", 1, 3, 4, 7, List.of("Player", "Ally"), 23L);

        new PlayerHudView().renderSidebar(
                player, 20.0D, 10, 0.5D, 5, "剣士", "坑道", "最終室", 10,
                false, null, dungeonInfo, false, List.of());

        ArgumentCaptor<String> entries = ArgumentCaptor.forClass(String.class);
        verify(objective, org.mockito.Mockito.times(15)).getScore(entries.capture());
        List<String> rendered = entries.getAllValues().stream().map(ColorCodeUtil::stripColor).toList();
        assertTrue(rendered.stream().anyMatch(entry -> entry.contains("黄昏の坑道")));
        assertTrue(rendered.stream().anyMatch(entry -> entry.contains("デス") && entry.contains("1/3")));
        assertTrue(rendered.stream().anyMatch(entry -> entry.contains("部屋") && entry.contains("4/7")));
        assertTrue(rendered.stream().anyMatch(entry -> entry.contains("Player") && entry.contains("Ally")));
        assertTrue(rendered.stream().anyMatch(entry -> entry.contains("帰還まで") && entry.contains("23s")));
        assertTrue(rendered.size() <= 15);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_1-モデル定義.md
     * 章・見出し: # 32_1-モデル定義 > ## 5. Dungeon Sidebar表示モデル
     * 検証契約: buff表示が有効でもDungeonの6行を先に予約し、clear後の強制帰還残秒を15行以内へ残す。
     */
    @Test
    void keepsDungeonReturnCountdownVisibleWhenBuffDisplayIsEnabled() {
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
        DungeonSidebarInfo dungeonInfo = new DungeonSidebarInfo(
                "黄昏の坑道", 1, 3, 7, 7, List.of("Player"), 9L);
        ActiveBuff buff = new ActiveBuff(
                new BuffType("test_buff", "TEST", "試験バフ", 1_200, false, null, List.of()),
                LocalDateTime.now(),
                LocalDateTime.now().plusMinutes(1)
        );

        new PlayerHudView().renderSidebar(
                player, 20.0D, 10, 0.5D, 5, "剣士", "坑道", "最終室", 10,
                false, null, dungeonInfo, true, List.of(buff));

        ArgumentCaptor<String> entries = ArgumentCaptor.forClass(String.class);
        verify(objective, org.mockito.Mockito.times(15)).getScore(entries.capture());
        List<String> rendered = entries.getAllValues().stream().map(ColorCodeUtil::stripColor).toList();
        assertTrue(rendered.stream().anyMatch(entry -> entry.contains("帰還まで") && entry.contains("9s")));
        assertTrue(rendered.size() <= 15);
    }

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
        verify(objective, org.mockito.Mockito.times(14)).getScore(entries.capture());
        assertEquals(14, entries.getAllValues().size());
        assertTrue(entries.getAllValues().stream().anyMatch(entry -> entry.contains("ワールド") && entry.contains("試練の大地")));
        assertTrue(entries.getAllValues().stream().anyMatch(entry -> entry.contains("エリア") && entry.contains("風待ち草原")));
        assertTrue(entries.getAllValues().stream().anyMatch(entry -> entry.contains("エリアレベル") && entry.contains("Lv.") && entry.contains("42")));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/10-hud/3-メソッド仕様/10_3-View.md
     * 章・見出し: # 10_3-View > ## 4. sidebar 描画・解除
     * 検証契約: プレイヤーはレベル値だけを表示し、クラス行の直下へ上下幅の小さいブロック形式のクラス経験値バーを描く。
     */
    @Test
    void rendersClassExperienceBarBelowClassWithoutPlayerExperienceBar() {
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

        new PlayerHudView().renderSidebar(
                player,
                20.0D,
                10,
                0.75D,
                5,
                "剣士",
                1_234L,
                "試練の大地",
                "風待ち草原",
                42,
                true,
                null,
                null,
                false,
                List.of()
        );

        ArgumentCaptor<String> entries = ArgumentCaptor.forClass(String.class);
        verify(objective, org.mockito.Mockito.times(11)).getScore(entries.capture());
        List<String> rendered = entries.getAllValues().stream().map(ColorCodeUtil::stripColor).toList();
        String classLine = rendered.stream()
                .filter(entry -> entry.contains("クラス") && entry.contains("剣士") && entry.contains("Lv.5"))
                .findFirst()
                .orElseThrow();
        String experienceLine = rendered.stream()
                .filter(entry -> entry.contains("EXP") && entry.contains("75%") && entry.contains("▰"))
                .findFirst()
                .orElseThrow();
        assertEquals(rendered.indexOf(classLine) + 1, rendered.indexOf(experienceLine));
        assertEquals(1L, rendered.stream().filter(entry -> entry.contains("EXP")).count());
        assertEquals(10L, experienceLine.chars().filter(character -> character == '▰').count());
        assertTrue(rendered.stream().anyMatch(entry -> entry.contains("Gold: 1234G")));
        assertTrue(rendered.stream().anyMatch(entry -> entry.contains("PING: 25ms")));
        assertTrue(rendered.stream().noneMatch(entry -> entry.contains("経験値")));
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
     * 章・見出し: # 10_3-View > ## 1. status ActionBar 描画
     * 検証契約: シールドリチャージ中も現在Shield/最大値を残し、同じ欄へリチャージ残り時間を併記する。
     */
    @Test
    void keepsShieldValueVisibleAlongsideRechargeCountdownOnActionBar() {
        Player player = mock(Player.class);
        StatusSnapshot snapshot = mock(StatusSnapshot.class);
        when(snapshot.getMaxValue(StatusType.MAX_HEALTH)).thenReturn(100.0D);
        when(snapshot.getMaxValue(StatusType.MAX_MANA)).thenReturn(80.0D);
        when(snapshot.getMaxValue(StatusType.MAX_ENERGY)).thenReturn(60.0D);
        when(snapshot.getMaxValue(StatusType.MAX_SHIELD)).thenReturn(30.0D);
        when(snapshot.getCurrentHp()).thenReturn(75.0D);
        when(snapshot.getCurrentMp()).thenReturn(40.0D);
        when(snapshot.getCurrentEnergy()).thenReturn(30.0D);
        when(snapshot.getCurrentShield()).thenReturn(4.0D);

        new PlayerHudView().renderActionBar(
                player,
                snapshot,
                List.of(),
                new ShieldRechargeState(1_000L, System.currentTimeMillis() + 8_000L, 0.6D)
        );

        ArgumentCaptor<Component> component = ArgumentCaptor.forClass(Component.class);
        verify(player).sendActionBar(component.capture());
        String plainText = PlainTextComponentSerializer.plainText().serialize(component.getValue());
        assertTrue(plainText.contains("SH 4/30"));
        assertTrue(plainText.contains("(RC "));
        assertTrue(plainText.contains("s)"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/10-hud/3-メソッド仕様/10_3-View.md
     * 章・見出し: # 10_3-View > ## 3. vanilla bar 描画
     * 検証契約: MPを最大10個の黄色い吸収ハートへ、アカウント経験値を経験値バーとレベル値へ描画する。
     */
    @Test
    void rendersManaAsAbsorptionHeartsAndAccountExperienceAsVanillaExperience() {
        Player player = mock(Player.class);
        StatusSnapshot snapshot = mock(StatusSnapshot.class);
        AttributeInstance maxHealth = mock(AttributeInstance.class);
        AttributeInstance maxAbsorption = mock(AttributeInstance.class);
        AttributeInstance armor = mock(AttributeInstance.class);
        when(player.getAttribute(Attribute.MAX_HEALTH)).thenReturn(maxHealth);
        when(player.getAttribute(Attribute.MAX_ABSORPTION)).thenReturn(maxAbsorption);
        when(player.getAttribute(Attribute.ARMOR)).thenReturn(armor);
        when(maxHealth.getBaseValue()).thenReturn(20.0D);
        when(maxAbsorption.getBaseValue()).thenReturn(0.0D);
        when(player.getHealth()).thenReturn(20.0D);
        when(player.getAbsorptionAmount()).thenReturn(0.0D);
        when(snapshot.getMaxValue(StatusType.MAX_HEALTH)).thenReturn(100.0D);
        when(snapshot.getMaxValue(StatusType.MAX_MANA)).thenReturn(80.0D);
        when(snapshot.getMaxValue(StatusType.MAX_ENERGY)).thenReturn(60.0D);
        when(snapshot.getMaxValue(StatusType.MAX_SHIELD)).thenReturn(0.0D);
        when(snapshot.getCurrentHp()).thenReturn(100.0D);
        when(snapshot.getCurrentMp()).thenReturn(40.0D);
        when(snapshot.getCurrentEnergy()).thenReturn(30.0D);

        new PlayerHudView().renderBars(player, snapshot, 12, 0.75D);

        verify(maxAbsorption).setBaseValue(20.0D);
        verify(player).setAbsorptionAmount(10.0D);
        verify(player).sendExperienceChange(0.75F, 12);
        org.mockito.Mockito.verify(player, org.mockito.Mockito.never()).setExp(org.mockito.ArgumentMatchers.anyFloat());
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
                        new BuffType("buff_" + index, "TEST", "バフ" + index, 1_200, false, null, List.of()),
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
        verify(objective, org.mockito.Mockito.times(15)).getScore(entries.capture());
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
