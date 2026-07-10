package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobDropConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropItem;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MobDropServiceTest {

    @Test
    void rollPreservesConfiguredRateForResultPresentation() {
        MobDropConfig drops = new MobDropConfig(
            10,
            null,
            List.of(new MobDropItem("rare_item", 100.0D, "2", false, false)),
            null
        );

        MobDropResult result = new MobDropService().roll(drops, null);

        assertEquals(1, result.items().size());
        assertEquals("rare_item", result.items().getFirst().itemId());
        assertEquals(2, result.items().getFirst().amount());
        assertEquals(100.0D, result.items().getFirst().dropRate());
    }
}
