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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
     * 設計入力: 00_docs/10_Plugin設計書/feature/10-hud/3-メソッド仕様/10_3-View.md
     * 章・見出し: # 10_3-View > ## 4. sidebar 描画・解除
     * 検証契約: ダンジョン／ボス参加者名に含まれる内部 {@code &} カラーコードをスコアボードへそのまま渡さず、
     * アカウントのスロット番号を灰色で表示する。
     */
    @Test
    void translatesLegacyColorCodesInChallengeParticipantNames() {
        PlayerHudView view = new PlayerHudView();

        Player bossPlayer = mock(Player.class);
        Objective bossObjective = prepareSidebar(bossPlayer);
        view.renderSidebar(
                bossPlayer, 20.0D, 10, 0.5D, 5, "剣士", "坑道", "入口", 10,
                false,
                new BossChallengeSidebarInfo(
                        "星喰らい", 42, 0, 3, 30L, 180L,
                        List.of("BossAccount&7#0")
                )
        );

        Player dungeonPlayer = mock(Player.class);
        Objective dungeonObjective = prepareSidebar(dungeonPlayer);
        view.renderSidebar(
                dungeonPlayer, 20.0D, 10, 0.5D, 5, "剣士", "坑道", "入口", 10,
                false,
                null,
                new DungeonSidebarInfo(
                        "黄昏の坑道", 1, 3, 4, 7,
                        List.of("DungeonAccount&7#1"), -1L
                ),
                false,
                List.of()
        );

        List<String> bossRendered = collectRawRenderedEntries(bossObjective, 14);
        List<String> dungeonRendered = collectRawRenderedEntries(dungeonObjective, 15);
        assertTrue(bossRendered.stream().anyMatch(entry -> entry.contains("BossAccount" + ColorCodeUtil.GRAY + "#0")));
        assertTrue(dungeonRendered.stream().anyMatch(entry -> entry.contains("DungeonAccount" + ColorCodeUtil.GRAY + "#1")));
        assertFalse(bossRendered.stream().anyMatch(entry -> entry.contains("&7")));
        assertFalse(dungeonRendered.stream().anyMatch(entry -> entry.contains("&7")));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/10-hud/3-メソッド仕様/10_3-View.md
     * 章・見出し: # 10_3-View > ## 4. sidebar 描画・解除
     * 検証契約: bossとdungeonの参加者名を名前単位で一定幅ごとに折り返し、継続行を参加者ラベル位置へ揃えて表示する。
     */
    @Test
    void wrapsParticipantNamesForBossAndDungeonSidebars() {
        List<String> participantNames = List.of("PlayerOne", "AllyTwo", "MageThree");
        PlayerHudView view = new PlayerHudView();

        Player bossPlayer = mock(Player.class);
        Objective bossObjective = prepareSidebar(bossPlayer);
        view.renderSidebar(
                bossPlayer, 20.0D, 10, 0.5D, 5, "剣士", "坑道", "入口", 10,
                false,
                new BossChallengeSidebarInfo("星喰らい", 42, 0, 3, 30L, 180L, participantNames)
        );

        Player dungeonPlayer = mock(Player.class);
        Objective dungeonObjective = prepareSidebar(dungeonPlayer);
        view.renderSidebar(
                dungeonPlayer, 20.0D, 10, 0.5D, 5, "剣士", "坑道", "最終室", 10,
                false,
                null,
                new DungeonSidebarInfo("黄昏の坑道", 1, 3, 4, 7, participantNames, -1L),
                false,
                List.of()
        );

        assertParticipantLines(collectRenderedEntries(bossObjective));
        assertParticipantLines(collectRenderedEntries(dungeonObjective));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/10-hud/3-メソッド仕様/10_3-View.md
     * 章・見出し: # 10_3-View > ## 4. sidebar 描画・解除
     * 検証契約: 参加者行が15行枠を超える場合も、参加者名を無言で切り捨てず省略人数を表示する。
     */
    @Test
    void keepsOverflowParticipantIndicatorInsideSidebarLimit() {
        List<String> participantNames = List.of(
                "12345678901234567890",
                "ABCDEFGHIJKLMNOPQRST",
                "abcdefghijklmnopqrst",
                "あいうえおかきくけこさしすせそたちつてと",
                "PlayerFives",
                "PlayerSix"
        );
        Player player = mock(Player.class);
        Objective objective = prepareSidebar(player);

        new PlayerHudView().renderSidebar(
                player, 20.0D, 10, 0.5D, 5, "剣士", "坑道", "入口", 10,
                false,
                new BossChallengeSidebarInfo("星喰らい", 42, 0, 3, 30L, 180L, participantNames)
        );

        List<String> rendered = collectRenderedEntries(objective);
        assertTrue(rendered.stream().anyMatch(entry -> entry.contains("参加者: 12345678901234567890")));
        assertTrue(rendered.stream().anyMatch(entry -> entry.contains("…ほか2人")));
        assertTrue(rendered.size() <= 15);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/10-hud/3-メソッド仕様/10_3-View.md
     * 章・見出し: # 10_3-View > ## 4. sidebar 描画・解除
     * 検証契約: 挑戦任意情報を省略した空き行へbuffを再配置し、参加者の折返しも維持する。
     */
    @Test
    void reallocatesOmittedChallengeRowsToBuffDisplay() {
        Player player = mock(Player.class);
        Objective objective = prepareSidebar(player);
        ActiveBuff buff = new ActiveBuff(
                new BuffType("reallocation_buff", "TEST", "再配置バフ", 1_200, false, null, List.of()),
                LocalDateTime.now(),
                LocalDateTime.now().plusMinutes(1)
        );

        new PlayerHudView().renderSidebar(
                player, 20.0D, 10, 0.5D, 5, "剣士", "坑道", "入口", 10,
                false,
                new BossChallengeSidebarInfo(
                        "星喰らい", 42, 0, 3, 30L, 180L,
                        List.of("PlayerOne", "AllyTwo", "MageThree")
                ),
                true,
                List.of(buff)
        );

        List<String> rendered = collectRenderedEntries(objective);
        assertTrue(rendered.stream().anyMatch(entry -> entry.contains("再配置バフ")));
        assertTrue(rendered.stream().anyMatch(entry -> entry.contains("参加者: PlayerOne、AllyTwo")));
        assertTrue(rendered.stream().anyMatch(entry -> entry.contains("     MageThree")));
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
     * 検証契約: buff表示が有効でも参加者表示を維持し、clear後の強制帰還残秒を15行以内へ残す。
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
        assertTrue(rendered.stream().anyMatch(entry -> entry.contains("Gold: 1234 G")));
        assertTrue(rendered.stream().anyMatch(entry -> entry.contains("PING: 25ms")));
        assertTrue(rendered.stream().noneMatch(entry -> entry.contains("経験値")));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/10-hud/3-メソッド仕様/10_3-View.md
     * 章・見出し: # 10_3-View > ## 4. sidebar 描画・解除
     * 検証契約: スキルツリーワールド用の CP / PP を1行へ表示し、性能情報と併記できる。
     */
    @Test
    void rendersSkillTreePointsOnSidebar() {
        Player player = mock(Player.class);
        Objective objective = prepareSidebar(player);

        new PlayerHudView().renderSidebar(
                player,
                20.0D,
                10,
                0.75D,
                5,
                "剣士",
                1_234L,
                "CP[剣士]",
                7,
                8,
                "スキルツリー",
                "スキルツリー",
                0,
                true,
                null,
                null,
                false,
                List.of()
        );

        List<String> rendered = collectRenderedEntries(objective, 12);
        assertTrue(rendered.stream().anyMatch(entry -> entry.contains("CP[剣士]: 7 / PP: 8")));
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
     * 検証契約: HP/MP/ENG/必要時shieldと装飾済みcondition最大3件を同じActionBarへ描く。
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

    private Objective prepareSidebar(Player player) {
        Scoreboard scoreboard = mock(Scoreboard.class);
        Objective objective = mock(Objective.class);
        Score score = mock(Score.class);
        when(player.getScoreboard()).thenReturn(scoreboard);
        when(player.getPing()).thenReturn(25);
        when(scoreboard.getObjective("astral_info")).thenReturn(objective);
        when(scoreboard.getEntries()).thenReturn(Collections.emptySet());
        when(objective.getScoreboard()).thenReturn(scoreboard);
        when(objective.getScore(anyString())).thenReturn(score);
        return objective;
    }

    private List<String> collectRenderedEntries(Objective objective) {
        return collectRenderedEntries(objective, 15);
    }

    private List<String> collectRenderedEntries(Objective objective, int expectedCallCount) {
        ArgumentCaptor<String> entries = ArgumentCaptor.forClass(String.class);
        verify(objective, org.mockito.Mockito.times(expectedCallCount)).getScore(entries.capture());
        return entries.getAllValues().stream().map(ColorCodeUtil::stripColor).toList();
    }

    private List<String> collectRawRenderedEntries(Objective objective, int expectedCallCount) {
        ArgumentCaptor<String> entries = ArgumentCaptor.forClass(String.class);
        verify(objective, org.mockito.Mockito.times(expectedCallCount)).getScore(entries.capture());
        return entries.getAllValues();
    }

    private void assertParticipantLines(List<String> rendered) {
        assertTrue(rendered.stream().anyMatch(entry -> entry.contains("参加者: PlayerOne、AllyTwo")));
        assertTrue(rendered.stream().anyMatch(entry -> entry.contains("     MageThree")));
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
