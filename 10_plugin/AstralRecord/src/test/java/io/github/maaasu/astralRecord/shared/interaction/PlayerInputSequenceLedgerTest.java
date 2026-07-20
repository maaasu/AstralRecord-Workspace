package io.github.maaasu.astralRecord.shared.interaction;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerInputSequenceLedgerTest {
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID OTHER_PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");

    @Test
    void sameTickDeliveriesCorrelateAndKeepOneSequence() {
        PlayerInputSequenceLedger ledger = new PlayerInputSequenceLedger();

        PlayerInputToken generic = ledger.correlate(
            PLAYER_ID,
            120,
            InputFamily.RIGHT_CLICK,
            InputSource.PLAYER_INTERACT,
            "HAND",
            ""
        );
        PlayerInputToken entity = ledger.correlate(
            PLAYER_ID,
            120,
            InputFamily.RIGHT_CLICK,
            InputSource.PLAYER_INTERACT_ENTITY,
            "HAND",
            "entity:npc-1"
        );

        assertAll(
            () -> assertEquals(generic, entity),
            () -> assertEquals(120, entity.serverTick()),
            () -> assertEquals(InputFamily.RIGHT_CLICK, entity.family())
        );
    }

    @Test
    void differentEntitySourcesCorrelateForSameDirectTarget() {
        PlayerInputSequenceLedger ledger = new PlayerInputSequenceLedger();

        PlayerInputToken entity = ledger.correlate(
            PLAYER_ID,
            240,
            InputFamily.RIGHT_CLICK,
            InputSource.PLAYER_INTERACT_ENTITY,
            "HAND",
            "entity:npc-2"
        );
        PlayerInputToken positionedEntity = ledger.correlate(
            PLAYER_ID,
            240,
            InputFamily.RIGHT_CLICK,
            InputSource.PLAYER_INTERACT_AT_ENTITY,
            "HAND",
            "entity:npc-2"
        );

        assertEquals(entity, positionedEntity);
    }

    @Test
    void differentEntitySourcesDoNotCorrelateDifferentDirectTargets() {
        PlayerInputSequenceLedger ledger = new PlayerInputSequenceLedger();

        PlayerInputToken firstTarget = ledger.correlate(
            PLAYER_ID,
            241,
            InputFamily.RIGHT_CLICK,
            InputSource.PLAYER_INTERACT_ENTITY,
            "HAND",
            "entity:npc-a"
        );
        PlayerInputToken secondTarget = ledger.correlate(
            PLAYER_ID,
            241,
            InputFamily.RIGHT_CLICK,
            InputSource.PLAYER_INTERACT_AT_ENTITY,
            "HAND",
            "entity:npc-b"
        );

        assertNotEquals(firstTarget, secondTarget);
    }

    @Test
    void mainAndOffHandDuplicatesFromSameSourceCorrelate() {
        PlayerInputSequenceLedger ledger = new PlayerInputSequenceLedger();

        PlayerInputToken mainHand = ledger.correlate(
            PLAYER_ID,
            360,
            InputFamily.RIGHT_CLICK,
            InputSource.PLAYER_INTERACT,
            "HAND",
            "block:world:10:64:20"
        );
        PlayerInputToken offHand = ledger.correlate(
            PLAYER_ID,
            360,
            InputFamily.RIGHT_CLICK,
            InputSource.PLAYER_INTERACT,
            "OFF_HAND",
            "block:world:10:64:20"
        );

        assertEquals(mainHand, offHand);
    }

    @Test
    void nextTickCreatesIndependentInput() {
        PlayerInputSequenceLedger ledger = new PlayerInputSequenceLedger();

        PlayerInputToken currentTick = ledger.correlate(
            PLAYER_ID,
            480,
            InputFamily.RIGHT_CLICK,
            InputSource.PLAYER_INTERACT,
            "HAND",
            ""
        );
        ledger.claim(currentTick);
        PlayerInputToken nextTick = ledger.correlate(
            PLAYER_ID,
            481,
            InputFamily.RIGHT_CLICK,
            InputSource.PLAYER_INTERACT,
            "HAND",
            ""
        );

        assertAll(
            () -> assertNotEquals(currentTick, nextTick),
            () -> assertNotEquals(currentTick.sequence(), nextTick.sequence()),
            () -> assertEquals(481, nextTick.serverTick()),
            () -> assertFalse(ledger.isClaimed(nextTick))
        );
    }

    @Test
    void claimIsSharedByCorrelatedDeliveriesAndClearRemovesState() {
        PlayerInputSequenceLedger ledger = new PlayerInputSequenceLedger();
        PlayerInputToken firstDelivery = ledger.correlate(
            PLAYER_ID,
            600,
            InputFamily.RIGHT_CLICK,
            InputSource.PLAYER_INTERACT_ENTITY,
            "HAND",
            "entity:npc-3"
        );
        PlayerInputToken duplicateDelivery = ledger.correlate(
            PLAYER_ID,
            600,
            InputFamily.RIGHT_CLICK,
            InputSource.PLAYER_INTERACT_AT_ENTITY,
            "HAND",
            "entity:npc-3"
        );

        ledger.claim(duplicateDelivery, true);

        assertAll(
            () -> assertTrue(ledger.isClaimed(firstDelivery)),
            () -> assertTrue(ledger.isCancelRequested(firstDelivery))
        );

        ledger.clear(PLAYER_ID);
        PlayerInputToken replacement = ledger.correlate(
            PLAYER_ID,
            600,
            InputFamily.RIGHT_CLICK,
            InputSource.PLAYER_INTERACT_ENTITY,
            "HAND",
            "entity:npc-3"
        );

        assertAll(
            () -> assertFalse(ledger.isClaimed(firstDelivery)),
            () -> assertFalse(ledger.isCancelRequested(firstDelivery)),
            () -> assertNotEquals(firstDelivery, replacement),
            () -> assertFalse(ledger.isClaimed(replacement))
        );
    }

    @Test
    void semanticObservationIsScopedToExactInputSequence() {
        PlayerInputSequenceLedger ledger = new PlayerInputSequenceLedger();
        PlayerInputToken observed = ledger.correlate(
            PLAYER_ID,
            720,
            InputFamily.LEFT_CLICK,
            InputSource.PLAYER_ARM_SWING,
            "HAND",
            ""
        );
        PlayerInputToken independentSameTick = ledger.correlate(
            PLAYER_ID,
            720,
            InputFamily.LEFT_CLICK,
            InputSource.PLAYER_ARM_SWING,
            "HAND",
            ""
        );
        PlayerInputToken otherPlayer = ledger.correlate(
            OTHER_PLAYER_ID,
            720,
            InputFamily.LEFT_CLICK,
            InputSource.PLAYER_ARM_SWING,
            "HAND",
            ""
        );

        ledger.observeSemanticInput(observed);

        assertAll(
            () -> assertTrue(ledger.hasSemanticInput(observed)),
            () -> assertFalse(ledger.hasSemanticInput(independentSameTick)),
            () -> assertFalse(ledger.hasSemanticInput(otherPlayer))
        );

        ledger.clear(PLAYER_ID);

        assertFalse(ledger.hasSemanticInput(observed));
    }

    @Test
    void previousTickStateSurvivesCurrentTickInputForDeferredFallback() {
        PlayerInputSequenceLedger ledger = new PlayerInputSequenceLedger();
        PlayerInputToken pending = ledger.correlate(
            PLAYER_ID,
            800,
            InputFamily.LEFT_CLICK,
            InputSource.PLAYER_ARM_SWING,
            "HAND",
            ""
        );
        ledger.observeSemanticInput(pending);

        ledger.correlate(
            PLAYER_ID,
            801,
            InputFamily.RIGHT_CLICK,
            InputSource.PLAYER_INTERACT,
            "HAND",
            ""
        );

        assertTrue(ledger.hasSemanticInput(pending));

        ledger.correlate(
            PLAYER_ID,
            802,
            InputFamily.RIGHT_CLICK,
            InputSource.PLAYER_INTERACT,
            "HAND",
            ""
        );

        assertFalse(ledger.hasSemanticInput(pending));
    }

    @Test
    void semanticObservationCanBeQueriedAcrossFamiliesByPlayerTickAndHand() {
        PlayerInputSequenceLedger ledger = new PlayerInputSequenceLedger();
        PlayerInputToken blockPlace = ledger.correlate(
            PLAYER_ID,
            900,
            InputFamily.BLOCK_MUTATION,
            InputSource.BLOCK_PLACE,
            "HAND",
            "block:world:10:64:20"
        );
        ledger.observeSemanticInput(blockPlace);
        ledger.correlate(
            PLAYER_ID,
            900,
            InputFamily.LEFT_CLICK,
            InputSource.PLAYER_ARM_SWING,
            "HAND",
            ""
        );

        assertAll(
            () -> assertTrue(ledger.hasSemanticInput(PLAYER_ID, 900, "HAND")),
            () -> assertFalse(ledger.hasSemanticInput(PLAYER_ID, 900, "OFF_HAND")),
            () -> assertFalse(ledger.hasSemanticInput(PLAYER_ID, 901, "HAND")),
            () -> assertFalse(ledger.hasSemanticInput(OTHER_PLAYER_ID, 900, "HAND"))
        );
    }
}
