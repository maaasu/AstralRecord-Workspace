package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.item.service.BuiltInWeaponAttackDefinitions;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class NormalAttackDegradationServiceTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 12. 通常攻撃劣化
     * 検証契約: 最初の4回は段階0、5回目以降は定義されたダメージ倍率・攻撃速度倍率へ進み、段階11で固定する。
     */
    @Test
    void normalAttackModifiersAdvanceFromFifthAttackAndSaturateAtStageEleven() {
        AtomicLong now = new AtomicLong(1_000L);
        NormalAttackDegradationService service = new NormalAttackDegradationService(now::get);
        AstPlayer player = astPlayer();

        int[] expectedStages = {0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 11};
        double[] expectedDamageMultipliers = {
            1.0D, 1.0D, 1.0D, 1.0D, 0.90D, 0.85D, 0.85D, 0.70D,
            0.60D, 0.50D, 0.40D, 0.30D, 0.20D, 0.10D, 0.0D, 0.0D
        };
        double[] expectedAttackSpeedMultipliers = {
            1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 0.90D,
            0.80D, 0.70D, 0.60D, 0.50D, 0.50D, 0.50D, 0.50D, 0.50D
        };

        for (int index = 0; index < expectedStages.length; index++) {
            NormalAttackDegradationService.AttackTicket ticket = service.beginNormalAttack(player);
            assertEquals(expectedStages[index], ticket.stage(), "attack=" + (index + 1));
            assertEquals(expectedDamageMultipliers[index], ticket.damageMultiplier(), 0.000001D);
            assertEquals(expectedAttackSpeedMultipliers[index], ticket.attackSpeedMultiplier(), 0.000001D);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 12. 通常攻撃劣化
     * 検証契約: 冒険者は通常攻撃を連続しても劣化状態・ダメージ倍率・攻撃速度倍率が変化しない。
     */
    @Test
    void adventurerDoesNotAccumulateNormalAttackDegradation() {
        AtomicLong now = new AtomicLong(1_000L);
        NormalAttackDegradationService service = new NormalAttackDegradationService(now::get);
        AstPlayer player = adventurerAstPlayer();

        for (int index = 0; index < 20; index++) {
            NormalAttackDegradationService.AttackTicket ticket = service.beginNormalAttack(player);
            assertEquals(0, ticket.stage(), "attack=" + (index + 1));
            assertEquals(1.0D, ticket.damageMultiplier(), 0.000001D);
            assertEquals(1.0D, ticket.attackSpeedMultiplier(), 0.000001D);
        }

        assertEquals(0, service.currentStage(player));
        assertEquals(1.0D, service.currentDamageMultiplier(player), 0.000001D);
        assertEquals(1.0D, service.currentAttackSpeedMultiplier(player), 0.000001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 12. 通常攻撃劣化
     * 検証契約: 劣化が段階1へ遷移した成功時だけ、通常攻撃の連続使用に関する案内を1回送信する。
     */
    @Test
    void degradationStartSendsAdviceOnlyOnce() {
        NormalAttackDegradationService service = new NormalAttackDegradationService(() -> 1_000L);
        AstPlayer player = astPlayer();
        PlayerMessageService messages = mock(PlayerMessageService.class);

        try (MockedStatic<PlayerMessageService> messageService = mockStatic(PlayerMessageService.class)) {
            messageService.when(PlayerMessageService::getInstance).thenReturn(messages);

            for (int index = 0; index < 5; index++) {
                service.beginNormalAttack(player);
            }
            service.onSkillCast(player, BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_MELEE);

            service.beginNormalAttack(player);
            service.onSkillCast(player, BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_MELEE);

            verify(messages, times(1)).send(player, PlayerMsgId.P_5357);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 12. 通常攻撃劣化
     * 検証契約: 最後の通常攻撃から10秒で段階を初期化し、通常攻撃ごとに有効期限を10秒へ更新する。
     */
    @Test
    void degradationExpiresAfterTenSecondsAndEachAttackRefreshesTheTimer() {
        AtomicLong now = new AtomicLong(1_000L);
        NormalAttackDegradationService service = new NormalAttackDegradationService(now::get);
        AstPlayer player = astPlayer();

        for (int index = 0; index < 5; index++) {
            service.beginNormalAttack(player);
        }
        now.addAndGet(9_999L);
        assertEquals(1, service.currentStage(player));

        now.incrementAndGet();
        assertEquals(0, service.currentStage(player));

        for (int index = 0; index < 5; index++) {
            service.beginNormalAttack(player);
        }
        now.addAndGet(9_000L);
        NormalAttackDegradationService.AttackTicket refreshed = service.beginNormalAttack(player);
        assertEquals(2, refreshed.stage());
        now.addAndGet(9_999L);
        assertEquals(2, service.currentStage(player));
        now.incrementAndGet();
        assertEquals(0, service.currentStage(player));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 12. 通常攻撃劣化
     * 検証契約: 通常攻撃自身の成功通知は保持し、通常攻撃以外のスキル成功は即時解除し、失敗した通常攻撃は状態を巻き戻す。
     */
    @Test
    void skillSuccessClearsOnlyNonNormalAttackAndFailedAttackRollsBack() {
        AtomicLong now = new AtomicLong(1_000L);
        NormalAttackDegradationService service = new NormalAttackDegradationService(now::get);
        AstPlayer player = astPlayer();

        for (int index = 0; index < 5; index++) {
            service.beginNormalAttack(player);
        }
        service.onSkillCast(player, BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_MELEE);
        assertEquals(1, service.currentStage(player));
        service.onSkillCast(player, "skill_active_test");
        assertEquals(0, service.currentStage(player));

        for (int index = 0; index < 5; index++) {
            service.beginNormalAttack(player);
        }
        NormalAttackDegradationService.AttackTicket failed = service.beginNormalAttack(player);
        assertEquals(2, failed.stage());
        service.rollbackNormalAttack(player, failed);
        assertEquals(1, service.currentStage(player));

        NormalAttackDegradationService.AttackTicket first = service.beginNormalAttack(player);
        assertEquals(2, first.stage());
        service.clearPlayer(player.getBukkit().getUniqueId());
        assertEquals(0, service.currentStage(player));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 12. 通常攻撃劣化
     * 検証契約: 劣化中だけ赤色・6分割の専用BossBarを表示し、タイトルと残り時間を更新し、解除時に非表示にする。
     */
    @Test
    void bossBarShowsStageAndRemainingTenSecondProgressOnlyWhileDegraded() {
        AtomicLong now = new AtomicLong(1_000L);
        NormalAttackDegradationService service = new NormalAttackDegradationService(now::get);
        PlayerMock bukkitPlayer = server().addPlayer();
        AstPlayer player = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.PLAYER);
        player.selectClass("swordsman");
        UUID playerId = bukkitPlayer.getUniqueId();
        AstPlayerCache.put(player);
        try {
            for (int index = 0; index < 5; index++) {
                service.beginNormalAttack(player);
            }
            BossBar bossBar = service.bossBarFor(playerId);
            assertSame(BarColor.RED, bossBar.getColor());
            assertSame(BarStyle.SEGMENTED_6, bossBar.getStyle());
            assertEquals("通常攻撃劣化[1]", bossBar.getTitle());
            assertEquals(1.0D, bossBar.getProgress(), 0.000001D);

            now.addAndGet(2_500L);
            service.updateAll();
            assertEquals(0.75D, bossBar.getProgress(), 0.000001D);
            assertEquals("通常攻撃劣化[1]", bossBar.getTitle());

            service.clearPlayer(playerId);
            assertFalse(bossBar.isVisible());
            assertNull(service.bossBarFor(playerId));
        } finally {
            AstPlayerCache.remove(playerId, player);
            service.stop();
        }
    }

    private AstPlayer astPlayer() {
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        player.selectClass("swordsman");
        return player;
    }

    private AstPlayer adventurerAstPlayer() {
        return DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
    }
}
