package io.github.maaasu.astralRecord.feature.trainingdummy.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrainingDummyDefinitionTest {

    @Test
    void maxHealthIsAlwaysFixedToIntegerMaximum() {
        TrainingDummyDefinition definition = new TrainingDummyDefinition(
                "dummy", "world", 0.0D, 64.0D, 0.0D, 0.0F,
                100.0D, 0.0D, 0.0D, false, 10.0D, 40L
        );

        assertEquals((double) Integer.MAX_VALUE, definition.maxHealth());
        assertEquals(
                (double) Integer.MAX_VALUE,
                definition.withStats(1.0D, 0.0D, 0.0D, false, 10.0D).maxHealth()
        );
    }
}
