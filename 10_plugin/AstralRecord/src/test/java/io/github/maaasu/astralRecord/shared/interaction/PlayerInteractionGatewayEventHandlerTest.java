package io.github.maaasu.astralRecord.shared.interaction;

import io.github.maaasu.astralRecord.AstralRecord;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerInteractionGatewayEventHandlerTest {
    private final UUID playerId = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private AstralRecord plugin;
    private Server server;
    private World world;
    private Player player;
    private BukkitScheduler scheduler;

    @BeforeEach
    void setUp() {
        plugin = mock(AstralRecord.class);
        server = mock(Server.class);
        world = mock(World.class);
        player = mock(Player.class);
        scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getCurrentTick()).thenReturn(100);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("gateway-test-player");
        when(player.getWorld()).thenReturn(world);
        when(player.getEyeLocation()).thenReturn(new Location(world, 0.0D, 64.0D, 0.0D, 0.0F, 0.0F));
    }

    @Test
    void executesOnlyHighestPriorityResolverAndCancelsBeforeExecution() {
        AtomicInteger worldExecutions = new AtomicInteger();
        AtomicInteger fallbackExecutions = new AtomicInteger();
        PlayerInteractionGatewayEventHandler gateway = gateway(
            context -> List.of(candidate(
                "action-ring",
                InteractionTier.FALLBACK,
                0.0D,
                InteractionCandidateOrder.NEW_ACTION_RING,
                fallbackExecutions
            )),
            context -> List.of(candidate(
                "npc",
                InteractionTier.WORLD_INTERACTION,
                2.0D,
                InteractionCandidateOrder.NPC,
                worldExecutions
            ))
        );
        PlayerInteractEvent event = interactEvent(EquipmentSlot.HAND, false);

        gateway.onPlayerInteract(event);

        assertEquals(1, worldExecutions.get());
        assertEquals(0, fallbackExecutions.get());
        verify(event).setCancelled(true);
        verify(event).setUseItemInHand(Event.Result.DENY);
        verify(event).setUseInteractedBlock(Event.Result.DENY);
    }

    @Test
    void ignoresAlreadyCancelledEventWithoutResolvingOrExecuting() {
        AtomicInteger resolverCalls = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        PlayerInteractionGatewayEventHandler gateway = gateway(context -> {
            resolverCalls.incrementAndGet();
            return List.of(candidate(
                "npc",
                InteractionTier.WORLD_INTERACTION,
                1.0D,
                InteractionCandidateOrder.NPC,
                executions
            ));
        });
        PlayerInteractEvent event = interactEvent(EquipmentSlot.HAND, true);

        gateway.onPlayerInteract(event);

        assertEquals(0, resolverCalls.get());
        assertEquals(0, executions.get());
        verify(event, never()).setUseItemInHand(any());
    }

    @Test
    void mainAndOffHandDeliveriesExecuteOneWinner() {
        AtomicInteger executions = new AtomicInteger();
        PlayerInteractionGatewayEventHandler gateway = gateway(context -> List.of(candidate(
            "item-use",
            InteractionTier.ITEM_USE,
            0.0D,
            0,
            executions
        )));
        PlayerInteractEvent mainHand = interactEvent(EquipmentSlot.HAND, false);
        PlayerInteractEvent offHand = interactEvent(EquipmentSlot.OFF_HAND, false);

        gateway.onPlayerInteract(mainHand);
        gateway.onPlayerInteract(offHand);

        assertEquals(1, executions.get());
        verify(mainHand).setCancelled(true);
        verify(offHand).setCancelled(true);
    }

    @Test
    void genericAndEntityDeliveriesShareClaimAndExecuteOneWinner() {
        AtomicInteger executions = new AtomicInteger();
        PlayerInteractionGatewayEventHandler gateway = gateway(context -> List.of(candidate(
            "npc",
            InteractionTier.WORLD_INTERACTION,
            1.0D,
            InteractionCandidateOrder.NPC,
            executions
        )));
        PlayerInteractEvent generic = interactEvent(EquipmentSlot.HAND, false);
        PlayerInteractEntityEvent entityEvent = mock(PlayerInteractEntityEvent.class);
        Entity target = mock(Entity.class);
        when(entityEvent.getPlayer()).thenReturn(player);
        when(entityEvent.getHand()).thenReturn(EquipmentSlot.HAND);
        when(entityEvent.getRightClicked()).thenReturn(target);
        when(target.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000302"));
        when(target.getBoundingBox()).thenReturn(new BoundingBox(-0.5D, 63.5D, 1.0D, 0.5D, 65.5D, 2.0D));

        gateway.onPlayerInteract(generic);
        gateway.onPlayerInteractEntity(entityEvent);

        assertEquals(1, executions.get());
        verify(entityEvent).setCancelled(true);
    }

    @Test
    void claimedVanillaDeliveryDoesNotCancelCorrelatedEvent() {
        AtomicInteger executions = new AtomicInteger();
        PlayerInteractionGatewayEventHandler gateway = gateway(context -> List.of(new PlayerInputCandidate(
            "vanilla-interaction",
            InteractionTier.WORLD_INTERACTION,
            1.0D,
            InteractionCandidateOrder.VANILLA_INTERACTION,
            "vanilla:target",
            InputClaimPolicy.CLAIM,
            executions::incrementAndGet
        )));
        PlayerInteractEvent mainHand = interactEvent(EquipmentSlot.HAND, false);
        PlayerInteractEvent offHand = interactEvent(EquipmentSlot.OFF_HAND, false);

        gateway.onPlayerInteract(mainHand);
        gateway.onPlayerInteract(offHand);

        assertEquals(1, executions.get());
        verify(mainHand, never()).setCancelled(true);
        verify(offHand, never()).setCancelled(true);
    }

    @Test
    void evaluatesCustomInteractionWhenPreAttackIsInitiallyCancelled() {
        AtomicInteger executions = new AtomicInteger();
        PlayerInteractionGatewayEventHandler gateway = gateway(context -> List.of(candidate(
            "npc-left-interaction",
            InteractionTier.WORLD_INTERACTION,
            1.0D,
            InteractionCandidateOrder.NPC,
            executions
        )));
        PrePlayerAttackEntityEvent event = mock(PrePlayerAttackEntityEvent.class);
        Entity target = mock(Entity.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getAttacked()).thenReturn(target);
        when(event.willAttack()).thenReturn(false);
        when(event.isCancelled()).thenReturn(true);
        when(target.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000303"));
        when(target.getBoundingBox()).thenReturn(new BoundingBox(-0.5D, 63.5D, 1.0D, 0.5D, 65.5D, 2.0D));

        gateway.onPrePlayerAttackEntity(event);

        assertEquals(1, executions.get());
    }

    @Test
    void armSwingDefersAndSkipsInvalidatedWinnerWithoutEarlyCancel() {
        AtomicInteger executions = new AtomicInteger();
        AtomicReference<Runnable> scheduled = captureNextTask();
        PlayerInteractionGatewayEventHandler gateway = gateway(context -> List.of(new PlayerInputCandidate(
            "npc-left-interaction",
            InteractionTier.WORLD_INTERACTION,
            1.0D,
            InteractionCandidateOrder.NPC,
            "npc:stale",
            InputClaimPolicy.CLAIM_AND_CANCEL,
            () -> false,
            executions::incrementAndGet
        )));
        PlayerArmSwingEvent event = armSwingEvent();

        gateway.onPlayerArmSwing(event);

        assertEquals(0, executions.get());
        verify(event, never()).setCancelled(true);
        scheduled.get().run();
        assertEquals(0, executions.get());
    }

    @Test
    void semanticLeftClickExecutesOnceAndSuppressesPendingArmFallback() {
        AtomicInteger executions = new AtomicInteger();
        AtomicReference<Runnable> scheduled = captureNextTask();
        PlayerInteractionGatewayEventHandler gateway = gateway(context -> List.of(candidate(
            "weapon-left-action",
            InteractionTier.FALLBACK,
            0.0D,
            InteractionCandidateOrder.WEAPON_ACTION,
            executions
        )));
        PlayerArmSwingEvent armSwing = armSwingEvent();
        PlayerInteractEvent semantic = interactEvent(EquipmentSlot.HAND, false);
        when(semantic.getAction()).thenReturn(Action.LEFT_CLICK_AIR);

        gateway.onPlayerArmSwing(armSwing);
        gateway.onPlayerInteract(semantic);
        scheduled.get().run();

        assertEquals(1, executions.get());
        verify(armSwing, never()).setCancelled(true);
        verify(semantic).setCancelled(true);
    }

    @SafeVarargs
    private final PlayerInteractionGatewayEventHandler gateway(
        PlayerInputResolver<PlayerInteractionSnapshot>... resolvers
    ) {
        return new PlayerInteractionGatewayEventHandler(
            plugin,
            List.of(resolvers),
            ignored -> false,
            ignored -> false,
            ignored -> {
            }
        );
    }

    private PlayerInteractEvent interactEvent(EquipmentSlot hand, boolean cancelled) {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getHand()).thenReturn(hand);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(event.useInteractedBlock()).thenReturn(Event.Result.DENY);
        when(event.useItemInHand()).thenReturn(cancelled ? Event.Result.DENY : Event.Result.DEFAULT);
        return event;
    }

    private PlayerArmSwingEvent armSwingEvent() {
        PlayerArmSwingEvent event = mock(PlayerArmSwingEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.isCancelled()).thenReturn(false);
        when(player.isOnline()).thenReturn(true);
        return event;
    }

    private AtomicReference<Runnable> captureNextTask() {
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            scheduled.set(invocation.getArgument(1));
            return mock(BukkitTask.class);
        });
        return scheduled;
    }

    private PlayerInputCandidate candidate(
        String id,
        InteractionTier tier,
        double hitDistance,
        int stableOrder,
        AtomicInteger executions
    ) {
        return new PlayerInputCandidate(
            id,
            tier,
            hitDistance,
            stableOrder,
            id + ":target",
            InputClaimPolicy.CLAIM_AND_CANCEL,
            executions::incrementAndGet
        );
    }
}
