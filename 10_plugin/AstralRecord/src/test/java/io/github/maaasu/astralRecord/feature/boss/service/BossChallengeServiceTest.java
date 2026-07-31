package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeConfig;
import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeInstance;
import io.github.maaasu.astralRecord.feature.boss.model.BossLocation;
import io.github.maaasu.astralRecord.feature.boss.model.BossScalingConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BossChallengeServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 5. 参加人数補正
     * 検証契約: 3人・HP50%/人・攻撃25%/人の補正で最大HPと現在HPを200、攻撃倍率を1.5へ設定する。
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
            new BossScalingConfig(true, 50.0D, 25.0D)
        );
        MobInstance boss = DesignTestFixtures.mobInstance(100.0D, 0.0D, 0.0D);
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

        assertEquals(200.0D, boss.maxHealth(), 0.0001D);
        assertEquals(200.0D, boss.currentHealth(), 0.0001D);
        assertEquals(1.5D, boss.outgoingDamageMultiplier(), 0.0001D);
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
}
