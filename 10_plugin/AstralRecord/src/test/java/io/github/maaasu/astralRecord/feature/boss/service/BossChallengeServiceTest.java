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
}
