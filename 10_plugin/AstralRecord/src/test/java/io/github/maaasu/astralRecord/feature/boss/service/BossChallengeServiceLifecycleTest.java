package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeConfig;
import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeInstance;
import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeState;
import io.github.maaasu.astralRecord.feature.boss.model.BossLocation;
import io.github.maaasu.astralRecord.feature.boss.model.BossScalingConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.party.model.Party;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.death.PlayerDeathService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.challenge.ChallengeParticipationRegistry;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BossChallengeServiceLifecycleTest extends MockBukkitTestBase {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/26_0-概要.md
     * 章・見出し: # 26_0-概要 > ## 4. 実装構成
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/26_0-概要.md
     * 章・見出し: # 26_0-概要 > ## 8. 依存・連携
     * 検証契約: 他挑戦の参加予約競合があっても、現在パーティーメンバーが全員Hub外なら待機Boss挑戦を終了し、終了完了後に共有予約を解放する。
     */
    @Test
    void endsWaitingBossChallengeBeforeCrossChallengeReservationConflictCanKeepItAlive() throws Exception {
        WorldServiceFixture fixture = new WorldServiceFixture();
        BossChallengeService service = service(fixture.worldService, fixture.partyService);
        UUID partyId = UUID.randomUUID();
        PlayerMock player = server().addPlayer();
        player.teleport(new Location(fixture.outsideWorld, 0.5D, 65.0D, 0.5D));
        Party party = new Party(partyId, player.getUniqueId());
        when(fixture.partyService.findPartyById(partyId)).thenReturn(party);

        BossLocation location = new BossLocation("entry", 0.5D, 64.0D, 0.5D, 0.0F, 0.0F);
        BossChallengeConfig config = new BossChallengeConfig(
                "field",
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
        MobInstance boss = DesignTestFixtures.mobInstance(
                MobCategory.BOSS,
                100.0D,
                0.0D,
                0.0D,
                io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig.EMPTY
        );
        BossChallengeInstance challenge = new BossChallengeInstance(
                UUID.randomUUID(),
                "party:" + partyId,
                player.getUniqueId(),
                boss.template(),
                config,
                List.of(player.getUniqueId())
        );
        challenge.confirmParticipants(List.of());

        ChallengeParticipationRegistry registry = field(
                service, "challengeParticipationRegistry", ChallengeParticipationRegistry.class);
        assertTrue(registry.reserve(
                UUID.randomUUID(),
                "party:other",
                List.of(player.getUniqueId()),
                "別の挑戦"
        ).acquired());

        try (MockedStatic<Logger> ignored = Mockito.mockStatic(Logger.class)) {
            invoke(service, challenge);
        }

        assertEquals(BossChallengeState.ENDING, challenge.state());
        server().getScheduler().performTicks(1L);
        assertFalse(registry.contains(challenge.challengeId()));
    }

    private BossChallengeService service(
            WorldService worldService,
            PartyService partyService
    ) {
        AstralRecord plugin = mock(AstralRecord.class);
        when(plugin.isEnabled()).thenReturn(true);
        return new BossChallengeService(
                plugin,
                mock(MobService.class),
                worldService,
                partyService,
                mock(PlayerMessageService.class),
                mock(BossFieldInstanceService.class),
                mock(ParticleDisplayService.class),
                mock(DisplayTextService.class),
                mock(PlayerDeathService.class),
                "hub"
        );
    }

    private void invoke(BossChallengeService service, BossChallengeInstance challenge)
            throws ReflectiveOperationException {
        Method method = BossChallengeService.class.getDeclaredMethod(
                "synchronizeWaitingParty", BossChallengeInstance.class);
        method.setAccessible(true);
        method.invoke(service, challenge);
    }

    @SuppressWarnings("unchecked")
    private <T> T field(Object target, String name, Class<T> type) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    private final class WorldServiceFixture {
        private final WorldService worldService = mock(WorldService.class);
        private final PartyService partyService = mock(PartyService.class);
        private final World hubWorld = server().addSimpleWorld("boss-waiting-conflict-hub");
        private final World outsideWorld = server().addSimpleWorld("boss-waiting-conflict-outside");

        private WorldServiceFixture() {
            WorldMasterData hubData = worldData("hub", WorldType.BASE);
            when(worldService.getById("hub")).thenReturn(hubData);
            when(worldService.resolveLoadedWorld(hubData)).thenReturn(hubWorld);
        }

        private static WorldMasterData worldData(String id, WorldType type) {
            return new WorldMasterData(
                    1,
                    id,
                    id,
                    type,
                    "",
                    "target/boss-waiting-lifecycle",
                    false,
                    type == WorldType.BOSS_FIELD,
                    4,
                    false,
                    false,
                    false,
                    false,
                    WorldSpawnLocation.defaultLocation(),
                    "",
                    null,
                    null,
                    null
            );
        }
    }

}
