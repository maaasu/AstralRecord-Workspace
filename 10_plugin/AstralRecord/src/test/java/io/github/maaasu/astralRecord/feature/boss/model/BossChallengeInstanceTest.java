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
