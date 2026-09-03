package io.github.maaasu.astralRecord.shared.effect;

import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedParticleDefinitionsTest extends MockBukkitTestBase {

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## パーティクル表示共通ルール
     * 検証契約: 全共有Particle定義でVoid data typeはnull、それ以外はParticle要求型の非null dataを保持する。
     */
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
