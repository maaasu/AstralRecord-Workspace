package io.github.maaasu.astralRecord.shared.interaction;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerInputDispatcherMatrixTest {
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final PlayerInputContext<String> RIGHT_CLICK_CONTEXT = new PlayerInputContext<>(
        PLAYER_ID,
        10L,
        InputFamily.RIGHT_CLICK,
        InputSource.SYNTHETIC,
        "right-click-snapshot"
    );
    private static final PlayerInputContext<String> BLOCK_MUTATION_CONTEXT = new PlayerInputContext<>(
        PLAYER_ID,
        11L,
        InputFamily.BLOCK_MUTATION,
        InputSource.BLOCK_BREAK,
        "block-break-snapshot"
    );

    @TestFactory
    Stream<DynamicTest> npcWaystoneItemAndActionRingMatrix() {
        List<Scenario> scenarios = List.of(
            new Scenario(
                "nearer waystone wins while lower tiers are closer",
                List.of(
                    npc(4.0D),
                    waystone(2.0D),
                    item(0.1D),
                    actionRing(0.0D)
                ),
                "waystone"
            ),
            new Scenario(
                "nearer npc wins while lower tiers are closer",
                List.of(
                    npc(1.0D),
                    waystone(3.0D),
                    item(0.1D),
                    actionRing(0.0D)
                ),
                "npc"
            ),
            new Scenario(
                "item wins when no world interaction exists",
                List.of(
                    item(3.0D),
                    actionRing(0.0D)
                ),
                "item"
            ),
            new Scenario(
                "action ring runs only as the remaining fallback",
                List.of(actionRing(0.0D)),
                "action-ring"
            )
        );

        return scenarios.stream().map(scenario -> DynamicTest.dynamicTest(
            scenario.name(),
            () -> assertScenario(scenario)
        ));
    }

    @Test
    void blockMutationExecutesOnlyOneStableWinner() {
        AtomicInteger mobSpawnerExecutions = new AtomicInteger();
        AtomicInteger gatheringSpawnerExecutions = new AtomicInteger();
        AtomicInteger fallbackExecutions = new AtomicInteger();
        AtomicInteger beforeExecutionCalls = new AtomicInteger();
        PlayerInputCandidate mobSpawner = candidate(
            "mob-spawner-break",
            InteractionTier.WORLD_INTERACTION,
            0.0D,
            InteractionCandidateOrder.MOB_SPAWNER,
            "block:world:20:64:30",
            InputClaimPolicy.CLAIM,
            mobSpawnerExecutions::incrementAndGet
        );
        PlayerInputCandidate gatheringSpawner = candidate(
            "gathering-spawner-break",
            InteractionTier.WORLD_INTERACTION,
            0.0D,
            InteractionCandidateOrder.GATHERING_SPAWNER,
            "block:world:20:64:30",
            InputClaimPolicy.CLAIM,
            gatheringSpawnerExecutions::incrementAndGet
        );
        PlayerInputCandidate fallback = candidate(
            "block-mutation-fallback",
            InteractionTier.FALLBACK,
            0.0D,
            InteractionCandidateOrder.VANILLA_COMBAT,
            "block:world:20:64:30",
            InputClaimPolicy.PASS_THROUGH,
            fallbackExecutions::incrementAndGet
        );
        PlayerInputDispatcher<String> dispatcher = new PlayerInputDispatcher<>(List.of(
            context -> List.of(gatheringSpawner),
            context -> List.of(fallback),
            context -> List.of(mobSpawner)
        ));

        PlayerInputDispatchResult result = dispatcher.dispatch(
            BLOCK_MUTATION_CONTEXT,
            winner -> beforeExecutionCalls.incrementAndGet()
        );

        assertAll(
            () -> assertEquals("mob-spawner-break", result.winner().orElseThrow().id()),
            () -> assertTrue(result.isClaimed()),
            () -> assertFalse(result.isCancelRequested()),
            () -> assertEquals(1, beforeExecutionCalls.get()),
            () -> assertEquals(1, mobSpawnerExecutions.get()),
            () -> assertEquals(0, gatheringSpawnerExecutions.get()),
            () -> assertEquals(0, fallbackExecutions.get()),
            () -> assertEquals(
                1,
                mobSpawnerExecutions.get()
                    + gatheringSpawnerExecutions.get()
                    + fallbackExecutions.get()
            )
        );
    }

    private static void assertScenario(Scenario scenario) {
        Map<String, AtomicInteger> executions = new LinkedHashMap<>();
        List<PlayerInputCandidate> candidates = scenario.candidates().stream()
            .map(spec -> {
                AtomicInteger executionCount = new AtomicInteger();
                executions.put(spec.id(), executionCount);
                return candidate(
                    spec.id(),
                    spec.tier(),
                    spec.hitDistance(),
                    spec.stableOrder(),
                    spec.targetKey(),
                    InputClaimPolicy.CLAIM_AND_CANCEL,
                    executionCount::incrementAndGet
                );
            })
            .toList();
        PlayerInputDispatcher<String> dispatcher = new PlayerInputDispatcher<>(List.of(
            context -> candidates
        ));

        PlayerInputDispatchResult result = dispatcher.dispatch(RIGHT_CLICK_CONTEXT);

        assertAll(
            () -> assertEquals(scenario.expectedWinner(), result.winner().orElseThrow().id()),
            () -> assertEquals(1, executions.get(scenario.expectedWinner()).get()),
            () -> assertEquals(
                1,
                executions.values().stream().mapToInt(AtomicInteger::get).sum()
            ),
            () -> executions.forEach((id, count) -> assertEquals(
                id.equals(scenario.expectedWinner()) ? 1 : 0,
                count.get(),
                id
            ))
        );
    }

    private static CandidateSpec npc(double hitDistance) {
        return new CandidateSpec(
            "npc",
            InteractionTier.WORLD_INTERACTION,
            hitDistance,
            InteractionCandidateOrder.NPC,
            "entity:npc"
        );
    }

    private static CandidateSpec waystone(double hitDistance) {
        return new CandidateSpec(
            "waystone",
            InteractionTier.WORLD_INTERACTION,
            hitDistance,
            InteractionCandidateOrder.WAYSTONE,
            "waystone:spawn"
        );
    }

    private static CandidateSpec item(double hitDistance) {
        return new CandidateSpec(
            "item",
            InteractionTier.ITEM_USE,
            hitDistance,
            InteractionCandidateOrder.MENU_SHORTCUT,
            "item:menu"
        );
    }

    private static CandidateSpec actionRing(double hitDistance) {
        return new CandidateSpec(
            "action-ring",
            InteractionTier.FALLBACK,
            hitDistance,
            InteractionCandidateOrder.OPEN_ACTION_RING,
            "action-ring:open"
        );
    }

    private static PlayerInputCandidate candidate(
        String id,
        InteractionTier tier,
        double hitDistance,
        int stableOrder,
        String targetKey,
        InputClaimPolicy claimPolicy,
        Runnable executor
    ) {
        return new PlayerInputCandidate(
            id,
            tier,
            hitDistance,
            stableOrder,
            targetKey,
            claimPolicy,
            executor
        );
    }

    private record CandidateSpec(
        String id,
        InteractionTier tier,
        double hitDistance,
        int stableOrder,
        String targetKey
    ) {
    }

    private record Scenario(
        String name,
        List<CandidateSpec> candidates,
        String expectedWinner
    ) {
    }
}
