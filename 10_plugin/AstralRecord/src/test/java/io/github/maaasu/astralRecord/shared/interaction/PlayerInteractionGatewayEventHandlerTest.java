package io.github.maaasu.astralRecord.shared.interaction;

import io.github.maaasu.astralRecord.AstralRecord;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
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
        when(world.getUID()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000304"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/28_4-統合フロー.md
     * 章・見出し: # 28_4-統合フロー > ## 1. 同期クリック調停
     * 検証契約: WORLD_INTERACTION勝者だけを実行し、元event・item use・block useを実行前に抑止する。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-イベント.md
     * 章・見出し: # 28_3-イベント > ## 1. 右・左クリック受付
     * 検証契約: 通常の初期cancel済みinteractはresolverもexecutorも呼ばずevent状態を維持する。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-サービス.md
     * 章・見出し: # 28_3-サービス > ## 5. 入力token相関
     * 検証契約: main/offhandの相関配送でexecutorを一回だけ実行し、cancel要求を両eventへ反映する。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-イベント.md
     * 章・見出し: # 28_3-イベント > ## 2. entity右クリック受付
     * 検証契約: 汎用interactとentity interactを同一tokenへ相関し、勝者を一回だけ実行して後続eventへcancelを反映する。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/28_0-概要.md
     * 章・見出し: # 28_0-概要 > ## 4. claimとvanilla動作
     * 検証契約: CLAIMのvanilla候補は重複executorを抑止するが相関する元eventをcancelしない。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-イベント.md
     * 章・見出し: # 28_3-イベント > ## 4. entity左クリック受付
     * 検証契約: willAttack=false由来の初期cancel済みPrePlayerAttackでもcustom interaction候補を評価する。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-イベント.md
     * 章・見出し: # 28_3-イベント > ## 2. entity右クリック受付
     * 検証契約: server-side Interaction entityへの初期cancel済み右clickでもcustom候補を評価する。
     */
    @Test
    void evaluatesCustomInteractionWhenEntityRightClickIsInitiallyCancelled() {
        AtomicInteger executions = new AtomicInteger();
        PlayerInteractionGatewayEventHandler gateway = gateway(context -> List.of(candidate(
            "skill-tree-right-interaction",
            InteractionTier.EXCLUSIVE_CONTEXT,
            1.0D,
            InteractionCandidateOrder.SKILL_TREE,
            executions
        )));
        PlayerInteractEntityEvent event = mock(PlayerInteractEntityEvent.class);
        Interaction target = mock(Interaction.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getRightClicked()).thenReturn(target);
        when(event.isCancelled()).thenReturn(true);
        when(target.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000304"));
        when(target.getBoundingBox()).thenReturn(new BoundingBox(-0.5D, 63.5D, 1.0D, 0.5D, 65.5D, 2.0D));

        gateway.onPlayerInteractEntity(event);

        assertEquals(1, executions.get());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-イベント.md
     * 章・見出し: # 28_3-イベント > ## 3. entity位置指定右クリック受付
     * 検証契約: server-side Interaction entityへの初期cancel済み位置指定右clickでもcustom候補を評価する。
     */
    @Test
    void evaluatesCustomInteractionWhenPositionRightClickIsInitiallyCancelled() {
        AtomicInteger executions = new AtomicInteger();
        PlayerInteractionGatewayEventHandler gateway = gateway(context -> List.of(candidate(
            "skill-tree-position-right-interaction",
            InteractionTier.EXCLUSIVE_CONTEXT,
            1.0D,
            InteractionCandidateOrder.SKILL_TREE,
            executions
        )));
        PlayerInteractAtEntityEvent event = mock(PlayerInteractAtEntityEvent.class);
        Interaction target = mock(Interaction.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getRightClicked()).thenReturn(target);
        when(event.isCancelled()).thenReturn(true);
        when(target.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000305"));
        when(target.getBoundingBox()).thenReturn(new BoundingBox(-0.5D, 63.5D, 1.0D, 0.5D, 65.5D, 2.0D));

        gateway.onPlayerInteractAtEntity(event);

        assertEquals(1, executions.get());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/28_4-統合フロー.md
     * 章・見出し: # 28_4-統合フロー > ## 2. arm swing 遅延fallback
     * 検証契約: arm swing受信tickでは実行・cancelせず、次tickでguard falseなら選択済みexecutorを見送る。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/28_4-統合フロー.md
     * 章・見出し: # 28_4-統合フロー > ## 2. arm swing 遅延fallback
     * 検証契約: 同tickのsemantic左clickを一回実行し、予約済みarm fallbackを抑止する。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/28_4-統合フロー.md
     * 章・見出し: # 28_4-統合フロー > ## 2. arm swing 遅延fallback
     * 検証契約: 同tick・同handのblock place semantic観測後のarm swingをfallbackとして予約しない。
     */
    @Test
    void blockPlaceSemanticPreventsFollowingArmFallback() {
        AtomicInteger executions = new AtomicInteger();
        PlayerInteractionGatewayEventHandler gateway = gateway(context ->
            context.family() == InputFamily.LEFT_CLICK
                ? List.of(candidate(
                    "spawner-left-interaction",
                    InteractionTier.WORLD_INTERACTION,
                    1.0D,
                    InteractionCandidateOrder.MOB_SPAWNER,
                    executions
                ))
                : List.of()
        );

        gateway.onBlockPlace(blockPlaceEvent());
        gateway.onPlayerArmSwing(armSwingEvent());

        assertEquals(0, executions.get());
        verify(scheduler, never()).runTask(eq(plugin), any(Runnable.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/28_4-統合フロー.md
     * 章・見出し: # 28_4-統合フロー > ## 2. arm swing 遅延fallback
     * 検証契約: arm swing予約後に同tickのblock place semanticを観測した場合も次tickexecutorを抑止する。
     */
    @Test
    void blockPlaceSemanticSuppressesAlreadyPendingArmFallback() {
        AtomicInteger executions = new AtomicInteger();
        AtomicReference<Runnable> scheduled = captureNextTask();
        PlayerInteractionGatewayEventHandler gateway = gateway(context ->
            context.family() == InputFamily.LEFT_CLICK
                ? List.of(candidate(
                    "spawner-left-interaction",
                    InteractionTier.WORLD_INTERACTION,
                    1.0D,
                    InteractionCandidateOrder.MOB_SPAWNER,
                    executions
                ))
                : List.of()
        );

        gateway.onPlayerArmSwing(armSwingEvent());
        gateway.onBlockPlace(blockPlaceEvent());
        scheduled.get().run();

        assertEquals(0, executions.get());
    }

    private PlayerInteractionGatewayEventHandler gateway(
        PlayerInputResolver<PlayerInteractionSnapshot> resolver
    ) {
        return gateway(List.of(resolver));
    }

    private PlayerInteractionGatewayEventHandler gateway(
        PlayerInputResolver<PlayerInteractionSnapshot> first,
        PlayerInputResolver<PlayerInteractionSnapshot> second
    ) {
        return gateway(List.of(first, second));
    }

    private PlayerInteractionGatewayEventHandler gateway(
        List<PlayerInputResolver<PlayerInteractionSnapshot>> resolvers
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

    private BlockPlaceEvent blockPlaceEvent() {
        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        Block block = mock(Block.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getBlockPlaced()).thenReturn(block);
        when(event.isCancelled()).thenReturn(false);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(10);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(20);
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
