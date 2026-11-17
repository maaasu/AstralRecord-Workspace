package io.github.maaasu.astralRecord.feature.gathering.service;

import io.github.maaasu.astralRecord.feature.status.model.StatusValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatheringServiceStatusTest {

    /**
     * MINING_SPEED未設定/0でも最低1 damageを与える。
     */
    @Test
    void missingMiningSpeedUsesMinimumDamage() {
        assertEquals(1, GatheringService.resolveMiningDamage((StatusValue) null));
    }

    /**
     * 固定MINING_SPEEDを四捨五入して採集damageへ使う。
     */
    @Test
    void fixedMiningSpeedBecomesGatheringDamage() {
        assertEquals(10, GatheringService.resolveMiningDamage(new StatusValue(10.0D, 0.0D)));
    }

    /**
     * tool由来MINING_SPEEDを重複加算せず1回分damageとして使う。
     */
    @Test
    void toolMiningSpeedIsUsedAsSingleGatheringDamage() {
        assertEquals(2, GatheringService.resolveMiningDamage(new StatusValue(0.0D, 2.0D)));
    }

    /**
     * range MINING_SPEEDのroll値を上下限内の採集damageにする。
     */
    @Test
    void rangedMiningSpeedRollsDamageWithinBounds() {
        StatusValue value = new StatusValue(0.0D, 0.0D, 2.0D, 10.0D);

        for (int i = 0; i < 1_000; i++) {
            int damage = GatheringService.resolveMiningDamage(value);
            assertTrue(damage >= 2 && damage <= 10);
        }
    }

    /**
     * GATHERING_LEVEL未定義時はアクセス判定値を0とする。
     */
    @Test
    void missingGatheringLevelDoesNotGrantAccessLevel() {
        assertEquals(0, GatheringService.resolveGatheringLevel((StatusValue) null));
    }

    /**
     * 装備由来GATHERING_LEVELは整数部分をアクセス判定値とする。
     */
    @Test
    void gatheringLevelUsesDefinedWholeLevel() {
        assertEquals(2, GatheringService.resolveGatheringLevel(new StatusValue(0.0D, 2.75D)));
    }
}
