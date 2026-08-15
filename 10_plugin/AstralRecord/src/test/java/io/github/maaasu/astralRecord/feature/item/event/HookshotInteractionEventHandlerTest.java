package io.github.maaasu.astralRecord.feature.item.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.item.service.HookshotUseService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.shared.interaction.InputClaimPolicy;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InputSource;
import io.github.maaasu.astralRecord.shared.interaction.InteractionCandidateOrder;
import io.github.maaasu.astralRecord.shared.interaction.InteractionTier;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputCandidate;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputResolver;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionGatewayEventHandler;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionRayTrace;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HookshotInteractionEventHandlerTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-イベント.md
     * 章・見出し: # 04_3-イベント > ## 1. クリック入力受付 > ### フックショット入力候補解決
     * 検証契約: フックショットの右クリックは候補を返さず、装填を開始しない。
     */
    @Test
    void ignoresRightClickWithoutStartingLoading() {
        HandlerFixture fixture = handlerFixture();
        when(fixture.service().findCurrentHookshotInstanceId(fixture.astPlayer())).thenReturn("hookshot-instance");
        HookshotInteractionEventHandler handler = new HookshotInteractionEventHandler(fixture.service());

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(fixture.player())).thenReturn(fixture.astPlayer());
            assertTrue(handler.resolve(
                hookshotContext(fixture, InputFamily.RIGHT_CLICK, Action.RIGHT_CLICK_AIR)
            ).isEmpty());
        }

        verify(fixture.service(), never()).startLoading(fixture.astPlayer());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-イベント.md
     * 章・見出し: # 04_3-イベント > ## 1. クリック入力受付 > ### フックショット入力候補解決
     * 検証契約: 未装填フックショットのmain hand左クリックは、アンカー照準を必要とせず装填候補を返す。
     */
    @Test
    void resolvesLeftClickAsLoadingCandidateWithoutAnchor() {
        HandlerFixture fixture = handlerFixture();
        when(fixture.service().findCurrentHookshotInstanceId(fixture.astPlayer())).thenReturn("hookshot-instance");
        when(fixture.service().canStartLoading(fixture.astPlayer())).thenReturn(true);
        HookshotInteractionEventHandler handler = new HookshotInteractionEventHandler(fixture.service());

        PlayerInputCandidate candidate;
        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(fixture.player())).thenReturn(fixture.astPlayer());
            candidate = handler.resolve(hookshotContext(fixture, InputFamily.LEFT_CLICK, Action.LEFT_CLICK_AIR)).stream()
                .findFirst()
                .orElseThrow();
        }

        assertEquals(InteractionTier.WORLD_INTERACTION, candidate.tier());
        assertEquals(8.0D, candidate.hitDistance());
        assertEquals(InteractionCandidateOrder.HOOKSHOT, candidate.stableOrder());
        assertEquals(InputClaimPolicy.CLAIM_AND_CANCEL, candidate.claimPolicy());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-イベント.md
     * 章・見出し: # 04_3-イベント > ## 1. クリック入力受付 > ### フックショット入力候補解決
     * 検証契約: 左クリックは装填済みかつ有効な固体アンカーを再確認できる場合だけ発射候補を返す。
     */
    @Test
    void doesNotResolveLeftClickWhenLoadedShotCannotFire() {
        HandlerFixture fixture = handlerFixture();
        when(fixture.service().findCurrentHookshotInstanceId(fixture.astPlayer())).thenReturn("hookshot-instance");
        when(fixture.service().isCurrentHookshotLoaded(fixture.astPlayer(), "hookshot-instance"))
            .thenReturn(true);
        when(fixture.service().canFire(fixture.astPlayer(), "hookshot-instance")).thenReturn(false);
        HookshotInteractionEventHandler handler = new HookshotInteractionEventHandler(fixture.service());

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(fixture.player())).thenReturn(fixture.astPlayer());
            assertTrue(handler.resolve(hookshotContext(fixture, InputFamily.LEFT_CLICK, Action.LEFT_CLICK_AIR)).isEmpty());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-イベント.md
     * 章・見出し: # 28_3-イベント > ## 1. 右・左クリック受付
     * 検証契約: hookshotのRIGHT_CLICK_BLOCKは候補を返さず、装填も入力claimも発生させない。
     */
    @Test
    void gatewayLeavesRightClickUntouched() {
        GatewayFixture fixture = gatewayFixture(Action.RIGHT_CLICK_BLOCK, 6.0D, 2.0D);
        HookshotInteractionEventHandler handler = new HookshotInteractionEventHandler(fixture.service());
        PlayerInteractionGatewayEventHandler gateway = gateway(fixture.plugin(), List.of(handler));

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(fixture.player())).thenReturn(fixture.astPlayer());
            gateway.onPlayerInteract(fixture.event());
        }

        verify(fixture.service(), never()).startLoading(fixture.astPlayer());
        verify(fixture.event(), never()).setCancelled(true);
        verify(fixture.event(), never()).setUseItemInHand(Event.Result.DENY);
        verify(fixture.event(), never()).setUseInteractedBlock(Event.Result.DENY);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-イベント.md
     * 章・見出し: # 28_3-イベント > ## 1. 右・左クリック受付
     * 検証契約: 装填済みで有効アンカーを向くLEFT_CLICKは発射を一回だけ実行する。
     */
    @Test
    void gatewayFiresLoadedHookshotOnLeftClick() {
        GatewayFixture fixture = gatewayFixture(Action.LEFT_CLICK_BLOCK, 6.0D, 2.0D, true);
        HookshotInteractionEventHandler handler = new HookshotInteractionEventHandler(fixture.service());
        PlayerInteractionGatewayEventHandler gateway = gateway(fixture.plugin(), List.of(handler));

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(fixture.player())).thenReturn(fixture.astPlayer());
            gateway.onPlayerInteract(fixture.event());
        }

        verify(fixture.service()).fire(fixture.astPlayer());
        verify(fixture.service(), never()).startLoading(fixture.astPlayer());
        verify(fixture.event()).setCancelled(true);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-サービス.md
     * 章・見出し: # 28_3-サービス > ## 3. family別候補契約
     * 検証契約: 同距離の既存独自WORLD_INTERACTION候補はフックショット装填より先に実行される。
     */
    @Test
    void gatewayDefersLoadingToExistingWorldInteractionAtSameDistance() {
        GatewayFixture fixture = gatewayFixture(Action.LEFT_CLICK_BLOCK, 3.0D, 3.0D, false);
        HookshotInteractionEventHandler handler = new HookshotInteractionEventHandler(fixture.service());
        AtomicInteger existingWorldExecutions = new AtomicInteger();
        PlayerInputResolver<PlayerInteractionSnapshot> existingWorldResolver = context -> List.of(
            new PlayerInputCandidate(
                "existing-world-action",
                InteractionTier.WORLD_INTERACTION,
                context.inputSnapshot().blockingDistance(),
                InteractionCandidateOrder.NPC,
                "existing-world-action:target",
                InputClaimPolicy.CLAIM_AND_CANCEL,
                existingWorldExecutions::incrementAndGet
            )
        );
        PlayerInteractionGatewayEventHandler gateway = gateway(
            fixture.plugin(),
            List.of(handler, existingWorldResolver)
        );

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(fixture.player())).thenReturn(fixture.astPlayer());
            gateway.onPlayerInteract(fixture.event());
        }

        assertEquals(1, existingWorldExecutions.get());
        verify(fixture.service(), never()).startLoading(fixture.astPlayer());
    }

    private HandlerFixture handlerFixture() {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getMode()).thenReturn(AccountMode.PLAYER);
        return new HandlerFixture(player, astPlayer, mock(HookshotUseService.class), playerId);
    }

    private PlayerInputContext<PlayerInteractionSnapshot> hookshotContext(
        HandlerFixture fixture,
        InputFamily family,
        Action action
    ) {
        PlayerInteractionSnapshot snapshot = new PlayerInteractionSnapshot(
            fixture.player(),
            mock(Event.class),
            EquipmentSlot.HAND,
            action,
            null,
            null,
            null,
            false,
            PlayerInteractionRayTrace.create(new Vector(), new Vector(0.0D, 0.0D, 1.0D), 8.0D),
            8.0D
        );
        return new PlayerInputContext<>(
            fixture.playerId(),
            1L,
            family,
            InputSource.SYNTHETIC,
            snapshot
        );
    }

    private GatewayFixture gatewayFixture(Action action, double blockingDistance, double clickedBlockDistance) {
        return gatewayFixture(action, blockingDistance, clickedBlockDistance, false);
    }

    private GatewayFixture gatewayFixture(
        Action action,
        double blockingDistance,
        double clickedBlockDistance,
        boolean loaded
    ) {
        HandlerFixture handler = handlerFixture();
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        World world = mock(World.class);
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        Block clickedBlock = mock(Block.class);
        RayTraceResult blockHit = mock(RayTraceResult.class);
        Location eye = new Location(world, 0.0D, 64.0D, 0.0D);
        eye.setDirection(new Vector(0.0D, 0.0D, 1.0D));

        when(plugin.getServer()).thenReturn(server);
        when(server.getCurrentTick()).thenReturn(100);
        when(handler.player().getName()).thenReturn("hookshot-test-player");
        when(handler.player().getWorld()).thenReturn(world);
        when(handler.player().getEyeLocation()).thenReturn(eye);
        when(world.getUID()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000401"));
        when(world.rayTraceBlocks(
            any(Location.class),
            any(Vector.class),
            eq(8.0D),
            eq(FluidCollisionMode.NEVER),
            eq(true)
        )).thenReturn(blockHit);
        when(blockHit.getHitPosition()).thenReturn(new Vector(0.0D, 64.0D, blockingDistance));
        when(event.getPlayer()).thenReturn(handler.player());
        when(event.getAction()).thenReturn(action);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getClickedBlock()).thenReturn(clickedBlock);
        when(event.useInteractedBlock()).thenReturn(Event.Result.DEFAULT);
        when(event.useItemInHand()).thenReturn(Event.Result.DEFAULT);
        when(clickedBlock.getWorld()).thenReturn(world);
        when(clickedBlock.getX()).thenReturn(0);
        when(clickedBlock.getY()).thenReturn(64);
        when(clickedBlock.getZ()).thenReturn((int) clickedBlockDistance);
        when(clickedBlock.getBoundingBox()).thenReturn(
            new BoundingBox(
                -0.5D,
                63.5D,
                clickedBlockDistance,
                0.5D,
                65.5D,
                clickedBlockDistance + 1.0D
            )
        );
        when(handler.service().findCurrentHookshotInstanceId(handler.astPlayer())).thenReturn("hookshot-instance");
        when(handler.service().isCurrentHookshot(handler.astPlayer(), "hookshot-instance")).thenReturn(true);
        when(handler.service().isCurrentHookshotLoaded(handler.astPlayer(), "hookshot-instance"))
            .thenReturn(loaded);
        if (loaded) {
            when(handler.service().canFire(handler.astPlayer(), "hookshot-instance")).thenReturn(true);
        } else {
            when(handler.service().canStartLoading(handler.astPlayer())).thenReturn(true);
        }

        return new GatewayFixture(plugin, event, handler.player(), handler.astPlayer(), handler.service());
    }

    private PlayerInteractionGatewayEventHandler gateway(
        AstralRecord plugin,
        List<? extends PlayerInputResolver<PlayerInteractionSnapshot>> resolvers
    ) {
        return new PlayerInteractionGatewayEventHandler(
            plugin,
            resolvers,
            ignored -> false,
            ignored -> false,
            ignored -> {
            }
        );
    }

    private record HandlerFixture(
        Player player,
        AstPlayer astPlayer,
        HookshotUseService service,
        UUID playerId
    ) {
    }

    private record GatewayFixture(
        AstralRecord plugin,
        PlayerInteractEvent event,
        Player player,
        AstPlayer astPlayer,
        HookshotUseService service
    ) {
    }
}
