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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-サービス.md
     * 章・見出し: # 28_3-サービス > ## 5. 入力token相関
     * 検証契約: 同tickの汎用interactとentity interactを同じRIGHT_CLICK token・sequenceへ相関する。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-サービス.md
     * 章・見出し: # 28_3-サービス > ## 5. 入力token相関
     * 検証契約: 同tick・同hand・同entityのEntity/AtEntity sourceを同じtokenへ相関する。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-サービス.md
     * 章・見出し: # 28_3-サービス > ## 5. 入力token相関
     * 検証契約: 同tickでも直接対象entityが異なる配送は別sequenceにする。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-サービス.md
     * 章・見出し: # 28_3-サービス > ## 5. 入力token相関
     * 検証契約: 同tick・同source・同targetのmain/offhand重複配送を同じtokenへ相関する。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-サービス.md
     * 章・見出し: # 28_3-サービス > ## 5. 入力token相関
     * 検証契約: 次tickの入力は前tickのclaim済みtokenと異なるsequenceを持ち未claimで開始する。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-サービス.md
     * 章・見出し: # 28_3-サービス > ## 5. 入力token相関
     * 検証契約: 相関配送間でclaim/cancel要求を共有し、player clear後は旧状態を破棄して新tokenを発行する。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-サービス.md
     * 章・見出し: # 28_3-サービス > ## 5. 入力token相関
     * 検証契約: semantic観測を同tick全体でなく正確なplayer・sequenceへ限定しclearで破棄する。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-サービス.md
     * 章・見出し: # 28_3-サービス > ## 5. 入力token相関
     * 検証契約: 前ticksemantic状態を次tickfallback参照まで保持し、2tick後の相関時に破棄する。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/28_4-統合フロー.md
     * 章・見出し: # 28_4-統合フロー > ## 2. arm swing 遅延fallback
     * 検証契約: 同player・tick・handのsemantic ingressをfamily横断で検出し、別hand・tick・playerには波及させない。
     */
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
