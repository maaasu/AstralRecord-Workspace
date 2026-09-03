package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeConfig;
import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeInstance;
import io.github.maaasu.astralRecord.feature.boss.model.BossLocation;
import io.github.maaasu.astralRecord.feature.boss.model.BossScalingConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BossChallengeServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 5. BOSS Mob の参加人数補正
     * 検証契約: 3人のBOSS MobへHP50%/人・シールド30%/人を適用し、最大HPと現在HPを200、シールドを160、設定済み攻撃倍率を1.5へ設定する。
     */
    @Test
    void participantScalingUpdatesRuntimeMaxHealthAndCurrentHealthTogether() {
        List<UUID> participants = List.of(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID()
        );
        BossLocation location = new BossLocation("world", 0.5D, 64.0D, 0.5D, 0.0F, 0.0F);
        BossChallengeConfig config = new BossChallengeConfig(
            "boss_field",
            location,
            2.0D,
            location,
            location,
            1,
            6,
            600L,
            5,
            5L,
            new BossScalingConfig(true, 25.0D)
        );
        MobInstance boss = DesignTestFixtures.mobInstance(
            MobCategory.BOSS,
            100.0D,
            0.0D,
            0.0D,
            new MobShieldConfig(true, 100.0D)
        );
        BossChallengeInstance challenge = new BossChallengeInstance(
            UUID.randomUUID(),
            "party:test",
            participants.getFirst(),
            boss.template(),
            config,
            participants
        );
        challenge.confirmParticipants(participants);

        BossChallengeService.applyParticipantScaling(challenge, boss);
        BossChallengeService.applyParticipantScaling(challenge, boss);

        assertEquals(200.0D, boss.maxHealth(), 0.0001D);
        assertEquals(200.0D, boss.currentHealth(), 0.0001D);
        assertEquals(160.0D, boss.currentShield(), 0.0001D);
        assertEquals(160.0D, boss.shieldDisplayCapacity(), 0.0001D);
        assertEquals(1.5D, boss.outgoingDamageMultiplier(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 5. BOSS Mob の参加人数補正
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_1-モデル定義.md
     * 章・見出し: # 12_1-モデル定義 > ## 18. Mob インスタンス
     * 検証契約: 3人のBOSS Mobがシールド再充填を完了したとき、設定回復量50へ同じシールド倍率1.6を適用し、現在値と表示容量を80にする。
     */
    @Test
    void participantScalingKeepsShieldMultiplierAfterRecharge() {
        List<UUID> participants = List.of(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID()
        );
        BossLocation location = new BossLocation("world", 0.5D, 64.0D, 0.5D, 0.0F, 0.0F);
        BossChallengeConfig config = new BossChallengeConfig(
            "boss_field",
            location,
            2.0D,
            location,
            location,
            1,
            6,
            600L,
            5,
            5L,
            BossScalingConfig.EMPTY
        );
        MobInstance boss = DesignTestFixtures.mobInstance(
            MobCategory.BOSS,
            100.0D,
            0.0D,
            0.0D,
            new MobShieldConfig(true, 100.0D, 10.0D, 50.0D)
        );
        BossChallengeInstance challenge = new BossChallengeInstance(
            UUID.randomUUID(),
            "party:test",
            participants.getFirst(),
            boss.template(),
            config,
            participants
        );
        challenge.confirmParticipants(participants);

        BossChallengeService.applyParticipantScaling(challenge, boss);
        boss.currentShield(0.0D, 1_000L);

        assertTrue(boss.startShieldRecharge(1_000L, 0L));
        assertTrue(boss.completeShieldRechargeIfReady(1_000L));
        assertEquals(80.0D, boss.currentShield(), 0.0001D);
        assertEquals(80.0D, boss.shieldDisplayCapacity(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-タスク・スケジューラ.md
     * 章・見出し: # 26_3-タスク・スケジューラ > ## 3. 挑戦監視 tick
     * 検証契約: 挑戦中ボスの現在HPを最大HPで割ったBossBar進捗率を0.0以上1.0以下へ制限する。
     */
    @Test
    void bossBarProgressIsClampedToValidRange() {
        assertEquals(
            "§cTest Boss §7| §cHP: §f25§7/§f100",
            BossChallengeService.formatBossBarTitle("&cTest Boss", 25.0D, 100.0D)
        );
        assertEquals(0.25D, BossChallengeService.bossBarProgress(25.0D, 100.0D), 0.0001D);
        assertEquals(0.0D, BossChallengeService.bossBarProgress(-10.0D, 100.0D), 0.0001D);
        assertEquals(1.0D, BossChallengeService.bossBarProgress(150.0D, 100.0D), 0.0001D);
        assertEquals(0.0D, BossChallengeService.bossBarProgress(50.0D, 0.0D), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 9. ボス討伐
     * 検証契約: 討伐時間は戦闘開始から討伐処理までの経過秒数を切り捨て、未開始または時刻逆行時は0秒とする。
     */
    @Test
    void defeatElapsedSecondsIsCalculatedFromCombatStart() {
        assertEquals(12L, BossChallengeService.calculateDefeatElapsedSeconds(1_000L, 13_999L));
        assertEquals(0L, BossChallengeService.calculateDefeatElapsedSeconds(1_000L, 1_999L));
        assertEquals(0L, BossChallengeService.calculateDefeatElapsedSeconds(0L, 10_000L));
        assertEquals(0L, BossChallengeService.calculateDefeatElapsedSeconds(10_000L, 9_000L));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 14. フィールド作成
     * 検証契約: 参加者退出またはフィールド準備 callback が未完了の間は作成枠を回収せず、両方が完了した後だけ回収へ進む。
     */
    @Test
    void fieldCleanupWaitsForPreparationAndParticipantExit() {
        assertFalse(BossChallengeService.isFieldCleanupReady(false, true, false));
        assertFalse(BossChallengeService.isFieldCleanupReady(false, true, true));
        assertFalse(BossChallengeService.isFieldCleanupReady(true, false, false));
        assertTrue(BossChallengeService.isFieldCleanupReady(false, false, true));
        assertTrue(BossChallengeService.isFieldCleanupReady(true, true, true));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 3. ボス挑戦受付
     * 検証契約: 待機ハブから離脱した本人は再参加候補として予定者一覧に残しつつ、Boss Sidebar の対象から外し、再到着後は再表示する。
     */
    @Test
    void excludesWaitingHubDepartedPlayerFromBossSidebarUntilRejoin() {
        UUID remainingPlayer = UUID.randomUUID();
        UUID departedPlayer = UUID.randomUUID();
        BossLocation location = new BossLocation("world", 0.5D, 64.0D, 0.5D, 0.0F, 0.0F);
        BossChallengeConfig config = new BossChallengeConfig(
                "boss_field",
                location,
                2.0D,
                location,
                location,
                1,
                6,
                600L,
                0,
                5L,
                BossScalingConfig.EMPTY
        );
        BossChallengeInstance challenge = new BossChallengeInstance(
                UUID.randomUUID(),
                "party:test",
                remainingPlayer,
                DesignTestFixtures.mobInstance(
                        MobCategory.BOSS,
                        100.0D,
                        0.0D,
                        0.0D,
                        MobShieldConfig.EMPTY
                ).template(),
                config,
                List.of(remainingPlayer, departedPlayer)
        );

        challenge.markWaitingAbsent(departedPlayer);

        assertTrue(BossChallengeService.isSidebarParticipant(challenge, remainingPlayer));
        assertFalse(BossChallengeService.isSidebarParticipant(challenge, departedPlayer));
        assertEquals(List.of(remainingPlayer, departedPlayer), challenge.expectedParticipantIds());

        challenge.clearWaitingAbsent(departedPlayer);

        assertTrue(BossChallengeService.isSidebarParticipant(challenge, departedPlayer));
    }
}
