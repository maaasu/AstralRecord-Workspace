package io.github.maaasu.astralRecord.feature.item.event;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.item.service.DebugFishingRodUseService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.shared.interaction.InputClaimPolicy;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InputSource;
import io.github.maaasu.astralRecord.shared.interaction.InteractionCandidateOrder;
import io.github.maaasu.astralRecord.shared.interaction.InteractionTier;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputCandidate;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionRayTrace;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DebugFishingRodInteractionEventHandlerTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-イベント.md
     * 章・見出し: # 04_3-イベント > ## 1. クリック入力受付 > ### デバッグ釣り竿入力候補解決
     * 検証契約: main handの右クリックは釣り竿キャスト候補として入力をclaimし、候補実行時にcastへ到達する。
     */
    @Test
    void resolvesAndExecutesRightClickAsCastCandidate() {
        Fixture fixture = fixture();
        when(fixture.service.findCurrentFishingRodInstanceId(fixture.astPlayer)).thenReturn("rod-instance");
        when(fixture.service.isCasting(fixture.astPlayer)).thenReturn(false);
        when(fixture.service.canCast(fixture.astPlayer)).thenReturn(true);
        when(fixture.service.isCurrentFishingRod(fixture.astPlayer, "rod-instance")).thenReturn(true);
        DebugFishingRodInteractionEventHandler handler = new DebugFishingRodInteractionEventHandler(fixture.service);

        PlayerInputCandidate candidate;
        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(fixture.player)).thenReturn(fixture.astPlayer);
            candidate = handler.resolve(context(fixture)).stream().findFirst().orElseThrow();
        }

        assertEquals("debug-fishing-rod-cast", candidate.id());
        assertEquals(InteractionTier.WORLD_INTERACTION, candidate.tier());
        assertEquals(InteractionCandidateOrder.FISHING_ROD, candidate.stableOrder());
        assertEquals(InputClaimPolicy.CLAIM_AND_CANCEL, candidate.claimPolicy());
        assertTrue(candidate.executeIfValid());
        verify(fixture.service).cast(fixture.astPlayer);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-イベント.md
     * 章・見出し: # 04_3-イベント > ## 1. クリック入力受付 > ### デバッグ釣り竿入力候補解決
     * 検証契約: キャスト中のmain hand右クリックは回収を開始し、再発射しない。
     */
    @Test
    void retrievesOnRightClickWithoutRecastingWhileCasting() {
        Fixture fixture = fixture();
        when(fixture.service.findCurrentFishingRodInstanceId(fixture.astPlayer)).thenReturn("rod-instance");
        when(fixture.service.isCasting(fixture.astPlayer)).thenReturn(true);
        when(fixture.service.isCurrentFishingRod(fixture.astPlayer, "rod-instance")).thenReturn(true);
        DebugFishingRodInteractionEventHandler handler = new DebugFishingRodInteractionEventHandler(fixture.service);

        PlayerInputCandidate candidate;
        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(fixture.player)).thenReturn(fixture.astPlayer);
            candidate = handler.resolve(context(fixture)).stream().findFirst().orElseThrow();
        }

        assertEquals("debug-fishing-rod-active", candidate.id());
        assertEquals(InputClaimPolicy.CLAIM_AND_CANCEL, candidate.claimPolicy());
        assertTrue(candidate.executeIfValid());
        verify(fixture.service, never()).cast(fixture.astPlayer);
        verify(fixture.service).retract(fixture.astPlayer);
        verify(fixture.service, never()).cancel(fixture.playerId);
    }

    private Fixture fixture() {
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("debug-fishing-rod-test");
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getMode()).thenReturn(AccountMode.PLAYER);
        return new Fixture(player, astPlayer, mock(DebugFishingRodUseService.class), playerId);
    }

    private PlayerInputContext<PlayerInteractionSnapshot> context(Fixture fixture) {
        PlayerInteractionSnapshot snapshot = new PlayerInteractionSnapshot(
            fixture.player,
            mock(Event.class),
            EquipmentSlot.HAND,
            Action.RIGHT_CLICK_AIR,
            null,
            null,
            null,
            false,
            PlayerInteractionRayTrace.create(
                new Vector(0.0D, 64.0D, 0.0D),
                new Vector(0.0D, 0.0D, 1.0D),
                8.0D
            ),
            8.0D
        );
        return new PlayerInputContext<>(
            fixture.playerId,
            1L,
            InputFamily.RIGHT_CLICK,
            InputSource.SYNTHETIC,
            snapshot
        );
    }

    private record Fixture(
        Player player,
        AstPlayer astPlayer,
        DebugFishingRodUseService service,
        UUID playerId
    ) {
    }
}
