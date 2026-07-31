package io.github.maaasu.astralRecord.feature.boss.model;

import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionsConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobVariantConfig;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BossChallengeInstanceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/26_1-モデル定義.md
     * 章・見出し: # 26_1-モデル定義 > ## 4. ボス挑戦インスタンス
     * 検証契約: 受付時参加予定者を維持しつつ、入場直前の実参加者を別一覧へ確定してconfirmedを立てる。
     */
    @Test
    void confirmsActualEntrantsSeparatelyFromExpectedMembers() {
        UUID first = UUID.randomUUID();
        UUID departed = UUID.randomUUID();
        BossChallengeInstance challenge = createChallenge(List.of(first, departed));

        challenge.confirmParticipants(List.of(first));

        assertEquals(List.of(first, departed), challenge.expectedParticipantIds());
        assertEquals(List.of(first), challenge.participantIds());
        assertTrue(challenge.participantsConfirmed());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/26_1-モデル定義.md
     * 章・見出し: # 26_1-モデル定義 > ## 4. ボス挑戦インスタンス
     * 検証契約: 死亡ごとに共有回数とplayer別回数を同時加算し、immutable snapshotへ反映する。
     */
    @Test
    void recordsSharedAndPerPlayerDeathCounts() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        BossChallengeInstance challenge = createChallenge(List.of(first, second));

        assertEquals(1, challenge.recordDeath(first));
        assertEquals(2, challenge.recordDeath(first));
        assertEquals(3, challenge.recordDeath(second));

        assertEquals(3, challenge.deathCount());
        assertEquals(2, challenge.playerDeathCount(first));
        assertEquals(1, challenge.playerDeathCount(second));
        assertTrue(challenge.deathSnapshot().containsKey(first));
    }

    private BossChallengeInstance createChallenge(List<UUID> expectedParticipants) {
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
        return new BossChallengeInstance(
                UUID.randomUUID(),
                "party:test",
                expectedParticipants.getFirst(),
                createTemplate(config),
                config,
                expectedParticipants
        );
    }

    private MobTemplate createTemplate(BossChallengeConfig config) {
        return new MobTemplate(
                1,
                "test_boss",
                MobCategory.BOSS,
                "Test Boss",
                null,
                1,
                EntityType.IRON_GOLEM,
                null,
                null,
                true,
                null,
                List.of(),
                List.of(),
                null,
                MobVariantConfig.DEFAULT,
                MobEquipmentConfig.EMPTY,
                List.of(),
                MobShieldConfig.EMPTY,
                MobIdleConfig.defaults(),
                false,
                MobInteractionsConfig.EMPTY,
                null,
                null,
                null,
                config
        );
    }
}
