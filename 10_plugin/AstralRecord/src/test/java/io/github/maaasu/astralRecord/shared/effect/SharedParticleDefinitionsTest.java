package io.github.maaasu.astralRecord.shared.effect;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedParticleDefinitionsTest {

    @Test
    void allDefinitionsProvideDataRequiredByTheirParticle() throws IllegalAccessException {
        for (Field field : SharedParticleDefinitions.class.getFields()) {
            if (field.getType() != SharedParticleDefinition.class) {
                continue;
            }

            SharedParticleDefinition definition = (SharedParticleDefinition) field.get(null);
            Class<?> requiredDataType = definition.particle().getDataType();
            Object data = definition.data();
            if (requiredDataType == Void.class) {
                assertNull(data, definition.id());
                continue;
            }

            assertNotNull(data, definition.id());
            assertTrue(requiredDataType.isInstance(data), definition.id());
        }
    }
}
